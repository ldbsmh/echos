package com.echos.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var cardsContainer: LinearLayout
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var openSwipeCard: View? = null
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                ProxyService.log("[VPN] 授权被拒绝，仅本地代理模式运行")
            }
        }

    private val tick = object : Runnable {
        override fun run() {
            val running = ProxyService.isRunning
            val vpn = EchVpnService.isVpnRunning
            statusView.text = when {
                running && vpn -> "运行中 · VPN 全局接管"
                running -> "运行中 · 本地代理"
                vpn -> "仅 VPN（内核未运行）"
                else -> getString(R.string.stopped)
            }
            btnStart.isEnabled = !running && !vpn
            btnStop.isEnabled = running || vpn
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardsContainer = findViewById(R.id.cardsContainer)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        statusView = findViewById(R.id.statusView)

        findViewById<View>(R.id.btnAddCard).setOnClickListener {
            val idx = ConfigStore.addCard(this, ConfigStore.EntryCard("", 443))
            openCardEditor(idx)
        }
        findViewById<View>(R.id.btnImport).setOnClickListener { importFromClipboard() }
        findViewById<View>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            // 先给 VPN 服务明确的清理动作，再 stopService 兜底关闭 TUN fd。
            EchVpnService.stop(this)
            stopService(Intent(this, ProxyService::class.java))
            getSystemService(android.app.NotificationManager::class.java)
                .cancel(ProxyService.NOTIF_ID)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildCards()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ==================== 线路卡片 ====================

    private fun rebuildCards() {
        openSwipeCard = null
        cardsContainer.removeAllViews()
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        cfg.cards.forEachIndexed { i, card -> addCardView(i, card, cfg.activeCard) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addCardView(index: Int, card: ConfigStore.EntryCard, activeIndex: Int) {
        val v = layoutInflater.inflate(R.layout.item_entry_card, cardsContainer, false)
        val cardRoot = v.findViewById<MaterialCardView>(R.id.cardRoot)
        val rowText = v.findViewById<TextView>(R.id.cardText)
        val activeTag = v.findViewById<TextView>(R.id.cardActive)
        val btnEdit = v.findViewById<TextView>(R.id.btnEdit)
        val btnDelete = v.findViewById<TextView>(R.id.btnDelete)
        val actionBar = v.findViewById<LinearLayout>(R.id.actionBar)

        rowText.text = card.display()
        val active = index == activeIndex
        cardRoot.strokeWidth = if (active) 3 else 1
        cardRoot.strokeColor =
            if (active) Color.parseColor("#0B57D0") else Color.parseColor("#E0E0E0")
        activeTag.visibility = if (active) View.VISIBLE else View.GONE

        fun indexOfCard() = cardsContainer.indexOfChild(v)

        fun closeAllSwipes() {
            for (i in 0 until cardsContainer.childCount) {
                cardsContainer.getChildAt(i)
                    .findViewById<MaterialCardView>(R.id.cardRoot)
                    .animate().translationX(0f).setDuration(140).start()
            }
            openSwipeCard = null
        }

        btnEdit.setOnClickListener {
            closeAllSwipes()
            openCardEditor(indexOfCard())
        }
        btnDelete.setOnClickListener {
            closeAllSwipes()
            if ((ConfigStore.load(this)?.cards?.size ?: 0) <= 1) {
                Toast.makeText(this, "至少保留一个线路卡片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ConfigStore.removeCard(this@MainActivity, indexOfCard())
            rebuildCards()
        }

        // 点卡片本体 = 切换为使用中线路
        cardRoot.setOnClickListener {
            if (openSwipeCard != null) {
                closeAllSwipes()
                return@setOnClickListener
            }
            val i = indexOfCard()
            ConfigStore.setActive(this@MainActivity, i)
            rebuildCards()
            if (ProxyService.isRunning) ProxyService.restart(this@MainActivity)
        }

        // 左滑 ~1/4 宽度露出「编辑 / 删除」
        var downX = 0f
        var downY = 0f
        var horizontal = false
        var swiping = false
        var reveal = 0

        cardRoot.setOnTouchListener { vw, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    horizontal = false; swiping = false
                    reveal = btnEdit.width + btnDelete.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!horizontal && !swiping) {
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            horizontal = true
                            swiping = true
                            (vw.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                            // 同时只允许一张卡片处于展开状态
                            if (openSwipeCard != null && openSwipeCard !== vw) {
                                openSwipeCard?.findViewById<MaterialCardView>(R.id.cardRoot)
                                    ?.animate()?.translationX(0f)?.setDuration(120)?.start()
                                openSwipeCard = null
                            }
                        } else if (abs(dy) > touchSlop) {
                            return@setOnTouchListener false // 交给外层 ScrollView
                        }
                    }
                    if (swiping) {
                        val base = if (openSwipeCard === vw) -reveal.toFloat() else 0f
                        vw.translationX = (base + dx).coerceIn(-reveal.toFloat(), 0f)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (swiping) {
                        swiping = false
                        if (vw.translationX < -reveal / 2f) {
                            openSwipeCard = vw
                            vw.animate().translationX(-reveal.toFloat()).setDuration(140).start()
                        } else {
                            vw.animate().translationX(0f).setDuration(140).start()
                            if (openSwipeCard === vw) openSwipeCard = null
                        }
                        true
                    } else if (!horizontal) {
                        vw.performClick()
                        true
                    } else false
                }
                MotionEvent.ACTION_CANCEL -> {
                    val wasOpen = openSwipeCard === vw
                    val target = if (wasOpen || vw.translationX < -reveal / 2f) {
                        -reveal.toFloat()
                    } else 0f
                    vw.animate().translationX(target).setDuration(120).start()
                    openSwipeCard = if (target < 0f) vw else null
                    swiping = false; horizontal = false
                    true
                }
                else -> false
            }
        }

        cardsContainer.addView(v)
    }

    private fun openCardEditor(index: Int) {
        startActivity(
            Intent(this, CardEditActivity::class.java).putExtra("index", index)
        )
    }

    // ==================== 剪贴板导入 ====================

    private fun importFromClipboard() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = try {
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }
        if (text.isBlank()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = ConfigStore.parseProxyList(text)
        if (parsed.isEmpty()) {
            Toast.makeText(this, "未识别到线路（需要 IP:端口 格式）", Toast.LENGTH_LONG).show()
            return
        }
        val old = ConfigStore.load(this) ?: ConfigStore.default()
        val existing = old.cards.map { "${it.ips}:${it.port}" }.toSet()
        val fresh = parsed.filter { "${it.ips}:${it.port}" !in existing }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("识别到 ${parsed.size} 条线路")
            .setMessage(
                "其中新线路 ${fresh.size} 条，与现有重复 ${parsed.size - fresh.size} 条。\n\n" +
                    "「追加」保留现有线路并加入新的；「替换全部」清空后导入。"
            )
            .setPositiveButton("追加") { _, _ ->
                val merged = old.cards + fresh
                ConfigStore.save(
                    this, old.copy(
                        cards = merged,
                        activeCard = old.activeCard.coerceIn(0, merged.size - 1)
                    )
                )
                rebuildCards()
                Toast.makeText(this, "已追加 ${fresh.size} 条线路", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("替换全部") { _, _ ->
                ConfigStore.save(this, old.copy(cards = parsed, activeCard = 0))
                rebuildCards()
                Toast.makeText(this, "已导入 ${parsed.size} 条线路", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    // ==================== 启动 / 停止 ====================

    private fun onStartClicked() {
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        val card = cfg.active
        if (cfg.domain.isBlank() || card == null || card.port !in 1..65535) {
            Toast.makeText(
                this, "请先在「设置」填写服务地址，并确保当前线路端口有效",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        ProxyService.clearLogs()
        if (cfg.vpn) {
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                vpnPermissionLauncher.launch(prepare)
            } else {
                startVpn()
            }
        } else {
            ContextCompat.startForegroundService(
                this, Intent(this, ProxyService::class.java)
                    .setAction(ProxyService.ACTION_START)
            )
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(
            this, Intent(this, EchVpnService::class.java)
                .setAction(EchVpnService.ACTION_START)
        )
    }
}
