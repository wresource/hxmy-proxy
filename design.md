# hxmy proxy App 设计方案

> 目标：设计一款 Android 10–17 兼容的多网络入口 VPN 出口共享工具。它不是传统意义上的 VPN App，而是在手机本机运行 HTTP / HTTPS CONNECT / SOCKS5 / PAC 代理服务，让同一 Wi‑Fi、手机热点、USB 网络、蓝牙 PAN、以太网等入口下的其他设备，通过手机当前网络出口，尤其是系统已有 VPN，例如 Google 内置 VPN，访问网络。

---

## 1. 产品定位

### 1.1 核心定位

本 App 应定位为：

> 一个可感知 VPN、可感知本地网络接口变化、可自动发布稳定连接入口的 Android 本地代理网关。

它不应该被设计成“手机热点代理工具”，因为热点只是入口之一。更准确的共享入口包括：

- 同一个 Wi‑Fi 局域网；
- 手机开启热点，其他设备连接手机热点；
- USB 网络共享；
- 蓝牙 PAN；
- 以太网扩展坞；
- 厂商自定义局域网接口。

### 1.2 与 Every Proxy 的区别

Every Proxy 的核心能力是“在手机上开 HTTP / SOCKS 代理端口”。你的 App 要解决的是 Every Proxy 的体验痛点：

| 痛点 | 改进方向 |
|---|---|
| 网络变化后代理 IP 变化，需要重新扫描和配置 | 自动监听网络变化、自动更新入口、提供 `hxmyproxy.local`、PAC 和通知栏推荐入口 |
| 热点思维过重 | 显示所有可分享接口和网段：Wi‑Fi、热点、USB、蓝牙、以太网 |
| 断流原因不清楚 | 提供诊断页，检测 VPN、权限、端口、PAC、mDNS、接口状态 |
| 断流后需要手动重启 | Watchdog 自动恢复监听器和健康检查 |
| 安全控制弱 | 可选认证、网段选择、客户端列表、限速、黑名单 |
| Android 新版本兼容风险 | 明确适配 Android 14 前台服务类型与 Android 17 本地网络权限 |

---

## 2. 技术边界

### 2.1 为什么不要把第一版做成 VpnService

如果你的目标是分享 Google 内置 VPN 的流量，那么第一版不应该实现自己的 `VpnService`。

Android 官方 VPN 文档说明，同一时间只有一个 App 可以成为当前准备好的 VPN 服务。如果你的 App 自己启动 `VpnService`，可能会与 Google VPN、系统 VPN、Always-on VPN 发生冲突。

因此，第一版应采用：

```text
Google VPN / 系统 VPN 已经在手机上运行
        ↓
hxmy proxy App 在手机本地启动代理服务
        ↓
其他设备通过 Wi‑Fi / 热点 / USB / 蓝牙连接手机代理
        ↓
代理 App 的出站连接走手机当前默认网络出口，通常包含系统 VPN
```

### 2.2 Root 与非 Root 模式

| 模式 | 是否 Root | 客户端是否需要设置代理 | 是否适合第一版 | 说明 |
|---|---:|---:|---:|---|
| 本地代理模式 | 否 | 是 | 是 | 主力方案，兼容性最好 |
| Root 透明共享模式 | 是 | 否 | 否 | 后续高级功能，可用 iptables / tproxy / redsocks / tun2socks |
| 自建 VPN 模式 | 否 | 视情况 | 否 | 会与 Google VPN 冲突，不适合分享 Google VPN |

---

## 3. Android 10–17 兼容重点

### 3.1 Android 17：本地网络权限

Android 17 引入 `ACCESS_LOCAL_NETWORK` 运行时权限，用于保护用户免受未经授权的本地网络访问。官方行为变更说明指出，面向 Android 17 或更高版本的 App 需要适配该权限。

对本项目影响极大，因为你的 App 本质上需要：

- 接受局域网设备的 TCP 连接；
- 发布 mDNS / NSD 服务；
- 提供 PAC 服务；
- 在局域网内监听 HTTP / SOCKS5 端口。

