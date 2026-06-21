package com.mzstd.hxmyproxy.core.security

import java.net.InetAddress

/**
 * 真实准入：按「连接被接收的本地接口地址」是否属于用户选定接口判断（grounded-ref：以
 * `Socket.getLocalAddress()` 归属，而非可伪造的远端 IP）。**仍仅是便利过滤、非安全边界。**
 *
 * 未配置（空）时放行全部，避免早期/误配时全盘拒绝；一旦配置则只放行选定接口接收的连接。
 */
class SubnetAccessController : AccessController {

    @Volatile
    private var allowedLocalAddresses: Set<InetAddress> = emptySet()

    /** 由网络层在选定接口变化时更新。 */
    fun update(selectedInterfaceAddresses: Set<InetAddress>) {
        allowedLocalAddresses = selectedInterfaceAddresses
    }

    override fun admit(localAddress: InetAddress, remoteAddress: InetAddress): Boolean =
        allowedLocalAddresses.isEmpty() || localAddress in allowedLocalAddresses
}
