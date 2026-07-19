package com.mzstd.hxmyproxy.core.model

/**
 * 代理协议。HTTP 监听在同一端口上同时处理「普通 HTTP 转发」与「HTTPS CONNECT 隧道」。
 * PAC 是一个轻量 HTTP 服务，按当前可用入口动态生成 proxy.pac。
 */
enum class ProxyProtocol { HTTP, SOCKS5, PAC }

/**
 * 可分享接口类型。接口名（wlan0/ap0/rndis0/...）因 OEM 而异、不可靠，
 * 实际由结构化特征推断（见 InterfaceScanner），UNKNOWN 兜底。
 */
enum class InterfaceType { WIFI, HOTSPOT, USB, BLUETOOTH, ETHERNET, UNKNOWN }

enum class InterfaceStatus { UP, DOWN }

/**
 * 出站出口网络选择。[AUTO]=跟随系统默认网络（系统 VPN 在即走 VPN，否则物理网络）；
 * 其余把 PROXY 出站流量 per-socket 绑到对应 transport 的网络，实现「选哪张网出去」。
 * ⚠️ 系统第三方 VPN 未 allowBypass / lockdown 时，绑物理网络会失败——UI 侧据在线状态提示。
 */
enum class EgressNetworkChoice { AUTO, VPN, WIFI, CELLULAR, ETHERNET }

/** 外观（深浅色）。默认 [SYSTEM] 跟随系统。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