因此 Android 17 上必须处理：

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

并在运行时请求授权。否则代理端口可能在局域网内不可访问。

### 3.2 Android 14：前台服务类型

Android 14 开始，面向 Android 14 的 App 必须声明合适的前台服务类型。代理服务属于长时间运行的网络服务，必须使用 Foreground Service，并在通知栏中明确显示运行状态。

可考虑的类型：

```xml
<service
    android:name=".service.ProxyForegroundService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice|dataSync" />
```

实际发布到 Google Play 时，需要结合 Play Console 对前台服务类型的解释要求准备合规说明。

### 3.3 Android 13：通知权限

Android 13 引入通知运行时权限。由于代理服务需要常驻通知，App 应请求：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

如果用户拒绝通知权限，前台服务仍需要符合系统要求，但用户体验会变差，应在诊断页中提示。

### 3.4 Android 10–12

主要关注：

- `ConnectivityManager.registerNetworkCallback()`；
- `NetworkInterface` 枚举；
- 前台服务保活；
- 电池优化白名单引导；
- 不同厂商热点接口命名差异。

### 3.5 兼容矩阵

| Android 版本 | 适配重点 |
|---|---|
| Android 10–12 | 基础代理、网络接口枚举、NetworkCallback、Foreground Service |
| Android 13 | 通知权限 `POST_NOTIFICATIONS` |
| Android 14 | 前台服务类型必填 |
| Android 15 | Edge-to-edge UI、Material 3 适配 |
| Android 16 | 可提前测试本地网络保护行为 |
| Android 17 | `ACCESS_LOCAL_NETWORK`、mDNS / LAN TCP / UDP 访问权限 |

---

## 4. 总体架构

### 4.1 架构定位

采用 Google 推荐的现代 Android 架构：

- UI 层：Jetpack Compose + Material 3；
- 状态管理：ViewModel + StateFlow；
- 业务层：UseCase；
- 数据层：Repository；
- 后台服务：Foreground Service；
- 网络核心：ProxyCore + NetworkMonitor + Watchdog。

### 4.2 模块划分

```text
app/
  ui/
    dashboard/
    interfaces/
    clients/
    diagnostics/
    settings/

  domain/
    usecase/
      StartSharingUseCase
      StopSharingUseCase
      SelectInterfacesUseCase
      DiagnoseNetworkUseCase
      GeneratePacUseCase
      GenerateClientConfigUseCase

  data/
    repository/
      NetworkRepository
      ProxyRepository
      SettingsRepository
      ClientRepository
    datastore/
    db/

  service/
    ProxyForegroundService

  core/
    proxy/
      HttpProxyServer
      Socks5ProxyServer
      PacServer
      RelayEngine
    network/
      ConnectivityObserver
      InterfaceScanner
      VpnStateDetector
      LocalNetworkPermissionManager
      MdnsPublisher
    security/
      Authenticator
      AccessController
    monitor/
      HealthChecker
      Watchdog
```

### 4.3 数据流

```text
ConnectivityManager / NetworkInterface / ProxyCore
          ↓
Repository 层转换为 StateFlow
          ↓
ViewModel 聚合为 UiState
          ↓
Compose UI 展示
          ↓
用户 Action
          ↓
ViewModel → UseCase → Repository / Service
```

---

## 5. 核心状态模型

### 5.1 ShareState

```kotlin
data class ShareState(
    val running: Boolean,
    val vpnDetected: Boolean,
    val localNetworkPermissionGranted: Boolean,
    val selectedInterfaces: List<ShareInterface>,
    val recommendedEntries: List<ProxyEntry>,
    val clients: List<ClientSession>,
    val diagnostics: DiagnosticsSummary
)
```

### 5.2 ShareInterface

