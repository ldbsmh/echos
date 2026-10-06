package com.echos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 配置持久化：设置页（共享参数）+ 主界面多线路卡片 + 分应用代理。 */
object ConfigStore {
    private const val FILE = "server_config.json"

    /** 一条线路：优选IP(域名) + 服务端口。 */
    data class EntryCard(
        val ips: String,   // 优选 IP（域名），可空（走域名解析）
        val port: Int      // 服务端口
    ) {
        fun display(): String = "${ips.ifBlank { "默认解析" }}:$port"
    }

    data class Server(
        val domain: String,      // 服务地址（域名，设置页）
        val ech: String,         // ECH 公钥查询域名
        val doh: String,         // ECH DoH 服务器
        val token: String,       // 身份令牌
        val listenAddr: String,  // 监听地址
        val listenPort: Int,     // 本地 SOCKS5 端口（HTTP 自动 +1）
        val vpn: Boolean,        // true=VPN 全局接管 false=本地代理模式
        val cards: List<EntryCard>,
        val activeCard: Int,     // 当前使用线路下标
        val appMode: String,     // off=不分应用 allow=仅选中走代理 exclude=选中不走代理
        val appList: List<String> // 分应用代理的应用包名列表
    ) {
        val active: EntryCard?
            get() = cards.getOrNull(activeCard)

        fun isValid(): Boolean =
            domain.isNotBlank() &&
                active != null && active!!.port in 1..65535 &&
                listenPort in 1024..65535
    }

