package com.mzstd.hxmyproxy.core.security

import java.net.InetAddress

/**
 * 准入控制（**便利过滤，非安全边界**——同 L2 段来源 IP 可伪造，安全靠认证 + EgressGuard）。
 * 依据连接被接收的本地接口地址与远端地址判断是否放行。
 */
interface AccessController {
    fun admit(localAddress: InetAddress, remoteAddress: InetAddress): Boolean
}

/** 默认放行全部。真实的「按选定接口子网匹配」在网络层（Step 3）实现并注入。 */
object AllowAllAccessController : AccessController {
    override fun admit(localAddress: InetAddress, remoteAddress: InetAddress) = true
}