```kotlin
data class ShareInterface(
    val id: String,
    val name: String,          // wlan0, ap0, rndis0, bt-pan, eth0 等
    val type: InterfaceType,   // WIFI, HOTSPOT, USB, BLUETOOTH, ETHERNET, UNKNOWN
    val address: InetAddress,
    val prefixLength: Int,
    val gatewayLike: Boolean,
    val isSelected: Boolean,
    val status: InterfaceStatus
)
```

### 5.3 ProxyEntry

```kotlin
data class ProxyEntry(
    val host: String,        // hxmyproxy.local 或 192.168.x.x
    val port: Int,
    val protocol: ProxyProtocol,
    val sourceInterface: String,
    val priority: Int,
    val reachable: Boolean
)
```

### 5.4 ClientSession

```kotlin
data class ClientSession(
    val clientIp: InetAddress,
    val interfaceId: String,
    val activeConnections: Int,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val currentUploadRate: Long,
    val currentDownloadRate: Long,
    val lastSeenAt: Instant,
    val blocked: Boolean
)
```

---

## 6. 多入口网段共享设计

### 6.1 用户需要一眼看到所有入口

首页应该显示当前所有可分享网段，而不是只显示热点 IP：

```text
当前可分享入口

[✓] Wi‑Fi 局域网
    192.168.1.34/24
    wlan0
    适合：同一 Wi‑Fi 下的电脑、平板、电视访问

[✓] 手机热点
    192.168.216.1/24
    ap0
    适合：其他设备连接本机热点

[ ] USB 共享
    192.168.42.129/24
    rndis0
    适合：电脑 USB 连接手机

[ ] 蓝牙网络
    192.168.44.1/24
    bt-pan
```

### 6.2 监听策略

底层监听 `0.0.0.0`，业务层通过来源 IP 判断是否允许访问。

```text
Proxy Listener Manager
  ├─ Global Listener: 0.0.0.0:PORT
  ├─ Interface Allowlist
  │   ├─ wlan0 / 192.168.1.34/24
  │   ├─ ap0 / 192.168.216.1/24
  │   └─ rndis0 / 192.168.42.129/24
  └─ Client Admission Control
      ├─ 根据客户端来源 IP 判断属于哪个网段
      ├─ 判断该网段是否被用户勾选
      └─ 未授权网段直接拒绝
```

这样网络变化时不用频繁销毁代理服务，只需要刷新入口列表和访问控制策略。

---

## 7. 代理协议设计

### 7.1 HTTP / HTTPS CONNECT

第一版必须支持：

- 普通 HTTP 代理；
- HTTPS `CONNECT host:443`；
- 可选 Basic Auth；
- 不做 HTTPS 解密；
- 不注入证书；
- 不做 MITM。

### 7.2 SOCKS5

第一版建议支持：

- TCP CONNECT；
- domain-name 方式连接；
- 可选用户名密码认证；
- 客户端远程 DNS 友好。

第一版暂不建议支持：

- UDP ASSOCIATE；
- QUIC 透明代理；
- 游戏 UDP 转发。

这些可以作为 V2 功能。

### 7.3 PAC 服务

App 内置 PAC 服务：

```text
http://hxmyproxy.local:8899/proxy.pac
http://192.168.1.34:8899/proxy.pac
http://192.168.216.1:8899/proxy.pac
```

示例 PAC：

```javascript
function FindProxyForURL(url, host) {
  return "SOCKS5 hxmyproxy.local:1080; PROXY hxmyproxy.local:8080; DIRECT";
}
```

PAC 内容应根据当前可用入口动态生成。

---

## 8. 解决 IP 变化痛点

### 8.1 问题本质

Every Proxy 的一个明显痛点是：网络一变，手机 IP 可能变化，客户端代理配置就失效。例如：

```text
家里 Wi‑Fi：192.168.1.34
公司 Wi‑Fi：192.168.31.88
手机热点：192.168.216.1
USB 共享：192.168.42.129
```

如果客户端必须手动输入 IP，那么每次网络变化都要重新扫描、重新配置，体验非常差。

### 8.2 解决方案组合

建议采用四层机制：

