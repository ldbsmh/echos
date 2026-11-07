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

    /** 每条线路的测速结果 / 正在测试状态 / 卡片上的徽章，键为 IP:端口。 */
    private val testResults = mutableMapOf<String, NodeTester.Result>()
    private val testingKeys = mutableSetOf<String>()
    private val delayBadges = mutableMapOf<String, TextView>()

    /** 测速结果对应的配置指纹；服务地址/Token 等改过后旧结果要作废。 */
    private var resultSig: String? = null

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

        // 上次的测速结果（含时间戳）读回来，重启后徽章和排序都在
        testResults.putAll(TestResultStore.load(this))
        resultSig = TestResultStore.currentSig(this)

        findViewById<View>(R.id.btnAddCard).setOnClickListener {
            showAddMenu()
        }
        findViewById<View>(R.id.btnEmptyAdd).setOnClickListener {
            openNodeDialog(null)
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnTestAll).setOnClickListener { testAll() }
        findViewById<TextView>(R.id.btnSort).setOnClickListener { toggleSort() }

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
        // 排序/测速完成都会重建视图，先把滚动位置记下来，重建后还原，免得列表跳回顶部
        val scroll = findViewById<android.widget.ScrollView>(R.id.cardsScroll)
        val scrollY = scroll.scrollY
        cardsContainer.removeAllViews()
        delayBadges.clear() // 视图重建，旧引用作废；结果本身留着，重建后照常显示
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        // 服务地址 / ECH / DoH / Token 改过 → 旧结果不再有参考价值，整体作废
        val sig = TestResultStore.currentSig(this)
        if (resultSig != null && sig != resultSig) {
            testResults.clear()
            TestResultStore.save(this, testResults)
        }
        resultSig = sig
        // 线路被删/被改（key 变了）后，旧结果留着只会占地方，顺手清掉
        val liveKeys = cfg.cards.map { NodeTester.key(it) }.toSet()
        if (testResults.keys.retainAll(liveKeys)) TestResultStore.save(this, testResults)
        val hasCards = cfg.cards.isNotEmpty()
        cardsContainer.visibility = if (hasCards) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasCards) View.GONE else View.VISIBLE
        findViewById<View>(R.id.listHeader).visibility =
            if (hasCards) View.VISIBLE else View.GONE
        // 排序开关的颜色就是开关状态本身（开=绿），文字也带上状态，避免看不出来
        val btnSort = findViewById<TextView>(R.id.btnSort)
        btnSort.text = getString(
            if (cfg.sortByLatency) R.string.sort_latency_on else R.string.sort_latency
        )
        btnSort.setTextColor(
            ContextCompat.getColor(
                this,
                if (cfg.sortByLatency) R.color.ech_green else R.color.ech_sub
            )
        )
        // ProxyCloud 空状态时不显示底部控制区
        val bottomArea = listOf(
            findViewById<View>(R.id.statusCard),
            findViewById<View>(R.id.btnStart),
            findViewById<View>(R.id.btnStop)
        )
        bottomArea.forEach { it.visibility = if (hasCards) View.VISIBLE else View.GONE }
        if (hasCards) {
            // 注意：这里的 index 始终是「配置里的真实下标」，排序只改显示顺序，
            // 切换/编辑/删除都还按真实下标走，不会因为排序而改错线路。
            displayOrder(cfg).forEach { (i, card) -> addCardView(i, card, cfg.activeCard) }
        }
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    /**
     * 显示顺序：默认就是配置顺序；开了「按延迟排序」后
     * 通且快的在前 → 通了但慢的 → 没连通的 → 没测过的垫底，同级保持原顺序。
     * sortedWith 是稳定排序，所以同延迟的线路不会乱跳。
     */
    private fun displayOrder(
        cfg: ConfigStore.Server
    ): List<Pair<Int, ConfigStore.EntryCard>> {
        val indexed = cfg.cards.mapIndexed { i, c -> i to c }
        if (!cfg.sortByLatency) return indexed

        fun result(card: ConfigStore.EntryCard) = testResults[NodeTester.key(card)]
        fun rank(card: ConfigStore.EntryCard) = when {
            result(card)?.ok == true -> 0   // 真通了
            result(card) != null -> 1       // 测过但不通 / 仅握手
            else -> 2                       // 还没测
        }
        fun latency(card: ConfigStore.EntryCard) = result(card)?.ms ?: Long.MAX_VALUE

        return indexed.sortedWith(compareBy({ rank(it.second) }, { latency(it.second) }))
    }

    private fun addCardView(index: Int, card: ConfigStore.EntryCard, activeIndex: Int) {
        val v = layoutInflater.inflate(R.layout.item_entry_card, cardsContainer, false)
        val cardRoot = v.findViewById<MaterialCardView>(R.id.cardRoot)
        val cardIcon = v.findViewById<TextView>(R.id.cardIcon)
        val cardTitle = v.findViewById<TextView>(R.id.cardTitle)
        val cardEndpoint = v.findViewById<TextView>(R.id.cardEndpoint)
        val activeTag = v.findViewById<TextView>(R.id.cardActive)
        val btnMenu = v.findViewById<TextView>(R.id.btnMenu)
        val cardDelay = v.findViewById<TextView>(R.id.cardDelay)

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

        // 延迟徽章：点一下重新测试，长按看详情
        val key = NodeTester.key(card)
        delayBadges[key] = cardDelay
        renderDelay(key)
        cardDelay.setOnClickListener { testNode(index) }
        cardDelay.setOnLongClickListener {
            showTestDetail(key)
            true
        }

        // 三点菜单：编辑 / 删除
        btnMenu.setOnClickListener { menuBtn ->
            PopupMenu(this, menuBtn).apply {
                menu.add(getString(R.string.test_latency))
                menu.add(getString(R.string.edit_node))
                menu.add(getString(R.string.delete))
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        getString(R.string.test_latency) ->
                            handler.postDelayed({ testNode(index) }, 90L)
                        getString(R.string.edit_node) ->
                            handler.postDelayed({ openNodeDialog(index) }, 90L)
                        getString(R.string.delete) -> {
                            if ((ConfigStore.load(this@MainActivity)?.cards?.size ?: 0) <= 1) {
                                Toast.makeText(
                                    this@MainActivity, "至少保留一个线路卡片",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                ConfigStore.removeCard(this@MainActivity, index)
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
            ConfigStore.setActive(this@MainActivity, index)
            rebuildCards()
            if (ProxyService.isRunning) ProxyService.restart(this@MainActivity)
        }

        cardsContainer.addView(v)
    }

    // ==================== 连通性测试（测延迟） ====================

    /**
     * 测一条线路。实际握手在内核子进程里做（ECH 只有 Go 侧支持），
     * 这里只负责状态管理和界面反馈。
     */
    private fun testNode(index: Int, toast: Boolean = true) {
        val cfg = ConfigStore.load(this) ?: return
        val card = cfg.cards.getOrNull(index) ?: return
        val key = NodeTester.key(card)
        if (!testingKeys.add(key)) return // 同一条正在测，忽略重复点击
        renderDelay(key)

        val label = card.remark.ifBlank { card.display() }
        NodeTester.test(this, cfg, card) { r ->
            testingKeys.remove(key)
            testResults[key] = r
            TestResultStore.save(this, testResults) // 落盘，重启后不用重测
            if (isFinishing || isDestroyed) return@test // 页面已销毁，只留结果不弹提示
            renderDelay(key)
            if (toast) {
                Toast.makeText(
                    this,
                    if (r.ok) getString(R.string.test_ok_toast, label, r.ms)
                    else getString(R.string.test_fail_toast, label, r.err.ifBlank { r.summary() }),
                    Toast.LENGTH_SHORT
                ).show()
            }
            // 排序模式下，一轮测试全部结束再重排一次：
            // 中途每条都重排会让列表边测边跳，看着很晕，滚动位置也保不住。
            if (testingKeys.isEmpty() && ConfigStore.load(this)?.sortByLatency == true) {
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) rebuildCards()
                }, 300L)
            }
        }
    }

    /** 全部测速：逐条并发跑（NodeTester 内部限 4 并发），结果就地刷新到各卡片。 */
    private fun testAll() {
        val cfg = ConfigStore.load(this) ?: return
        if (cfg.cards.isEmpty()) return
        cfg.cards.indices.forEach { testNode(it, toast = false) }
        Toast.makeText(this, "开始测试 ${cfg.cards.size} 条线路", Toast.LENGTH_SHORT).show()
    }

    /** 切换「按延迟排序」。开启时若一条结果都没有，顺手先跑一轮测速。 */
    private fun toggleSort() {
        val cfg = ConfigStore.load(this) ?: return
        val on = !cfg.sortByLatency
        ConfigStore.setSortByLatency(this, on)
        rebuildCards()
        if (!on) {
            Toast.makeText(this, getString(R.string.sort_off), Toast.LENGTH_SHORT).show()
            return
        }
        if (testResults.isEmpty() && testingKeys.isEmpty()) {
            Toast.makeText(this, getString(R.string.sort_no_data), Toast.LENGTH_SHORT).show()
            testAll()
        } else {
            // 结果都是从本地恢复的旧数据时，提醒一句（排序照旧生效）
            val allStale = testResults.isNotEmpty() &&
                testResults.values.all { TestResultStore.isStale(it) }
            Toast.makeText(
                this,
                getString(if (allStale) R.string.sort_on_stale else R.string.sort_on),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 把测试结果画到卡片右侧的徽章上；没有结果就隐藏。 */
    private fun renderDelay(key: String) {
        val tv = delayBadges[key] ?: return
        val r = testResults[key]
        when {
            key in testingKeys -> {
                tv.visibility = View.VISIBLE
                tv.text = getString(R.string.testing)
                tv.setTextColor(ContextCompat.getColor(this, R.color.ech_sub))
            }

            r == null -> tv.visibility = View.GONE

            r.ok -> {
                tv.visibility = View.VISIBLE
                // 太久以前的结果加 * 提醒，避免把旧数据当现在的延迟
                tv.text = "${r.ms} ms" + if (TestResultStore.isStale(r)) "*" else ""
                tv.setTextColor(ContextCompat.getColor(this, latencyColor(r.ms)))
            }

            // 服务端连得上、token 也对，但数据出不去：多半是 Worker 兼容日期
            r.stage == "handshake" -> {
                tv.visibility = View.VISIBLE
                tv.text = getString(R.string.test_half)
                tv.setTextColor(ContextCompat.getColor(this, R.color.ech_yellow))
            }

            else -> {
                tv.visibility = View.VISIBLE
                tv.text = if (r.err.contains("超时")) getString(R.string.test_timeout)
                else getString(R.string.test_failed)
                tv.setTextColor(ContextCompat.getColor(this, R.color.ech_red))
            }
        }
    }

    /** 延迟分档：300ms 内绿、800ms 内黄、再慢橙（仍是通的，只是慢）。 */
    private fun latencyColor(ms: Long): Int = when {
        ms <= 300 -> R.color.ech_green
        ms <= 800 -> R.color.ech_yellow
        else -> R.color.ech_orange
    }

    private fun showTestDetail(key: String) {
        val r = testResults[key]
        val msg = if (r == null) {
            getString(R.string.test_nothing)
        } else {
            buildString {
                append("状态：").append(r.summary()).append('\n')
                append("握手：").append(r.hsMs).append(" ms")
                if (r.echMs > 0) append("（其中 ECH 公钥查询 ").append(r.echMs).append(" ms）")
                append('\n')
                append("数据回包：").append(r.rttMs).append(" ms\n")
                append("测试目标：").append(r.target.ifBlank { "-" })
                if (r.err.isNotBlank()) append("\n失败原因：").append(r.err)
                if (r.at > 0) {
                    append("\n测试时间：").append(agoText(r.at))
                    if (TestResultStore.isStale(r)) append("（带 * 的是旧数据，建议重测）")
                }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.test_detail_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 相对时间文案，给测试详情用。 */
    private fun agoText(at: Long): String {
        val d = System.currentTimeMillis() - at
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000L} 分钟前"
            d < 86_400_000L -> "${d / 3_600_000L} 小时前"
            else -> "${d / 86_400_000L} 天前"
        }
    }

    /** 加号二级页面：手动添加节点 / 从剪贴板导入。 */
    private fun showAddMenu() {
        val anchor = findViewById<View>(R.id.btnAddCard)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, getString(R.string.add_node))
            menu.add(0, 2, 0, getString(R.string.import_clipboard))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> handler.postDelayed({ openNodeDialog(null) }, 90L)
                    2 -> handler.postDelayed({ importFromClipboard() }, 90L)
                }
                true
            }
            show()
        }
    }

    /**
     * 添加/编辑节点对话框（ProxyCloud 风格）。
     * @param index 非 null = 编辑该节点（预填当前值）；null = 新建。
     */
    private fun openNodeDialog(index: Int?) {
        val editing = index != null
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        val orig = if (editing && index!! in cfg.cards.indices) cfg.cards[index!!] else null

        val etIp = EditText(this)
        etIp.hint = getString(R.string.node_ip)
        etIp.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etIp.setTextColor(Color.WHITE)
        etIp.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etIp.textSize = 15f
        etIp.setSingleLine(true)
        if (orig != null) etIp.setText(orig.ips)

        val etPort = EditText(this)
        etPort.hint = getString(R.string.node_port)
        etPort.inputType = InputType.TYPE_CLASS_NUMBER
        etPort.setTextColor(Color.WHITE)
        etPort.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etPort.textSize = 15f
        etPort.setSingleLine(true)
        if (orig != null) etPort.setText(orig.port.toString())

        val etRemark = EditText(this)
        etRemark.hint = getString(R.string.remark_hint)
        etRemark.inputType = InputType.TYPE_CLASS_TEXT
        etRemark.setTextColor(Color.WHITE)
        etRemark.setHintTextColor(resources.getColor(R.color.ech_hint, theme))
        etRemark.textSize = 15f
        etRemark.setSingleLine(true)
        if (orig != null) etRemark.setText(orig.remark)

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

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (editing) R.string.edit_node else R.string.add_node)
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
                if (editing && orig != null) {
                    ConfigStore.updateCard(this, index!!, orig.copy(ips = ip, port = port, remark = remark))
                } else {
                    val cards = cfg.cards.toMutableList()
                    cards.add(ConfigStore.EntryCard(ip, port, remark))
                    ConfigStore.save(this, cfg.copy(cards = cards, activeCard = cfg.activeCard))
                }
                rebuildCards()
            }
            .show()
    }

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
