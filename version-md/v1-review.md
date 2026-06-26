# hxmy proxy · V1 第一轮设计评审

本评审对原始 `design.md` 执行了 **5 维度对抗式评审**（Android 平台兼容性、网络与代理正确性、安全、架构与可行性、产品/范围/UX），共产出 **37 条 findings**。每一条都经过 **独立事实核查**——对照官方 Android 文档与 RFC 逐条判定（confirmed / partially-correct / refuted / uncertain），并在判定中对夸大的严重度或描述错误的机制做出更正。评审与 V1 详细设计并行推进，**绝大多数问题已在 V1 详细设计中被跨切面决策（D1–D7）解决**；本文逐条说明解决方式或为何无需处理。

---

## ⚠️ 决策更新 (2026-06-22)

用户已对四个原开放项拍板，**以下决策为准，并 SUPERSEDE 下文各 finding "V1 处理" 中仍引用的旧决策 D1(推迟 mDNS) / D3(pin targetSdk 36) / D5(默认强制认证)**，详见 v1-design.md §2：

1. **mDNS 进 V1**（非 V1.1）：作为 IP 方案之上的便利层，`MdnsPublisher`(core/network) 进 V1；PAC/推荐入口链 `hxmyproxy.local` 在前、随后强制附具体接口 IP 作为 MANDATORY fallback（绝不单独 `.local`），逐接口 IP 列表仍是主路径与最广兼容路径。
2. **首发 targetSdk 37**（非 pin 36）：`ACCESS_LOCAL_NETWORK` 为 day-one 强制运行时权限（同时门控入站 accept 与 mDNS multicast），首次运行硬门控；minSdk 仍 29。
3. **认证可选**（非默认强制）：默认无认证 + 非可信网络明确告警；仍保留反 SSRF 的 EgressGuard 与开启认证时的加密凭据存储；源 IP 准入仅为便利过滤、非安全边界。
4. **连接/资源上限可调 + 三档预设**：省电 64/32、均衡(默认) 256/128/N32/32KiB、高吞吐 512/256；原则——连接数 ≠ 带宽。
5. FGS 仍 connectedDevice 主选 / specialUse 回退（Play 接受度仍 NEEDS VERIFICATION）；包名/命名空间为 `com.mzstd.hxmyproxy`。

---

## 评审结论速览

**按严重度（findings 原始标注）**

| critical | high | medium | low | 合计 |
|---|---|---|---|---|
| 6 | 16 | 10 | 5 | 37 |

**按事实核查判定**

| confirmed 已确认 | partially-correct 部分成立 | refuted 已驳回 | uncertain 存疑 | 合计 |
|---|---|---|---|---|
| 14 | 22 | 1 | 0 | 37 |

> 注：核查在多条 critical/high 上下调了严重度（如 #7 critical→medium、#8 critical→high、#32 critical→medium、#18/#33/#35 high→medium、#26 high→medium、#25 high→low-med、#24 medium→low），文中逐条标出。

### 🔴 必须在写代码前解决（confirmed/partially-correct 且 critical/high、触及正确性或安全）

- **#0 ACCESS_LOCAL_NETWORK 是 INBOUND 监听的硬门，而非"发现"小事**（critical / confirmed）— targetSdk 37 上被拒即整机失效。→ **首发 targetSdk 37（2026-06-22 决策）**：`ACCESS_LOCAL_NETWORK` 为 day-one 强制运行时权限，首次运行硬门控（同时门控入站 accept 与 mDNS multicast），minSdk 仍 29。
- **#1 dataSync FGS 受 6h/24h 上限**（critical / confirmed）— 长时网关被系统强杀。→ **D2**：dataSync 直接 DROP，主选 `connectedDevice`，回退 `specialUse`。
- **#2 connectedDevice 需配套权限，且有 Play 政策风险**（high / partially-correct）— 缺 `FOREGROUND_SERVICE` 基础权限会崩。→ **D2**：声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 合格的 `CHANGE_NETWORK_STATE/CHANGE_WIFI_STATE`，并在 `startForeground()` 前满足；回退 specialUse + 子类型属性 + Play 说明。
- **#7 入站 LAN 回包可能被 VPN 路由黑洞**（critical→**medium** / partially-correct）— 机制被夸大（Android 源地址感知路由通常已对称回包），但作为防御加固保留。→ **D4**：每个 server FD 仅做对称 pinning（`Network.bindSocket`），并加真实握手探针。
- **#8 出口 VPN 保证只声明未强制**（critical→**high** / partially-correct）— 当前并非实缺陷，但属潜在泄漏。→ **D4**：出口 socket 永不 bind（跟随默认网=VPN），**禁用 `bindProcessToNetwork`**；block 策略由 IP-echo 自检驱动，而非仅 `TRANSPORT_VPN`。
- **#9 SOCKS5 缺远程 DNS 语义与 REP/ATYP/BND 回复契约**（high / partially-correct）。→ 按 **grounded §8**（RFC 1928/1929）钉死线格式：on-device 解析走默认网、REP 映射、`0xFF`/RFC1929 子协商、`ATYP=0x03` 无 NUL。
- **#10 普通 HTTP 正向代理转发规则全缺**（high / confirmed）。→ 按 **grounded §8**：absolute-URI→origin 改写、Host 协调、hop-by-hop/`Proxy-*` 剥离；V1 可先 `Connection: close` 单请求避开 framing/走私。
- **#16 默认开放代理：无认证 + bind 0.0.0.0**（critical / confirmed）— 任意同网段对端搭乘用户 VPN 身份。→ **认证可选（2026-06-22 决策）**：默认无认证 + 非可信网络明确告警；保留反 SSRF 的 EgressGuard 与开启认证时的加密凭据存储；残留风险由用户接受。
- **#17 无出口限制 → SSRF 可达本机 loopback / 内网**（high / confirmed）。→ **D5**：默认阻断 loopback/link-local/private-LAN 出口（解析后按 IP 校验防 rebinding），RFC1918 改为显式 opt-in。
- **#23 并发模型从未拍板**（high / confirmed）— I/O 原语贯穿 RelayEngine 难后换。→ **D6**：决策已做——coroutines + 阻塞 socket + `Dispatchers.IO.limitedParallelism(N)`，弃 raw NIO/Netty。
- **#27 代理 CoroutineScope 归属与跨生命周期存活未定义**（high / confirmed）— 共享服务最核心的生命周期问题。→ Service 拥有 `SupervisorJob` scope；进程级 StateFlow holder 供 UI 读取；`START_STICKY` + DataStore 重建。
- **#34 VPN-down 默认 block 在客户端表现为静默断连**（high / confirmed）。→ 双向可观测：手机端高可见通知，客户端返回合成 502/503 或 SOCKS5 `0x03`。

---

## Android Platform & Compatibility