```text
第一优先级：稳定本地域名 hxmyproxy.local
第二优先级：PAC 动态配置
第三优先级：App 首页显示所有入口和网段
第四优先级：通知栏实时显示推荐入口
```

### 8.3 mDNS / NSD

App 使用 Android NSD / mDNS 发布服务：

```text
服务名：hxmyproxy.local
HTTP Proxy：_http._tcp.local
SOCKS5 Proxy：_socks._tcp.local
PAC：_hxmyproxy-pac._tcp.local
```

客户端优先使用：

```text
SOCKS5 hxmyproxy.local:1080
HTTP   hxmyproxy.local:8080
PAC    http://hxmyproxy.local:8899/proxy.pac
```

当手机 IP 改变时，mDNS 自动发布新地址，客户端不必重新扫描。

### 8.4 Profile 机制

为常用环境保存配置：

```text
Home Wi‑Fi Profile
  SSID: Home-5G
  Preferred Entry:
    hxmyproxy.local
    fallback: 192.168.1.34
  Protocol: SOCKS5
  Port: 1080

Phone Hotspot Profile
  Interface: ap0
  Preferred Entry:
    hxmyproxy.local
    fallback: 192.168.216.1
  Protocol: HTTP CONNECT
  Port: 8080
```

网络变化时，App 自动识别当前环境并切换 Profile。

---

## 9. 网络变化监听与自动刷新

### 9.1 不要依赖手动扫描

“扫描”应该是内部行为，用户不应该频繁点击扫描按钮。

应使用 `ConnectivityManager.registerNetworkCallback()` 监听：

- 默认网络变化；
- Wi‑Fi 切换；
- VPN 接入或断开；
- 热点接口出现或消失；
- USB 网络出现或消失；
- 蓝牙 PAN 出现或消失。

### 9.2 事件驱动流程

```text
App 启动
  ↓
注册 NetworkCallback
  ↓
任何网络变化
  ↓
自动重新枚举 NetworkInterface
  ↓
计算 ShareInterface 列表
  ↓
更新 ShareState
  ↓
刷新 UI / 通知栏 / PAC / mDNS / 访问控制
```

---

## 10. VPN 状态检测

### 10.1 检测方式

使用 `NetworkCapabilities.TRANSPORT_VPN` 判断当前网络能力是否包含 VPN。

同时可以补充：

- 出口 IP 检测；
- DNS 检测；
- 代理链路检测；
- 默认网络变化检测。

### 10.2 VPN 断开策略

提供三种策略：

| 策略 | 行为 |
|---|---|
| 自动继续 | VPN 断开后继续走普通网络 |
| 阻断模式 | 检测不到 VPN 时拒绝代理请求 |
| 提醒模式 | 继续工作，但通知栏和 UI 明确警告 |

默认建议使用“阻断模式”，避免用户误以为还在走 VPN。

---

## 11. 断流自恢复设计

### 11.1 Watchdog

设计 `ProxyWatchdog`，定期检查：

- HTTP 端口是否监听；
- SOCKS5 端口是否监听；
- PAC 端口是否监听；
- mDNS 是否发布；
- VPN 是否仍存在；
- 当前入口是否可达；
- 活跃连接是否异常堆积。

异常时执行：

```text
stop listener
  ↓
wait 500ms
  ↓
restart listener
  ↓
refresh PAC / mDNS / notification
```

### 11.2 连接状态机

每条连接维护状态：

```text
NEW → HANDSHAKE → CONNECTING → RELAYING → IDLE → CLOSING → CLOSED
```

建议默认参数：

```text
最大并发连接：512
单客户端最大连接：64
连接建立超时：10s
DNS 超时：5s
无数据空闲超时：300s
半关闭等待：15s
```

### 11.3 错误分类

错误不能只显示“连接失败”，需要分类：

- VPN 不可用；
- 本地网络权限未授权；
- 端口被占用；
- DNS 解析失败；
- 远程连接超时；
- 客户端主动断开；
- 手机网络切换；
- 被访问控制拒绝；
- 电池优化导致后台受限。

