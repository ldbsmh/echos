package com.echos.app

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 测速结果持久化。
 *
 * 之前结果只放在内存里，App 一关就没了：卡片上的延迟徽章清空、
 * 「按延迟排序」也就无从排序（看起来像"排序丢了"）。这里把每次结果
 * 连同时间戳写入 files/test_results.json，重启后直接恢复到界面上。
 *
 * 另外记录一份配置指纹（服务地址 / ECH / DoH / Token）。改过这些设置后
 * 旧结果已经没有参考价值（可能换了出口或鉴权），指纹对不上就整体丢弃。
 */
object TestResultStore {
    private const val FILE = "test_results.json"

    /** 超过 6 小时的结果在徽章上加 * 标记，提醒重测。 */
    const val STALE_MS = 6 * 60 * 60 * 1000L

    /** 读取上次的测速结果（键 = 线路 key，IP:端口）。 */
    fun load(ctx: Context): MutableMap<String, NodeTester.Result> {
        val out = mutableMapOf<String, NodeTester.Result>()
        try {
            if (!ctx.getFileStreamPath(FILE).exists()) return out
            val text = ctx.openFileInput(FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val sig = root.optString("sig", "")
            val cfg = ConfigStore.load(ctx)
            // 服务/鉴权设置变过 → 旧测速结果作废
            if (cfg == null || sig != signature(cfg)) return out

            val items = root.optJSONObject("items") ?: return out
            val keys = items.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = items.optJSONObject(k) ?: continue
                out[k] = NodeTester.Result(
                    ok = o.optBoolean("ok", false),
                    stage = o.optString("stage", "dial"),
                    ms = o.optLong("ms", 0),
                    hsMs = o.optLong("hsMs", 0),
                    rttMs = o.optLong("rttMs", 0),
                    echMs = o.optLong("echMs", 0),
                    target = o.optString("target", ""),
                    err = o.optString("err", ""),
                    at = o.optLong("at", 0)
                )
            }
        } catch (_: Exception) {
            out.clear() // 文件坏了就当没测过，不影响使用
        }
        return out
    }

    /** 整体写回（条目就几十个，直接全量覆盖最省事）。 */
    fun save(ctx: Context, results: Map<String, NodeTester.Result>) {
        try {
            val cfg = ConfigStore.load(ctx) ?: return
            val items = JSONObject()
            results.forEach { (k, r) ->
                items.put(
                    k,
                    JSONObject()
                        .put("ok", r.ok)
                        .put("stage", r.stage)
                        .put("ms", r.ms)
                        .put("hsMs", r.hsMs)
                        .put("rttMs", r.rttMs)
                        .put("echMs", r.echMs)
                        .put("target", r.target)
                        .put("err", r.err)
                        .put("at", r.at)
                )
            }
            val root = JSONObject().put("sig", signature(cfg)).put("items", items)
            ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(root.toString().toByteArray())
            }
        } catch (_: Exception) {
        }
    }

    /** 结果是否已过期（给界面加 * 用）。 */
    fun isStale(r: NodeTester.Result, now: Long = System.currentTimeMillis()): Boolean =
        r.at > 0 && now - r.at > STALE_MS

    /** 当前配置的指纹；界面上用来判断"设置改过了，内存里的结果也该作废"。 */
    fun currentSig(ctx: Context): String? = ConfigStore.load(ctx)?.let { signature(it) }

    /** 配置指纹：只关心会影响测试结果的字段。 */
    private fun signature(s: ConfigStore.Server): String {
        val raw = listOf(s.domain, s.ech, s.doh, s.token).joinToString("\u0001")
        val md = MessageDigest.getInstance("MD5")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
