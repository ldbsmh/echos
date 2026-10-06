package com.echos.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 日志页：内核 + VPN + App 全部日志，实时刷新。 */
class LogActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogSize = -1

    private val tick = object : Runnable {
        override fun run() {
            val lines = ProxyService.logLines()
            if (lines.size != lastLogSize) {
                lastLogSize = lines.size
                logView.text = lines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)

        findViewById<TextView>(R.id.btnClearLog).setOnClickListener {
            ProxyService.clearLogs()
            lastLogSize = -1
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }
}