---

## 12. 安全与访问控制

### 12.1 认证可选

认证应可选，而不是强制。

建议提供三种安全等级：

```text
关闭认证：适合临时可信网络
简单认证：HTTP Basic Auth / SOCKS5 username-password
白名单模式：只允许已批准客户端访问
```

### 12.2 默认提示

如果用户关闭认证，应提示：

```text
当前未开启认证。
同一局域网内的设备可能连接你的代理。
建议仅在可信 Wi‑Fi 或个人热点下使用。
```

### 12.3 访问控制

支持：

- 按网段开关；
- 按客户端 IP 拉黑；
- 单客户端最大连接数；
- 单客户端限速；
- 每日流量上限；
- 一键断开所有客户端。

---

## 13. UI 设计：Material You / Material 3

### 13.1 技术栈

- Jetpack Compose；
- Material 3；
- Dynamic Color；
- Edge-to-edge；
- Navigation Compose；
- ViewModel + StateFlow。

### 13.2 首页 Dashboard

```text
hxmy proxy

状态卡片
┌────────────────────────┐
│ 共享中                 │
│ VPN：已检测到          │
│ 客户端：3 台           │
│ 下载：2.4 MB/s         │
└────────────────────────┘

推荐入口
┌────────────────────────┐
│ SOCKS5                 │
│ hxmyproxy.local:1080    │
│ [复制] [二维码]        │
└────────────────────────┘

可分享网段
[✓] Wi‑Fi      192.168.1.34/24
[✓] Hotspot   192.168.216.1/24
[ ] USB       192.168.42.129/24

底部按钮
[停止共享] [诊断] [配置客户端]
```

### 13.3 网段选择页

```text
Share interfaces

Wi‑Fi 局域网
192.168.1.34/24
wlan0
允许同一 Wi‑Fi 下设备连接
[开关]

手机热点
192.168.216.1/24
ap0
允许连接本机热点的设备连接
[开关]

USB 网络
192.168.42.129/24
rndis0
允许 USB 共享设备连接
[开关]
```

### 13.4 诊断页

```text
诊断

本地网络权限：已允许
VPN 状态：已检测到
HTTP 端口：8080 正常
SOCKS5 端口：1080 正常
PAC：可访问
mDNS：hxmyproxy.local 已发布
Wi‑Fi 入口：可访问
热点入口：可访问
后台服务：正常
电池优化：建议关闭
```

### 13.5 客户端页

```text
已连接设备

192.168.1.23
入口：Wi‑Fi / wlan0
连接数：18
下载：230 MB
上传：16 MB
当前速度：1.2 MB/s
[限速] [拉黑] [断开]

192.168.216.44
入口：Hotspot / ap0
连接数：5
下载：80 MB
上传：9 MB
当前速度：420 KB/s
[限速] [拉黑] [断开]
```

---

## 14. 通知栏设计

前台服务通知应显示：

```text
hxmy proxy 正在共享
VPN：已检测到
推荐：hxmyproxy.local:1080
客户端：3 台
↓ 2.4 MB/s  ↑ 320 KB/s
```

通知操作按钮：

- 停止共享；
- 复制代理地址；
- 打开诊断；
- 阻断全部连接。

---

## 15. 开发路线

### 15.1 MVP：现代化最小可用版

MVP 不应该是“热点 IP + 开端口”，而应该是：

- HTTP / HTTPS CONNECT；
- SOCKS5 TCP；
- 多入口网段识别；
- 用户选择共享网段；
- VPN 状态检测；
- Android 10–17 权限适配；
- Material 3 UI；
- Foreground Service；
- 网络变化自动刷新；
- 通知栏显示推荐入口。

### 15.2 V1 稳定版

增加：

- PAC 服务；
- mDNS / NSD 发布 `hxmyproxy.local`；
- Watchdog；
- 健康检查；
- 客户端列表；
- 可选认证；
- 访问控制；
- 日志导出；
- 断流原因分类。

