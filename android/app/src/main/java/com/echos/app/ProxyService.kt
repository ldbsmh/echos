package com.echos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 本地代理模式的唯一前台 Service；VPN 模式不会启动本服务。 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.echos.app.action.START"
        const val ACTION_STOP = "com.echos.app.action.STOP"
        const val CHANNEL_ID = "proxy"
        const val NOTIF_ID = 1
        const val MAX_LOG_LINES = 400

        @Volatile
        var isServiceRunning = false
            private set

        val isRunning: Boolean
            get() = KernelRunner.isRunning

        private val logBuffer = ArrayDeque<String>()

        fun logLines(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun log(line: String) {
            synchronized(logBuffer) {
                logBuffer.addLast(line)
                while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            }
        }

        fun clearLogs() = synchronized(logBuffer) { logBuffer.clear() }

        fun createChannelStatic(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                ctx.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }

        fun buildNotification(ctx: Context, vpn: Boolean): Notification {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(if (vpn) "EchOS VPN 运行中" else ctx.getString(R.string.notif_title))
                .setContentText(if (vpn) "全部流量经 ECH 隧道转发" else ctx.getString(R.string.notif_text))
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
        }

        fun restart(ctx: Context) {
            KernelRunner.stop()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                KernelRunner.start(ctx.applicationContext)
            }, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelStatic(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        isServiceRunning = true
        startForegroundCompat()
        if (!KernelRunner.start(this)) shutdown()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        KernelRunner.stop()
        isServiceRunning = false
        removeForeground()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = buildNotification(this, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun shutdown() {
        KernelRunner.stop()
        isServiceRunning = false
        removeForeground()
        stopSelf()
    }

    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
