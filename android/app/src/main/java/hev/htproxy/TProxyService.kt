package hev.htproxy

/**
 * hev-socks5-tunnel 的 JNI 绑定。
 *
 * 类名/包名与上游 hev-jni.c 的默认 PKGNAME/CLSNAME（hev/htproxy/TProxyService）
 * 严格对应，方法名即 native 注册名，不能改。
 *
 * fd 所有权由 VpnService 保留；native 只使用 getFd()，停止时由 Service 显式 close。
 */
class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    /** 读取 yaml 配置并用传入的 TUN fd 启动转发，成功后立即返回（工作在内部线程）。 */
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    /** 停止转发并关闭 fd。 */
    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    /** [发送包数, 接收包数]。 */
    external fun TProxyGetStats(): LongArray
}
