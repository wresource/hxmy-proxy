package com.mzstd.hxmyproxy.core.model

import java.net.InetAddress

/**
 * 一个可分享的本地网络接口（手机持有该地址，客户端从该网段接入）。
 *
 * @param id          稳定标识，形如 "wlan0/192.168.1.34"，用于设置持久化与准入归属。
 * @param name        系统接口名（wlan0/ap0/rndis0/bt-pan/eth0...），仅作弱提示。
 * @param gatewayLike 手机是否持有 .1/网关式地址（热点/USB/蓝牙主端特征）。
 */
data class ShareInterface(
    val id: String,
    val name: String,
    val type: InterfaceType,
    val address: InetAddress,
    val prefixLength: Int,
    val gatewayLike: Boolean,
    val isSelected: Boolean,
    val status: InterfaceStatus,
) {
    /**
     * 地址的**可直接使用**形式：IPv4 原样，IPv6 加方括号（`[fd00::1]`）。
     *
     * 显示处一律用它，而不是裸的 `hostAddress`。理由是用户会照着界面往别的设备上填，
     * 而 `fd00::1:8090` 这种写法是非法的——冒号既属于地址又是端口分隔符，
     * 必须靠方括号消歧（RFC 3986 §3.2.2）。界面上就显示成能直接用的样子，省得误导。
     *
     * 顺带去掉 scope（`fe80::1%wlan0` 的 `%wlan0`）：它只在本机有意义，填到别的设备上无效。
     */
    val displayAddress: String get() = (address.hostAddress ?: "?").let {
        if (it.contains(':')) "[${it.substringBefore('%')}]" else it
    }

    /** 接口地址的可读形式，如 "192.168.1.34/24"、"[fd00::1]/64"。 */
    val cidr: String get() = "$displayAddress/$prefixLength"

    /** 是否 IPv6 地址（供「显示 IPv6」偏好过滤，见 [visibleUnderIpv6Pref]）。 */
    val isIpv6: Boolean get() = address is java.net.Inet6Address
}

/**
 * 按「显示 IPv6」偏好过滤展示列表。**只影响展示，不影响准入与转发**——
 * 隐藏的 v6 接口照样进 `entrySubnets`，v6 客户端连进来照样放行。
 *
 * 用户的诉求是「v6 地址不容易记忆」，那是**抄地址**的问题，不是「v6 有害」；
 * 所以这里只做视觉降噪，不做功能开关。
 *
 * 全空兜底：过滤后一条不剩（纯 IPv6 局域网）就原样返回。否则界面显示「无可共享接口」、
 * 入口卡空白，而实际上共享是好的——那是比看到长地址严重得多的误导。
 */
fun <T> visibleUnderIpv6Pref(items: List<T>, showIpv6: Boolean, isIpv6: (T) -> Boolean): List<T> {
    if (showIpv6) return items
    val kept = items.filterNot(isIpv6)
    return if (kept.isEmpty()) items else kept
}
