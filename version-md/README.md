# version-md · hxmy proxy 版本设计与复盘

本目录沉淀 **hxmy proxy** 各版本的设计、评审与核证资料，供后续开发与复盘使用。产品原始构想见上层 [../design.md](../design.md)。工程命名空间 **`com.mzstd.hxmyproxy`**（仓库已有 `app/` Compose 骨架）。

> **hxmy proxy 是什么**：面向 Android 10–17 的**本地代理网关**。手机已有系统/Google VPN 时，本机起 HTTP / HTTPS CONNECT / SOCKS5 / PAC 端口，让同网（Wi‑Fi / 热点 / USB / 蓝牙 / 以太网）的其他设备借手机出口（含 VPN）上网。**V1 不实现自己的 VpnService**。

## 怎么读

1. 先看 **[v1-design.md](./v1-design.md)** — V1 总览、范围、关键决策、模块树、不变量、连接设置与预设、构建与调试、子系统索引。**这是入口。**
2. 任何涉及 Android 行为/协议的细节，以 **[v1-grounded-reference.md](./v1-grounded-reference.md)** 为准（16 份官方文档/RFC 实抓核证，带出处）。
3. 想了解"为什么这么定"，看 **[v1-review.md](./v1-review.md)** — 第一轮 5 维度对抗式评审，37 条逐条 fact-check。
4. 后续版本与待办，看 **[roadmap.md](./roadmap.md)**。

## 文件清单

| 文件 | 用途 |
|---|---|
| [v1-design.md](./v1-design.md) | **V1 主文档**：总览 / 范围 / 决策 / 架构 / 不变量 / 连接预设 / 构建调试 / 索引 |
| [v1-grounded-reference.md](./v1-grounded-reference.md) | 官方文档 + RFC 事实基准（权威，带 URL 出处） |
| [v1-proxy-core.md](./v1-proxy-core.md) | 代理核心：HTTP/HTTPS CONNECT、SOCKS5、RelayEngine、并发与连接参数 |
| [v1-pac-and-sharing.md](./v1-pac-and-sharing.md) | PAC server、入口发布、**mDNS（V1）+ IP fallback**、客户端消费 |
| [v1-network-and-admission.md](./v1-network-and-admission.md) | 网络监听、接口枚举/分类、VPN 检测、来源准入、mDNS 发布 |
| [v1-service-permissions-compat.md](./v1-service-permissions-compat.md) | 前台服务、通知、Android 10–17 权限与兼容（targetSdk 37） |
| [v1-architecture-ui-state.md](./v1-architecture-ui-state.md) | 模块树、状态模型、ViewModel/StateFlow、Compose UI（4 屏） |
| [v1-review.md](./v1-review.md) | 第一轮评审（37 条 + 核查判定 + V1 处理 + 开放决策） |
| [roadmap.md](./roadmap.md) | V1.1 / V1.2 / V2 规划与携带的待核实项 |

## V1 范围速记

**做**：HTTP/HTTPS CONNECT、SOCKS5（含可选认证，无 UDP）、PAC、**mDNS `hxmyproxy.local`（IP 为主、带 fallback）**、多入口识别+选择、`0.0.0.0`+来源准入、VPN 检测与出口复用（默认 fail-closed 阻断）、前台服务、Android 10–17 权限（targetSdk 37）、网络变化自动刷新、M3 UI（4 屏含设置/预设）、加密凭据存储。
**不做（→ roadmap）**：Watchdog 全量、客户端富管控/限速/拉黑、Profile、二维码、日志导出，及全部 V2。

## 七条关键决策（详见 v1-design.md §2）

D1 mDNS 进 V1（IP 为主、`.local` 带 IP fallback）｜ D2 FGS 用 `connectedDevice`（弃 `dataSync`）｜ D3 targetSdk=37、`ACCESS_LOCAL_NETWORK` day-one 硬门 ｜ D4 出站不绑网+fail-closed+出口自检 ｜ D5 **认证可选**+反 SSRF+加密凭据 ｜ D6 协程+阻塞 socket+`limitedParallelism`、**连接参数可调+三档预设** ｜ D7 裁剪模块+节流 UI 状态。

## 连接预设（详见 v1-design.md §4）

省电 64/32 · 均衡（默认）256/128 · 高吞吐 512/256（连接数 ≠ 带宽；参数全部可调）。

## 构建与调试

根 `gradle.properties` 已配置避免重复编译（build cache + 增量 + daemon 8G 堆，机器 32G 内存）。详见 v1-design.md §7。

## ⚠️ 仍可后续微调（非阻塞）

FGS 类型 `connectedDevice` vs `specialUse`（待 Play 审核）｜ EgressGuard 私网默认放行是否收紧 ｜ 连接参数实测调优。详见 [v1-review.md](./v1-review.md) 与 v1-design.md §8。

## 方法可信度

5 维度对抗式评审（37 条）每条经独立 fact-check；16 份官方文档/RFC 实抓形成事实基准；子系统设计逐条对照核证、剔除记忆性断言，未能核实者标 `NEEDS VERIFICATION`。**Android 行为以 grounded-reference 出处为准，而非模型记忆。**
