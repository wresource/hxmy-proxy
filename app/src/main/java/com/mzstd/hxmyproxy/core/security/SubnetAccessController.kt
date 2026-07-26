package com.mzstd.hxmyproxy.core.security

import java.net.InetAddress

/**
 * 真实准入：按「连接被接收的本地接口地址」是否属于用户选定接口判断（grounded-ref：以
 * `Socket.getLocalAddress()` 归属，而非可伪造的远端 IP）。**仍仅是便利过滤、非安全边界。**
 *
 * fail-closed：允许集为空（未选任何网段 / 所选接口全部消失）→ **拒绝全部**。
 * 开关状态与连通性一一对应——没开的网段连不上，一个没开谁都连不上。
 * 换网导致「选过但接口消失」的场景由 refresh 的回退规则先行兜底（回退全部接口，集合非空），
 * 不会落入空集分支，故无需为它放行。
 */
class SubnetAccessController : AccessController {

    @Volatile
    private var allowedLocalAddresses: Set<InetAddress> = emptySet()

    /** 由网络层在选定接口变化时更新。 */
    fun update(selectedInterfaceAddresses: Set<InetAddress>) {
        allowedLocalAddresses = selectedInterfaceAddresses
    }

    override fun admit(localAddress: InetAddress, remoteAddress: InetAddress): Boolean =
        // 不放行 loopback：曾为自探短路过（1.18.1 开发中），review 推翻——「本机进程」包含全部
        // 第三方 app 与 adb forward，等于免认证暴露面，且直接打破「不开=全拒」与 UI 黄警示的
        // 一一对应。自探也**不需要**它：准入拒绝发生在 accept 之后，探针的 connect() 在内核
        // backlog 层就握手成功并返回，拒绝只表现为随后连接被关，不影响探测结果。
        localAddress in allowedLocalAddresses
}
