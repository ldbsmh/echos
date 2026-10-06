package com.echos.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/** 线路编辑页：修改优选IP(域名)与端口，保存/删除。 */
class CardEditActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_edit)

        val index = intent.getIntExtra("index", -1)
        val cfg = ConfigStore.load(this)
        val card = cfg?.cards?.getOrNull(index) ?: ConfigStore.EntryCard("", 443)

        val editIps = findViewById<TextInputEditText>(R.id.editIps)
        val editPort = findViewById<TextInputEditText>(R.id.editPort)
        editIps.setText(card.ips)
        editPort.setText(card.port.toString())

        val wasActive = cfg?.activeCard == index
        val running = ProxyService.isRunning

        findViewById<MaterialButton>(R.id.btnSaveCard).setOnClickListener {
            val newCard = ConfigStore.EntryCard(
                ips = editIps.text?.toString()?.trim() ?: "",
                port = editPort.text?.toString()?.toIntOrNull() ?: 443
            )
            if (newCard.port !in 1..65535) {
                Toast.makeText(this, "端口无效", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ConfigStore.updateCard(this, index, newCard)
            // 使用中的线路被修改且代理在跑：重启内核生效
            if (wasActive && running) ProxyService.restart(this)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnDeleteCard).setOnClickListener {
            val size = ConfigStore.load(this)?.cards?.size ?: 0
            if (size <= 1) {
                Toast.makeText(this, "至少保留一个线路卡片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ConfigStore.removeCard(this, index)
            finish()
        }
    }
}
