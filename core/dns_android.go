//go:build android

// Android 平台 DNS 引导补丁。
//
// Android 没有 /etc/resolv.conf（DNS 配置由 netd 管理，不暴露给普通进程），
// Go 的纯 Go 解析器读不到配置会退到 [::1]:53 —— 本机 53 端口没人监听，
// 结果是所有默认域名解析全部失败：
//   - DoH 服务器域名（dns.alidns.com）解析不了 → ECH 公钥查询死循环
//   - 内核解析器回退路径拨 [::1]:53 → connection refused
//
// 修复（仅在无 resolv.conf 时生效，其他平台零改动）：
//  1. 替换全局默认解析器：向公共 DNS 发标准查询；
//  2. 安装 dnsRedirectForPlatform 钩子（定义于 tun_stub_other.go）：
//     把内核内部解析器回退的 [::1]:53 死地址重定向到公共 DNS。
//
// 查询传输：先 UDP 53，超时自动改 TCP 53 重试 —— 不少运营商网络对
// UDP 53 限速/丢包，TCP 53 反而稳定；Go 解析器本身也有 TCP 兜底，
// 这里把"引导服务器不可达"和"线路抖动"两种情况都兜住。
package main

import (
	"context"
	"net"
	"os"
	"strings"
	"time"
)

// 阿里公共 DNS 任意播 IP 列表，UDP/TCP 53 均开放；
// 某个 IP 在当前网络不可达时自动切换下一个。
var androidBootstrapDNSList = []string{
	"223.5.5.5:53",
	"223.6.6.6:53",
	"119.29.29.29:53", // 腾讯 DNSPod 公共 DNS（UDP/TCP 53）
}

const bootstrapQueryTimeout = 3 * time.Second

func androidDialDNS(ctx context.Context, network, _ string) (net.Conn, error) {
	// 带版本的网络（udp4/tcp6 等）保持原样；裸 udp/tcp 强制 IPv4，
	// 避免设备无 IPv6 时向公共 DNS 发 AAAA 传输路径卡住。
	if !strings.Contains(network, "4") && !strings.Contains(network, "6") {
		if strings.HasPrefix(network, "tcp") {
			network = "tcp4"
		} else {
			network = "udp4"
		}
	}
	d := &net.Dialer{Timeout: bootstrapQueryTimeout}
	// 依次尝试候选服务器；UDP 失败再试 TCP（运营商对 UDP 53 限速/劫持时
	// TCP 53 往往可达）。
	var lastErr error
	for _, server := range androidBootstrapDNSList {
		if strings.HasPrefix(network, "udp") {
			if c, err := d.DialContext(ctx, "udp4", server); err == nil {
				return c, nil
			} else {
				lastErr = err
			}
			if c, err := d.DialContext(ctx, "tcp4", server); err == nil {
				return c, nil
			} else {
				lastErr = err
			}
		} else {
			c, err := d.DialContext(ctx, network, server)
			if err == nil {
				return c, nil
			}
			lastErr = err
		}
	}
	return nil, lastErr
}

func androidBootstrapResolver() *net.Resolver {
	return &net.Resolver{
		PreferGo: true,
		Dial:     androidDialDNS,
	}
}

func init() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return // 有 resolv.conf（刷机环境等）就保持默认行为
	}
	net.DefaultResolver = androidBootstrapResolver()
	dnsRedirectForPlatform = func(addr string) string {
		host, _, err := net.SplitHostPort(addr)
		if err != nil {
			return ""
		}
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			return androidBootstrapDNSList[0]
		}
		return "" // 非回环地址（可能是 TUN 网关 DNS）不干预
	}
}
