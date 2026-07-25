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
- **手机没 VPN → 规则过滤网关**：出口是普通网络，但下游设备白赚一层规则引擎——广告/追踪拦截、65 组 App/服务直连、
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
| **主页 Dashboard** | 第一排「共享 \| 防护」双状态 tile（三态：已停止/未就绪/分享中，红/黄/绿）；实时速率 + 统计竖条（连接数/信号/**链路时延 p50·p95**/累计）；入口配置卡（HTTP/PAC 地址 + 复制 + 扫码 QR）；**接入网络卡（入口，每接口一开关）**；**出口网络卡**（PROXY 出口：Auto / VPN / Wi-Fi / 蜂窝 / 以太网-USB）+ **直连出口卡**（DIRECT 出口独立配置：Auto=以太网/USB→Wi-Fi→蜂窝 或手动指定，蜂窝首次弹移动流量确认）；开始/停止按钮（竖屏悬浮、横屏竖排 rail）。 |
| **防护 Protection** | 独立 tab：本会话拦截总数大字 + 广告拦截开关 + 拦截明细入口；**拦截明细页按命中次数降序**（排查误封）；点任一域名弹**三态救济弹窗**（走代理/直连/拦截，最高优先级覆盖）。有无 VPN 都生效。 |
| **监控 Monitor** | 诊断三态网格（本地网络权限/VPN 出口/通知/电池/HTTP/SOCKS5/PAC 端口）；服务延迟测量；客户端列表；**目标域名**（协议色圆点 + 规则引擎**实时判定**的直连/拦截标；「查看全部」进独立详情页，点域名即写入 block/allow 规则、免手输）；历史/错误日志入口。 |
| **规则 Rules** | 语义重组为 **🛡️拦截(Reject) / 🌐放行(Bypass)** 两大模块：快速拦截（域名/IP/CIDR，带整体开关）、白名单直连、**每条独立启停开关**（停用不删、Active/Off 状态标、停用项排后）、App/服务规则集两行式（一键在放行/拦截间移）+ **分类/全部一键批量开关**。内置 **65 组**（含 Apple / App Store 直连）；per-host 三态覆盖。 |
| **设置 Settings** | 语言、外观、性能预设 + 连接上限/缓冲/超时；端口、协议开关、**备用 DNS（DoH）开关**、认证、诊断日志总开关、**设置备份（导出/导入 JSON）**。 |

## 4. 技术架构

- **代理核心**：HTTP/HTTPS CONNECT + SOCKS5（可选认证、反 SSRF）+ PAC server。relay 已从阻塞多线程
  改为 **NIO 非阻塞**（2 selector 取代 128 线程，见 [proxy-relay-nonblocking-plan.md](./version-md/proxy-relay-nonblocking-plan.md)）。
  IPv4 优先 / dnsCache / Happy Eyeballs / TCP_NODELAY。
- **规则引擎**：`RuleEngine.decide(host)` 短路优先级链——per-host 三态覆盖 > 用户白名单 > 快速拦截 >
  内置广告 > app 直连组 > 默认 PROXY。支持泛域名 / IP 字面量 / **CIDR**（`InetAddresses` 前缀匹配，不查 DNS）。
- **出口选择器（PROXY + DIRECT 双通道）**：`UnderlyingNetworkProvider` 多网络句柄提供者（WiFi/蜂窝/以太网/VPN 各 `registerNetworkCallback`），
  按用户选择 **per-socket 绑定**（`Network.socketFactory` / `bindSocket`，官方推荐）；选物理出口时 `requestNetwork` 拉起保活。
  **DIRECT 分流独立配置**（AUTO=以太网/USB→WiFi→蜂窝），拿不到物理网 **fail-closed 断开、绝不回落 VPN**（防地理敏感直连泄漏）；
  蜂窝可选性依**免权限** SIM 能力（`FEATURE_TELEPHONY`+`getSimState`），WiFi 在线也能选蜂窝出口。
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

这一条是**用机制保证的、不只是承诺**：日志落 `noBackupFilesDir` 且 `allowBackup=false`，
连 Android Auto Backup 都不会把它传到云端；release 版另用 R8 剥离 logcat 的 v/d/i，
正式版不向系统日志输出访问域名与客户端 IP。云端恢复因此被主动放弃，换机迁移改由
**设置 → 设置备份**（导出/导入 JSON，不含代理凭据）承担。

## 6. 版本演进（关键里程碑）