### [critical] ACCESS_LOCAL_NETWORK gates the INBOUND listening socket itself
**核查判定:** 已确认 (confirmed)
**问题:** 设计把 `ACCESS_LOCAL_NETWORK` 当作"发布 mDNS / 端口可达"的软需求并仅用一个 boolean 表示。实际它在网络栈深层对**双向所有网络 API** 生效，含"accepting incoming TCP connections"，覆盖的地址集（10/8、172.16/12、192.168/16、169.254/16、100.64/10、直连路由等）恰是本产品所有客户端的来源 IP。targetSdk 37+ 上被拒即核心代理整机失效（客户端连接超时、机上无报错）。
**V1 处理:** 按 **2026-06-22 决策（首发 targetSdk 37）** 把它当作与 FGS 同级的 day-one 硬门：`ACCESS_LOCAL_NETWORK` 为强制运行时权限，首次运行硬门控（同时门控入站 accept 与 mDNS multicast），minSdk 仍 29；未授权则**硬阻断运行态**，denial 经超时/`android_getnetworkblockedreason()` 映射到"本地网络权限未授权"错误类。对照 grounded §1。（SUPERSEDES 旧 D3 pin targetSdk 36 推迟强制方案。）

### [high] connectedDevice FGS type requires specific permissions + Play-policy mismatch
**核查判定:** 部分成立 (partially-correct)
**问题:** 3.2 的 manifest 声明了 `foregroundServiceType` 但权限清单缺 `FOREGROUND_SERVICE` 基础权限及 connectedDevice 的合格伴随权限，默认路径下 `startForeground()` 会抛 `SecurityException`；且把被动 LAN 监听服务声明为 connectedDevice 有 Play 审核语义风险。
**核查更正:** 崩溃是**有条件的**而非必然——同 manifest 还声明了 `dataSync`（无运行时前置），显式 `startForeground(..., DATA_SYNC)` 不会崩。最干净的修法是丢弃 connectedDevice；但无论选哪种类型，`FOREGROUND_SERVICE` 都必须补上（finding 此点正确）。
**V1 处理:** 采纳 **D2**——声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 合格的 `CHANGE_NETWORK_STATE/CHANGE_WIFI_STATE`，并在 `startForeground()` **前**满足前置；dataSync 因 6h 上限被弃用（见 #1），故不走 dataSync 这条"零门槛"退路；回退 `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` + 预写 Play 说明。对照 grounded §2。

### [critical] dataSync FGS type capped at 6h/24h — fatal for an always-on gateway
**核查判定:** 已确认 (confirmed)
**问题:** 3.2 提议 `connectedDevice|dataSync`。targetSdk 35+ 下 dataSync 累计 6h/24h 触发 `Service.onTimeout()` 须秒级 `stopSelf()` 否则被杀；位掩码组合也无法逃脱 dataSync 计时器；Watchdog 无法挽救（平台级强杀，重启会撞 `ForegroundServiceStartNotAllowedException`）。
**V1 处理:** 采纳 **D2**——**直接 DROP dataSync**，改用单一无计时类型 `connectedDevice`（无时限，前置由 `CHANGE_WIFI_STATE/CHANGE_NETWORK_STATE` 满足），回退 `specialUse`。Play 接受度标记为 **NEEDS VERIFICATION**。对照 grounded §2。

### [high] Interface-name assumptions (ap0/rndis0/bt-pan) are vendor-specific; NetworkInterface enumeration unreliable for tether
**核查判定:** 部分成立 (partially-correct)
**问题:** 5.2/6.1 把接口名硬映射为类型（ap0=HOTSPOT 等），但这些名字非契约（softap 可能是 wlan1/swlan0/aware_data0，USB 可能 usb0/ncm0）；Android 11+ MAC 被置空；且 `registerNetworkCallback` **不会**把本机 AP/tether 接口当作 Network 上报，9.1 把热点/USB/BT 出现-消失列为 NetworkCallback 事件是错的。
**核查更正:** 最可靠的真缺陷是 **NetworkCallback gap**——应改用 `TetheringManager.addTetheringEventCallback()/getTetheredIfaces()`（API 30+）或 `ACTION_TETHER_STATE_CHANGED` 广播 + `NetworkInterface` 轮询。"枚举完全看不到接口"被夸大（通常能看到，主要风险是按名误标）；MAC 点对本设计无影响（已按 client IP 标识）。
**V1 处理:** V1 结构化判型——按本机是否持 `.1/网关地址`、`InterfaceAddress` 子网、接口 flags 综合判定，名字仅作弱提示（宽前缀 allowlist），保留 UNKNOWN 桶；tether/hotspot 检测改用 `TetheringManager` + 轮询，不依赖 `registerNetworkCallback`；标识永不用 MAC。mDNS 相关入口在 V1 用 RAW IP（见 **D1**）。

### [high] Android 12+ background FGS-start restriction breaks Watchdog/auto-recovery restarts
**核查判定:** 部分成立 (partially-correct，severity 调整: high → medium)
**问题:** 11.1/17.3 依赖 Watchdog 在失败后重启服务；Android 12+ 后台不允许 `startForegroundService()`，OEM 杀进程后自恢复属被禁场景，"断流自恢复"承诺受限。
**核查更正:** 两点夸大：(1) 11.1 实际只是"停监听→等 500ms→重启监听"，FGS 仍存活、**不调用** `startForegroundService()`，该路径完全合法不会崩；异常仅在假想的"从后台重启被杀服务"扩展中出现。(2) 电池优化白名单（Doze allowlist）下豁免该限制，17.3 已含"电池优化白名单引导"，故配合 WorkManager/Alarm/FCM 可合法自动重启——并非绝对不可能。
**V1 处理:** V1 如实划界——Watchdog 仅在 FGS 存活时重启进程内监听 socket/协程（允许）；进程被杀的全自动恢复**仅在白名单或用户交互/精确闹钟/高优 FCM 豁免下成立**；非白名单用户以通知点击（用户交互豁免）一键拉起为兜底。对照 grounded §4。

### [medium] VPN-egress undermined by per-app/split-tunnel; TRANSPORT_VPN check doesn't prove this app egresses via VPN
**核查判定:** 部分成立 (partially-correct)
**问题:** 产品前提是出口 socket 跟随默认网（通常承载系统 VPN），并用 `TRANSPORT_VPN` 验证。但路由型 split-tunnel 下 `TRANSPORT_VPN` 在场而部分目的地仍明文出网，"VPN: detected" 可与实际泄漏并存。
**核查更正:** 机制需纠正——真正的误报源是**路由型/部分 split-tunnel**（`addRoute` 只覆盖部分目的地）；finding 举的 per-app **排除列表**例子不成立：被排除的 app 看到的默认网是底层非 VPN 网（`TRANSPORT_VPN` 缺失），block 策略反而会正确触发。
**V1 处理:** 采纳 **D4**——把 10.1 的出口 IP 检测从可选**升为权威信号**：以真实出口 IP（IP-echo）对比基线驱动 block 决策，不只看 capability bit；并在 UI 提示 split-tunnel 可能排除本 app、需用户确认系统 VPN 包含本包名。对照 grounded §3/§6。

