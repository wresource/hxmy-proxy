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
 * VPN 断开时的策略。默认 [BLOCK]（fail-closed，避免用户误以为还在走 VPN）。
 */
enum class VpnDownStrategy { CONTINUE, BLOCK, WARN }
