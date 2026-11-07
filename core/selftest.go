package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// ======================== 连通性测试（节点测延迟） ========================
//
// App 里的「测试延迟」不是简单 ping 一下 IP，而是**把真实链路完整走一遍**：
//
//	1. DoH 查 ECH 公钥（和正式启动时同一套逻辑）
//	2. ECH + TLS1.3 握手 → WebSocket 升级（带 token 子协议）
//	3. 发 CONNECT 指令，等服务端回 CONNECTED（证明 Worker 能出去）
//	4. 通过隧道发一个 HTTP 请求，等首个回包字节（证明数据面真的通）
//
// 只有 4 步都过了才算「通」，这跟 Clash / v2rayN 的 URL 测速是同一套语义：
// 只看 TCP 握手会被中间设备或挂掉的 Worker 骗过（能连上但打不开网页）。
//
// 测试模式不监听任何端口，测完把结果以单行 JSON 打到 stdout 后退出，
// 供 App 用子进程方式调用（Kotlin 侧解析 `ECHOS-SELFTEST ` 前缀）。
// 其余日志照常走 stderr，App 合并输出后只挑带前缀的那行。

const (
	// selfTestMarker 是结果行的前缀。App 侧按它定位 JSON，其余行当调试日志。
	selfTestMarker = "ECHOS-SELFTEST "

	// selfTestStageOK 表示链路完整可用（握手 + CONNECT + 数据回包全通过）。
	selfTestStageOK = "ok"
	// selfTestStageHandshake 表示服务端可连、token 也对，但拿不到数据回包。
	selfTestStageHandshake = "handshake"
	// selfTestStageDial 表示连服务端这一步就失败了（IP 不通 / 端口错 / token 错）。
	selfTestStageDial = "dial"
)

var (
	selfTest        bool
	selfTestTimeout time.Duration
	selfTestTarget  string
)

// 内置测试目标：走 80 端口，只要一个字节的响应，流量最小。
// 特意避开 Cloudflare 自家域名/网段 —— Worker 的 connect() 不允许连回 CF，
// 拿 cloudflare.com 去测会稳定收到 ERROR，把正常线路误判成不通。
var selfTestTargets = []string{
	"www.gstatic.com:80",
	"www.wikipedia.org:80",
}

func init() {
	flag.BoolVar(&selfTest, "selftest", false, "连通性测试模式：不监听端口，测完打印结果并退出（供 App 调用）")
	flag.DurationVar(&selfTestTimeout, "test-timeout", 12*time.Second, "连通性测试总超时（selftest 模式）")
	flag.StringVar(&selfTestTarget, "test-target", "", "自定义测试目标 host:port（默认内置 www.gstatic.com:80）")
}

type selfTestResult struct {
	OK     bool   `json:"ok"`     // 是否完整可用
	Stage  string `json:"stage"`  // ok / handshake / dial
	MS     int64  `json:"ms"`     // 端到端延迟（握手 + CONNECT + 首字节），失败时为已耗时
	HSMs   int64  `json:"hsMs"`   // WebSocket(ECH+TLS) 握手耗时
	RTTMs  int64  `json:"rttMs"`  // CONNECT + HTTP 首字节耗时
	EchMs  int64  `json:"echMs"`  // ECH 公钥查询耗时（仅诊断用，不计入延迟）
	IP     string `json:"ip"`     // 实际连的优选 IP（空=按域名解析）
	Target string `json:"target"` // 实际测试目标
	Err    string `json:"err"`    // 失败原因（中文，可直接显示）
}

