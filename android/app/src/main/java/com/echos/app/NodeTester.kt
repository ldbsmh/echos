package com.echos.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 节点连通性测试（卡片上的「测延迟」）。
 *
 * 真正的握手是 ECH + TLS1.3，Android 侧的 OkHttp / Conscrypt 都不支持 ECH，
 * 只能交给 Go 内核做：这里以子进程方式跑 `libxtun.so -selftest`，
 * 内核把整条链路走一遍（ECH 公钥 → TLS → WebSocket → CONNECT → HTTP 回包），
 * 结果以单行 JSON 打到 stdout，这里解析后回调到主线程。
 *
 * 子进程与本 App 同 UID，而 VPN 模式本来就把自己排除在 TUN 之外，
 * 所以测试走的是物理直连，不会绕进自己刚建立的隧道里。
 */
object NodeTester {

    /** 结果行前缀，与 Go 侧 selftest.go 的 selfTestMarker 必须一致。 */
    private const val MARKER = "ECHOS-SELFTEST "

    /** 内核自己的总超时（秒），给够 ECH 查询 + 握手 + 回包。 */
    private const val KERNEL_TIMEOUT_SEC = 12

    /** 子进程兜底超时：比内核超时多一点，超了就强杀，避免线程永久挂住。 */
    private const val PROCESS_TIMEOUT_MS = (KERNEL_TIMEOUT_SEC + 6) * 1000L

    /** 同时最多测 4 条，再多也只是抢带宽，反而都变慢。 */
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 一次测试的结果。
     * @param stage ok=完全可用 / handshake=连上了但拿不到数据 / dial=连服务端就失败
     * @param ms    端到端延迟（握手 + CONNECT + 回包），失败时为握手耗时
     */
    data class Result(
        val ok: Boolean,
        val stage: String,
        val ms: Long,
        val hsMs: Long,
        val rttMs: Long,
        val echMs: Long,
        val target: String,
        val err: String
    ) {
        /** 状态一句话，给详情弹窗用。 */
        fun summary(): String = when {
            ok -> "连通正常 · $ms ms"
            stage == "handshake" -> "服务端可达，但数据通道不通"
            else -> "无法连接服务端"
        }
    }

    /** 异步测一条线路，onResult 在主线程回调。 */
    fun test(
        ctx: Context,
        server: ConfigStore.Server,
        card: ConfigStore.EntryCard,
        onResult: (Result) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        pool.execute {
            val r = runTest(appCtx, server, card)
            main.post { onResult(r) }
        }
    }

    private fun runTest(
        ctx: Context,
        server: ConfigStore.Server,
        card: ConfigStore.EntryCard
    ): Result {
        fun fail(msg: String) = Result(false, "dial", 0, 0, 0, 0, "", msg)

        val args = buildArgs(server, card) ?: return fail("服务地址或线路端口没填好")
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxtun.so")
        if (!bin.exists()) return fail("找不到内核 libxtun.so，请重新安装")

        val proc = try {
            ProcessBuilder(listOf(bin.absolutePath) + args)
                .redirectErrorStream(true) // 日志与结果混在一个流里，按前缀挑结果行
                .apply { environment()["HOME"] = ctx.filesDir.absolutePath }
                .start()
        } catch (e: Exception) {
            return fail("启动测试进程失败：${e.message}")
        }

        // 兜底看门狗：内核自身有超时，这里防的是"进程卡死/被系统暂停"这种极端情况
        val watchdog = Thread {
            try {
                if (!proc.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }
        watchdog.start()

        var json: String? = null
        var lastLog = ""
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val i = line.indexOf(MARKER)
                    if (i >= 0) {
                        json = line.substring(i + MARKER.length).trim()
                    } else if (line.isNotBlank()) {
                        lastLog = line.trim()
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            proc.waitFor(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        watchdog.interrupt()

        val raw = json ?: return fail(
            lastLog.ifBlank { "测试进程没有输出结果（内核启动失败？）" }
        )

        return try {
            val o = JSONObject(raw)
            Result(
                ok = o.optBoolean("ok", false),
                stage = o.optString("stage", "dial"),
                ms = o.optLong("ms", 0),
                hsMs = o.optLong("hsMs", 0),
                rttMs = o.optLong("rttMs", 0),
                echMs = o.optLong("echMs", 0),
                target = o.optString("target", ""),
                err = o.optString("err", "")
            )
        } catch (e: Exception) {
            fail("测试结果解析失败：${e.message}")
        }
    }

    /** 拼内核 selftest 参数；与 ConfigStore.buildArgs 保持同一套配置来源。 */
    private fun buildArgs(
        server: ConfigStore.Server,
        card: ConfigStore.EntryCard
    ): List<String>? {
        if (server.domain.isBlank() || card.port !in 1..65535) return null
        val args = mutableListOf(
            "-selftest",
            "-f", "wss://${server.domain}:${card.port}",
            "-ech", server.ech.ifBlank { "cloudflare-ech.com" },
            "-dns", ConfigStore.normalizedDoh(server.doh),
            "-test-timeout", "${KERNEL_TIMEOUT_SEC}s"
        )
        if (card.ips.isNotBlank()) args += listOf("-ip", card.ips)
        if (server.token.isNotBlank()) args += listOf("-token", server.token)
        return args
    }

    /** 卡片唯一键：IP:端口（同一 IP 不同端口算不同线路）。 */
    fun key(card: ConfigStore.EntryCard): String = "${card.ips}:${card.port}"
}
