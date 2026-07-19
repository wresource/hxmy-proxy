# hxmy proxy · 应用综合总览

> [English](README.md) · **简体中文**

> 一份高层总览：hxmy proxy 是什么、能做什么、怎么实现、如何演进。细节见 version-md 各子文档。
> 工程命名空间 `com.mzstd.hxmyproxy`，开发/上架主体 **mzstd**。面向 Android 10–17（minSdk 29 / targetSdk 37）。

---

## 1. 是什么

**hxmy proxy 是一台 Android 本地代理网关**。手机在本机运行 HTTP / HTTPS CONNECT、SOCKS5、PAC
代理端口，让同一网络下的其他设备（笔记本、平板、另一台手机）借用**这台手机当前的网络出口**上网
——包括手机已连接的系统/Google VPN。

它**不是传统 VPN App**，V1 不自建 VpnService、不自己实现隧道；它是「把手机当成网络中转站」，
其他设备把代理指向手机即可。核心气质：**可感知 VPN、可感知本地网络接口变化、能自动发布稳定入口**。

**两种模式（有没有 VPN 都成立）**：

- **手机有 VPN → 共享出口**：其他设备借用手机当前的 VPN 通道，一处联网、多设备共享，不必每台单独配。
- **手机没 VPN → 规则过滤网关**：出口是普通网络，但下游设备白赚一层规则引擎——广告/追踪拦截（64 组）、
  白名单直连、域名分流、按连接监控。相当于一台免 root、免额外硬件的全屋广告拦截网关；且工作在
  **代理层**（HTTP CONNECT / SOCKS），拦的是真实连接目标，即使加密 DNS（DoH/DoT）也绕不过——
  这是它与 DNS 层拦截（如 Pi-hole）的本质区别。

app 自身即感知这两态：监控页显示 `VPN: detected / not detected`，无 VPN 时延迟检测会提示可能不反映共享出口。

## 2. 核心场景

- 手机连着 VPN，想让电脑/平板也走同一出口，但不想在每台设备单独配 VPN。
- 同一 Wi‑Fi / 手机热点 / USB 网络共享 / 以太网下，一台设备借另一台的网络通道。
- 需要对部分流量分流（直连/拦截/走代理）与广告拦截的轻量网关。

## 3. 功能模块（五屏）

| 屏 | 内容 |
|---|---|
| **主页 Dashboard** | 第一排「共享 \| 防护」双状态 tile（三态：已停止/未就绪/分享中，红/黄/绿）；入口配置卡（HTTP/PAC 地址 + 复制 + 扫码 QR）；**可分享接口卡（入口，每接口一开关）**；**出口网络卡（出口选择器：Auto / VPN / Wi-Fi / 蜂窝 / 以太网-USB，离线置灰、VPN 冲突警示）**；开始/停止按钮（竖屏悬浮、横屏竖排 rail）。 |
| **防护 Protection** | 独立 tab：本会话拦截总数大字 + 广告拦截开关 + 拦截明细入口；**拦截明细页按命中次数降序**（排查误封）；点任一域名弹**三态救济弹窗**（走代理/直连/拦截，最高优先级覆盖）。有无 VPN 都生效。 |
| **监控 Monitor** | 诊断三态网格（本地网络权限/VPN 出口/通知/电池/HTTP/SOCKS5/PAC 端口）；服务延迟测量；客户端列表；目标域名 Top N（协议色圆点 + 直连标识）；历史/错误日志入口。 |
| **规则 Rules** | 语义重组为 **🛡️拦截(Reject) / 🌐放行(Bypass)** 两大模块：快速拦截（域名/IP/CIDR）、白名单直连、App/服务规则集两行式（一键在放行/拦截间移）。内置 64 组 5437 域名；per-host 三态覆盖。 |
| **设置 Settings** | 语言、外观、性能预设 + 连接上限/缓冲/超时；端口、协议开关、**备用 DNS（DoH）开关**、认证、诊断。 |

## 4. 技术架构

- **代理核心**：HTTP/HTTPS CONNECT + SOCKS5（可选认证、反 SSRF）+ PAC server。relay 已从阻塞多线程
  改为 **NIO 非阻塞**（2 selector 取代 128 线程，见 [proxy-relay-nonblocking-plan.md](./version-md/proxy-relay-nonblocking-plan.md)）。
  IPv4 优先 / dnsCache / Happy Eyeballs / TCP_NODELAY。