### [low] Fixed ports lack EADDRINUSE / SO_REUSEADDR handling across restart cycles
**核查判定:** 部分成立 (partially-correct)
**问题:** 端口固定（8080/1080/8899）且把"端口被占用"列为错误类，但无 bind 重试/选择；共存 app 占端口或 Watchdog 快速重启的 `TIME_WAIT` 竞争会导致"FGS 在跑但无端口监听"。
**核查更正:** `SO_REUSEADDR` 仅解决 `TIME_WAIT` 重绑（Watchdog 竞争），**不能**让两个活监听共用端口（那是 `SO_REUSEPORT`），故"别的 app 已占用"仍需 fallback 端口或明确诊断。
**V1 处理:** V1 所有监听 socket 设 `SO_REUSEADDR`；bind 后校验实际端口（`ProxyEntry.port` 已支持动态端口）；`EADDRINUSE` 时自动选备用端口并反映到 PAC/推荐入口 UI，或明确报"端口被占用"并指明冲突。与 #28 合并实现。

---

## Network & Proxy Correctness

### [critical] Inbound LAN reply packets can be black-holed by VPN routing unless server sockets pinned
**核查判定:** 部分成立 (partially-correct，severity 调整: critical → medium)
**问题:** 入站 SYN 总会送达本地 socket，但回包路径在 VPN 装入 per-UID 规则时可能被导入 VPN 表、找不到 LAN /24 路由而丢弃，导致 device/VPN 特定的间歇性连接失败。
**核查更正:** 机制被夸大——Android 多表路由**源地址感知**：已建立的 accepted socket 从物理接口本地 IP 回包，per-network 源/oif 规则通常先于 per-UID VPN 规则把流量送回物理接口表（含连通 LAN /24）。这也是 Every Proxy/KDE Connect 等同类应用在 VPN 开启下仍可达的原因。属非典型 VPN 规则下的边缘风险，非"几乎必然黑洞"。
**V1 处理:** 采纳 **D4** 的对称 pinning 作为防御加固——对每个 accepted server FD 调 `Network.bindSocket()` 绑定承载该入站接口的非 VPN underlying Network，使回包对称；同时加"真实完成 LAN 侧 TCP 握手"的诊断探针（而非仅 isListening）。**只 pin LAN 侧 server socket，出口 socket 绝不 pin**（见 #8）。

### [critical] VPN-egress guarantee asserted but never enforced; nothing forbids binding outbound to non-VPN network
**核查判定:** 部分成立 (partially-correct，severity 调整: critical → high)
**问题:** 核心卖点是共享系统 VPN 出口；若开发者为修 #7 而调 `bindProcessToNetwork`/进程级 `Network.bindSocket`，所有出口连接会被 pin 到 wlan0 而绕过 VPN，造成静默泄漏且仍能通过 naive is-VPN-detected 检查。
**核查更正:** 这是**潜在缺陷而非现有缺陷**——2.1 的默认网模型本身正确无泄漏，设计从未调用 `bindProcessToNetwork`；`InterfaceScanner/sourceInterface` 服务于入站枚举而非出口绑定。真正的现有缺口是 10.2 仅凭 `TRANSPORT_VPN` 驱动 block。
**V1 处理:** 采纳 **D4**——明确不变量：**出口(origin) socket 不做任何 Network 绑定**，跟随默认网=VPN；**全局禁用 `bindProcessToNetwork`**；入站对称 pinning 仅在 server FD 逐 socket 进行；Diagnostics 加真实出口自检（按 relay 同样方式开 socket 命中 IP-echo 比对出口 IP），block 策略 fail-closed 由该实测结果驱动。对照 grounded §3。

### [high] SOCKS5 omits remote-DNS semantics and the BND/ATYP/REP reply contract
**核查判定:** 部分成立 (partially-correct)
**问题:** 7.2 仅列特性不定线格式：(a) `ATYP=0x03` 域名须在机上经默认网解析以免 DNS 泄漏；(b) CONNECT 成功回复 REP/ATYP/BND 结构未定；(c) REP 错误码映射未定；(d) RFC1929 用户名/口令子协商与 `0xFF` 未提（误处理会令客户端挂起）。
**核查更正:** DNS 泄漏是**有条件**的——本应用非 VpnService、走默认网，`InetAddress.getByName` + 普通 socket 本就让 DNS 与 TCP 同走 VPN；泄漏仅在显式把出口 socket 绑到非默认 Network 时发生。(b)(c)(d) 是任何遵循 RFC 1928/1929 的实现都会处理的标准细节，整体偏 medium-high。
**V1 处理:** 按 **grounded §8** 钉死线契约：域名经默认网 on-device 解析后按 IP 转发（绑定纪律）；成功回复 `REP=0x00`、`ATYP=0x01`、`BND=0.0.0.0:0`；完整 REP 映射（host-unreachable `0x04`/network-unreachable `0x03`/refused `0x05`/timeout `0x01`/access-denied `0x02`/unsupported-ATYP `0x08`）；offer `0x00`/`0x02`，无可接受方法回 `0xFF` 并关闭；RFC1929 `VER=0x01` 长度前缀子协商，失败非零 STATUS 即关闭；`ATYP=0x03` 读 1 长度字节、无 NUL。

### [high] Plain-HTTP forward-proxy correctness rules unspecified
**核查判定:** 已确认 (confirmed)
**问题:** 7.1 说支持普通 HTTP 代理但零转发规则。正确实现须解析 absolute-form 请求行、改写为 origin-form、协调 Host、剥离 hop-by-hop 头（`Connection/Proxy-Connection/Proxy-Authenticate/Proxy-Authorization/TE/Trailer/Transfer-Encoding/Upgrade/Keep-Alive` 及 `Connection` token 列出的头）、按 `Content-Length`/chunked 定界消息体。转发 `Proxy-Authorization` 给 origin 是凭据泄漏；framing 错会导致请求走私/流错位。
**V1 处理:** V1 plain-HTTP 先**限定 `Connection: close`、每连接一请求**以规避 body framing 与走私，同时实现 absolute→origin 改写、Host 协调、hop-by-hop/`Proxy-*` 剥离（`Proxy-Authorization/Proxy-Connection` 永不转发）；尽量引导客户端走 CONNECT 缩小解析面。对照 grounded §8。

### [high] No IPv6 strategy for listeners, admission, or origin connections
**核查判定:** 部分成立 (partially-correct，severity 调整: high → medium-high)
**问题:** 全设计皆 IPv4：bind `0.0.0.0` 不接受 IPv6 客户端；admission 无 IPv6 前缀逻辑；SOCKS5 `ATYP=0x04` 与 bracketed `[v6]:port` CONNECT 未处理；#7 的回包 pinning 须匹配地址族。
**核查更正:** 两点收窄：(1) "IPv6-only origin 不可达"过强——域名经系统解析的 v6 目的地本就可达，真缺口仅是**字面 IPv6 目的地**（`ATYP=0x04`/bracketed host）；(2) 客户端侧缺口集中在**共享 Wi-Fi LAN**，热点/USB/BT-PAN tether 多为 IPv4。最简修法是 bind 未指定的**双栈通配**（`InetSocketAddress(port)` 无地址，Android 默认 `IPV6_V6ONLY=false` 同收两族），而非同时 bind `0.0.0.0` 与 `::`。
**V1 处理:** V1 监听绑双栈通配 socket；admission 泛化为按 `prefixLength` 比较（`ShareInterface` 已携带 `InetAddress`+前缀长度）；支持 `ATYP=0x04` 与 bracketed-host CONNECT；origin 连接用系统解析以覆盖 v6 域名目的地。

