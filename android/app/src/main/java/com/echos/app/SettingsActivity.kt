package com.echos.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

/** 设置页：服务地址 / TOKEN / 监听地址与端口 / ECH / DOH / VPN / 分应用代理。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var switchVpn: MaterialSwitch
    private lateinit var switchAppFilter: MaterialSwitch
    private lateinit var rbAllow: RadioButton
    private lateinit var rbExclude: RadioButton
    private lateinit var rgAppMode: RadioGroup
    private lateinit var btnPickApps: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editDomain = findViewById<TextInputEditText>(R.id.editDomain)
        val editToken = findViewById<TextInputEditText>(R.id.editToken)
        val editListenAddr = findViewById<TextInputEditText>(R.id.editListenAddr)
        val editListenPort = findViewById<TextInputEditText>(R.id.editListenPort)
        val spinnerEch = findViewById<AutoCompleteTextView>(R.id.spinnerEch)
        val spinnerDoh = findViewById<AutoCompleteTextView>(R.id.spinnerDoh)
        switchVpn = findViewById(R.id.switchVpn)
        switchAppFilter = findViewById(R.id.switchAppFilter)
        rgAppMode = findViewById(R.id.rgAppMode)
        rbAllow = findViewById(R.id.rbAllow)
        rbExclude = findViewById(R.id.rbExclude)
        btnPickApps = findViewById(R.id.btnPickApps)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        setupPresetDropdown(spinnerEch, R.array.ech_domain_presets)
        setupPresetDropdown(spinnerDoh, R.array.doh_presets)

        ConfigStore.load(this)?.let { cfg ->
            editDomain.setText(cfg.domain)
            editToken.setText(cfg.token)
            editListenAddr.setText(cfg.listenAddr)
            editListenPort.setText(cfg.listenPort.toString())
            spinnerEch.setText(cfg.ech, false)
            spinnerDoh.setText(cfg.doh, false)
            switchVpn.isChecked = cfg.vpn
            switchAppFilter.isChecked = cfg.appMode != "off"
            if (cfg.appMode == "exclude") rbExclude.isChecked = true else rbAllow.isChecked = true
            refreshAppCount()
        }
        if (spinnerEch.text.isNullOrBlank()) spinnerEch.setText("cloudflare-ech.com", false)
        if (spinnerDoh.text.isNullOrBlank()) spinnerDoh.setText("dns.alidns.com/dns-query", false)

        // 分应用开关/模式的改动立即持久化，方便跳转选择页时带上状态
        switchAppFilter.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                rgAppMode.visibility = android.view.View.VISIBLE
                btnPickApps.visibility = android.view.View.VISIBLE
                val mode = if (rbExclude.isChecked) "exclude" else "allow"
                ConfigStore.setAppFilter(this, mode, null)
            } else {
                rgAppMode.visibility = android.view.View.GONE
                btnPickApps.visibility = android.view.View.GONE
                ConfigStore.setAppFilter(this, "off", null)
            }
        }

        rgAppMode.setOnCheckedChangeListener { _, _ ->
            if (switchAppFilter.isChecked) {
                val mode = if (rbExclude.isChecked) "exclude" else "allow"
                ConfigStore.setAppFilter(this, mode, null)
            }
        }

        btnPickApps.setOnClickListener {
            val mode = if (rbExclude.isChecked) "exclude" else "allow"
            val intent = Intent(this, AppPickerActivity::class.java)
                .putExtra("mode", mode)
                .putStringArrayListExtra(
                    "selected",
                    ArrayList(ConfigStore.load(this)?.appList ?: emptyList())
                )
            startActivity(intent)
        }

        btnSave.setOnClickListener {
            val old = ConfigStore.load(this)
            val s = ConfigStore.Server(
                domain = editDomain.text?.toString()?.trim() ?: "",
                ech = spinnerEch.text?.toString()?.trim() ?: "cloudflare-ech.com",
                doh = spinnerDoh.text?.toString()?.trim() ?: "dns.alidns.com/dns-query",
                token = editToken.text?.toString()?.trim() ?: "",
                listenAddr = editListenAddr.text?.toString()?.trim() ?: "127.0.0.1",
                listenPort = editListenPort.text?.toString()?.toIntOrNull() ?: 30000,
                vpn = switchVpn.isChecked,
                cards = old?.cards ?: listOf(ConfigStore.EntryCard("", 443)),
                activeCard = old?.activeCard ?: 0,
                appMode = if (switchAppFilter.isChecked) {
                    if (rbExclude.isChecked) "exclude" else "allow"
                } else "off",
                appList = old?.appList ?: emptyList()
            )
            try {
                ConfigStore.save(this, s)
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshAppCount() {
        val cfg = ConfigStore.load(this)
        val on = cfg?.appMode != "off"
        switchAppFilter.isChecked = on
        rgAppMode.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        btnPickApps.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        btnPickApps.text = "选择应用（已选 ${cfg?.appList?.size ?: 0} 个）"
    }

    /**
     * 预设下拉：条目显示「标签」，选中后把 value 填入输入框；
     * 输入框仍可自由编辑（与 macOS 版 customSentinel 行为一致）。
     */
    private fun setupPresetDropdown(view: AutoCompleteTextView, arrayRes: Int) {
        val presets = resources.getStringArray(arrayRes)
            .map {
                val (label, value) = it.split("|", limit = 2).let { p -> p[0] to p.getOrElse(1) { p[0] } }
                label to value
            }
        view.setAdapter(
            ArrayAdapter(
                this, android.R.layout.simple_list_item_1, presets.map { it.first }
            )
        )
        view.setOnItemClickListener { _, _, pos, _ ->
            view.setText(presets[pos].second, false)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAppCount()
    }
}
