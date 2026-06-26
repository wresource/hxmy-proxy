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
    /** 接口地址的可读形式，如 "192.168.1.34/24"。 */
    val cidr: String get() = "${address.hostAddress}/$prefixLength"
}