### [high] PAC and recommended entries publish hxmyproxy.local while V1 defers mDNS
**核查判定:** 已驳回 (refuted)
**问题:** finding 主张设计把 mDNS 推迟到"V1.1"却在 V1 发 PAC，导致 PAC 指向不可解析的 `hxmyproxy.local`。
**核查更正:** 核心前提**事实错误**——原 `design.md` 路线图（15 节）中 PAC 与 mDNS 是**同一 V1 阶段**的兄弟项，不存在"V1.1"，故无"PAC 已发而 mDNS 未发"的版本；设计也已要求 IP fallback 与按当前可用入口动态生成 PAC。
**V1 处理:** **本 finding 不需要按其原始主张处理**（前提不成立）。其衍生的排序建议有效，且与 **2026-06-22 决策（mDNS 进 V1）** 一致：mDNS 作为 IP 方案之上的便利层进 V1，PAC/推荐入口链 `hxmyproxy.local` 在前、随后**强制附具体接口 IP 作为 MANDATORY fallback**（绝不单独 `.local`），逐接口 IP 列表仍是主路径。因此 V1 的 `FindProxyForURL` 始终给出可解析的 IP 兜底，从根本上消除该隐患。（SUPERSEDES 旧 D1 推迟 mDNS / RAW-IP-only 方案。）

### [medium] Source-IP subnet admission is spoofable and mis-buckets under overlapping private ranges
**核查判定:** 已确认 (confirmed)
**问题:** 6.2 仅按来源 IP 子网准入：(1) 同 L2 段源 IP 可伪造，是便利过滤非安全控制，而 12 节把网段门控当控制项过度承诺；(2) 当所选接口子网重叠（如 rndis 192.168.42.x 与热点同段）时，仅凭远端 IP 无法判断连接从哪个接口进来——正确信号是 accepted socket 的**本地地址**；(3) 错桶会级联到 #7 的回包 pinning 导致掉连。
**核查更正:** 仅一处轻微夸大——rndis 与 Wi-Fi/热点的 192.168.42/24 完全重叠是"可能但有条件"，非默认（热点通常落在不同 /24）。不改判定与建议。
**V1 处理:** 采纳 **D5**——按 `Socket.getLocalAddress()`（接收接口）判桶后再做准入，远端 IP 仅用于显示；回包 pinning 同样取本地地址；文档明确网段门控为便利过滤而非安全边界，真正边界靠认证。

### [medium] Relay half-close / timeout / FD-leak semantics under-specified vs 512-connection ceiling
**核查判定:** 已确认 (confirmed)
**问题:** 11.2 列了状态机与超时数（idle 300s/half-close 15s/connect 10s/DNS 5s/max 512）但缺防泄漏不变量：半关闭须 `shutdownOutput()` 逐方向传播而非首个 EOF 即双关；origin 连接失败后须给客户端报错关闭而非泄漏；协程取消须在 `finally` 保证两端 FD 关闭，否则 512 上限被泄漏死连接填满且 Watchdog 重启反而掩盖泄漏；idle 超时须任一方向有字节即重置。
**V1 处理:** V1 明确实现：逐方向独立 relay，EOF 时 `shutdownOutput`、两端皆完或出错才全关；每连接一个 owning scope 的 `finally{}` 在任何取消/异常关闭两端 FD；idle 计时器任一方向有字节即重置；origin 连接失败发对应 CONNECT/SOCKS5 错误码再关闭；Diagnostics 加 FD/活动连接计量使泄漏可观测。与 **D6** 的并发模型一并落地。

### [medium] VPN detection via TRANSPORT_VPN doesn't prove this app's egress goes through VPN
**核查判定:** 部分成立 (partially-correct)
**问题:** 10.1 仅查"某网络有 `TRANSPORT_VPN`"不足；须查本 UID 实际默认网，且 always-on lockdown 与否改变 VPN down 时是否仍出网。把 VPN 在场当作"我的流量已隧道化"的布尔代理可能为假。
**核查更正:** 略夸大——10.1 已把出口 IP 检测与默认网变更列为补充信号；且 `getActiveNetwork()/registerDefaultNetworkCallback()` 本就返回**本 UID** 默认网，被 split-tunnel 排除的 app 会正确地不显示 `TRANSPORT_VPN`，故标准排除场景多已能处理。
**V1 处理:** 采纳 **D4**——对本 app 自身默认网（`registerDefaultNetworkCallback`）查 capability，**不**遍历 `getAllNetworks` 找任意 VPN；block 模式（10.2）由 #8 的实测出口 IP 自检而非 `vpnDetected` 布尔决定；并把"设备有 VPN 但本 app 可能被排除（split-tunnel/lockdown）"作为独立诊断态展示。对照 grounded §6。

---

## Security

### [critical] Open proxy by default: no auth + bind 0.0.0.0 lets any LAN/hotspot peer ride the user's VPN/identity
**核查判定:** 已确认 (confirmed)
**问题:** 12.1 把认证设为可选并把"关闭认证"列为最低/默认档，6.2 又把监听绑 `0.0.0.0`。开箱关认证时，加入同一 L2 段的任意设备都能把手机当全开放转发代理，且流量经手机默认网（通常承载 VPN）以用户出口 IP/身份出网。MVP 更是完全无认证、无访问控制。
**核查更正:** 一处精度——设计已有按网段的来源准入（6.2），残留洞是**所选子网内无逐对端认证**；且 Android 10–17 无平台机制弥补（`ACCESS_LOCAL_NETWORK` 管本 app 出站本地访问，不认证入站对端）。结论不变。
**V1 处理:** 按 **2026-06-22 决策**——**认证可选**：默认无认证 + 非可信网络明确告警，开启认证时支持 HTTP Basic / SOCKS5 RFC1929（口令经加密存储，见 #19）。残留开放代理风险由用户接受；纵深防御保留反 SSRF 的 **EgressGuard**（默认阻断 loopback/link-local/this-host/multicast/自身监听，private-LAN RFC1918 默认放行、可选 block 开关，见 #17）。源 IP 准入仅为便利过滤、非安全边界。（SUPERSEDES 旧 D5 默认强制认证方案。）

