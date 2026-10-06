# EchOS Android

**Kotlin 界面 + Go 内核 + VPN 全局接管**。Go 内核（`core/`）交叉编译为
Android 可执行文件打进 APK，前台服务拉起子进程运行。

## 两种运行模式

### VPN 全局接管（默认，推荐）

- `VpnService` 建立 TUN（全局 IPv4/IPv6 路由），`hev-socks5-tunnel`（JNI，
  官方 2.17.1）把全部流量转进内核 SOCKS5
- DNS 走 mapdns（fake-IP）：域名以原始形式进入 SOCKS5，内核 geosite 分流可用
- **自环规避**：`addDisallowedApplication(packageName)`，内核的 WSS/DoH 出站
  天然绕过 TUN（同包名），无路由死循环，无需 protect() 私有 API
- UDP 转发：hev 的 socks5 udp 模式 → 内核 UDP 转发（配 block 443 端口）

### 本地代理模式（关闭 VPN 开关）

- 内核监听 `127.0.0.1:30000`（SOCKS5）/ `30001`（HTTP），给浏览器等
  支持手动代理的应用使用


## 配置项与内核参数对应关系

| macOS 客户端字段 | Android 字段 | 传给内核的参数 |
|---|---|---|
| 服务地址 | 服务器地址（域名:端口） | `-f wss://域名:端口` |
| 监听端口 | 本地代理端口 | `-l socks5://127.0.0.1:P,http://127.0.0.1:P+1` |
| DOH服务器 | ECH DOH 服务器 | `-dns` |
| ECH域名 | ECH 公钥查询域名 | `-ech` |
| 优选IP(域名) | 优选 IP（逗号分隔） | `-ip` |
| TOKEN | 身份令牌 | `-token` |

## 目录结构

```
android/
├── app/src/main/
│   ├── java/com/echos/app/
│   │   ├── MainActivity.kt    # 配置界面 + 日志 + VPN 授权流程
│   │   ├── ProxyService.kt    # 前台服务，拉起内核子进程
│   │   ├── EchVpnService.kt   # VPN 全局接管（VpnService + hev）
│   │   └── ConfigStore.kt     # 配置持久化（JSON）
│   ├── java/hev/htproxy/
│   │   └── TProxyService.kt   # hev-socks5-tunnel JNI 绑定
│   ├── res/                   # 布局与图标
│   └── AndroidManifest.xml
├── build.gradle
└── settings.gradle
```

## 构建方式

### 方式一：GitHub Actions（推荐）

推送代码或手动触发 `Android Build` 工作流即可产出 APK：

1. 进入仓库 **Actions → Android Build → Run workflow**
2. 填版本号（如 `1.1.0`）→ 运行
3. 完成后在 **Artifacts** 下载 `EchOS-android-apk`，或打 `v*` 标签自动发 Release

CI 流程：`actions/checkout` → 交叉编译 Go 内核到 arm64（`GOOS=android`，
`CGO_ENABLED=0`，产物命名 `libxtun.so` 放入 `jniLibs/`）→ NDK 编译
hev-socks5-tunnel JNI（`libhev-socks5-tunnel.so`，VPN 全局接管用）→
`gradle assembleRelease` → 上传产物 / 发 Release。

### 方式二：本机构建

```bash
# 前提：Go 1.25+、JDK 17、Android SDK（compileSdk 34 + NDK）、Gradle 8.9
# 1. 内核（arm64，CGO_ENABLED=0 纯 Go 内部链接，其余 ABI 需要 NDK）
cd core
mkdir -p ../android/app/src/main/jniLibs/arm64-v8a
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -trimpath -ldflags "-s -w" \
  -o ../android/app/src/main/jniLibs/arm64-v8a/libxtun.so .
# 2. hev-socks5-tunnel（VPN 模式）
git clone --depth 1 --branch 2.17.1 https://github.com/heiher/hev-socks5-tunnel
cd hev-socks5-tunnel && git submodule update --init --depth 1
ndk-build -j4 APP_ABI=arm64-v8a
cp libs/arm64-v8a/libhev-socks5-tunnel.so ../../android/app/src/main/jniLibs/arm64-v8a/
# 3. APK
cd ../../android && gradle assembleRelease
```

## 签名说明

CI 每次构建都会生成**临时签名**（`release.keystore`，密码 `echos-android`），
产物可以安装但不能覆盖安装不同次构建的包。想固定签名，把自己的 keystore
放到 `android/app/release.keystore` 提交（或用 secrets 注入），CI 会复用。

## 与 macOS 版的差异

- Android 无 `/etc/resolv.conf`，Go 解析器默认不可用 → 内核新增
  `core/dns_android.go`（`//go:build android`），自动走公共 DNS 引导。
  只影响 android 构建，macOS/Windows 内核源码零改动。
- 不做 TUN 全局接管（需要 VpnService，后续版本考虑）；本版本提供
  本地 SOCKS5 + HTTP 代理，配合支持代理设置的应用使用。
- 默认 `-default all` 全局模式；规则分流需 geo 数据，暂未打包。
