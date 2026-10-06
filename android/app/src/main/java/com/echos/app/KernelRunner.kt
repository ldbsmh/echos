package com.echos.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Go 内核进程的唯一管理器。
 * ProxyService（本地模式）与 EchVpnService（VPN 模式）二选一调用，
 * 避免同时启动两个前台 Service 和两条通知。
 */
object KernelRunner {
    @Volatile
    var isRunning = false
        private set

    private var process: Process? = null

    @Synchronized
    fun start(ctx: Context): Boolean {
        if (isRunning && process?.isAlive == true) return true

        val cfg = ConfigStore.load(ctx) ?: run {
            ProxyService.log("[App] 配置为空，请先填写并保存")
            return false
        }
        if (!cfg.isValid()) {
            ProxyService.log("[App] 配置不完整（服务地址 / 线路端口 / 监听端口）")
            return false
        }
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxtun.so")
        if (!bin.exists()) {
            ProxyService.log("[App] 找不到内核二进制: ${bin.absolutePath}")
            return false
        }
        val kernelArgs = ConfigStore.buildArgs(cfg) ?: run {
            ProxyService.log("[App] 内核参数生成失败（配置非法）")
            return false
        }
        val args = mutableListOf(bin.absolutePath).apply { addAll(kernelArgs) }
        ProxyService.log("[App] 启动内核: ${args.drop(1).joinToString(" ")}")

        return try {
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["HOME"] = ctx.filesDir.absolutePath
            val proc = pb.start()
            process = proc
            isRunning = true
            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                        lines.forEach { ProxyService.log(it) }
                    }
                } catch (_: Exception) {
                }
                val code = try { proc.waitFor() } catch (_: Exception) { -1 }
                synchronized(this) {
                    if (process === proc) {
                        process = null
                        isRunning = false
                    }
                }
                ProxyService.log("[App] 内核已退出 (exit=$code)")
            }, "EchOS-kernel-log").start()
            true
        } catch (e: Exception) {
            process = null
            isRunning = false
            ProxyService.log("[App] 启动失败: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        isRunning = false
        if (p != null) {
            try {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        ProxyService.log("[App] 代理已停止")
    }
}