### [high] No egress restriction: SOCKS5/HTTP CONNECT can reach phone-local and private-LAN services (SSRF)
**核查判定:** 已确认 (confirmed)
**问题:** 7 节定义 CONNECT 无目的地过滤，客户端可 `CONNECT 127.0.0.1:<port>`/`169.254.x.x`/`192.168.x.x`，RelayEngine 直接拨号；域名 SOCKS5 更使字面串黑名单可被 DNS rebinding 绕过。后果是 LAN/热点对端获得跳板，可探测本机 loopback 服务、路由器后台、内网主机，乃至回环进代理自身端口。
**核查更正:** 两点补充——`ACCESS_LOCAL_NETWORK` **不**缓解此问题（它不区分"接受入站"与"拨出至 LAN"，且使 app 能作 LAN 代理所必需的授权恰好也授权了 SSRF 出口）；169.254.169.254/ADB-over-TCP 等为情境性目标。loopback + RFC1918/link-local 跳板真实且默认暴露。
**V1 处理:** 采纳 **D5**——在 RelayEngine 加出口过滤，**解析后按 IP 校验**（防 rebinding）：默认 DENY loopback(`127.0.0.0/8`,`::1`)、link-local(`169.254/16`,`fe80::/10`)、this-host/`0.0.0.0`、multicast 及代理自身监听地址/端口；"允许 RFC1918 目的地"为默认关闭的显式 opt-in。

### [high] Source-IP admission is the only barrier yet trivially spoofable on a flat L2 segment
**核查判定:** 部分成立 (partially-correct，severity 调整: high → medium)
**问题:** 6.2 把"按来源 IP 拒非选网段"当访问控制，关认证时它是唯一屏障；同段任意设备自配段内 IP 即过准入，提供零客户端认证。risk 在于把网段选择误当安全保证。
**核查更正:** "设计未声明此局限"不准确——12.2 已警告关认证时同 LAN 设备可连并建议仅用于可信网络，12.1 还提供白名单与可选认证；真缺口更窄：未把网段准入框定为"非认证/可被同链路绕过"，且 UI 文案未带安全告诫。属框架/文案加固，非未缓解的 high 洞。
**V1 处理:** 采纳 **D5**——设计与 UI 明确网段准入为便利/范围过滤、非认证、可被同链路设备绕过；真正边界为认证（见 #16）；任何 UI 文案不得把网段选择表述为安全保证。

### [medium] Proxy credentials in DataStore stored in plaintext
**核查判定:** 已确认 (confirmed)
**问题:** 16 节选 DataStore 持久化、12 节引入可选认证，但未规定加密存储。Plain DataStore 明文写入 app 私有文件，在 root/备份提取/误入设备备份时口令可被读出。
**V1 处理:** 采纳 **D5**——口令存于 `EncryptedSharedPreferences`/Keystore（或 Keystore 持密钥加密 + Jetpack Security/Tink 模式），不入明文 DataStore；凭据文件排除自动备份；不打日志。低成本改动。

### [medium] VPN-down detection doesn't prevent leak during the detection gap; 'block' must fail closed atomically
**核查判定:** 已确认 (confirmed)
**问题:** 10 节用 `TRANSPORT_VPN` + 默认 block，但回调异步：VPN 掉线到回调触发之间，已建立的转发连接仍经裸链路出网；且 `TRANSPORT_VPN` 在场不保证本 socket 真经 VPN（split-tunnel/per-app）。
**核查更正:** 一处补充——被 VPN 排除的 app 通常看不到 `TRANSPORT_VPN`，故 naive "无 VPN 即 block" 反而能捕获该排除场景；更强的真风险是检测间隙 in-flight 泄漏、allowed-app/路由/IPv6 旁路，以及 capability 非逐 socket 出口证明。
**V1 处理:** 采纳 **D4/D5**——block 模式下默认网一变即**立即拆掉所有活动转发连接并停止接受新连接**直至重确认 VPN-up（fail-closed，而非仅门控新连接）；relay 前做出口 IP sanity check 验证真实出口；并在 Diagnostics 标注无法保证 split-tunnel 下逐 socket VPN 路由。

### [low] PAC/HTTP server on 0.0.0.0 leaks internal topology/entry inventory to any LAN peer
**核查判定:** 已确认 (confirmed)
**问题:** 7.3/8.3 在 `0.0.0.0:8899` 明文 HTTP 服务动态 PAC，任何能达 :8899 的对端可无认证 GET，获知手机跨多接口的内部地址、开放端口与协议清单（含其当前不在的网段），构成侦察便利。`ACCESS_LOCAL_NETWORK` 不缓解（它管出站不管入站）。
**V1 处理:** V1 对 PAC/诊断 HTTP 监听施加与代理相同的网段准入，且每份 PAC 仅含**请求方所在接口**的入口（不向只看得到一个接口的客户端列出其它网段）；不在网络上暴露任何诊断/状态端点；PAC body 保持最小。与 **D5** 一致、成本低。

### [low] Notification/clipboard 'copy entry' can leak entries/credentials on lock screen and to other apps
**核查判定:** 部分成立 (partially-correct)
**问题:** 13.2/14 提供"复制"按钮并在持久通知展示推荐入口；若认证方案把凭据嵌入复制串（如 `socks5://user:pass@host`），会落到系统剪贴板被读取，且通知默认在锁屏渲染入口细节。
**核查更正:** 威胁被夸大——Android 10+ 后台 app **无法**读剪贴板（仅当前 IME 与持焦 app 可读）；且当前设计只复制 host:port、无嵌入凭据，凭据泄漏是假设而非现状；锁屏暴露的仅是按设计本就公开的 LAN host:port。
**V1 处理:** 作为前瞻护栏采纳：**绝不**把凭据随入口串复制（仅复制 host:port，口令经显式 reveal）；敏感剪贴数据标 `EXTRA_IS_SENSITIVE`（API 33+）；前台通知敏感行设 `VISIBILITY_PRIVATE/SECRET`。当前非现存漏洞，按"若将来暴露凭据"的预防处理。

---

## Architecture & Feasibility

### [high] Concurrency model is named but never decided — the single highest-risk architectural choice
**核查判定:** 已确认 (confirmed)
**问题:** 16 节把代理核列为"Kotlin Coroutines + Java NIO，或 Netty"且从未敲定；三者架构互不兼容、I/O 原语贯穿 RelayEngine/状态机/字节计数难以后期更换。对手机场景，阻塞 socket + 协程远比手写 NIO Selector 简单（每隧道两个 `launch{}` 拷贝循环；512 连接≈1024 轻量协程映射到小线程池）。
**V1 处理:** 采纳 **D6**——**决策已做**（解决"从未拍板"）：coroutines + 阻塞 socket + `Dispatchers.IO.limitedParallelism(N)`，从 V1 删除 raw NIO 与 Netty；明确 512"连接"映射到约 1024 协程而非 1024 OS 线程；文档记录 head-of-line-blocking 取舍。对照 grounded §7。