- **规则引擎**：`RuleEngine.decide(host)` 短路优先级链——per-host 三态覆盖 > 用户白名单 > 快速拦截 >
  内置广告 > app 直连组 > 默认 PROXY。支持泛域名 / IP 字面量 / **CIDR**（`InetAddresses` 前缀匹配，不查 DNS）。
- **出口选择器**：`UnderlyingNetworkProvider` 多网络句柄提供者（WiFi/蜂窝/以太网/VPN 各 `registerNetworkCallback`），
  PROXY 出站按用户选择 **per-socket 绑定**（`Network.socketFactory` / `bindSocket` / `getAllByName`，官方推荐）；
  选物理出口时 `requestNetwork` 拉起保活。DIRECT 分流仍绑底层物理网络绕 VPN。
- **DNS 三层防线**：系统解析双路互援（默认网络 ↔ 底层 WiFi，换 netId 绕负缓存）→ **DoH 备援**
  （8.8.8.8 / 1.1.1.1，IP 直连 443）；上游失败按路径分类落 FileLog（可导出自证）。
- **网络与准入**：接口枚举/分类、VPN 检测、`0.0.0.0` 监听 + **fail-closed 来源准入**（不选网段=全拒 + 收缩即时清扫在途）。
- **服务与权限**：前台服务 `connectedDevice`；Android 10–17 权限（`ACCESS_LOCAL_NETWORK` day-one 硬门）；
  加密凭据存储（DataStore）。
- **UI**：Jetpack Compose + Material 3；Candy Azure 蓝配色（明暗分色）；共享组件 `SharedUi.kt`
  （CardGrid / AvatarCircle / LabeledSwitchRow / ExpandCollapseButton）+ `cardContainerColor` / `stdFilterChipColors`。
- **性能对标**：第二轮 vs Every Proxy 18.0.1，TTFB 快 ~14%、65% 胜、z=5.21（[proxy-benchmark-2](./version-md/proxy-benchmark-2-1.2.10-vs-every.md)）。

## 5. 隐私与合规

**完全本地运行**：无账号、无登录、不上云、无统计/追踪 SDK。仅在本地设备与手机网络之间转发流量；
设置/规则/诊断日志只存设备本地。上架文案定位为「局域网网络共享」中性工具，避开翻墙表述。

## 6. 版本演进（关键里程碑）

- **1.0–1.1.x**：V1 基线（四屏、代理核心、mDNS→后移除）+ 打磨加固（日志降噪、端口重试、Splash、Baseline Profile）。
- **1.2.x**：relay NIO 非阻塞；蜂窝/热点场景改进；换 Wi‑Fi 中断根因修复；PAC 展示链路 + 移除 mDNS。
- **1.3.x**：UI 蓝粉→Candy Azure 配色重构。
- **1.5.x**：内置规则 64 组；预测性返回根因修复 + 延迟逐格刷新 + 青春配色；UI 一致性打磨 + 抽 4 共享组件。
- **1.6–1.8.x**：规则系统 reject/bypass 双模块重构 + CIDR + per-host 三态覆盖；独立防护 tab（拦截明细 + 三态救济）；
  首页双状态 tile + 三态；全面中英双语打磨（扫码页/横屏按钮/图标）；
  **准入 fail-closed**（不选网段=全拒 + 在途清扫）；**DNS 三层防线 + DoH 备援**。
- **1.9.0**：**出口网络选择器**（Auto/VPN/Wi-Fi/蜂窝/以太网-USB，代理出站可选走哪张网，per-socket 绑定）——
  当前版本（versionCode 81）。

## 7. 文档索引

技术子文档均在 [`version-md/`](./version-md/)：设计入口 `v1-design.md` · 代理核心 `v1-proxy-core.md` ·
PAC/共享 `v1-pac-and-sharing.md` · 网络准入 `v1-network-and-admission.md` · 服务权限 `v1-service-permissions-compat.md` ·
架构/UI `v1-architecture-ui-state.md` · 路线 `roadmap.md`。
