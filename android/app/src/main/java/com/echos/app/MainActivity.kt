package com.echos.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var cardsContainer: LinearLayout
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var statusError: TextView
    private lateinit var emptyView: View

    private val handler = Handler(Looper.getMainLooper())

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
            showAddMenu()
        }
        findViewById<View>(R.id.btnAddBottom).setOnClickListener {
            openAddNodeDialog()
        }
        findViewById<View>(R.id.btnEmptyAdd).setOnClickListener {
            openAddNodeDialog()
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
        cardsContainer.removeAllViews()
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        val hasCards = cfg.cards.isNotEmpty()
        cardsContainer.visibility = if (hasCards) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasCards) View.GONE else View.VISIBLE
        findViewById<View>(R.id.listHeader).visibility =
            if (hasCards) View.VISIBLE else View.GONE
        // ProxyCloud 空状态时不显示底部控制区
        val bottomArea = listOf(
            findViewById<View>(R.id.statusCard),
            findViewById<View>(R.id.btnStart),
            findViewById<View>(R.id.btnStop),
            findViewById<View>(R.id.btnAddBottom)
        )
        bottomArea.forEach { it.visibility = if (hasCards) View.VISIBLE else View.GONE }
        if (hasCards) {
            cfg.cards.forEachIndexed { i, card -> addCardView(i, card, cfg.activeCard) }
        }
    }

    private fun addCardView(index: Int, card: ConfigStore.EntryCard, activeIndex: Int) {
        val v = layoutInflater.inflate(R.layout.item_entry_card, cardsContainer, false)
        val cardRoot = v.findViewById<MaterialCardView>(R.id.cardRoot)
        val cardIcon = v.findViewById<TextView>(R.id.cardIcon)
        val cardTitle = v.findViewById<TextView>(R.id.cardTitle)
        val cardEndpoint = v.findViewById<TextView>(R.id.cardEndpoint)
        val activeTag = v.findViewById<TextView>(R.id.cardActive)
        val btnMenu = v.findViewById<TextView>(R.id.btnMenu)

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
        cardIcon.text = if (active) "●" else "○"
        cardIcon.setTextColor(
            if (active) resources.getColor(R.color.ech_green, theme)
            else resources.getColor(R.color.ech_stroke, theme)
        )
        activeTag.visibility = if (active) View.VISIBLE else View.GONE

        fun indexOfCard() = cardsContainer.indexOfChild(v)

        // 三点菜单：编辑 / 删除
        btnMenu.setOnClickListener { menuBtn ->
            PopupMenu(this, menuBtn).apply {
                menu.add(getString(R.string.edit_node))
                menu.add(getString(R.string.delete))
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        getString(R.string.edit_node) -> openCardEditor(indexOfCard())
                        getString(R.string.delete) -> {
                            if ((ConfigStore.load(this@MainActivity)?.cards?.size ?: 0) <= 1) {
                                Toast.makeText(
                                    this@MainActivity, "至少保留一个线路卡片",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                ConfigStore.removeCard(this@MainActivity, indexOfCard())
                                rebuildCards()
                            }
                        }
                    }
                    true
                }
                show()
            }
        }

        // 点卡片本体 = 切换为使用中线路
        cardRoot.setOnClickListener {
            val i = indexOfCard()
            ConfigStore.setActive(this@MainActivity, i)
            rebuildCards()
            if (ProxyService.isRunning) ProxyService.restart(this@MainActivity)
        }

        cardsContainer.addView(v)
    }

    /** 加号二级页面：手动添加节点 / 从剪贴板导入。 */
    private fun showAddMenu() {
        val items = arrayOf(getString(R.string.add_node), getString(R.string.import_clipboard))
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_EchOS)
            .setTitle(getString(R.string.add_menu_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openAddNodeDialog()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** ProxyCloud 风格：添加节点对话框（节点地址 / 节点端口 / 备注）。 */
    private fun openAddNodeDialog() {
        val etIp = EditText(this)
        etIp.hint = getString(R.string.node_ip)
        etIp.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etIp.setTextColor(Color.WHITE)
        etIp.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etIp.textSize = 15f
        etIp.setSingleLine(true)

        val etPort = EditText(this)
        etPort.hint = getString(R.string.node_port)
        etPort.inputType = InputType.TYPE_CLASS_NUMBER
        etPort.setTextColor(Color.WHITE)
        etPort.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etPort.textSize = 15f
        etPort.setSingleLine(true)

        val etRemark = EditText(this)
        etRemark.hint = getString(R.string.remark_hint)
        etRemark.inputType = InputType.TYPE_CLASS_TEXT
        etRemark.setTextColor(Color.WHITE)
        etRemark.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etRemark.textSize = 15f
        etRemark.setSingleLine(true)

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

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_EchOS)
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
