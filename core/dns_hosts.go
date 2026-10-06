package main

import "strings"

// DNS 引导辅助：知名公共 DNS 的 IP 直连映射。
//
// ECH 公钥查询链路有个鸡生蛋问题：查 DoH 域名的 HTTPS 记录之前，
// 得先解析 DoH 服务器自己的域名；而这第一次解析走的就是引导 DNS，
// 网络对公共 DNS UDP/TCP 53 有干扰时整个查询死循环。
// 知名公共 DoH 都有稳定的 Anycast IP —— 直接拨 IP、保留原域名做
// TLS SNI/Host，查询链路完全脱离 DNS 依赖。

var knownDoHServerIPs = map[string]string{
	"dns.alidns.com":     "223.5.5.5",   // 阿里 DoH，https://223.5.5.5/dns-query 证书含此 IP
	"doh.pub":            "1.12.12.12",  // 腾讯 DNSPod
	"cloudflare-dns.com": "1.1.1.1",
	"one.one.one.one":    "1.1.1.1",
	"dns.google":         "8.8.8.8",
	"doh.onedns.net":     "52.80.62.19",
	"doh.360.cn":         "101.198.194.196",
}

// dnsHostOverride 若 host 是已知公共 DoH 域名，返回可直连的 IP；否则原样返回。
func dnsHostOverride(host string) string {
	if host == "" {
		return host
	}
	h := strings.ToLower(strings.TrimSuffix(host, "."))
	if ip, ok := knownDoHServerIPs[h]; ok {
		return ip
	}
	return host
}
