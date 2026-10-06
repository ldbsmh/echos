package com.echos.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.drawable.Drawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/** 分应用代理：选择走代理（或排除）的应用列表。已勾选项置顶。 */
class AppPickerActivity : AppCompatActivity() {

    private val selected = mutableSetOf<String>()
    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val mode = intent.getStringExtra("mode") ?: "allow"
        selected.addAll(intent.getStringArrayListExtra("selected") ?: emptyList())

        findViewById<TextView>(R.id.appPickerTitle).text =
            if (mode == "allow") "选择走代理的应用" else "选择不走代理的应用"

        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                it.enabled && it.packageName != packageName
            }
            .map {
                AppItem(
                    pm.getApplicationLabel(it).toString(),
                    it.packageName,
                    it.loadIcon(pm)
                )
            }
            .distinctBy { it.pkg }
            .sortedWith(compareByDescending<AppItem> { it.pkg in selected }.thenBy { it.label.lowercase() })

        adapter = AppListAdapter(apps, selected)
        val rv = findViewById<RecyclerView>(R.id.appList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<EditText>(R.id.appSearch).addTextChangedListener(
            object : android.text.TextWatcher {
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    adapter.filter(s?.toString() ?: "")
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        findViewById<MaterialButton>(R.id.btnAppDone).setOnClickListener {
            ConfigStore.setAppFilter(this, null, selected.toList())
            Toast.makeText(this, "已选择 ${selected.size} 个应用", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

data class AppItem(val label: String, val pkg: String, val icon: Drawable)

class AppListAdapter(
    private val all: List<AppItem>,
    private val selected: MutableSet<String>
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private var visible = all.toList()

    class VH(val root: android.view.View) : RecyclerView.ViewHolder(root)

    fun filter(q: String) {
        visible = if (q.isBlank()) all.toList()
        else all.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
        // 搜索后仍保持已选项置顶
        visible = visible.sortedWith(compareByDescending<AppItem> { it.pkg in selected }.thenBy { it.label.lowercase() })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false))

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = visible[pos]
        val icon = h.root.findViewById<ImageView>(R.id.appIcon)
        val label = h.root.findViewById<TextView>(R.id.appLabel)
        val pkg = h.root.findViewById<TextView>(R.id.appPackage)
        val cb = h.root.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.appCheck)

        icon.setImageDrawable(item.icon)
        label.text = item.label
        pkg.text = item.pkg
        cb.setOnCheckedChangeListener(null)
        cb.isChecked = item.pkg in selected
        cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(item.pkg) else selected.remove(item.pkg)
            // 立即把当前勾选项移动到顶部
            val i = visible.indexOfFirst { it.pkg == item.pkg }
            if (i >= 0) {
                visible = visible.sortedWith(compareByDescending<AppItem> { it.pkg in selected }.thenBy { it.label.lowercase() })
                notifyDataSetChanged()
            }
        }
        h.root.setOnClickListener { cb.performClick() }
    }
}
