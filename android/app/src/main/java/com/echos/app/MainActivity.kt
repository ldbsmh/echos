package com.echos.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.EditText
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
    private lateinit var statusDot: View
    private lateinit var statusError: TextView
    private lateinit var emptyView: View

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
            val connected = running || vpn
            // 状态圆点：绿=运行/VPN，红=断开
            statusDot.setBackgroundResource(
                if (connected) R.drawable.bg_status_dot_ok else R.drawable.bg_status_dot
            )
            statusView.text = when {
                running && vpn -> getString(R.string.connected)
                running -> getString(R.string.connected)
                vpn -> getString(R.string.connected)
                else -> getString(R.string.disconnected)
            }
            statusView.setTextColor(
                if (connected) resources.getColor(R.color.ech_green, theme)
                else resources.getColor(R.color.ech_text, theme)
            )
            // 错误信息（如有）
            val err = ProxyService.lastError
            if (err.isNullOrBlank()) {
                statusError.visibility = View.GONE
            } else {
                statusError.text = err
                statusError.visibility = View.VISIBLE
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
        statusDot = findViewById(R.id.statusDot)
        statusError = findViewById(R.id.statusError)
        emptyView = findViewById(R.id.emptyView)

        findViewById<View>(R.id.btnAddCard).setOnClickListener {
            openAddNodeDialog()
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
        if (cfg.cards.isEmpty()) {
            cardsContainer.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            cardsContainer.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            cfg.cards.forEachIndexed { i, card -> addCardView(i, card, cfg.activeCard) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addCardView(index: Int, card: ConfigStore.EntryCard, activeIndex: Int) {
        val v = layoutInflater.inflate(R.layout.item_entry_card, cardsContainer, false)
        val cardRoot = v.findViewById<MaterialCardView>(R.id.cardRoot)
        val cardTitle = v.findViewById<TextView>(R.id.cardTitle)
        val cardEndpoint = v.findViewById<TextView>(R.id.cardEndpoint)
        val activeTag = v.findViewById<TextView>(R.id.cardActive)
        val btnEdit = v.findViewById<TextView>(R.id.btnEdit)
        val btnDelete = v.findViewById<TextView>(R.id.btnDelete)
        val actionBar = v.findViewById<LinearLayout>(R.id.actionBar)

        cardTitle.text = card.remark.ifBlank { card.display() }
        cardEndpoint.text = card.display()
        val active = index == activeIndex
        cardRoot.strokeWidth = if (active) 3 else 1
        cardRoot.strokeColor =
            if (active) resources.getColor(R.color.ech_green, theme)
            else resources.getColor(R.color.ech_stroke, theme)
        cardRoot.setCardBackgroundColor(
            if (active) resources.getColor(R.color.ech_active_bg, theme)
            else resources.getColor(R.color.ech_card, theme)
        )
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

    /** ProxyCloud 风格：添加节点对话框（节点地址 / 节点端口 / 备注）。 */
    private fun openAddNodeDialog() {
        val etIp = EditText(this)
        etIp.hint = getString(R.string.node_ip)
        etIp.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etIp.setTextColor(Color.WHITE)
        etIp.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etIp.textSize = 15f
        etIp.singleLine = true

        val etPort = EditText(this)
        etPort.hint = getString(R.string.node_port)
        etPort.inputType = InputType.TYPE_CLASS_NUMBER
        etPort.setTextColor(Color.WHITE)
        etPort.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etPort.textSize = 15f
        etPort.singleLine = true

        val etRemark = EditText(this)
        etRemark.hint = getString(R.string.remark_hint)
        etRemark.inputType = InputType.TYPE_CLASS_TEXT
        etRemark.setTextColor(Color.WHITE)
        etRemark.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etRemark.textSize = 15f
        etRemark.singleLine = true

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(24, 8, 24, 0)
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.addView(etIp, lp)
        form.addView(etPort, lp)
        form.addView(etRemark, lp)

        val dialog = AlertDialog.Builder(this, R.style.Theme_EchOS)
            .setTitle(R.string.add_node)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val ip = etIp.text.toString().trim()
                val portStr = etPort.text.toString().trim()
                val remark = etRemark.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, getString(R.string.ip_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val port = portStr.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(this, getString(R.string.port_invalid), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cfg = ConfigStore.load(this) ?: ConfigStore.default()
                val cards = cfg.cards.toMutableList()
                cards.add(ConfigStore.EntryCard(ip, port, remark))
                ConfigStore.save(this, cfg.copy(cards = cards, activeCard = cfg.activeCard))
                rebuildCards()
            }
            .create()
        dialog.show()
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