### 15.3 V2 高级版

增加：

- Root 透明共享模式；
- UDP ASSOCIATE；
- QUIC / UDP 转发实验；
- Web 管理面板；
- 多 Profile；
- Windows / macOS / Android TV 配置向导；
- 自动生成系统代理配置说明。

---

## 16. 推荐技术选型

| 模块 | 推荐方案 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 状态管理 | ViewModel + StateFlow |
| 持久化 | DataStore + Room |
| 后台服务 | Foreground Service |
| 网络监听 | ConnectivityManager + NetworkCallback |
| 接口枚举 | NetworkInterface |
| 代理核心 | Kotlin Coroutines + Java NIO，或 Netty |
| mDNS / 服务发现 | Android NSD / JmDNS 备选 |
| 日志 | Timber + 本地 ring buffer |
| 配置分享 | QR Code + PAC + 本地域名 |

---

## 17. 关键风险

### 17.1 Android 17 权限风险

如果不适配 `ACCESS_LOCAL_NETWORK`，App 在 Android 17 上可能无法正常接受局域网客户端连接。

### 17.2 mDNS 兼容性风险

`hxmyproxy.local` 在不同客户端系统上的表现可能不同：

- macOS / iOS 通常较好；
- Windows 取决于版本和网络设置；
- Android 客户端取决于 App 和系统解析方式；
- 某些路由器会阻断 mDNS。

因此必须保留 IP fallback。

### 17.3 电池优化与厂商后台限制

部分厂商系统可能杀后台。应提供：

- 前台服务；
- 电池优化白名单引导；
- 诊断页提示；
- Watchdog；
- 日志导出。

### 17.4 Google Play 合规

需要解释：

- 为什么需要前台服务；
- 为什么需要本地网络权限；
- 为什么需要通知权限；
- 是否收集流量内容；
- 是否记录用户访问域名。

建议默认不记录完整 URL、不记录请求内容，只记录连接元数据。

---

## 18. 最终方案总结

这个 App 的核心价值不是“复制 Every Proxy”，而是解决 Every Proxy 没有解决好的体验和兼容性问题：

1. **多入口共享**：Wi‑Fi、热点、USB、蓝牙、以太网都可以作为入口；
2. **自动感知网络变化**：不再让用户频繁手动扫描；
3. **稳定连接入口**：`hxmyproxy.local` + PAC + 通知栏推荐入口；
4. **Android 10–17 兼容**：尤其适配 Android 14 前台服务类型和 Android 17 本地网络权限；
5. **断流自恢复**：Watchdog、健康检查、错误分类；
6. **现代 UI**：Material You / Material 3；
7. **可选安全控制**：认证可选，但保留网段选择、白名单、限速、断开客户端。

最终产品定义可以写成：

> hxmy proxy 是一个面向 Android 10–17 的多网络接口 VPN 出口共享工具。它通过 HTTP / HTTPS CONNECT / SOCKS5 / PAC 在手机本地提供代理入口，自动识别 Wi‑Fi、热点、USB、蓝牙、以太网等可分享网段，并通过本地域名、PAC、通知栏推荐入口和网络诊断系统，解决传统代理工具在网络变化后需要重新扫描 IP、频繁断流、诊断困难的问题。

---

## 19. 参考资料

1. Android Developers：Local network permission。https://developer.android.com/privacy-and-security/local-network-permission
2. Android Developers：Behavior changes: Apps targeting Android 17 or higher。https://developer.android.com/about/versions/17/behavior-changes-17
3. Android Developers：Foreground service types are required。https://developer.android.com/about/versions/14/changes/fgs-types-required
4. Android Developers：Recommendations for Android architecture。https://developer.android.com/topic/architecture/recommendations
5. Android Developers：ViewModel overview。https://developer.android.com/topic/libraries/architecture/viewmodel
6. Android Developers：VPN。https://developer.android.com/develop/connectivity/vpn
7. Android Developers：Jetpack Compose。https://developer.android.com/compose