### [medium] 512 max-connections with blocking I/O implies an unanalyzed thread/memory model
**核查判定:** 部分成立 (partially-correct，severity 调整: medium → low)
**问题:** 默认"最大并发 512"无线程/内存预算；若误用 thread-per-connection 阻塞 I/O，512 隧道≈1024 线程×512KB-1MB 栈≈0.5-1GB 会 OOM；若协程池过小则阻塞读饿死池致死锁。
**核查更正:** 一半是稻草人——原设计的 I/O 模型并非未定（已列 NIO/Netty 这类非阻塞），1024 线程 OOM 与池饿死针对的是设计不采用的模型。真正成立的是**缺缓冲内存预算与 512 的依据**。
**V1 处理:** 采纳 **D6/D7**——在 **D6** 选定的"阻塞 socket + 有界协程 dispatcher"下明确：禁止 thread-per-connection；计算真实上限≈512 隧道×2 方向×16–32KB 缓冲≈16–32MB；V1 默认上限下调到 128–256 且可配；并配小线程池服务全部挂起隧道。

### [high] Module tree heavily over-engineered for stated V1 scope
**核查判定:** 部分成立 (partially-correct，severity 调整: high → low-medium)
**问题:** 4.2 的目录含 ui/clients、HealthChecker、Watchdog、MdnsPublisher、ClientRepository、Room db、6 个 UseCase、4 个 Repository 与完整 domain/data/core 分层；首版会把时间耗在空 Repository/UseCase 间接层与无实体可存的 Room 上。
**核查更正:** 中心前提错——原 `design.md` 把 mDNS/Watchdog/HealthChecker/client list/log export 列在 **V1**（非"V1.1 推迟"），仅 Profiles 在 V2；故 4.2 大体与其 V1 路线一致。但"MVP/首版可更精简"仍成立；Room 也并非"无所存"（每日流量上限、IP 黑名单、日志导出是候选持久化）。
**V1 处理:** 采纳 **D7**——按 V1 实际范围裁剪过度工程模块：首版从 ViewModel 直接调 repository/core 而非先立 6 个 UseCase；`MdnsPublisher`(core/network) 按 **2026-06-22 决策进 V1**（作为 IP 方案之上的便利层，不再推迟）；`ClientSession` 最小化（见 #26）；Room vs DataStore 在访问控制/日志导出需求落定时再决（MVP 用 DataStore 足够）。

### [high] Per-connection byte counters in StateFlow will cause Compose recomposition storms
**核查判定:** 部分成立 (partially-correct，severity 调整: high → medium)
**问题:** `ShareState` 内嵌 `clients: List<ClientSession>`，`ClientSession` 持上下行字节与速率；若 relay 按 I/O 频率写入该 StateFlow，会在繁忙下载时每秒上千次更新、每次新建 `ShareState` 触发整个 dashboard 子树重组。
**核查更正:** 机制描述有误——`MutableStateFlow` 对慢收集者会 conflate，`collectAsStateWithLifecycle` 在帧率内取最新值，不会"每秒上千次重组"，重组也是**作用域内可跳过**的；真问题是 (a) 每次 socket 读都新建 `ShareState`/重算聚合的上游 CPU/GC 浪费，(b) `List<ClientSession>`+`InetAddress/Instant` 是不稳定类型致 per-client 段过度重组。设计也未规定按 I/O 频率写入。
**V1 处理:** 采纳 **D7**——字节计数在 proxy core 内用 atomics/逐连接状态累计，仅以**节流聚合**（如 `sample(1000ms)` 约 1Hz）暴露给 dashboard；V1 `ShareState` 移除 per-client 速率字段（rich client list 推迟），只暴露客户端数 + 聚合上下行；拆分 UiState 使吞吐/客户端段独立重组，类型用不可变/`@Immutable`。throttle StateFlow 快照避免重组风暴。

### [high] Ownership of the proxy CoroutineScope and survival across config-change/swipe-away/process-death undefined
**核查判定:** 已确认 (confirmed)
**问题:** 设计命名了 `ProxyForegroundService` 与 ViewModel+StateFlow 却从未说明谁拥有长寿命代理 scope、UI 如何观察实时态、服务启动 flag、进程死亡/划掉/旋转行为。这是共享服务的核心生命周期问题且未答：scope 放 ViewModel 会随 Activity 结束而停代理；bound-only 服务在最后一个 client 解绑时销毁；进程被杀重建时 Intent 为 null。
**核查更正:** 仅一处精度——子句 (e) 暗示 ViewModel-scope 会在**旋转**时重启代理；标准 ViewModel 经 ViewModelStore 在配置变更中存活，`viewModelScope` 仅在最终 clear 时取消，真正触发是后台到死/up-navigation。处方不变：scope 归 Service。
**V1 处理:** V1 明确：`ProxyForegroundService` 拥有 `SupervisorJob` 支撑的 `CoroutineScope(... + Dispatchers.IO)`，`onDestroy()` 取消；`startForegroundService` + `START_STICKY`，并在 DataStore 持久化足量配置以便 `onStartCommand` 进程死亡后重建（null Intent）；UI↔态用**进程级单例 holder** 暴露 `StateFlow<ShareState>`（Service 写、ViewModel 经 `stateIn` 读），绑定/命令接口仅用于 start/stop；旋转不触碰服务，划掉保持 FGS 运行（标注 OEM 注意）。对照 grounded §4/§7（自建 Service scope 标 NEEDS VERIFICATION）。

### [medium] Port-conflict/EADDRINUSE and startup-failure handling absent despite fixed default ports
**核查判定:** 部分成立 (partially-correct)
**问题:** 硬编码端口 + bind `0.0.0.0`，11.3 仅把"端口被占用"列为类别而无 `BindException` 处理：服务是否照常起、三端口原子还是部分失败、端口是否可配、是否自动选空端口均未设计；半起的 FGS 是僵尸。
**核查更正:** 两点平台纠正——`ForegroundServiceDidNotStartInTimeException` 由 Android 12（非 14）引入，仅当 `startForegroundService()` 后未在窗口内 `startForeground()` 才触发，`BindException` 本身不触发；默认结果是普通未捕获崩溃，仅当"先 bind 后 startForeground 且让异常逃逸"才转成 FGS 超时。
**V1 处理:** V1 显式 bind 处理：三端口可配（DataStore）；**先 `startForeground()` 再尝试 bind**，`BindException` 时把 `StartFailure(reason=PORT_IN_USE, port=X)` 写入既有 `ShareState`、转通知/UI 错误态，永不让 bind 失败阻挡 startForeground；可自动重试备用端口；Diagnostics 加端口可用性行。与 #6 合并。

### [medium] Admission control by source-IP subnet is spoofable and races interface changes
**核查判定:** 部分成立 (partially-correct)
**问题:** （与 #18 同源，侧重并发竞争）bind `0.0.0.0` + 按源 IP 准入，关认证为默认友好路径；多宿主时上游路由器段也可尝试连接；网络变更时 allowlist 异步重算（9.2），in-flight 连接与 OS 重分配 IP 到回调触发之间的窗口使准入读到陈旧数据。
**核查更正:** 略夸大——12.2 已有关认证警告且把网段选择与认证分列，未把网段当认证；旋转/过渡竞争的真实影响小于伪造问题；per-interface bind 的实操注意：ap0/rndis0/bt-pan 地址常不可稳定枚举/绑定，故 `0.0.0.0`+filter 是可辩护的主选。
**V1 处理:** 采纳 **D5**——网段准入明确为便利过滤、非 loopback 接口建议默认认证/白名单；allowlist 以**单次不可变 map 原子替换**快照，使每个 `accept()` 读一致视图；接口丢失时 in-flight 连接的丢弃策略明确化。与 #13/#18 一并落地。

