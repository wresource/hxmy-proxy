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

/**
 * 直连(DIRECT 规则)出口选择——独立于 PROXY 的 [EgressNetworkChoice]。DIRECT 语义即「绕过共享 VPN 走真实
 * 物理网络」，故无 VPN 选项。[AUTO]=按优先级链 以太网/USB → WiFi → 蜂窝，都拿不到即 fail-closed 断开
 * （绝不回落默认路由=VPN，防地理敏感直连泄漏）；其余为手动钉死某张物理网。手动指定蜂窝/以太网时
 * requestNetwork 拉起保活，AUTO 只用被动句柄不主动拉起（省电，见 UnderlyingNetworkProvider）。
 */
enum class DirectEgressChoice { AUTO, ETHERNET, WIFI, CELLULAR }

/**
 * 物理出口的**优先级顺序**——[DirectEgressChoice.AUTO] 按它挑第一张在线的网,
 * 「指定出口连不上」时的降级也按它挑替代路线。
 *
 * 顺序此前硬编码在 `current()` 里(以太网 → WiFi → 蜂窝),用户改不了;
 * 而哪条网更该优先完全取决于现场(办公室有 USB 网卡、外出只有蜂窝)。
 */
val PHYSICAL_EGRESS_ORDER_DEFAULT: List<DirectEgressChoice> =
    listOf(DirectEgressChoice.ETHERNET, DirectEgressChoice.WIFI, DirectEgressChoice.CELLULAR)

/**
 * 把存下来的顺序**规范化**:剔除 [DirectEgressChoice.AUTO] 与重复项,
 * 再把缺失的补到末尾(按默认相对顺序)。
 *
 * 必须有这一层:存储可能是旧版本写的、可能被导入的备份污染、也可能将来加了新的
 * 物理网类型。任何一种情况下都得吐出一个**完整且无重复**的全排列,
 * 否则 `current()` 会漏掉某张实际可用的网,表现为「明明连着却说没网」。
 */
fun normalizeEgressPriority(raw: List<DirectEgressChoice>): List<DirectEgressChoice> {
    val kept = raw.filter { it in PHYSICAL_EGRESS_ORDER_DEFAULT }.distinct()
    return kept + PHYSICAL_EGRESS_ORDER_DEFAULT.filterNot { it in kept }
}

/**
 * 备用 DNS（DoH）走哪张网。
 *
 * 为什么需要独立于 [EgressNetworkChoice]：DoH 是「系统 DNS 已经失败之后」才被调用的救济手段，
 * 而此前它用 `url.openConnection()`、跟随**进程默认路由**——egress=VPN 时那正是刚刚出问题的
 * 那条 VPN。于是 DoH 与它要救的业务走同一条正在死的路，一起失败（0803 实测：救援成功率仅 6.5%，
 * 72 次失败）。把它绑到物理网，才能在 VPN 半死时仍然问得到答案。
 *
 * [FOLLOW_DIRECT]（默认）=跟随「直连出口」那套选择（[DirectEgressChoice]），语义天然一致：
 * 两者要的都是「绕开可能坏掉的 VPN，走真实物理网」。
 * [DEFAULT] =保持旧行为，跟随系统默认路由（VPN 在线时即走 VPN）——需要经 VPN 才能到达
 * 境外 DoH 端点时用它。其余为手动钉死某张物理网。
 *
 * **注意与端点选择的配套关系**：绑到物理网后请求变成国内直连，而 8.8.8.8 / 1.1.1.1 在国内
 * 直连不可达，所以端点表里必须同时有国内可直连的（见 OutboundConnector.DOH_ENDPOINTS）。
 * 只改其一都会让 DoH 更糟。
 */
enum class DohEgressChoice { FOLLOW_DIRECT, DEFAULT, ETHERNET, WIFI, CELLULAR }

/**
 * 指定出口（[EgressNetworkChoice] 非 AUTO）连不通时怎么办。
 *
 * **默认 [STRICT]，因为两种失败的代价不对称**：
 * [DEGRADE] 保住的是「这次能用」，赔上的是**出口身份**——同一账号的请求一会儿从 VPN 出口发出、
 * 一会儿从物理网发出，对做 IP 一致性风控的服务（Claude CLI 报 403 并要求重新登录就是这个形态）
 * 本身就是异常模式，代价可能是封禁，而且不可逆。
 * [STRICT] 赔上的只是「这次连不上」——用户立刻能察觉、能自己决定换网还是关代理。
 *
 * 0806 实证：`api.anthropic.com` 在 32.4 小时内被降级 6 次，每次都换了出口 IP，
 * 而当时负载很低（conn 5~13）——不是拥塞，是那条出口本身不稳。
 *
 * 注意这**只管连接路径**。DNS 解析换网救援不受影响：解析只是拿地址，
 * 不涉及出口身份，救回来反而是纯收益。
 */
enum class EgressFallback {
    /** 指定出口连不通 → 降级默认路由重试（旧行为，保可用） */
    DEGRADE,
    /** 指定出口连不通 → 直接断开，绝不换路（保出口身份） */
    STRICT,
}

/** 外观（深浅色）。默认 [SYSTEM] 跟随系统。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
