# hxmy proxy · 版本路线图 (Roadmap)

> 本文记录 **V1 之后** 的规划，用于后续复盘。V1 详细设计见 [v1-design.md](./v1-design.md)，第一轮评审见 [v1-review.md](./v1-review.md)，官方文档事实基准见 [v1-grounded-reference.md](./v1-grounded-reference.md)。
>
> **2026-06-22 更新**：mDNS（`hxmyproxy.local`）已从 V1.1 **提前进 V1**（作便利层，IP 为主、永带 IP fallback）。

---

## 版本节奏总览

| 版本 | 主题 | 一句话目标 |
|---|---|---|
| **V1** | 可用的本地代理网关 | HTTP/HTTPS/SOCKS5/PAC + **mDNS 稳定入口** + 多入口共享，复用系统 VPN 出口，Android 10–17（targetSdk 37）合规 |
| **V1.1** | 配置易用性 | 客户端配置向导、二维码、Profile，进一步降低跨端摩擦 |
| **V1.2** | 运营与管控 | 客户端会话列表、限速/拉黑/配额、日志导出 |
| **V2** | 高级/透明 | Root 透明转发、UDP、Web 面板、跨平台向导 |

---

## V1.1 — 配置易用性

> mDNS 已在 V1。这一版进一步打磨"换网/换设备零摩擦"的配置体验。

1. **客户端配置向导/说明** — Windows / macOS / iOS / Android 各自如何设代理或用 PAC（评审指出跨端配置摩擦是最大易用性短板，V1 暂只给 PAC + 复制）。
2. **二维码分享** — 推荐入口（host:port + 协议 + 可选凭据）编码成二维码，扫码即配。
3. **Per-network Profile** — 按 SSID/接口记住首选入口与协议端口，换网自动切换。
4. **In-process Watchdog + 电池白名单引导** — 仅做 **FGS 存活期内**的监听器/协程自愈（停-等-重启 in-process，合法）；引导加入电池优化白名单以减少被杀。
   - ⚠️ 进程被杀后的**全自动复活**在 Android 12+ 受 `ForegroundServiceStartNotAllowedException` 限制，仅在电池白名单或用户交互/精确闹钟/高优先级 FCM 豁免下可能（见 v1-review.md Android Platform 维度）。不承诺"杀后台自动恢复"，以通知点击一键拉起兜底。
5. **mDNS 增强** — 在 V1 基础上：服务名冲突回读展示、跨网段解析体验优化、可选自定义服务名。

---

## V1.2 — 客户端管控与可观测

> V1 的 `ClientSession` 刻意保持最小（仅必要字段、节流快照）。这一版补齐运营能力。

1. **客户端会话列表** — 实时显示每个客户端：入口/接口、连接数、上下行流量与速率、最近活跃时间。
2. **限速 / 拉黑 / 每日配额 / 一键断开全部**。
3. **访问控制 UI** — 按网段开关、按客户端 IP 黑白名单。
4. **日志导出** — 连接元数据 ring buffer 导出（**默认不记完整 URL、不记请求内容**，仅元数据）。
5. **断流原因分类完整版** — 9 类错误全部接入 UI 与日志。

---

## V2 — 高级与透明模式

1. **Root 透明共享模式** — iptables / tproxy / redsocks / tun2socks，客户端无需手动设代理。
2. **SOCKS5 UDP ASSOCIATE** — 支持 UDP 转发。
3. **QUIC / UDP 转发实验**。
4. **Web 管理面板**。
5. **跨平台配置向导** — Windows / macOS / Android TV 一键说明与自动生成。
6. **代理核心 NIO 化（可选）** — 若 V1 阻塞 socket 模型在高并发高吞吐下压测不达标，作为吞吐后路。
7. **自建 VPN 模式（谨慎）** — 与"分享系统 VPN"目标互斥，默认不做。

---

## 跨版本携带的待核实项 (carried-forward NEEDS VERIFICATION)

来自 [v1-grounded-reference.md](./v1-grounded-reference.md) 与第一轮评审，**官方文档未能直接定论**，需实机或进一步查证：

| 项 | 影响版本 | 说明 |
|---|---|---|
| 入站 LAN 回包是否被 VPN 路由黑洞 | V1 | fact-check 认为多数情况回包对称、黑洞是边缘情形；仍建议对 server FD 做 `Network.bindSocket` 防御 + 真实握手探测，需实机验证 |
| `ACCESS_NETWORK_STATE` 是否必需 | V1 | "读网络状态"页称无需权限，但与 `ConnectivityManager` API 参考冲突；**防御性声明** |
| NSD/mDNS 运行时权限细节 | V1 | targetSdk 37 下 mDNS 多播由 `ACCESS_LOCAL_NETWORK` 覆盖（grounded-ref §1/§9）；NSD 具体行为需实机确认 |
| 长生命周期 Service 自定义 `CoroutineScope` 写法 | V1 | 官方协程页未覆盖；`CoroutineScope(SupervisorJob()+Dispatchers.IO)` 于 `onDestroy()` 取消，需对照 coroutines-best-practices 确认 |
| 阻塞 socket 在 500 Mbps 多连接下的吞吐 / `N` 取值 | V1 | 需压测确定 `limitedParallelism(N)` 与缓冲；不足先调缓冲/N，NIO 留 V2 |
| Google Play 对 `foregroundServiceType` 审核结论 | V1 | `connectedDevice` vs `specialUse`，需实际提审验证 |
| 出口自检 IP-echo 端点与隐私设计 | V1 | 选定探测端点/超时，并评估隐私 |

---

## 与 V1 的边界（防止 scope creep）

V1 **明确不包含**：Watchdog 全量自愈、客户端富列表/限速/拉黑/配额、Profile、二维码、日志导出，以及全部 V2。任何提前进 V1 的决定，先在 [v1-review.md](./v1-review.md) 的开放项中确认（mDNS 已于 2026-06-22 这样提前）。