### [low] Watchdog 'restart listener' loop conflicts with deferred scope and would mask real bind failures
**核查判定:** 部分成立 (partially-correct)
**问题:** 11.1 的盲目"停/等 500ms/重启监听"循环：对因端口被占而 bind 失败者会无限循环，周期性健康轮询重绑 socket 又与 17.3 的省电目标冲突；风险是被误拉进 V1 来"修"未处理的 bind/生命周期缺口。
**核查更正:** 范围前提颠倒——原 `design.md` 15.2 把 Watchdog/健康检查列在 **V1**（非 deferred），无"V1.1"。有效点纯属循环设计：盲目无退避、不区分可恢复/不可恢复故障、轮询重绑。
**V1 处理:** 结合 **D7** 处理：V1 的 Watchdog 仅在 FGS 存活时重启进程内监听（见 #4），并加**指数退避**、区分可恢复（瞬时接口抖动）与不可恢复（端口永久被占、权限被撤）故障，改由 ConnectivityManager 回调驱动刷新而非轮询；非可恢复故障交由 Diagnostics 暴露根因而非掩盖。

### [low] Testability of relay core not designed in
**核查判定:** 部分成立 (partially-correct)
**问题:** 无测试 seam：协议解析、CONNECT 解析、PAC 生成、子网准入这些最易出错的逻辑若与真实 socket/Android Context/Dispatcher 纠缠，只能靠慢而脆的 instrumented 测试。
**核查更正:** 两处前提偏差——原设计核选 NIO/Netty（非 java.net），且 4.2 已把 core/proxy 与 service 分离；属"对测试沉默"而非"已承诺不可测实现"。
**V1 处理:** V1 设计薄 seam（成本极低）：SOCKS5/HTTP 解析与 PAC 生成为对字节流的**纯函数**（`ByteArrayInputStream` 可测，无 socket）；定义 `SocketFactory`/连接源接口使 RelayEngine 可用内存管道驱动；注入 `CoroutineDispatcher`（默认 `Dispatchers.IO`）；CIDR/子网准入为 `(clientIp, allowlist)` 纯函数。与 **D6** 选定的阻塞-socket 模型一致。

---

## Product, Scope & UX

### [critical] Deferring mDNS reduces V1 to raw IPs and reproduces the exact Every Proxy pain
**核查判定:** 部分成立 (partially-correct，severity 调整: critical → medium)
**问题:** 设计自述以"稳定连接入口"为核心差异点（1.2 把 Every Proxy 头号痛点列为"网络变化后 IP 变化需重扫重配"，8.2 把 `hxmyproxy.local` 列第一优先级）。若 mDNS 缺席，PAC URL 本身（`http://192.168.1.34:8899/proxy.pac`）也随网络切换而变、须在每个客户端重输——PAC 并不解决移动 IP 痛点。
**核查更正:** 结构前提错——原 `design.md` 把 mDNS 与 PAC 排在**同一 V1 阶段**、无"V1.1"；故"PAC 已发而 mDNS 推迟致 PAC URL 漂移"在 V1 边界不成立。残留只是 MVP 层 mockup 显示 `.local` 而 MVP 无名称解析的一致性瑕疵。
**V1 处理:** 按 **2026-06-22 决策**，此开放项已拍板——**mDNS 进 V1** 以真正消除 IP 变化痛点：`MdnsPublisher` 作为 IP 方案之上的便利层进 V1，所有呈现面（推荐入口/通知/PAC）`hxmyproxy.local` 在前、随后**强制附具体接口 IP 作为 MANDATORY fallback**（绝不单独 `.local`），逐接口 IP 列表仍是主路径与最广兼容路径。该 finding 由此解决。（SUPERSEDES 旧 D1 推迟 mDNS / RAW-IP-only 方案。）

### [high] V1 UI/notification/PAC mockups hardcode hxmyproxy.local while mDNS out of scope
**核查判定:** 部分成立 (partially-correct，severity 调整: high → medium)
**问题:** Dashboard 推荐入口卡、持久通知、诊断页"mDNS 已发布"、示例 PAC 均以 `hxmyproxy.local` 为主入口；若 mDNS 缺席，用户复制到笔记本即得死地址，PAC 还会把不可解析 host 交给客户端并静默 DIRECT 绕过 VPN。
**核查更正:** load-bearing 前提（"mDNS out of scope"）在原设计中为假（PAC 与 mDNS 同属 V1）；示例 PAC 明标"应按当前可用入口动态生成"，17.2 已要求保留 IP fallback，13.2 已渲染 raw IP。真正可执行项仅是默认主入口用 IP、诊断行反映真实注册结果。
**V1 处理:** 按 **2026-06-22 决策（mDNS 进 V1）**——V1 入口/通知/诊断/PAC 生成器把 `hxmyproxy.local` 列在前、随后**强制附各接口具体 IP 作为 MANDATORY fallback**（绝不单独 `.local`），逐接口 RAW IP 仍是主路径与最广兼容路径；PAC 由当前可用入口动态构建 `FindProxyForURL`，每个 `.local` 条目必跟 IP 兜底；诊断 mDNS 行反映真实注册结果。直接消除该 broken-on-arrival 隐患。（SUPERSEDES 旧 D1 RAW-IP-only / 移除 `.local` 方案。）

### [high] VPN-down default 'block' strategy produces a silent, unexplained client-side outage
**核查判定:** 已确认 (confirmed)
**问题:** 10.2 默认 block（拒绝代理请求）意图正确，但只描述了手机端阻断、未规定远端**客户端**看到什么。VPN 一掉，客户端浏览器突然得到拒连/挂起的 CONNECT 而无可读原因；手机用户可能没看手机；11.3 的"VPN 不可用"是机内分类、不上线到客户端。结果：最常见的 VPN 瞬断在客户端表现为总断网，用户在错误层排障，侵蚀信任。
**V1 处理:** V1 让 block 双向可观测：(1) 手机端在 `TRANSPORT_VPN` 消失瞬间升起高可见通知态（"VPN 已断—流量已阻断"），含一键切"提醒/继续"（10.2 已有提醒模式可复用）；(2) 客户端侧 HTTP/CONNECT 返回合成 502/503 + 简短原因行，SOCKS5 返回 `0x03`（网络不可达）而非静默丢弃；(3) 首次运行说明 block 模式含义。对已建立隧道中途掉线则只能干净关闭。对照 grounded §8。