- **1.0–1.1.x**：V1 基线（四屏、代理核心、mDNS→后移除）+ 打磨加固（日志降噪、端口重试、Splash、Baseline Profile）。
- **1.2.x**：relay NIO 非阻塞；蜂窝/热点场景改进；换 Wi‑Fi 中断根因修复；PAC 展示链路 + 移除 mDNS。
- **1.3.x**：UI 蓝粉→Candy Azure 配色重构。
- **1.5.x**：内置规则 64 组；预测性返回根因修复 + 延迟逐格刷新 + 青春配色；UI 一致性打磨 + 抽 4 共享组件。
- **1.6–1.8.x**：规则系统 reject/bypass 双模块重构 + CIDR + per-host 三态覆盖；独立防护 tab（拦截明细 + 三态救济）；
  首页双状态 tile + 三态；全面中英双语打磨（扫码页/横屏按钮/图标）；
  **准入 fail-closed**（不选网段=全拒 + 在途清扫）；**DNS 三层防线 + DoH 备援**。
- **1.9.0**：**出口网络选择器**（Auto/VPN/Wi-Fi/蜂窝/以太网-USB，代理出站可选走哪张网，per-socket 绑定）。
- **1.10.x**：Bento UI 定稿 + 六页重构；换网每 3 秒 refresh 风暴消除；DNS 连接超时 8→2.5s + DoH 并入 Happy Eyeballs 竞速。
- **1.11–1.13.0**：DIRECT **fail-closed** 防泄漏 VPN + arxiv 长连接卡顿修复；**Apple/App Store 直连组**（64→65 组）+
  监控页 override 徽章即时显示；**多出口**（蜂窝免权限检测）+ **直连出口独立配置**（AUTO=以太网→WiFi→蜂窝）+ 出口卡全宽；
  规则集**分类/全部一键批量开关** + 快速拦截总开关。
- **1.14.x**：规则条目**带启用状态**（`Set<String>`→`List<RuleEntry>`，DataStore 转 JSON + 旧数据平滑迁移；停用=不进匹配表、**不切反面**；小开关操作 + Active/Off 状态标；启用按添加序在前、停用按最近停用在前）；
  **目标域名独立详情页** + 点域名统一写入 block/allow 规则（互斥自动移）；域名标改用 `RuleEngine.decide` **实时完整判定**（规则一变即重判，修「移除规则仍显示直连」的陈旧缓存）。
- **1.14.4–1.14.6**：**开机 / app 更新后自动恢复共享**（`RestartReceiver` 监听 `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`，
  仅当上次确实在共享中才拉起，主动停止即清零）。修「装新版后须手动重开」——那段空窗里客户端连不上监听端口，
  浏览器会把该代理标记 bad proxy 并退避 5 分钟（该状态在浏览器进程内、app 无法清除）。
  同期打开拒连黑箱（准入拒绝此前完全静默、上限拒绝只进 logcat），并补上热重启路径缺失的 `registry.reset()`。
- **1.15.0–1.16.0（隐私收口）**：日志目录改 `noBackupFilesDir` 且 **关闭系统云备份**（`allowBackup=false`）——
  此前日志落在 Auto Backup 默认包含范围内，含访问域名的 `app.log` 会被上传，与「完全本地」的承诺字面冲突；
  release 版用 R8 剥离 logcat 的 v/d/i（正式版不再向系统日志输出域名与客户端 IP）；日志容量 512KB→10MB
  （原容量保底仅约 4600 行，一次失败风暴几小时即冲掉崩溃栈）。因云端恢复被主动关掉，新增
  **设置导出/导入 JSON**（遍历 DataStore 键值 + 类型标记，新增设置项自动进备份）承担换机迁移。
- **1.17.0（可观测性重构）**：结构化事件门面 `Ev`（`evt=… k=v`，tag 位改为 13 个子系统分类，可按子系统 grep）；
  **`key.log` 独立环**——生命周期/准入/拒连/崩溃镜像写入，永不被高频日志冲出滚动窗口；常驻 BufferedWriter
  替代每条 open/write/close；统一节流并补报 `suppressed=N`；`RuleEngine.decideDetailed` 返回**命中来源**
  （回答「某域名为什么被拦/为什么走直连」）；诊断日志总开关。
- **1.18.0**：**段① 客户端→本机链路时延**（此前只测本机→互联网，而手机当网关时最先劣化的恰是段①，
  于是「手机自己正常、连它的设备却卡死」在 UI 上毫无线索）。ICMP 优先、TCP-RST 兜底，10 秒一次且仅在有
  在线客户端时探；p50 主数字 + p95 小字，按阈值着色。同时修 `LatencyProbe` 两个真 bug：DNS 被算进延迟、
  探针未绑定用户选定的出口网络——当前版本（versionCode 103）。

## 7. 文档索引

技术子文档均在 [`version-md/`](./version-md/)：设计入口 `v1-design.md` · 代理核心 `v1-proxy-core.md` ·
PAC/共享 `v1-pac-and-sharing.md` · 网络准入 `v1-network-and-admission.md` · 服务权限 `v1-service-permissions-compat.md` ·
架构/UI `v1-architecture-ui-state.md` · 路线 `roadmap.md`。