// runSelfTest 是 selftest 模式的入口，由 main 在 flag.Parse 后直接调用。
func runSelfTest() {
	start := time.Now()
	timeout := selfTestTimeout
	if timeout < 3*time.Second {
		timeout = 3 * time.Second
	}
	deadline := start.Add(timeout)

	// remain 返回距总超时的剩余时间，并留 1 秒给收尾（关连接、打印结果）。
	remain := func() time.Duration {
		d := time.Until(deadline) - 500*time.Millisecond
		if d < 500*time.Millisecond {
			return 500 * time.Millisecond
		}
		return d
	}

	res := selfTestResult{IP: strings.TrimSpace(ipAddr), Stage: selfTestStageDial}
	fail := func(stage, msg string) {
		res.Stage = stage
		res.Err = msg
		if res.MS == 0 {
			res.MS = time.Since(start).Milliseconds()
		}
		printSelfTestResult(res)
		os.Exit(1)
	}
	ok := func() {
		res.OK = true
		res.Stage = selfTestStageOK
		res.Err = ""
		printSelfTestResult(res)
		os.Exit(0)
	}

	if forwardAddr == "" {
		fail(selfTestStageDial, "缺少服务地址")
	}
	u, err := url.Parse(forwardAddr)
	if err != nil {
		fail(selfTestStageDial, "服务地址格式错误: "+err.Error())
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "wss" && scheme != "ws" {
		fail(selfTestStageDial, "服务地址必须是 wss:// 或 ws://")
	}
	if u.Hostname() == "" {
		fail(selfTestStageDial, "服务地址缺少主机名")
	}

	// —— 步骤 1：ECH 公钥查询 ——
	// 正式启动走的是 prepareECH()，失败会无限重试；测试场景必须可超时，
	// 所以这里单独实现一次性版本，并且把耗时单独记下来：
	// 每次测试都是新进程，DoH 查询属于「测试进程固定开销」，不该算进节点延迟。
	if scheme == "wss" && !fallback {
		ms, err := prepareECHOnce(min(remain(), 6*time.Second))
		res.EchMs = ms
		if err != nil {
			fail(selfTestStageDial, err.Error())
		}
	}

	// —— 步骤 2：ECH + TLS + WebSocket 握手 ——
	clientID = "selftest-" + uuid.NewString()[:8]
	var targetIPs []string
	if res.IP != "" {
		for _, p := range strings.Split(res.IP, ",") {
			if p = strings.TrimSpace(p); p != "" {
				targetIPs = append(targetIPs, p)
			}
		}
	}
	if len(targetIPs) > 0 {
		res.IP = targetIPs[0] // 只测第一个：测速只需要一条链路
	}

	cfg.DialTimeout = min(remain(), 6*time.Second)
	cfg.WSHandshakeTimeout = min(remain(), 8*time.Second)

	echPool = NewECHPool(forwardAddr, 1, targetIPs, clientID)

	targets := selfTestTargets
	if t := strings.TrimSpace(selfTestTarget); t != "" {
		targets = []string{t}
	}

	var connectErr error
	for _, target := range targets {
		res.Target = target
		dialStart := time.Now()
		ws, err := echPool.dialWebSocketWithECH(forwardAddr, 1, res.IP, clientID, 0)
		res.HSMs = time.Since(dialStart).Milliseconds()
		if err != nil {
			res.Err = friendlyDialError(err)
			res.MS = res.HSMs
			printSelfTestResult(res)
			os.Exit(1)
		}

		// —— 步骤 3：CONNECT 指令 ——
		if _, err := selfTestConnect(ws, target, min(remain(), 8*time.Second)); err != nil {
			_ = ws.Close()
			connectErr = err
			// 服务端明确说了「我连不上这个目标」，换下一个目标再试；
			// 超时/无响应则大概率不是目标的问题（协议不匹配），不必再试。
			if strings.Contains(err.Error(), "无法连接测试目标") {
				continue
			}
			break
		}

		// —— 步骤 4：隧道里发 HTTP 请求，等首字节 ——
		host, _, _ := net.SplitHostPort(target)
		rtt, err := selfTestHTTP(ws, host, min(remain(), 8*time.Second))
		_ = ws.Close()
		if err != nil {
			res.Stage = selfTestStageHandshake
			res.Err = err.Error()
			res.MS = res.HSMs
			printSelfTestResult(res)
			os.Exit(1)
		}

		res.RTTMs = rtt.Milliseconds()
		res.MS = res.HSMs + res.RTTMs
		ok()
	}

	res.Stage = selfTestStageHandshake
	res.MS = res.HSMs
	if connectErr != nil {
		res.Err = connectErr.Error()
	} else {
		res.Err = "服务端未能建立到测试目标的连接"
	}
	printSelfTestResult(res)
	os.Exit(1)
}

// prepareECHOnce 是 prepareECH 的「一次性 + 可超时」版本，供测试模式使用。
// 返回查询耗时（毫秒）；失败时耗时也照常返回，方便界面显示是卡在哪一步。
func prepareECHOnce(timeout time.Duration) (int64, error) {
	log.Printf("[测试] DNS查询 ECH: %s -> %s", dnsServer, echDomain)
	start := time.Now()
	type echReply struct {
		b64 string
		err error
	}
	ch := make(chan echReply, 1)
	go func() {
		b64, err := queryHTTPSRecord(echDomain, dnsServer)
		ch <- echReply{b64: b64, err: err}
	}()

	select {
	case <-time.After(timeout):
		return time.Since(start).Milliseconds(), fmt.Errorf("ECH 公钥查询超时（%s 不可用？）", dnsServer)
	case r := <-ch:
		ms := time.Since(start).Milliseconds()
		if r.err != nil {
			return ms, fmt.Errorf("ECH 公钥查询失败: %v", r.err)
		}
		if r.b64 == "" {
			return ms, errors.New("ECH 公钥查询为空（ECH 域名不正确？）")
		}
		raw, err := base64.StdEncoding.DecodeString(r.b64)
		if err != nil {
			return ms, fmt.Errorf("ECH 公钥解码失败: %v", err)
		}
		echListMu.Lock()
		echList = raw
		echListMu.Unlock()
		return ms, nil
	}
}

// selfTestConnect 在已建立的 WebSocket 上完成一次 CONNECT 握手。
func selfTestConnect(ws *websocket.Conn, target string, timeout time.Duration) (time.Duration, error) {
	start := time.Now()
	_ = ws.SetWriteDeadline(start.Add(timeout))
	if err := ws.WriteMessage(websocket.TextMessage, []byte("CONNECT:"+target+"|")); err != nil {
		return 0, fmt.Errorf("发送 CONNECT 失败: %v", err)
	}

	_ = ws.SetReadDeadline(start.Add(timeout))
	for {
		mt, data, err := ws.ReadMessage()
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				return 0, errors.New("等待 CONNECTED 超时：服务端无响应（Worker 兼容日期是否早于 2026 年？）")
			}
			return 0, fmt.Errorf("等待 CONNECTED 失败: %v", err)
		}
		if mt != websocket.TextMessage {
			continue
		}
		text := string(data)
		switch {
		case text == "CONNECTED":
			return time.Since(start), nil
		case strings.HasPrefix(text, "ERROR:"):
			return 0, fmt.Errorf("服务端无法连接测试目标（%s）", strings.TrimPrefix(text, "ERROR:"))
		case text == "CLOSE":
			return 0, errors.New("服务端主动关闭连接")
		}
	}
}