### [high] No client-side setup guidance ships in V1 despite cross-client config being highest friction
**核查判定:** 部分成立 (partially-correct，severity 调整: high → medium)
**问题:** 产品价值只有第二台设备成功路由才兑现，但客户端配置向导列为 V2，`[配置客户端]` 按钮的 `GenerateClientConfigUseCase` V1 输出未定义；跨 OS 现实不均（iOS 无覆盖全 app 的系统 PAC、Android 客户端无 per-app 代理等）却未盘点每端步骤。
**核查更正:** 一处事实错——QR **未**推迟：13.2 已在 V1 Dashboard 入口卡放 `[二维码]`，16 节把 QR+PAC+本地域名列为 V1 共享机制；故"pull QR forward"无意义。V1 其实已有可复制 host:port、QR、PAC URL，并非"毫无可操作"；真缺口窄到"没有告诉用户在各 OS 何处粘贴地址"。
**V1 处理:** 采纳 **D7**——V1 加一个静态、可复制的客户端设置单页（无需向导引擎）于 `[配置客户端]` 后：3–4 条 per-platform 配方（Windows 设置>代理；macOS 网络>代理；iOS Wi-Fi>配置代理；Android Wi-Fi 高级代理），插值当前所选接口 IP/端口，并附 PAC URL（标注 `.local` 在 Windows/部分 Android 客户端解析弱、须给 IP fallback，对照 17.2）。定义 `GenerateClientConfigUseCase` 的 V1 输出。

### [medium] Permission-request sequencing unspecified, risking a first-run that opens dead ports
**核查判定:** 已确认 (confirmed)
**问题:** 三道运行时门（`POST_NOTIFICATIONS`、`ACCESS_LOCAL_NETWORK`、电池优化白名单）都列了但未定**顺序**及与"开始共享"的门控关系。`ACCESS_LOCAL_NETWORK` 是承重项——若在 Start 后惰性请求，用户点 Start 见"共享中"、把 IP 告诉笔记本，而连接在 OS 层被静默拒（超时），机上无任何信号关联到缺权限。
**核查更正:** 两点细化（不改判定）：失败表现为 TCP **超时**而非即时拒绝（`android_getnetworkblockedreason` 返回 LNP），使静默更难诊断；强制只在 targetSdk 37+ 生效。
**V1 处理:** 按 **2026-06-22 决策（首发 targetSdk 37）**——定义 V1 首次运行序列并以此门控 Start：(1) 请求 `POST_NOTIFICATIONS`（FGS 通知所需）；(2) **day-one 请求 `ACCESS_LOCAL_NETWORK`**（targetSdk 37 强制，同时门控入站 accept 与 mDNS multicast），denial 视为对 Start 的**硬阻断**并给解释态；(3) 电池优化作软性可忽略提示。诊断页"本地网络权限"行在未授权时**视觉禁用 Start**。对照 grounded §1/§4/§5。（SUPERSEDES 旧 D3 仅 37+ 强制 / pin 36 推迟方案。）

---

## 仍需用户拍板的开放项

> **已拍板 (2026-06-22)：** mDNS 进 V1（含 mandatory IP fallback）、首发 targetSdk 37（`ACCESS_LOCAL_NETWORK` day-one 强制）、认证可选（默认无认证 + 非可信网络告警，保留 EgressGuard/加密凭据）、连接/资源上限可调 + 三档预设——均已由上方 **决策更新 (2026-06-22)** 横幅定案，不再为开放项。

以下为仍真正需用户定夺的开放项（非技术正确性问题）：

1. **FGS 类型 connectedDevice 还是 specialUse 的 Play 取舍。** D2 主选 connectedDevice（无时限）、回退 specialUse（更诚实但需 Play 审核 free-text 说明）。Play 实际接受度未知（见 NEEDS VERIFICATION），需用户决定提交策略。
2. **EgressGuard 对 private-LAN(RFC1918) 默认放行是否收紧。** 当前 EgressGuard 默认放行 RFC1918（提供可选 block 开关），默认阻断 loopback/link-local/this-host/multicast/自身监听；若用户更重视防内网跳板，可改为默认 block private-LAN。需用户权衡便利与 SSRF 暴露面。
3. **连接/资源参数的压测调优。** 三档预设（省电 64/32、均衡 256/128/N32/32KiB、高吞吐 512/256）与可调上限的具体数值需在实机负载测试下校准（缓冲内存预算、`limitedParallelism(N)`、head-of-line-blocking 取舍，见 NEEDS VERIFICATION），用户可据目标机型调整默认档。
4. **默认 VPN-down 策略 block 还是 warn。** #34 指出 block 默认会把 VPN 瞬断呈现为客户端总断网；需决定默认档及首次运行的解释强度。

---

## 需实机/进一步核实 (NEEDS VERIFICATION)

汇总 grounded reference 的待核实项与事实核查留下的不确定点（无 uncertain 判定，但以下机制需实机/官方进一步确认）：

1. **入站 LAN 回包在本机是否被 VPN 路由黑洞**（#7）。核查认为 Android 源地址感知路由通常已对称回包，但非典型 VPN 规则下仍存边缘风险——需在装有系统 VPN 的真机上以同段 LAN 客户端做**完整 TCP 握手**验证 D4 的 server-FD pinning 是否必要/充分。
2. **`ACCESS_NETWORK_STATE` 的确切要求**（grounded §6）。Reading-network-state 页称读连接状态无需特定权限，但明确与 `ConnectivityManager` API 参考存在出入——**防御性声明 `ACCESS_NETWORK_STATE`**，并对照 API 参考核实。
3. **NSD/mDNS 的运行时权限交互**（grounded §9，**V1——发 V1 前必须确认**）。NSD 页对权限只字未提；`ACCESS_LOCAL_NETWORK`（Android 17 targetSdk 37，§1 说其适用于 `NsdManager` 与 multicast）与 `NEARBY_WIFI_DEVICES`（Android 13/14）是否适用须在**发 V1 前**对照 local-network-permission / nearby-devices 文档核实（mDNS 已进 V1，此项成为发版阻塞项）。
4. **自建长寿命 Service `CoroutineScope` 的最佳实践**（grounded §7，#27）。Kotlin coroutines 页未覆盖 `Dispatchers.Default`、`lifecycleScope`，也未给自建 Service scope 指引；`CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `onDestroy()` 取消属未被所供事实背书的最佳实践——对照 coroutines best-practices/advanced 文档核实。
5. **阻塞 socket 并发上限 N**（grounded §6/§7，#23/#24）。coroutines 页认可"单线程多协程"但无数值上限，而阻塞 `java.net` 读会占住 `Dispatchers.IO` 线程；`limitedParallelism(N)` 的 N 取值、缓冲内存预算（≈16–32MB）与 head-of-line-blocking 取舍需在 advanced best-practices 与实机压测下确定。
6. **Play 对所选 FGS 类型的审核结果**（grounded §2/§7，#1/#2）。无文档化的 proxy/gateway FGS 类型；Play 是否接受 connectedDevice（说明偏牵强）还是要求 specialUse 属政策判断，须经实际 Play Console 提交/审核确认。