    fun load(ctx: Context): Server? {
        val file = ctx.getFileStreamPath(FILE)
        if (!file.exists()) return null
        return try {
            ctx.openFileInput(FILE).bufferedReader().use { r ->
                parse(JSONObject(r.readText()))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(o: JSONObject): Server {
        // 旧版兼容：addr = "域名:端口"
        val domain: String
        val legacyPort: Int
        if (o.has("domain")) {
            domain = o.getString("domain")
            legacyPort = 443
        } else {
            val addr = o.optString("addr", "")
            val idx = addr.lastIndexOf(':')
            domain = if (idx > 0) addr.substring(0, idx) else addr
            legacyPort = if (idx > 0) addr.substring(idx + 1).toIntOrNull() ?: 443 else 443
        }

        val cards = mutableListOf<EntryCard>()
        val arr = o.optJSONArray("cards")
        if (arr != null && arr.length() > 0) {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                cards.add(EntryCard(c.optString("ips", ""), c.optInt("port", 443)))
            }
        } else {
            cards.add(EntryCard(o.optString("ips", ""), legacyPort))
        }

        val appList = mutableListOf<String>()
        val appArr = o.optJSONArray("appList")
        if (appArr != null) {
            for (i in 0 until appArr.length()) appList.add(appArr.getString(i))
        }

        return Server(
            domain = domain,
            ech = o.optString("ech", "cloudflare-ech.com"),
            doh = o.optString("doh", "https://dns.alidns.com/dns-query"),
            token = o.optString("token", ""),
            listenAddr = o.optString("listenAddr", "127.0.0.1"),
            listenPort = o.optInt("listenPort", o.optInt("port", 30000)),
            vpn = o.optBoolean("vpn", true),
            cards = cards,
            activeCard = o.optInt("activeCard", 0),
            appMode = o.optString("appMode", "off"),
            appList = appList
        )
    }

    fun save(ctx: Context, s: Server) {
        val o = JSONObject()
        o.put("domain", s.domain)
        o.put("ech", s.ech)
        o.put("doh", s.doh)
        o.put("token", s.token)
        o.put("listenAddr", s.listenAddr)
        o.put("listenPort", s.listenPort)
        o.put("vpn", s.vpn)
        val cardsArr = JSONArray()
        s.cards.forEach { c ->
            cardsArr.put(JSONObject().put("ips", c.ips).put("port", c.port))
        }
        o.put("cards", cardsArr)
        o.put("activeCard", s.activeCard)
        o.put("appMode", s.appMode)
        val appArr = JSONArray()
        s.appList.forEach { appArr.put(it) }
        o.put("appList", appArr)
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { out ->
            out.write(o.toString().toByteArray())
        }
    }

    // ==================== 单卡片操作（供编辑页使用） ====================

    private fun mutate(ctx: Context, f: (Server) -> Server) {
        val s = f(load(ctx) ?: default())
        try {
            save(ctx, s)
        } catch (_: Exception) {
        }
    }

    fun updateCard(ctx: Context, index: Int, card: EntryCard) = mutate(ctx) { s ->
        if (index in s.cards.indices)
            s.copy(cards = s.cards.mapIndexed { i, c -> if (i == index) card else c })
        else s
    }

    fun removeCard(ctx: Context, index: Int) = mutate(ctx) { s ->
        if (s.cards.size > 1 && index in s.cards.indices) {
            val cards = s.cards.toMutableList()
            cards.removeAt(index)
            s.copy(cards = cards, activeCard = s.activeCard.coerceAtMost(cards.size - 1))
        } else s
    }

    fun setActive(ctx: Context, index: Int) = mutate(ctx) { s ->
        if (index in s.cards.indices) s.copy(activeCard = index) else s
    }

    /** 新增卡片，返回其下标。 */
    fun addCard(ctx: Context, card: EntryCard): Int {
        val s = load(ctx) ?: default()
        val updated = s.copy(cards = s.cards + card)
        save(ctx, updated)
        return updated.cards.size - 1
    }

    /** 更新分应用代理设置（mode 传 null 表示保持不变）。 */
    fun setAppFilter(ctx: Context, mode: String?, list: List<String>?) = mutate(ctx) { s ->
        s.copy(appMode = mode ?: s.appMode, appList = list ?: s.appList)
    }

    /** 生成内核命令行参数（不含二进制路径），配置非法返回 null。 */
    fun buildArgs(s: Server): List<String>? {
        val card = s.active ?: return null
        if (s.domain.isBlank() || card.port !in 1..65535) return null
        val listen = s.listenAddr.ifBlank { "127.0.0.1" }
        val args = mutableListOf(
            "-l", "socks5://$listen:${s.listenPort},http://$listen:${s.listenPort + 1}",
            "-f", "wss://${s.domain}:${card.port}",
            "-n", "2",
            "-ech", s.ech,
            // 与 macOS 版 normalizedDoH 一致：带路径的补 https://，udp:// 剥壳
            "-dns", normalizedDoh(s.doh),
            // Android 版未打包 geo 数据，规则分流无意义，恒为全局
            "-default", "all"
        )
        if (card.ips.isNotBlank()) args += listOf("-ip", card.ips)
        if (s.token.isNotBlank()) args += listOf("-token", s.token)
        return args
    }

    /**
     * 把界面上的 DoH 写法归一化为内核可识别的形式：
     * - 已带 http(s):// 原样传（内核靠前缀判定走 DoH）
     * - udp:// 剥掉 scheme（那是界面标注，内核不认），当 UDP DNS 传
     * - 带路径的（如 dns.alidns.com/dns-query）补 https://
     * - 纯主机名/IP（如 223.5.5.5）当 UDP DNS 原样传
     */
    fun normalizedDoh(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return s
        if (lower.startsWith("udp://")) return s.drop(6)
        val pathStart = s.indexOf('/')
        return if (pathStart > 0) "https://$s" else s
    }

    /** 从剪贴板文本解析线路列表（自动识别 IPv4/域名:端口，忽略统计与来源区块）。 */
    fun parseProxyList(text: String): List<EntryCard> {
        val out = mutableListOf<EntryCard>()
        val seen = mutableSetOf<String>()
        val ipRe = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})""")
        val domainRe = Regex(
            """([a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)+):(\d{1,5})"""
        )
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('·', '.', '-').trim()
            val m = ipRe.find(line) ?: domainRe.find(line) ?: continue
            val host = m.groupValues[1]
            val port = m.groupValues[2].toIntOrNull() ?: continue
            if (port !in 1..65535) continue
            val key = "$host:$port"
            if (seen.add(key)) out.add(EntryCard(host, port))
        }
        return out
    }

    fun default(): Server = Server(
        domain = "", ech = "cloudflare-ech.com",
        doh = "https://dns.alidns.com/dns-query", token = "",
        listenAddr = "127.0.0.1", listenPort = 30000,
        vpn = true,
        cards = listOf(EntryCard("", 443)), activeCard = 0,
        appMode = "off", appList = emptyList()
    )
}
