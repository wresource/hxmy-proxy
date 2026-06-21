# hxmy proxy · V1 详细设计（总览）

> **一句话定义**：hxmy proxy 是一个面向 **Android 10–17** 的**本地代理网关**。手机上已有系统/Google VPN 在运行；hxmy proxy 在本机起 **HTTP / HTTPS CONNECT / SOCKS5 / PAC** 端口，让同一 Wi‑Fi、热点、USB、蓝牙 PAN、以太网下的其他设备，**借手机当前默认网络出口（通常携带系统 VPN）**上网。V1 **不**实现自己的 `VpnService`（会与系统 VPN 冲突）。
>
> 本文是 V1 的入口与决策中枢。子系统细节见各分册；所有 Android 行为均已对照官方文档核证（[v1-grounded-reference.md](./v1-grounded-reference.md)）；第一轮评审见 [v1-review.md](./v1-review.md)；后续版本见 [roadmap.md](./roadmap.md)。产品原始构想见 [../design.md](../design.md)。
>
> **工程**：包名/命名空间 **`com.mzstd.hxmyproxy`**（仓库已有 `app/` Compose 骨架）；Kotlin + Kotlin-DSL Gradle；**minSdk 29，targetSdk 37**。

---

## 0. 文档结构

| 文档 | 内容 |
|---|---|
| **v1-design.md**（本文） | 总览、范围、关键决策、模块树、不变量、连接设置与预设、构建与调试、子系统索引 |
| [v1-grounded-reference.md](./v1-grounded-reference.md) | **官方文档/RFC 事实基准**（16 份来源，带出处），所有 Android 行为以此为准 |
| [v1-proxy-core.md](./v1-proxy-core.md) | HTTP/HTTPS CONNECT、SOCKS5、RelayEngine、并发模型、连接参数 |
| [v1-pac-and-sharing.md](./v1-pac-and-sharing.md) | PAC server、入口/推荐项发布、**mDNS（V1）+ IP fallback**、客户端消费 |
| [v1-network-and-admission.md](./v1-network-and-admission.md) | 网络监听、接口枚举/分类、VPN 检测、`0.0.0.0` + 来源准入、mDNS 发布 |
| [v1-service-permissions-compat.md](./v1-service-permissions-compat.md) | 前台服务、通知、Android 10–17 权限与兼容（targetSdk 37） |
| [v1-architecture-ui-state.md](./v1-architecture-ui-state.md) | 模块树、状态模型、ViewModel/StateFlow、Compose UI（4 屏） |
| [v1-review.md](./v1-review.md) | 第一轮 5 维度对抗式评审（37 条，逐条 fact-check）+ 开放决策 |
| [roadmap.md](./roadmap.md) | V1.1 / V1.2 / V2 规划与携带的待核实项 |

---

## 1. V1 范围（Scope）

### 1.1 做（IN SCOPE）

- **HTTP 正向代理 + HTTPS `CONNECT` 隧道**（不做 MITM / 不解密 TLS / 不注入证书）
- **SOCKS5**：TCP `CONNECT`、domain-name 寻址、**可选**用户名/密码认证（RFC 1929）——**不做 UDP ASSOCIATE**
- **PAC server**：按当前可用入口动态生成
- **mDNS / NSD 发布 `hxmyproxy.local`**（V1 内，作为便利层；IP 方案为主、永带 IP fallback）
- **多入口识别 + 用户选择**：Wi‑Fi / 热点 / USB / 蓝牙 PAN / 以太网
- **准入控制**：监听 `0.0.0.0`，按客户端**接收接口**归属判断是否放行（便利过滤，非安全边界）
- **VPN 检测与出口复用**：`TRANSPORT_VPN` + 出口自检；VPN 断开策略（默认**阻断、fail-closed**）
- **前台服务 + 常驻通知**（`foregroundServiceType=connectedDevice`）
- **权限适配**：`ACCESS_LOCAL_NETWORK`(targetSdk 37，**day-one 硬门**)、`POST_NOTIFICATIONS`(13)、电池优化引导
- **网络变化自动刷新**（`registerDefaultNetworkCallback`，无手动扫描按钮）
- **Material 3 / Compose UI（4 屏）**：Dashboard、入口选择、诊断、**设置（含性能预设）**
- **持久化**：DataStore（设置）+ EncryptedSharedPreferences/Keystore（启用认证时的凭据）

### 1.2 不做（DEFERRED — 见 [roadmap.md](./roadmap.md)）

