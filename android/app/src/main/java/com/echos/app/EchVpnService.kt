package com.echos.app

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * VPN 模式唯一的前台 Service。
 * 生命周期严格参考 ECH-Workers_1.0.apk：
 * stopForeground → 停内核 → 停 native → close 原始 tunFd → stopSelf。
 */
class EchVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.echos.app.VPN_START"
        const val ACTION_STOP = "com.echos.app.VPN_STOP"
        const val TUN_ADDR = "198.18.0.1"
        const val TUN_DNS = "198.18.0.2"
        const val TUN_V6 = "fc00::1"

        @Volatile
        var isVpnRunning = false
            private set

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, EchVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val tproxy = hev.htproxy.TProxyService()
    /** Builder.establish() 返回的原始 PFD；不 dup、不 detach，停止时直接 close。 */
    private var tunFd: ParcelFileDescriptor? = null
    private var stopping = false

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        ProxyService.createChannelStatic(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (tunFd != null) return START_STICKY
        startForegroundCompat()
        if (!startVpn()) shutdown()
        return START_STICKY
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = ProxyService.buildNotification(this, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ProxyService.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ProxyService.NOTIF_ID, n)
        }
    }

    private fun startVpn(): Boolean {
        val cfg = ConfigStore.load(this) ?: run {
            ProxyService.log("[VPN] 配置为空，请先填写并保存")
            return false
        }
        if (!cfg.isValid()) {
            ProxyService.log("[VPN] 配置不完整（服务地址 / 线路端口 / 监听端口）")
            return false
        }

        // VPN 模式由本 Service 自己启动内核，不再启动 ProxyService，因此只有一条通知。
        if (!KernelRunner.start(this)) return false

        val builder = Builder()
            .setSession("EchOS VPN")
            .setBlocking(false)
            .setMtu(8500)
            .addAddress(TUN_ADDR, 32)
            .addAddress(TUN_V6, 128)
            .addDnsServer(TUN_DNS)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        // allowlist 与 denylist 不能混用。
        when (cfg.appMode) {
            "allow" -> {
                cfg.appList.filter { it != packageName }.forEach { pkg ->
                    try { builder.addAllowedApplication(pkg) }
                    catch (_: Exception) { ProxyService.log("[VPN] 跳过不存在的应用: $pkg") }
                }
                ProxyService.log("[VPN] 仅 ${cfg.appList.count { it != packageName }} 个应用走代理")
            }
            "exclude" -> {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                cfg.appList.filter { it != packageName }.forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) }
                    catch (_: Exception) { ProxyService.log("[VPN] 跳过不存在的应用: $pkg") }
                }
                ProxyService.log("[VPN] 排除 ${cfg.appList.count { it != packageName }} 个应用")
            }
            else -> {
                // 内核在同一 App UID 中，必须绕过自己建立的 VPN。
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
        }

        val pfd = try { builder.establish() } catch (e: Exception) {
            ProxyService.log("[VPN] TUN 建立异常: ${e.message}")
            null
        } ?: return false
        tunFd = pfd

        val conf = File(filesDir, "hev.yml")
        try {
            conf.writeText(
                """
                tunnel:
                  name: tun0
                  mtu: 8500
                  ipv4: $TUN_ADDR
                  ipv6: '$TUN_V6'
                socks5:
                  port: ${cfg.listenPort}
                  address: 127.0.0.1
                  udp: 'udp'
                mapdns:
                  address: $TUN_DNS
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  log-file: '${File(filesDir, "hev.log").absolutePath}'
                  log-level: info
                """.trimIndent()
            )
        } catch (e: Exception) {
            ProxyService.log("[VPN] 写配置失败: ${e.message}")
            return false
        }

        // 与参考 APK 一致：native 直接使用原始 PFD 的 getFd()，不复制所有权。
        val ok = try { tproxy.TProxyStartService(conf.absolutePath, pfd.fd) }
        catch (e: Throwable) {
            ProxyService.log("[VPN] hev 启动异常: ${e.message}")
            false
        }
        if (!ok) return false

        isVpnRunning = true
        ProxyService.log("[VPN] TUN 已建立，全局接管生效（SOCKS5 127.0.0.1:${cfg.listenPort}）")
        return true
    }

    @Synchronized
    private fun shutdown() {
        if (stopping) return
        stopping = true

        // 顺序与参考 APK 相同：先撤前台，再停内核/native，最后关闭原始 fd。
        removeForeground()
        cleanupResources()
        stopSelf()
    }

    private fun cleanupResources() {
        KernelRunner.stop()
        try { tproxy.TProxyStopService() } catch (_: Throwable) {}
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
        isVpnRunning = false
        ProxyService.log("[VPN] 全局接管已停止")
        getSystemService(NotificationManager::class.java).cancel(ProxyService.NOTIF_ID)
    }

    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
