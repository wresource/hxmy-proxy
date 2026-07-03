# hxmy proxy · 应用综合总览

> 一份高层总览：hxmy proxy 是什么、能做什么、怎么实现、如何演进。细节见 version-md 各子文档。
> 工程命名空间 `com.mzstd.hxmyproxy`，开发/上架主体 **mzstd**。面向 Android 10–17（minSdk 29 / targetSdk 37）。

---

## 1. 是什么

**hxmy proxy 是一台 Android 本地代理网关**。手机在本机运行 HTTP / HTTPS CONNECT、SOCKS5、PAC
代理端口，让同一网络下的其他设备（笔记本、平板、另一台手机）借用**这台手机当前的网络出口**上网
——包括手机已连接的系统/Google VPN。

它**不是传统 VPN App**，V1 不自建 VpnService、不自己实现隧道；它是「把手机当成网络中转站」，
其他设备把代理指向手机即可。核心气质：**可感知 VPN、可感知本地网络接口变化、能自动发布稳定入口**。

## 2. 核心场景

- 手机连着 VPN，想让电脑/平板也走同一出口，但不想在每台设备单独配 VPN。
- 同一 Wi‑Fi / 手机热点 / USB 网络共享 / 以太网下，一台设备借另一台的网络通道。
- 需要对部分流量分流（直连/拦截/走代理）与广告拦截的轻量网关。

## 3. 功能模块（四屏）

| 屏 | 内容 |
|---|---|
| **主页 Dashboard** | 大字运行状态（共享中/已停止 + 状态点）；入口配置卡（HTTP/PAC 地址 + 复制 + 扫码配置 QR）；可分享接口卡（每接口一个开关）；开始/停止共享按钮（竖屏悬浮、横屏竖排 rail）。 |
| **监控 Monitor** | 诊断三态网格（本地网络权限/VPN 出口/通知/电池/HTTP/SOCKS5/PAC 端口）；服务延迟（12 个海外服务，逐格进度式测量，三态：测量中/超时/值）；客户端列表；目标域名 Top N（协议色圆点 + 直连标识）；历史/日志入口。 |
| **规则 Rules** | ① IP/域名白名单（直连，绕过共享 VPN，监控标「直连」）；② App 与服务规则集（一键分流 + 管理页增删集/域名）；③ 广告拦截（每表开关 + 用户白名单覆盖）。内置 64 组 5437 域名。 |
| **设置 Settings** | 语言（System/English/中文）、外观（System/浅色/深色）、性能预设（low/medium/high/自定义）+ 连接上限/缓冲/超时可调；端口、认证、诊断入口。 |

## 4. 技术架构

- **代理核心**：HTTP/HTTPS CONNECT + SOCKS5（可选认证、反 SSRF）+ PAC server。relay 已从阻塞多线程
  改为 **NIO 非阻塞**（2 selector 取代 128 线程，见 [proxy-relay-nonblocking-plan.md](./proxy-relay-nonblocking-plan.md)）。
  出站不绑网 + fail-closed + 出口自检；IPv4 优先 / dnsCache / Happy Eyeballs / TCP_NODELAY。
- **网络与准入**：监听底层网络 IP 变化、接口枚举/分类、VPN 检测与出口复用、`0.0.0.0` + 来源准入。
- **服务与权限**：前台服务 `connectedDevice`；Android 10–17 权限（`ACCESS_LOCAL_NETWORK` day-one 硬门）；
  加密凭据存储（DataStore）。
- **UI**：Jetpack Compose + Material 3；Candy Azure 蓝配色（明暗分色）；共享组件 `SharedUi.kt`
  （CardGrid / AvatarCircle / LabeledSwitchRow / ExpandCollapseButton）+ `cardContainerColor` / `stdFilterChipColors`。
- **性能对标**：第二轮 vs Every Proxy 18.0.1，TTFB 快 ~14%、65% 胜、z=5.21（[proxy-benchmark-2](./proxy-benchmark-2-1.2.10-vs-every.md)）。

## 5. 隐私与合规

**完全本地运行**：无账号、无登录、不上云、无统计/追踪 SDK。仅在本地设备与手机网络之间转发流量；
设置/规则/诊断日志只存设备本地。隐私政策见 `google-play/privacy-policy.html`（主体 mzstd）。
上架文案定位为「局域网网络共享」中性工具，避开翻墙表述。

## 6. 版本演进（关键里程碑）

- **1.0–1.1.x**：V1 基线（四屏、代理核心、mDNS→后移除）+ 打磨加固（日志降噪、端口重试、Splash、Baseline Profile）。
- **1.2.x**：relay NIO 非阻塞；蜂窝/热点场景改进；换 Wi‑Fi 中断根因修复；PAC 展示链路 + 移除 mDNS。
- **1.3.x**：UI 蓝粉→Candy Azure 配色重构。
- **1.5.x**：内置规则 64 组；预测性返回根因修复 + 延迟逐格刷新 + 青春配色；
  **UI 一致性打磨 + 抽 4 共享组件**（见 [v1.5-ui-consistency.md](./v1.5-ui-consistency.md)，当前 1.5.21）。

## 7. 文档索引

设计入口 [v1-design.md](./v1-design.md) · 事实基准 [v1-grounded-reference.md](./v1-grounded-reference.md) ·
代理核心 [v1-proxy-core.md](./v1-proxy-core.md) · PAC/共享 [v1-pac-and-sharing.md](./v1-pac-and-sharing.md) ·
网络准入 [v1-network-and-admission.md](./v1-network-and-admission.md) · 服务权限 [v1-service-permissions-compat.md](./v1-service-permissions-compat.md) ·
架构/UI [v1-architecture-ui-state.md](./v1-architecture-ui-state.md) · 评审 [v1-review.md](./v1-review.md) ·
UI 一致性 [v1.5-ui-consistency.md](./v1.5-ui-consistency.md) · 路线 [roadmap.md](./roadmap.md) ·
上架素材 `../google-play/`（store-listing / release-notes / privacy-policy / graphics）。