Watchdog 全量自愈、客户端富列表/限速/拉黑/配额、Profile、二维码、日志导出；以及全部 V2（Root 透明、UDP、QUIC、Web 面板、跨平台向导）。
> 注：**mDNS 已从延后项提前进 V1**（用户 2026-06-22 决定）。

---

## 2. 关键决策（V1 Decisions，2026-06-22 更新）

| # | 决策 | 依据 |
|---|---|---|
| **D1** | **mDNS 进 V1，但只作便利层**：IP 按场景列接口入口为**主干**（适用性最广）；mDNS 用 `NsdManager` 发布 `hxmyproxy.local`，PAC/推荐项里 **`.local` 在前、具体 IP 紧随其后作 fallback**，**绝不**单发解析不了的 `.local` | 用户决定；mDNS 在 Windows/部分路由器/部分 Android 不稳定（grounded-ref §9、design.md §17.2） |
| **D2** | 前台服务类型用 **`connectedDevice`**（声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`，并在 `startForeground()` 前满足）；备选 **`specialUse`**；**弃用 `dataSync`** | grounded-ref §2：`dataSync` 在 Android 15+ 有 6h/24h 上限会杀常驻网关 |
| **D3** | **首发 targetSdk=37**；`ACCESS_LOCAL_NETWORK` 为 **day-one 强制运行时权限**（同时管**入站 accept** 与 **mDNS 多播**）；首启**硬门**流程：先讲清再请求，被拒则**拒绝进入运行态**（被拒表现为客户端 TCP 超时）；API 调用按 `Build.VERSION` 守卫 | grounded-ref §1；用户选 targetSdk 37 |
| **D4** | **出站 socket 永不绑定特定 Network**（跟随默认网=VPN）；**禁用 `bindProcessToNetwork`**；入站对称 pin 只在 server FD 上做；阻断策略**原子 fail-closed**，并由**出口自检（IP-echo）**驱动，而非仅看 `TRANSPORT_VPN` 位 | grounded-ref §3/§6；评审 Network 维度 |
| **D5** | **认证可选**（默认关闭，附"未认证"警告）；保留**反 SSRF EgressGuard**（默认禁 loopback/链路本地/本机/自身监听；**私网默认放行**、可选开关禁用）；启用认证时凭据存 **EncryptedSharedPreferences/Keystore**；来源 IP 准入仅便利过滤 | 用户选非强制认证；评审 Security 维度（保留其余加固） |
| **D6** | 并发=**协程 + 阻塞 socket + `Dispatchers.IO.limitedParallelism(N)`**（不用裸 NIO，不用 Netty）；**连接/资源上限做成可调设置 + 三档预设**（见 §4），记录队头阻塞权衡 | grounded-ref §7；用户要可调+预设 |
| **D7** | 按 V1 **裁剪过度设计的模块**；`ClientSession` 最小化；UI 状态用**节流不可变快照**（~1Hz）流入 StateFlow，避免 Compose 重组风暴 | 评审 Architecture 维度 |

---

## 3. 总体架构

代理引擎放在 data 层、由 `ProxyServerRepository` 封装，前台服务委托它，业务逻辑不写在 Service 或 ViewModel 里（grounded-ref §7）。

```
UI (Compose + M3)  →  ViewModel (单一不可变 uiState: StateFlow)  →  UseCase  →  Repository  →  Core
        ↑ collectAsStateWithLifecycle                                                   ↓
        └──────────────── 节流不可变快照（StateFlow，单一数据源在 Core）──────────────────┘

service/ProxyForegroundService  拥有  CoroutineScope(SupervisorJob + Dispatchers.IO)，onDestroy() 取消
   └─ 委托 → core/proxy (HTTP/SOCKS5/PAC + RelayEngine)  +  core/network (监听/接口/VPN/mDNS)  +  core/security (认证/准入/出口护栏)
```

### 3.1 V1 模块树（命名空间 `com.mzstd.hxmyproxy`）

```text
com.mzstd.hxmyproxy
  ui/        dashboard/   interfaces/   diagnostics/   settings/     # V1 四屏
  domain/    usecase/  StartSharing / StopSharing / SelectInterfaces /
                       DiagnoseNetwork / GeneratePac / GenerateClientConfig
  data/      repository/  NetworkRepository / ProxyServerRepository / SettingsRepository
             datastore/   (设置/预设)     security store/ (EncryptedSharedPreferences 凭据)
  service/   ProxyForegroundService
  core/
    proxy/     HttpProxyServer / Socks5ProxyServer / PacServer / RelayEngine / OutboundConnector / ProxyError / ProxyTuning
    network/   ConnectivityObserver / InterfaceScanner / VpnStateDetector / LocalNetworkPermissionManager / MdnsPublisher
    security/  Authenticator / AccessController / EgressGuard(反 SSRF)
