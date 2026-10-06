# EchOS

基于 **ECH（Encrypted Client Hello）** 的加密代理客户端，**Android 原生应用**。

**Go 内核 + Kotlin 界面**，一个 App 完成代理全部功能，不需要额外装 v2rayN / ClashX。
服务端跑在 Cloudflare Workers 上，免费额度足够个人日常使用。

---

## 功能特性

- **ECH 加密隧道** — 基于 TLS ECH 的代理协议，抗 DPI 检测
- **Go 内核（x-tunnel）** — 交叉编译进 APK，随应用启动，自动探测服务端协议
- **SOCKS5 本地代理** — 内核监听本地 SOCKS5，tun2socks 接管系统流量
- **VPN 接管** — 启动即通过 VpnService 接管全部流量，停止自动还原
- **多服务器管理** — 新增 / 编辑 / 删除线路卡片，点击切换使用中的服务器
- **优选 IP** — 支持 Cloudflare 优选 IP，逗号分隔多个
- **DoH 兼容** — ECH 公钥查询支持阿里 / 腾讯 / 360 / OpenDNS / Quad9 等 DoH
- **隧道兜底（防黑洞卡死）** — 客户端已向目标发过数据、服务端却长时间从未返回时自动断开并点名提示，连接不再永久挂起
- **分应用代理** — 选择不走代理的应用（白名单 / 黑名单模式）
- **运行日志** — 界面日志 + 落盘文件（`logs/`，可回溯上次崩溃）
- **连通性自检** — 启动前预检服务器地址 + Token 鉴权（真实 WebSocket 升级握手），人工确认配置能用了再启动；启动时自动静默检测一次

---

## 系统要求

- Android 8.0+（API 26+）

---

## 快速开始

### 使用预打包 APK

1. 从 Releases 下载 `echos-universal.apk`（或对应 ABI 的 APK）
2. 安装后打开应用
3. 点击右上角 **+** 添加线路（优选 IP + 端口 + 备注）
4. 填好服务器配置（设置页：服务地址 / TOKEN / ECH 域名 / DoH）→ 点 **连接**

### 配置项

| 字段 | 说明 | 示例 |
|---|---|---|
| 服务地址 | Workers 域名（不含协议头） | `xxx.workers.dev` |
| 服务端口 | 连接端口 | `443` |
| 监听地址 | 本地代理地址 | `127.0.0.1` |
| 监听端口 | SOCKS5 端口 | `10808` |
| 优选IP(域名) | Cloudflare 优选 IP，逗号分隔 | `104.16.158.132` |
| ECH域名 | ECH 公钥查询域名 | `cloudflare-ech.com` |
| DOH服务器 | ECH 公钥查询 DNS | `dns.alidns.com/dns-query` |
| TOKEN | 可选，与服务端 Workers 的 `TOKEN` 环境变量一致 | |

> **ECH域名 / DOH服务器 已内置常用选项**：ECH 域名含 `cloudflare-ech.com`、`crypto.cloudflare.com` 等；
> DOH 含阿里 DoH、腾讯国密 DoH、360 DoH、OpenDNS、Quad9 等国内外共 10 个，直接选即可。

---

## Cloudflare 部署（服务端）

服务端是一个 Cloudflare Worker，仓库根目录的 `Worker-ECH.js` 就是完整代码。仓库已带 `wrangler.toml` 和 `deploy-worker.sh`，两种方式任选：

> <span style="color:red">**⚠️ 必读：Worker「兼容日期」必须设为 26 年之前的任意日期**（26 年 4 月反馈：用 26 年内的日期部署后隧道无法使用）。</span>
>
> 设置位置：Worker 项目 → **Settings（设置）→ Running（运行时）→ Compatibility Date（兼容日期）** 改成 `Sep 15, 2025`。
>
> 命令行部署（方式一）已由 `wrangler.toml` 的 `compatibility_date = "2025-09-15"` 自动带上；网页部署（方式二）需手动设置。

### 方式一：命令行一键部署（推荐）

```bash
# 安装 wrangler（需要 Node.js 环境）
npm install -g wrangler

# 首次需要登录 Cloudflare 账号（会打开浏览器）
wrangler login

# 部署；需要鉴权时带上 TOKEN（值自定义，一长串随机字符）
TOKEN=你的密钥 ./deploy-worker.sh
```

`wrangler.toml` 里的 `name` 就是 Worker 名称，部署前可自行修改。

### 方式二：网页 Dashboard 手动部署

1. 打开 [Cloudflare Dashboard](https://dash.cloudflare.com) → **Workers & Pages** → **Create** → 选 **Workers**
2. 名称随意，创建后点 **Edit code**，用 `Worker-ECH.js` 的内容覆盖默认代码，保存
3. <span style="color:red">**设置兼容日期**：**Settings（设置）→ Running（运行时）→ Compatibility Date（兼容日期）** 改成 `Sep 15, 2025`（⚠️ 不设则隧道数据不通，现象是「能连上但打不开网站」）</span>
4. 可选：**Settings → Variables and Secrets → Add**，加一个环境变量 `TOKEN`
   - 设了 TOKEN 后，客户端必须填相同的 TOKEN 才能连上
   - 不设 TOKEN 就是全公开，任何人都能拿你的 Worker 当代理，**强烈建议设置**
5. 部署后得到一个 `https://<你的名称>.workers.dev` 地址，填进客户端的「服务地址」

> 无需绑定域名、无需支付。Worker 免费计划每天 10 万请求，个人使用绰绰有余。

---

## 从源码构建（Android）

### 环境

- JDK 17+
- Android SDK（API 33+）
- Go 1.23+（编译内核）

### 构建 APK

```bash
# 1. 编译 Go 内核（产物落到 android/app/src/main/jniLibs/）
cd core && go build -o ../android/app/src/main/jniLibs/arm64-v8a/libxtun.so . && cd ..

# 2. 构建 APK
cd android && ./gradlew assembleRelease
```

> GitHub Actions 已配置好完整流水线（`.github/workflows/android-release.yml`）：自动编译内核 → 构建 APK → 发布到 Releases。

---

## 项目结构

```
android/                  # Android 应用（Kotlin）
  app/src/main/java/com/echos/app/
    MainActivity.kt       # 主界面（线路卡片 + 连接控制）
    SettingsActivity.kt   # 设置页（服务地址 / TOKEN / ECH / DoH / 分应用）
    CardEditActivity.kt   # 线路编辑
    ProxyService.kt       # 代理服务（内核进程管理）
    EchVpnService.kt      # VPN 接管
    ConfigStore.kt        # 配置持久化（SharedPreferences + JSON）
core/                     # Go 内核（x-tunnel）
  x-tunnel.go             # 主程序（ECH 引导 + 协议自动探测 + SOCKS5）
  go.mod
Worker-ECH.js             # 服务端 Cloudflare Worker（部署用）
wrangler.toml             # wrangler 部署配置
deploy-worker.sh          # 一键部署脚本
```

---

## 开源说明

仅供学习交流使用，请遵守当地法律法规，勿用于非法用途。