// selfTestHTTP 在隧道里发一个最小 HTTP 请求，返回等到首个数据包的时间。
// 收到任何字节就认为数据面可用；收到 HTTP 状态行当然更好（记在日志里）。
func selfTestHTTP(ws *websocket.Conn, host string, timeout time.Duration) (time.Duration, error) {
	start := time.Now()
	req := "GET / HTTP/1.1\r\nHost: " + host +
		"\r\nUser-Agent: EchOS-SelfTest\r\nAccept: */*\r\nConnection: close\r\n\r\n"
	_ = ws.SetWriteDeadline(start.Add(timeout))
	if err := ws.WriteMessage(websocket.BinaryMessage, []byte(req)); err != nil {
		return 0, fmt.Errorf("隧道内发送请求失败: %v", err)
	}

	_ = ws.SetReadDeadline(start.Add(timeout))
	for {
		mt, data, err := ws.ReadMessage()
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				return 0, errors.New("隧道已连通但没等到回包：服务端出口异常（检查 Worker 兼容日期）")
			}
			return 0, fmt.Errorf("隧道内读取回包失败: %v", err)
		}
		if mt != websocket.BinaryMessage || len(data) == 0 {
			continue
		}
		elapsed := time.Since(start)
		if line := firstLine(data); line != "" {
			log.Printf("[测试] %s 回包: %s", host, line)
		}
		return elapsed, nil
	}
}

func firstLine(b []byte) string {
	s := string(b)
	if i := strings.IndexAny(s, "\r\n"); i > 0 {
		s = s[:i]
	}
	if len(s) > 80 {
		s = s[:80]
	}
	return s
}

// friendlyDialError 把底层错误翻成用户能看懂的排查方向。
func friendlyDialError(err error) string {
	msg := err.Error()
	switch {
	case strings.Contains(msg, "认证失败"):
		return "Token 不匹配：设置页 TOKEN 需与服务端一致"
	case strings.Contains(msg, "no such host"):
		return "服务域名解析失败：检查服务地址是否填错"
	case strings.Contains(msg, "refused"):
		return "连接被拒绝：端口没有服务在监听"
	case strings.Contains(msg, "timeout") || strings.Contains(msg, "i/o timeout"):
		return "连接超时：优选 IP 不通，或端口不对 / 被墙"
	case strings.Contains(msg, "ECH"):
		return "ECH 握手失败：" + msg
	case strings.Contains(msg, "bad handshake"):
		return "WebSocket 升级失败：域名/端口不是这个 Worker（或未部署）"
	case strings.Contains(msg, "certificate"):
		return "证书校验失败：" + msg
	}
	return msg
}

// printSelfTestResult 把结果以单行 JSON 打到 stdout（App 唯一解析的那行）。
func printSelfTestResult(res selfTestResult) {
	b, err := json.Marshal(res)
	if err != nil {
		fmt.Println(selfTestMarker + `{"ok":false,"stage":"dial","err":"结果序列化失败"}`)
		return
	}
	fmt.Println(selfTestMarker + string(b))
}