```

> 延后（不在 V1）：`Watchdog`/`HealthChecker` 全量、客户端富管控。

---

## 4. 连接/资源设置 与 预设（D6）

连接相关上限**不再写死**，全部做成 **DataStore 可调设置、运行时生效**，并提供三档**预设**。

### 4.1 关键原则：连接数 ≠ 带宽

单条 TCP 即可跑满 100–500 Mbps，所以"用满带宽"靠的不是连接多。**连接数上限太小**的后果是：① 新连接被拒 → 网页加载卡住/请求失败；② 在 4G/5G 这种高带宽+较高延迟（高 BDP）链路上，**掐断客户端用来填满带宽的并行连接 → 实测吞吐反而下降**。真正决定**单流吞吐**的是 `N`（并行度）+ 缓冲区 + 链路本身。

### 4.2 设置项 · 默认 · 影响

| 设置 | 控制什么 | 默认（均衡档） | 范围 | 太小 | 太大 |
|---|---|---|---|---|---|
| 全局并发连接上限 | 同时存活 TCP 连接总数 | **256** | 32–1024 | 连接被拒、页面卡、吞吐被掐 | 内存上升 |
| 单客户端连接上限 | 单台设备连接数 | **128** | 16–512 | 重度设备页面加载失败 | 单台挤占全局 |
| relay 并行度 N (`limitedParallelism`) | 同刻并行搬字节的连接数 | **32** | 4–64 | 多路高速传输队头阻塞、用不满带宽 | 线程/CPU 上升 |
| 单连接缓冲区 | 每次 read/write 缓冲 | **32 KiB** | 8–256 KiB | 单连接吞吐略降 | 内存随连接数放大 |
| 空闲超时 | 无数据多久断开 | **300 s** | 30–1800 s | 长轮询/SSE 被误断 | 空闲连接占 fd |

内存预算：`256 × 2 × 32KiB ≈ 16 MiB`。

### 4.3 预设档（一键，下面可逐项覆盖）

| 预设 | 全局/单客户端 | N | 缓冲 | 适用 |
|---|---|---|---|---|
| **省电** | 64 / 32 | 16 | 16 KiB | 少设备、省电省内存 |
| **均衡**（默认） | 256 / 128 | 32 | 32 KiB | 一般共享 |
| **高吞吐** | 512 / 256 | 64 | 64 KiB | 多设备/大流量、32G 内存机器无压力 |

> `NEEDS VERIFICATION`：协程+阻塞 socket 在 **500 Mbps、多连接**下的真实吞吐需**实机压测**定 N/缓冲；不足先调缓冲/N，NIO 留作 V2 后路。

---

## 5. 跨切面不变量（写代码时必须守住）

1. **出站不绑网**：origin/upstream socket 一律不绑定 Network；进程内**禁止** `bindProcessToNetwork`。（D4）
2. **入站对称**：仅对 `accept()` 的 **server FD** 视需要做 `Network.bindSocket`（防 VPN 回包黑洞，**需实机核实**）。（D4）
3. **fail-closed**：阻断模式生效时原子拒绝并拆除在途 relay。（D4）
4. **本地网络硬门**：targetSdk 37、`ACCESS_LOCAL_NETWORK` 未授权时**不得进入运行态**（入站与 mDNS 都依赖它）。（D3）
5. **不发布解析不了的主机**：`hxmyproxy.local` 只能与具体 IP 同时出现、IP 在后兜底。（D1）
6. **准入≠认证**：来源 IP 子网匹配只是便利过滤；安全靠认证（可选）+ EgressGuard。（D5）
7. **协议按 RFC**：SOCKS5 按 RFC 1928/1929 字节布局与 REP 码；HTTP CONNECT 端口**必填无默认**、2XX 后盲转。（grounded-ref §8）
8. **UI 状态节流**：不得把逐次读写计数直接灌入 StateFlow。（D7）

---

## 6. 技术选型（V1）

| 模块 | 选型 |
|---|---|
| 语言 / UI | Kotlin / Jetpack Compose + Material 3 + Dynamic Color + Edge-to-edge |
| 状态 | ViewModel + 单一不可变 `uiState: StateFlow`（`stateIn` + `WhileSubscribed(5000)`），`collectAsStateWithLifecycle` |
| DI | Hilt；代理引擎/Repository 作 singleton（持有活动监听/连接） |
| 持久化 | DataStore（设置/预设）+ EncryptedSharedPreferences/Keystore（凭据） |
| 代理核心 | 协程 + 阻塞 `java.net` socket + `Dispatchers.IO.limitedParallelism(N)`（D6） |
| 网络 | `registerDefaultNetworkCallback` + `NetworkCapabilities`；接口用 `NetworkInterface` + 结构化分类（不信任接口名）；mDNS 用 `NsdManager` |
| 服务 | Foreground Service（`connectedDevice`，D2），Service 自有 `SupervisorJob` scope |
| 命名空间 / SDK | `com.mzstd.hxmyproxy`；**minSdk 29**（Android 10）；**compileSdk / targetSdk 37**（已落实，见下方） |

> **构建 SDK（已落实 37）**：本机已安装 **`android-37.0`**，`app/build.gradle.kts` 已设为 **`compileSdk 37 / targetSdk 37`**（Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10）。`ACCESS_LOCAL_NETWORK` 自此为 **day-one 强制**（入站 accept + mDNS 多播，D3）。**仍须**：所有权限/新 API 调用按 `Build.VERSION` 守卫，使 App 在 **Android 10–16 运行设备**上也正确（那里 `ACCESS_LOCAL_NETWORK` 为 opt-in/不强制）。

---

## 7. 构建与调试（避免重复编译）

仓库已是 Kotlin-DSL Gradle 工程（根 `build.gradle.kts` + `app/` 模块 + `settings.gradle.kts`）。为避免 **Android Studio 调试时不必要的全量重编**——未改源码就再次运行，应命中 `UP-TO-DATE`/build cache 近乎瞬时——已在根 `gradle.properties` 配置：

- `org.gradle.caching=true`（build cache，核心）、`daemon=true`、`parallel=true`、`configureondemand=true`、`configuration-cache=true`
- JVM 堆按机器 **32G 内存**给足：Gradle daemon `-Xmx8192m`、Kotlin daemon `kotlin.daemon.jvmargs=-Xmx4096m`
- Kotlin 增量：`kotlin.incremental=true` + `useClasspathSnapshot=true`

**写代码时必须遵守（否则缓存失效、每次重编）**：

1. **不要**在 `buildConfigField`/`resValue` 放时间戳/`Date`/随机/变动 git 哈希——会破坏 `UP-TO-DATE` 检查。
2. 自定义 Gradle task 必须声明 `@InputFiles`/`@OutputFiles`，否则永远不是最新。
3. 仅改 Kotlin/Java 代码时优先用 **Apply Changes**（热更）而非 Build/Run。
4. 不要来回切 minSdk/targetSdk/依赖/AGP 版本。

> 记忆已登记此偏好（避免重复编译）。

---

## 8. 开放决策 与 待核实

### 8.1 已按你的决定锁定
mDNS 进 V1（IP 为主、`.local` 带 IP fallback）｜ targetSdk=37 ｜ 认证非强制 ｜ 连接参数可调+三档预设（默认均衡 256/128/N32/32KiB）｜ FGS `connectedDevice`。

### 8.2 仍可后续微调（非阻塞）
- **FGS 类型**：先发 `connectedDevice`，若 Play 审核要求改 `specialUse`（两套清单/说明已备）——`NEEDS VERIFICATION`（提审验证）。
- **EgressGuard 私网默认放行**：当前为广适用默认放行；若你要更严可改默认禁止。
- **连接参数实测**：500 Mbps 下 N/缓冲的最优值待压测。

### 8.3 需实机/进一步核实（NEEDS VERIFICATION）
入站 LAN 回包黑洞（实机）、`ACCESS_NETWORK_STATE` 是否必需、长生命周期 Service 自定义协程 scope 写法、阻塞 socket 在 500 Mbps 多连接下的吞吐、Play 对 FGS 类型审核结论、出口自检 IP-echo 端点与隐私设计。详见 [v1-grounded-reference.md](./v1-grounded-reference.md) 文末与 [roadmap.md](./roadmap.md)。

---

## 9. 评审与核证方法（可信度说明）

- **5 维度对抗式评审**（37 条）针对原始 `design.md`，每条经**独立 fact-check**（confirmed/partially-correct/refuted/uncertain）。
- **16 份官方文档/RFC 实抓核证**形成 grounded-reference；子系统设计逐条对照核证、剔除记忆性断言，未能核实者标 `NEEDS VERIFICATION`。
- Android 行为**以 grounded-reference 出处为准**，而非模型记忆。
