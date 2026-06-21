# hxmy proxy · V1 · App Architecture, State Models & Compose UI

> 隶属 [v1-design.md](./v1-design.md)；Android 行为已对照 [v1-grounded-reference.md](./v1-grounded-reference.md) 核证，记忆性断言已剔除。

> Subsystem scope: the V1 application skeleton — module tree, state models, unidirectional data flow, Repository set, DataStore schema, the four Compose screens (Dashboard, Interfaces, Diagnostics‑lite, Settings), their ViewModels, Navigation, and the binding to `ProxyForegroundService`. The proxy core (`HttpProxyServer`, `Socks5ProxyServer`, `PacServer`, `RelayEngine`), `InterfaceScanner`, `ConnectivityObserver`, `VpnStateDetector` internals, `MdnsPublisher`, and the permission managers are consumed here through their public contracts but designed in their own subsystem sections.
>
> **V1 cross‑cutting decisions applied here (see [v1-grounded-reference.md](./v1-grounded-reference.md)):** mDNS/NSD is **now in V1 as a convenience layer** on top of the raw‑IP scheme — the per‑interface IP entry list + manual config + PAC stays the **primary, broadest‑compatibility path**, and `hxmyproxy.local` is **additive**, always emitted **with the concrete IP underneath as fallback** (never `.local` alone) (U1, supersedes D1). FGS type is **`connectedDevice`** primary / **`specialUse`** fallback, **never `dataSync`** (D2/U5). `ACCESS_LOCAL_NETWORK` is a **mandatory day‑one runtime permission** and **hard gate** on the running state — required for **both inbound accept() and mDNS multicast** — now that the first release targets SDK 37 (U2, supersedes D3). Outbound egress sockets stay **unbound** to follow the system default network (VPN), with a runtime egress check and **fail‑closed** block strategy (D4). Auth is **OPTIONAL (off by default)** with an unauthenticated‑network warning surface; anti‑SSRF `EgressGuard` stays (block loopback/link‑local/this‑host/multicast/own‑listeners by default, private‑LAN egress allowed by default with an opt‑in block toggle); credentials, when enabled, are **Keystore‑backed** (U3, supersedes D5). Proxy‑core concurrency is **Kotlin coroutines + blocking sockets on `Dispatchers.IO.limitedParallelism(N)`** (D6). Connection/resource limits are **user‑adjustable DataStore‑backed Settings + three presets (省电/均衡/高吞吐)** (U4). The module tree and `ClientSession` are **trimmed to exactly what V1 needs**, with throttled snapshot emission (D7).

Package root: `com.mzstd.hxmyproxy`. `minSdk = 29` (Android 10), `compileSdk = 37`. **`targetSdk = 37` for the first V1 release** (U2): the Android 17 local‑network enforcement (which gates inbound accept **and** mDNS multicast; §11, U2) is therefore a **mandatory runtime permission from day one**, with a hard‑gate first‑run flow. Compose BOM + Material 3, `kotlinx-coroutines`, `androidx.datastore:datastore-preferences`, `androidx.security:security-crypto` (or a Keystore wrapper) for the auth secret, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.lifecycle:lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`), `androidx.navigation:navigation-compose`, Hilt for DI.

---

### 1. V1 module tree (trimmed — refines design.md §4.2)

Only the packages below ship in V1. Per the confirmed review finding *"Module tree is heavily over‑engineered"* (Architecture & Feasibility) and decision **D7**, the following are intentionally **absent** in V1 and added later as additive seams: `ui/clients/`, `core/monitor/` (Watchdog/HealthChecker), `data/db/` (Room — no V1 entity needs a relational DB; ephemeral client sessions live in memory), `data/repository/ClientRepository`. **`ui/settings/` and `core/network/MdnsPublisher` are now IN V1** (U4 surfaces user‑adjustable performance/auth/egress settings; U1 promotes mDNS from deferred to an additive convenience layer). The 6‑UseCase domain layer is also trimmed: V1 keeps only the **two UseCases that genuinely encapsulate multi‑repository orchestration** (`StartSharingUseCase`, `DiagnoseNetworkUseCase`); the rest are inlined into repositories/ViewModels to avoid empty indirection. Pure helpers (`GeneratePac`, `GenerateClientConfig`) are plain functions in `core`/`domain`, not UseCase classes.

```text
com.mzstd.hxmyproxy/
  HxmyProxyApp.kt                      // @HiltAndroidApp; process-wide init
  MainActivity.kt                      // single-activity; setContent { HxmyApp() }

  ui/
    HxmyApp.kt                         // NavHost host + Scaffold + theme
    HxmyNavGraph.kt                    // routes + NavHost wiring
    theme/ { Theme.kt, Color.kt, Type.kt }   // M3 dynamic color, edge-to-edge
    common/ { CopyableField.kt, StatusPill.kt, SectionHeader.kt, ErrorBanner.kt }
    dashboard/    { DashboardScreen.kt, DashboardViewModel.kt, DashboardUiState.kt }
    interfaces/   { InterfacesScreen.kt, InterfacesViewModel.kt, InterfacesUiState.kt }
    diagnostics/  { DiagnosticsScreen.kt, DiagnosticsViewModel.kt, DiagnosticsUiState.kt }
    settings/     { SettingsScreen.kt, SettingsViewModel.kt, SettingsUiState.kt }   // U4 presets+overrides, U3 auth toggle/egress
    clienthelp/   { ClientSetupScreen.kt }   // V1: static per-OS recipe sheet (no wizard engine)

  domain/
    model/                             // pure Kotlin state models + enums (no Android types)
      ShareState.kt  ShareInterface.kt  ProxyEntry.kt  ClientSession.kt
      DiagnosticsSummary.kt  ProxyConfig.kt
      Enums.kt                         // InterfaceType, ProxyProtocol, InterfaceStatus,
                                       // PortState, VpnDownStrategy, LocalNetState, EgressState
    usecase/
      StartSharingUseCase.kt           // multi-repo orchestration (kept)
      DiagnoseNetworkUseCase.kt        // assembles DiagnosticsSummary (kept)
    result/
      StartResult.kt                   // sealed start outcome (port conflict, perm, ...)

  data/
    repository/
      NetworkRepository.kt   (interface) + NetworkRepositoryImpl.kt
      ProxyRepository.kt     (interface) + ProxyRepositoryImpl.kt
      SettingsRepository.kt  (interface) + SettingsRepositoryImpl.kt
    datastore/
      SettingsDataStore.kt             // Preferences DataStore (NON-secret settings only)
      SettingsKeys.kt
    secure/
      CredentialStore.kt               // Keystore/EncryptedSharedPreferences (auth secret) — D5

  service/
    ProxyForegroundService.kt          // bound + started FGS; owns proxy core lifecycle + scope
    ProxyStateHolder.kt                // @Singleton process-level StateFlow holder (state transport)
    ProxyServiceConnection.kt          // app-side bind helper for COMMANDS only
    ProxyNotification.kt               // notification builder + channel

  core/ (consumed, designed elsewhere)
    proxy/ { HttpProxyServer, Socks5ProxyServer, PacServer, RelayEngine, ProxyCoreFactory }
    network/ { ConnectivityObserver, InterfaceScanner, VpnStateDetector, TetherObserver,
               LocalNetworkPermissionManager, DefaultNetworkEgressMonitor,
               MdnsPublisher }   // MdnsPublisher: NSD advertise per active link (U1)
    security/ { Authenticator, AccessController, EgressGuard }   // EgressGuard = anti-SSRF (U3)

  di/
    AppModule.kt, RepositoryModule.kt, DispatcherModule.kt
```

> **State‑transport note (folds in confirmed finding *"Ownership of the proxy CoroutineScope … is undefined"*):** UI live state is **not** carried over the service binding. A process‑level `@Singleton ProxyStateHolder` exposes `StateFlow<ShareState>` that the **service writes** and the **ViewModel reads** via `stateIn`. Binding (`ProxyServiceConnection`) and intent actions are used **only to issue start/stop/apply commands**, never as the survival mechanism for state. This avoids the "bound‑only service dies when the Activity backgrounds" / "stale empty UI after return" failure modes the review flagged. (The custom long‑running Service scope itself is **NEEDS VERIFICATION**, §13.)

---

### 2. State models & enums (`domain/model`)

These supersede design.md §5 with three V1 changes: (a) `ClientSession` is reduced to a **minimal, immutable count + last‑seen** form with **throttled snapshot emission** (no per‑client byte/rate fields — those are deferred and would otherwise cause Compose churn); (b) `recommendedEntries`/`diagnostics`/`clients` ride **separate flows** so the UI never re‑renders the whole dashboard on a counter tick; (c) state models stay free of Android/`java.net` types per the official architecture guidance that ViewModels and the models they expose must not hold `Context`/framework types (*ViewModel overview*).

#### 2.1 Enums (`Enums.kt`)

```kotlin
enum class InterfaceType { WIFI, HOTSPOT, USB, BLUETOOTH, ETHERNET, UNKNOWN }

enum class ProxyProtocol { HTTP, HTTPS_CONNECT, SOCKS5, PAC }   // HTTP & HTTPS_CONNECT share one listener port

enum class InterfaceStatus { UP, DOWN, NO_ADDRESS, UNSUPPORTED }

enum class PortState { LISTENING, NOT_LISTENING, CONFLICT, DISABLED }

enum class VpnDownStrategy { CONTINUE, WARN, BLOCK }            // default = BLOCK; BLOCK fails closed (D4)

// U2: the local-network permission is NOT a soft boolean — it is a hard gate with a distinct
// "blocked at the OS layer (denied inbound times out)" state. Mandatory day-one at target SDK 37;
// gated by Build.VERSION (minSdk 29). Covers inbound accept AND mDNS multicast (U1).
enum class LocalNetState { NOT_REQUIRED, GRANTED, DENIED, NEEDS_REQUEST }

// D4: outbound egress posture, measured from the app's OWN default network.
enum class EgressState { VPN_VALIDATED, VPN_UNVALIDATED, NO_VPN, UNKNOWN }

enum class DiagnosticLevel(val severity: Int) { OK(0), WARN(1), UNKNOWN(2), ERROR(3) }
```

#### 2.2 ShareInterface (`ShareInterface.kt`)

`address`/`prefixLength` are kept as parsed primitives so the model stays Android/`java.net`‑free at the UI boundary; CIDR membership math lives in `core/security/AccessController`. `id` is stable across refreshes so a toggle survives a network‑callback refresh. **Type is derived structurally** (gateway‑like ownership of the subnet + interface flags), with the name used only as a weak hint, per the confirmed finding that names like `ap0`/`rndis0`/`bt-pan` are vendor‑specific and not contractual.

```kotlin
data class ShareInterface(
    val id: String,                 // stable: "<ifName>/<addr>/<prefix>"  e.g. "wlan0/192.168.1.34/24"
    val ifName: String,             // weak hint only (wlan*, ap*, swlan*, rndis*, usb*, ncm*, bt-pan*, eth*)
    val displayName: String,        // localized: "Wi-Fi LAN", "Phone hotspot"...
    val type: InterfaceType,        // derived structurally (gatewayLike + flags), not name-mapped
    val address: String,            // "192.168.1.34"
    val prefixLength: Int,          // 24
    val cidr: String,               // "192.168.1.34/24" (derived, for display + copy)
    val gatewayLike: Boolean,       // phone owns the subnet (.1 / DHCP server) -> hotspot/usb/bt
    val isSelected: Boolean,        // user toggle (joined from SettingsRepository)
    val status: InterfaceStatus
)
```

#### 2.3 ProxyEntry (`ProxyEntry.kt`)

**V1 baseline `host` is a live selected‑interface IPv4 literal — the IP scheme is the primary, always‑works path (U1).** Each entry corresponds to **one selected interface IP** (the scenario baseline the Dashboard always lists). The `hxmyproxy.local` mDNS name is an **additive convenience** carried in the separate `mdnsName` field, **never** replacing the IP: any user‑facing line that shows `.local` always shows the concrete IP underneath as fallback (mDNS is unreliable on Windows / some routers / some Android clients — grounded‑ref §9, design.md §17.2). `port` is the **actually‑bound** port (may differ from the configured default after a fallback bind — see §4.2 / §8 port handling), so PAC/UI/notification never advertise a port nothing is listening on.

```kotlin
data class ProxyEntry(
    val host: String,               // ALWAYS a live selected-interface IPv4 literal (the baseline)
    val port: Int,                  // the ACTUALLY-bound port (post-bind verified)
    val protocol: ProxyProtocol,
    val sourceInterfaceId: String,  // FK to ShareInterface.id
    val priority: Int,              // lower = more recommended (see §6 ranking)
    val reachable: Boolean,
    val mdnsName: String? = null    // U1: actual registered name read back in onServiceRegistered
                                    // (e.g. "hxmyproxy.local"); convenience layer, shown WITH the IP
) {
    /** Human copyable form used by Dashboard "copy" + notification (raw IP, always resolvable). */
    fun displayLine(): String = when (protocol) {
        ProxyProtocol.PAC -> "http://$host:$port/proxy.pac"
        else              -> "$host:$port"
    }
}
```

> **mDNS is additive, never standalone (U1).** When `mdnsName != null`, the Dashboard's `hxmyproxy.local` convenience entry renders the name **with the IP fallback shown beneath it**. PAC chains and the recommended‑entry list emit `hxmyproxy.local` **first**, then the concrete interface IP(s) as fallback (e.g. `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`). NEVER emit `.local` without the IP fallback underneath.

#### 2.4 ClientSession — MINIMAL V1 with throttled snapshots (`ClientSession.kt`)

Per confirmed finding *"Per‑connection byte counters in StateFlow will cause … churn"* and decision **D7**: V1 keeps **only** what the dashboard count + admission control need. Raw byte deltas are **never** written into a UI‑observed flow — they accumulate in plain atomics inside the proxy core and are exposed only as a **throttled aggregate** sampled at ~1 Hz. `ClientSession` is an immutable value type emitted as an immutable `List`, never a `mutableListOf` pushed into Compose (*State and Jetpack Compose* — never use non‑observable mutable collections as state).

```kotlin
// Deferred fields (uploadBytes/downloadBytes/rates) are NOT modeled in V1.
data class ClientSession(
    val clientIp: String,
    val interfaceId: String,        // bucketed by the ACCEPTED SOCKET LOCAL ADDRESS, not remote-IP subnet
    val activeConnections: Int,
    val lastSeenAtEpochMs: Long
)
```

The Dashboard consumes only `clientCount` and `connectionCount` plus an aggregate up/down rate (throttled). `AccessController` produces a `StateFlow<List<ClientSession>>` already, so the deferred rich page is a pure additive consumer. `interfaceId` is determined from `Socket.getLocalAddress()` (which interface received the connection) rather than a subnet‑contains test on the remote IP, per the confirmed admission‑bucketing finding (overlapping RFC1918 ranges otherwise mis‑bucket and would feed the wrong interface to display/reply logic).

#### 2.5 DiagnosticsSummary (`DiagnosticsSummary.kt`)

```kotlin
data class DiagnosticsSummary(
    val localNetwork: DiagnosticItem,                // U2: GRANTED/DENIED/NOT_REQUIRED (mandatory hard gate)
    val notificationPermission: DiagnosticItem,      // Android 13 POST_NOTIFICATIONS (soft)
    val egress: DiagnosticItem,                       // D4: measured VPN egress state + caveat
    val httpPort: DiagnosticItem,
    val socksPort: DiagnosticItem,
    val pacPort: DiagnosticItem,
    val pacReachable: DiagnosticItem,                // loopback HTTP GET /proxy.pac
    val mdns: DiagnosticItem,                        // U1: minimal NSD status (registered name / not advertised)
    val batteryOptimization: DiagnosticItem,         // guidance only
    val generatedAtEpochMs: Long
) {
    val worst: DiagnosticLevel
        get() = listOf(localNetwork, egress, httpPort, socksPort, pacPort, pacReachable)
            .maxOf { it.level }      // DiagnosticLevel.severity ordering; mDNS is informational, not in worst
}

data class DiagnosticItem(
    val level: DiagnosticLevel,
    val label: String,          // "Local network permission"
    val detail: String,         // "Granted" / "Denied — LAN clients will time out" / "Port 8080 in use"
    val actionHint: String? = null  // e.g. "Request permission", "Open settings"
)
```

> **A minimal mDNS status row IS in V1 (U1).** Since `MdnsPublisher` now ships, Diagnostics surfaces a single informational line: the **actual registered service name** (read back in `onServiceRegistered` — Android may rename on conflict, grounded‑ref §9) when advertising, or `OK`/`UNKNOWN` "not advertised (proxy stopped)" / "mDNS may not resolve on some clients — IP fallback in use" when not. It is **informational, not a gate** (mDNS is a convenience layer; the IP path always works), so it is excluded from `worst`. It must never present mDNS failure as blocking, because the IP entries below it remain fully usable.

#### 2.6 ShareState & ProxyConfig

`ShareState` is the single source of truth held in `ProxyStateHolder`, written by the service (§5). High‑churn `clients` and `diagnostics` ride **separate** flows so the running/interface section never recomposes on a throughput tick.

```kotlin
data class ShareState(
    val running: Boolean,
    val startInProgress: Boolean,
    val egress: EgressState,                       // measured from the app's own default network (D4)
    val vpnDownStrategy: VpnDownStrategy,
    val localNet: LocalNetState,                   // U2 hard-gate input (mandatory at target SDK 37)
    val interfaces: List<ShareInterface>,
    val recommendedEntries: List<ProxyEntry>,      // per-interface IP baseline, sorted by priority (U1);
                                                   // each may carry an additive mdnsName
    val mdnsServiceName: String? = null,           // U1: actual registered name (post onServiceRegistered)
    val authEnabled: Boolean = false,              // U3: drives the unauthenticated-network warning surface
    val activeConfig: ProxyConfig?,                // null when stopped
    val lastError: ProxyError? = null
)

data class ProxyConfig(                            // resolved snapshot at Start time
    val httpPort: Int, val socksPort: Int, val pacPort: Int,
    val httpEnabled: Boolean, val socksEnabled: Boolean, val pacEnabled: Boolean,
    val selectedInterfaceIds: Set<String>,
    val authEnabled: Boolean,                      // U3: OPTIONAL, default false
    val username: String?,                         // never logged; redacted in toString()
    val password: String?,                         // never logged; redacted in toString()
    val blockPrivateEgress: Boolean,               // U3 anti-SSRF: private-LAN egress ALLOWED by default
                                                   // (false); opt-in toggle blocks RFC1918 egress.
                                                   // loopback/link-local/this-host/multicast/own-listeners
                                                   // are ALWAYS blocked by EgressGuard regardless of this flag
    val vpnDownStrategy: VpnDownStrategy,
    // U4: performance/resource limits — runtime-applied snapshot, sourced from a preset or per-param overrides
    val maxConnections: Int,                       // global cap; default 256, range 32-1024
    val maxConnectionsPerClient: Int,              // per-client cap; default 128, range 16-512
    val relayParallelism: Int,                     // N for Dispatchers.IO.limitedParallelism(N); default 32, range 4-64
    val relayBufferBytes: Int,                     // per-connection relay buffer; default 32 KiB, range 8-256 KiB
    val idleTimeoutSeconds: Int                    // idle connection reap; default 300 s, range 30-1800 s
) {
    override fun toString() = "ProxyConfig(ports=$httpPort/$socksPort/$pacPort, auth=$authEnabled, " +
        "limits=$maxConnections/$maxConnectionsPerClient/N$relayParallelism/${relayBufferBytes}B/${idleTimeoutSeconds}s, …)"
}

sealed interface ProxyError {                      // surfaced as ErrorBanner
    data class PortConflict(val protocol: ProxyProtocol, val port: Int) : ProxyError
    data object LocalNetworkBlocked : ProxyError                // U2 (mandatory ACCESS_LOCAL_NETWORK)
    data object NoInterfaceSelected : ProxyError
    data object EgressBlockedNoVpn : ProxyError                 // D4 (block strategy fired)
    data class BindFailed(val protocol: ProxyProtocol, val port: Int) : ProxyError
    data class Unexpected(val message: String) : ProxyError
}                                                  // U3: AuthRequiredForUntrusted REMOVED — auth is optional
```

---

### 3. Unidirectional data flow

```text
[ truth sources ]                 [ data layer ]                 [ presentation ]            [ user ]
ConnectivityObserver  ─┐
InterfaceScanner       ├─► NetworkRepository.detectedInterfaces: StateFlow ─┐
TetherObserver         │   NetworkRepository.localNet / egress             │
DefaultNetworkEgress…  ┘                                                    │
                                                                            ├─► ViewModel
ProxyForegroundService ─► ProxyStateHolder (@Singleton) ─► ProxyRepository ─┤    .uiState:
   (writes ShareState,                       .shareState                   │    StateFlow<UiState>
    clients, ports)                          .clients (throttled)          │        │
SettingsDataStore ─────► SettingsRepository.settings: StateFlow ───────────┘        ▼
                                                                              Compose screen
                                                                                   │ onAction(UiAction)
                                                                                   ▼
                                                                              ViewModel method call
                                                                                   │
                                                          ┌────────────────────────┴───────────────┐
                                                          ▼                                         ▼
                                              SettingsRepository.write              ProxyRepository.start/stop
                                                                                            │ (command via intent/bind)
                                                                                            ▼
                                                                              ProxyForegroundService (FGS, owns scope)
                                                                                            │ writes back to
                                                                                            ▼
                                                                                    ProxyStateHolder
```

Rules (grounded in *Recommendations for Android architecture* + *ViewModel overview* + *State and Jetpack Compose*):
- **UDF / events‑up, state‑down.** Compose never touches repositories/service directly; it calls **ViewModel methods** (`onAction(UiAction)`). ViewModels **do not send events to the UI** — they process the action and emit a state update; errors are modeled as **state, not events** (e.g. "port in use" lives in `ShareState.lastError`).
- **ViewModels hold no `Context`/Service reference and are not `AndroidViewModel`.** Anything needing `Context` (FGS start, `ConnectivityManager`, `NotificationManager`, sockets) lives in the data/core layer, injected by constructor.
- **Single immutable `uiState: StateFlow`** per screen, built with `combine(...).stateIn(viewModelScope, WhileSubscribed(5000), initial)`.
- **Repositories own the hot `StateFlow`s** (the data layer holds business logic and the connectivity‑status provider is a data source). ViewModels read flows + call suspend functions within `viewModelScope`.

---

### 4. Repositories (`data/repository`)

#### 4.1 NetworkRepository

Aggregates connectivity + interface + VPN/egress + local‑net‑permission truth into one `@Singleton` (app scope) pipeline so the Interfaces screen and Dashboard share a single `registerNetworkCallback` registration.

```kotlin
interface NetworkRepository {
    val detectedInterfaces: StateFlow<List<ShareInterface>>
    val egress: StateFlow<EgressState>          // D4: measured from the app's OWN default network
    val localNet: StateFlow<LocalNetState>      // U2: GRANTED/DENIED/NOT_REQUIRED/NEEDS_REQUEST
    suspend fun refresh()                       // forced re-enumeration (after a permission grant)
}
```

```kotlin
override val detectedInterfaces: StateFlow<List<ShareInterface>> =
    merge(
        connectivityObserver.events,            // registerNetworkCallback (Wi-Fi/Ethernet/VPN egress)
        tetherObserver.events,                  // TetheringManager / poll — hotspot/USB/BT NOT seen by NetworkCallback
    )
        .onStart { emit(NetworkEvent.Bootstrap) }
        .mapLatest { interfaceScanner.scan() }   // suspend; enumerates NetworkInterface off main
        .distinctUntilChanged()
        .flowOn(ioDispatcher)
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

> **Tether detection (confirmed finding):** hotspot / USB‑tether / BT‑PAN interfaces are **not** surfaced as `ConnectivityManager` callback `Network`s. `tetherObserver` uses `TetheringManager.addTetheringEventCallback()` / `getTetheredIfaces()` (API 30+) and/or `ACTION_TETHER_STATE_CHANGED`, plus `NetworkInterface` polling as a fallback — `registerNetworkCallback` alone would silently miss the headline multi‑entry interfaces. `connectivityObserver.events` is debounced ~300 ms to collapse the burst of `onLost`/`onAvailable` during a Wi‑Fi switch.

**Egress measurement (D4).** `DefaultNetworkEgressMonitor` calls `registerDefaultNetworkCallback(callback, handler)` (Handler variant, off the main thread) and reads `NetworkCapabilities` of **this app's own default network** — *not* an `getAllNetworks()` scan — so a split‑tunnel‑excluded app correctly reports `NO_VPN`. `EgressState` is derived from `hasTransport(TRANSPORT_VPN)` gated on `NET_CAPABILITY_VALIDATED` (`VPN_VALIDATED` vs `VPN_UNVALIDATED` vs `NO_VPN`), re‑evaluated on every `onCapabilitiesChanged` (never via a synchronous call inside `onAvailable`). Source: *Reading network state*. `ACCESS_NETWORK_STATE` is declared defensively (the doc flags this as **NEEDS VERIFICATION**, §13).

**Local‑net permission (U2 — now mandatory day‑one).** Because the **first release targets SDK 37**, `android.permission.ACCESS_LOCAL_NETWORK` is a **mandatory runtime permission from day one**, required for **both inbound `accept()` AND mDNS multicast** (`224.0.0.251:5353`, the convenience layer added by U1). `LocalNetworkPermissionManager` computes `LocalNetState`, still guarded by `Build.VERSION` so the permission APIs are only touched on the OS versions that define them: on devices below Android 17 the app is unrestricted → `NOT_REQUIRED`; on Android 17 it is `GRANTED`/`DENIED`/`NEEDS_REQUEST` from the runtime grant. Per *Local network permission* (grounded‑ref §1), this gates **accepting incoming TCP connections** (both directions) and **multicast/`NsdManager`**, enforced deep in the networking stack so binding `0.0.0.0` cannot evade it; denial surfaces to clients as a **TCP timeout, not `ECONNREFUSED`**. It is in the `NEARBY_DEVICES` group, so a user who already granted a sibling is not re‑prompted. V1 ships a **hard‑gate first‑run flow**: request with a clear rationale **before** starting the proxy; if denied, refuse the running state and show a blocking explanation (§11).

#### 4.2 ProxyRepository

The app‑side facade over the service. It reads live state from `ProxyStateHolder` and forwards **commands** through `ProxyServiceConnection`. This is the only class that knows about service binding/intents.

```kotlin
interface ProxyRepository {
    val shareState: StateFlow<ShareState>           // from ProxyStateHolder (stopped default when service dead)
    val clients: StateFlow<List<ClientSession>>     // throttled, immutable snapshots
    val portStates: StateFlow<Map<ProxyProtocol, PortState>>

    suspend fun start(config: ProxyConfig): StartResult
    suspend fun stop()
    suspend fun applySelection(selectedInterfaceIds: Set<String>, strategy: VpnDownStrategy)
    suspend fun runPortDiagnostics(): Map<ProxyProtocol, PortState>
    suspend fun probePacReachable(host: String, port: Int): Boolean   // loopback probe
}
```

`StartResult` (in `domain/result`):

```kotlin
sealed interface StartResult {
    data object Success : StartResult
    data class PortConflict(val protocol: ProxyProtocol, val port: Int) : StartResult
    data object LocalNetworkBlocked : StartResult        // U2 hard gate (mandatory at target SDK 37)
    data object NoInterfaceSelected : StartResult
    data class BindFailed(val protocol: ProxyProtocol, val port: Int) : StartResult
    data class Failure(val message: String) : StartResult
}                                                        // U3: AuthRequiredForUntrusted REMOVED (auth optional)
```

> **Port‑conflict / bind handling (confirmed finding *"Port‑conflict / EADDRINUSE … is absent"*).** Default ports (HTTP 8080, SOCKS5 1080, PAC 8899) are routinely taken by other proxy apps. The service **always calls `startForeground()` promptly first, then attempts binds** (§5/§7), so a `BindException` is surfaced as a typed `StartResult.PortConflict` / `ProxyError.PortConflict` in `ShareState` and reflected in the notification + diagnostics — it can never threaten the mandatory `startForeground()` call (the FGS‑timeout `RemoteServiceException` only fires if you bind *before* `startForeground`; source *Foreground services overview*). All listening sockets set `SO_REUSEADDR` (mitigates the rapid stop→restart `TIME_WAIT` race only; it does **not** let two live listeners share a port). On `EADDRINUSE` from a competing app, V1 surfaces the conflict loudly with the port named; the **actually‑bound port is verified post‑bind** and is the value carried into `ProxyEntry.port`, PAC content, and the notification. Ports are user‑configurable via `SettingsRepository` (DataStore).

#### 4.3 SettingsRepository

```kotlin
interface SettingsRepository {
    val settings: StateFlow<ProxySettings>          // hot, backed by DataStore (NON-secret only)
    suspend fun setPorts(http: Int?, socks: Int?, pac: Int?)
    suspend fun setProtocolEnabled(protocol: ProxyProtocol, enabled: Boolean)
    suspend fun toggleInterface(id: String, selected: Boolean)
    suspend fun setVpnDownStrategy(strategy: VpnDownStrategy)
    suspend fun setBlockPrivateEgress(enabled: Boolean)              // U3 anti-SSRF toggle (private-LAN egress)
    suspend fun setAuth(enabled: Boolean, username: String?, password: String?)  // U3: optional; routes secret to CredentialStore

    // U4: performance — a preset SETS the underlying values; advanced users override each param.
    suspend fun applyPerformancePreset(preset: PerformancePreset)    // 省电 / 均衡 / 高吞吐
    suspend fun setMaxConnections(value: Int)                        // default 256, range 32-1024
    suspend fun setMaxConnectionsPerClient(value: Int)               // default 128, range 16-512
    suspend fun setRelayParallelism(value: Int)                      // N; default 32, range 4-64
    suspend fun setRelayBufferBytes(value: Int)                      // default 32 KiB, range 8-256 KiB
    suspend fun setIdleTimeoutSeconds(value: Int)                    // default 300 s, range 30-1800 s
}
```

`setAuth` is invoked only when the user enables the optional auth toggle (U3): it writes the **username + enabled flag** to DataStore but routes the **password to `CredentialStore`** (Keystore‑backed), never to plaintext Preferences (§8). Each per‑param setter **clamps to its documented range** and, by changing a value away from a preset's defaults, flips `PerformancePreset` to `CUSTOM` (a preset is just a named bundle of the same underlying values — U4).

---

### 5. Service‑owned ShareState & scope ownership

`ProxyForegroundService` is the **owner** of the running truth and of the long‑lived proxy `CoroutineScope`. It recomputes `ShareState` by `combine`‑ing the proxy core's port/listen status, `AccessController.clients`, the measured `EgressState` from `DefaultNetworkEgressMonitor`, and the `ProxyConfig` it was started with, and **writes the result into `ProxyStateHolder`**. The service does **not** own `detectedInterfaces` (that's `NetworkRepository`, alive even when stopped) — it only joins the *selected* ids to drive admission. This split lets Interfaces and Dashboard function fully while the service is stopped.

**Scope ownership (folds in confirmed finding):**
- The service owns `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`, **cancelled in `onDestroy()`**. The proxy lifecycle runs here, **not** in `viewModelScope` (which is cleared when the UI owner disappears). *This exact custom‑Service‑scope pattern is **NEEDS VERIFICATION** — the official coroutines page does not document building a long‑running Service scope, §13.*
- Started via `startForegroundService` + `START_STICKY`; on recreate after process death the intent is **null**, so `onStartCommand` **re‑derives config from persisted `SettingsRepository` state** rather than the intent. Configuration change (rotation) does not touch the service.
- Rotation does **not** restart the proxy (scope is in the service, not the ViewModel). Swipe‑away keeps the started FGS running (with the documented OEM caveat that some vendors still kill it). Fully‑automatic post‑process‑kill recovery is **out of V1 scope** unless the user is battery‑optimization‑allowlisted; the persistent notification (a tap is a user‑interaction exemption) is the relaunch path (per the Android 12 background‑FGS‑start rule, *Foreground services overview*).

---

### 6. UseCases & pure generators (`domain`)

V1 keeps **two** UseCases; the rest are inlined (D7).

**StartSharingUseCase** — orchestration heart. Runs the V1 start preflight in order and enforces the hard gates:

```kotlin
suspend operator fun invoke(): StartResult {
    val s = settingsRepo.settings.value
    val selected = networkRepo.detectedInterfaces.value.filter { it.id in s.selectedInterfaceIds }
    if (selected.isEmpty()) return StartResult.NoInterfaceSelected

    // U2: hard gate (mandatory at target SDK 37). A DENIED local-net permission means inbound accept
    // (and mDNS multicast) is blocked at the OS layer (clients time out); refuse to run at all.
    if (networkRepo.localNet.value == LocalNetState.DENIED) return StartResult.LocalNetworkBlocked

    // U3: auth is OPTIONAL — there is NO auth-required gate. If the share targets a non-gatewayLike
    // (joined Wi-Fi / Ethernet) interface with auth OFF, we do NOT block; the UI surfaces a clear
    // unauthenticated-network warning instead (Dashboard banner + Settings), and Start proceeds.

    val config = ProxyConfig(/* from s + selected ids; perf limits from preset/overrides;
                                blockPrivateEgress defaults FALSE (private-LAN egress allowed) */)
    return proxyRepo.start(config)   // service binds AFTER startForeground; surfaces PortConflict/BindFailed
}
```

Edge: VPN‑down `BLOCK` is enforced **inside the service**, and it must **fail closed atomically** (D4): on any default‑network change that loses VPN while in `BLOCK` mode, the service immediately stops accepting new connections **and tears down in‑flight relays** (gating new requests alone leaks during the detection gap). `WARN` starts and flags; `CONTINUE` starts silently. (The accept‑side inbound‑reply pinning under a VPN is **NEEDS VERIFICATION** on‑device, §13.)

**DiagnoseNetworkUseCase** — assembles `DiagnosticsSummary` by combining the local‑net permission state (U2), notification permission, measured `EgressState` (D4), `proxyRepo.runPortDiagnostics()`, `proxyRepo.probePacReachable(loopback, pacPort)`, the **mDNS registration status** (U1 — registered name or "not advertised"), and `PowerManager.isIgnoringBatteryOptimizations`. On‑demand only (no continuous poll — `HealthChecker` is deferred).

**Pure generators (plain functions, unit‑testable over byte arrays / values — folds in the testability finding):**
- `generatePac(entries: List<ProxyEntry>): String` — builds the `FindProxyForURL` body from currently usable selected entries. **U1: when an entry carries `mdnsName`, the chain lists `hxmyproxy.local` FIRST, then the concrete interface IP(s) as fallback, then `DIRECT`** — e.g. `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`. **NEVER emit `.local` without the IP fallback underneath** (mDNS is unreliable on Windows / some routers / some Android clients — grounded‑ref §9, design.md §17.2; a name that resolves on no client would make clients hang or fall through `DIRECT`, bypassing the VPN egress). Per‑protocol ordering within a hop: SOCKS5 first, then HTTP CONNECT, then `DIRECT`.
- `generateClientConfig(entry: ProxyEntry): String` — copyable per‑protocol string (`SOCKS5 192.168.1.34:1080`, or the PAC URL); when an `mdnsName` is present, the convenience form lists the `.local` host with the IP shown as fallback. **Host:port only — credentials are never embedded in the copied string** (U3 / clipboard finding); the password (only when optional auth is enabled) is offered via a deliberate reveal action, and sensitive clip data is marked `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+. QR is rendered from this string on the Dashboard.

Keeping parsing/CIDR/PAC as pure functions (and injecting `CoroutineDispatcher` via `DispatcherModule`) lets the bug‑prone protocol logic be unit‑tested with `ByteArrayInputStream` and a test dispatcher, no real sockets.

---

### 7. Service binding & notification (`service/`)

#### 7.1 ProxyForegroundService

```kotlin
@AndroidEntryPoint
class ProxyForegroundService : LifecycleService() {
    @Inject lateinit var proxyCoreFactory: ProxyCoreFactory
    @Inject lateinit var accessController: AccessController
    @Inject lateinit var egressMonitor: DefaultNetworkEgressMonitor
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var mdnsPublisher: MdnsPublisher         // U1: NSD advertise lifecycle (register on serve)
    @Inject lateinit var stateHolder: ProxyStateHolder        // @Singleton state transport

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)  // NEEDS VERIFICATION
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { val service get() = this@ProxyForegroundService }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }  // commands only

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharing(intent.getProxyConfigOrNull() ?: rebuildFromSettings())
            ACTION_STOP  -> stopSharing()
            ACTION_APPLY_SELECTION -> applySelection(intent)
            null -> startSharing(rebuildFromSettings())       // process-death recreate: intent is null
        }
        return START_STICKY
    }

    private fun startSharing(config: ProxyConfig) {
        // 1. Build notification + start foreground FIRST (within 5s; before any bind). D2/U5 + bind finding.
        startForeground(NOTIF_ID, ProxyNotification.build(this, ShareState.starting()),
                        FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // 2. THEN attempt binds; surface PortConflict/BindFailed as state, not a crash.
        // 3. On successful bind, advertise via NSD (U1): one registerService per active link/protocol
        //    (_http._tcp, _socks._tcp, PAC); read back the actual serviceName in onServiceRegistered.
        serviceScope.launch {
            boundProxyCore(config)
            mdnsPublisher.advertise(config)            // U1: register on serve
        }
    }

    private fun stopSharing() {
        mdnsPublisher.withdraw()                       // U1: unregisterService on stop (never outlive a live socket)
        // ... tear down listeners, cancel relays
    }

    override fun onDestroy() { mdnsPublisher.withdraw(); serviceScope.cancel(); super.onDestroy() }
}
```

- **FGS type (D2/U5 + confirmed `dataSync` finding).** Manifest declares `android:foregroundServiceType="connectedDevice"` and `startForeground()` passes `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`. The qualifying companion permission (`CHANGE_NETWORK_STATE` and/or `CHANGE_WIFI_STATE`) is declared in the manifest and is satisfied **before** `startForeground()`, or `startForeground()` throws `SecurityException` (*Foreground service types reference*). `dataSync` is **never** used — its 6h/24h cap + `RemoteServiceException` kill is fatal for an always‑on gateway (*FGS service types*; *Android 14 FGS‑types‑required*). Manifest also declares the base `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` permissions. **Fallback** = `specialUse` with a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="local network proxy gateway"/>` child + a Play‑Console justification, if the `connectedDevice` justification is rejected (NEEDS VERIFICATION — Play policy, §13).
  - **What `foregroundServiceType` means, in plain language (U5):** a *foreground service* (FGS) is how Android lets an app keep doing user‑visible, long‑running work while showing a persistent notification, so the OS does not silently kill it the way it kills ordinary background work. The `foregroundServiceType` is a label telling Android (and Google Play) **why** the service must keep running. A proxy gateway **needs an FGS** because it must hold listening sockets open and keep relaying traffic for connected LAN/hotspot devices even when the app's UI is in the background or the screen is off — that is exactly the always‑on, user‑visible work an FGS exists for. The type is **changeable later** (edit the manifest + update the Play Console declaration); changing it may trigger Play **re‑review**.
- **Lifecycle**: started (`ACTION_START`) AND bound (commands). `startForeground` is called within 5 s of `ACTION_START`, **before** binding the `0.0.0.0` listeners (*Foreground services overview*).
- **Notification updater** throttles to ≤1 update/2 s (`sample(2000)`) to avoid notification rate‑limiting. Notification visibility is `VISIBILITY_PRIVATE` for any sensitive line; it renders the recommended **interface‑IP** entry (the always‑works baseline; the `hxmyproxy.local` convenience name may be shown alongside but never instead of the IP — U1) + connection count + a Stop action, and an explicit **"VPN down — traffic blocked"** state when `BLOCK` fires (see §9.2 / VPN‑down finding).

#### 7.2 ProxyStateHolder + ProxyServiceConnection

```kotlin
@Singleton
class ProxyStateHolder @Inject constructor() {
    private val _shareState = MutableStateFlow(ShareState.stopped())
    val shareState: StateFlow<ShareState> = _shareState.asStateFlow()
    // clients (throttled) + portStates similarly
    fun publish(state: ShareState) { _shareState.value = state }   // written by the service
}

@Singleton
class ProxyServiceConnection @Inject constructor(@ApplicationContext private val ctx: Context) {
    fun start(config: ProxyConfig) =
        ContextCompat.startForegroundService(ctx, startIntent(ctx, config))
    fun stop() = ctx.startService(stopIntent(ctx))
    fun applySelection(ids: Set<String>, strategy: VpnDownStrategy) =
        ctx.startService(applyIntent(ctx, ids, strategy))
    // Optional bind() for synchronous command latency; NOT used for state transport.
}
```

The ViewModel reads live state from `ProxyStateHolder` (survives the Activity backgrounding/rotation); commands go out through `ProxyServiceConnection` via `startForegroundService`/`startService`. State transport is **not** carried over the binding, so the UI never goes stale/empty when the Activity unbinds (confirmed scope‑ownership finding).

---

### 8. Persistence (`data/datastore` + `data/secure`)

**Non‑secret settings** live in Preferences DataStore `"hxmy_settings"`:

```kotlin
object SettingsKeys {
    val HTTP_PORT          = intPreferencesKey("http_port")          // default 8080
    val SOCKS_PORT         = intPreferencesKey("socks_port")         // default 1080
    val PAC_PORT           = intPreferencesKey("pac_port")           // default 8899
    val HTTP_ENABLED       = booleanPreferencesKey("http_enabled")   // default true
    val SOCKS_ENABLED      = booleanPreferencesKey("socks_enabled")  // default true
    val PAC_ENABLED        = booleanPreferencesKey("pac_enabled")    // default true
    val SELECTED_IFACE_IDS = stringSetPreferencesKey("selected_interface_ids")  // default {}
    val VPN_DOWN_STRATEGY  = stringPreferencesKey("vpn_down_strategy")           // default "BLOCK"
    val BLOCK_PRIVATE_EGRESS = booleanPreferencesKey("block_private_egress")     // default FALSE (U3: LAN egress allowed)
    val AUTH_ENABLED       = booleanPreferencesKey("auth_enabled")   // default false (U3: auth OPTIONAL)
    val AUTH_USERNAME      = stringPreferencesKey("auth_username")   // default ""
    // NOTE: NO password key here — the secret lives in CredentialStore (U3).

    // U4: performance — preset selector + per-param overrides (a preset just sets the params below)
    val PERF_PRESET        = stringPreferencesKey("perf_preset")     // default "BALANCED" (均衡); or CUSTOM
    val MAX_CONNECTIONS    = intPreferencesKey("max_connections")            // default 256, range 32-1024
    val MAX_CONN_PER_CLIENT= intPreferencesKey("max_conn_per_client")       // default 128, range 16-512
    val RELAY_PARALLELISM  = intPreferencesKey("relay_parallelism")         // N; default 32, range 4-64
    val RELAY_BUFFER_BYTES = intPreferencesKey("relay_buffer_bytes")        // default 32768 (32 KiB), range 8-256 KiB
    val IDLE_TIMEOUT_SEC   = intPreferencesKey("idle_timeout_sec")          // default 300, range 30-1800
}

enum class PerformancePreset { POWER_SAVE, BALANCED, HIGH_THROUGHPUT, CUSTOM }  // 省电 / 均衡 / 高吞吐 / 自定义
```

> **Preset → underlying values (U4).** `均衡 (BALANCED)` = the documented defaults above. `省电 (POWER_SAVE)` = smaller caps/N/buffer (battery‑friendly). `高吞吐 (HIGH_THROUGHPUT)` = larger caps/N/buffer. A preset is **only** a named bundle of the five params; touching any single param via its setter flips `PERF_PRESET` to `CUSTOM`. All values are **runtime‑applied** at the next Start (carried into `ProxyConfig`); the live limited‑parallelism dispatcher / caps are re‑read from the started `ProxyConfig`, not the global default.

**Credentials (U3 — folds in confirmed plaintext‑credential finding).** Auth is **optional (off by default)**. When the user enables it, the auth **password is stored via `CredentialStore`** backed by Android Keystore (`androidx.security:security-crypto` `EncryptedSharedPreferences`, or a Keystore‑held key + Tink), **never** in plaintext Preferences DataStore. The credential file is excluded from auto‑backup (`dataExtractionRules`/`fullBackupContent`), and the secret is never logged or included in `ProxyConfig.toString()` / any diagnostics text. There is **no** auth‑required‑by‑default / mandatory‑auth‑beyond‑trusted‑network framing (U3 restores the "认证可选" stance); instead, sharing with auth off on a non‑trusted network surfaces a clear **unauthenticated‑network warning** (Settings + Dashboard). When auth IS enabled, the proxy core runs SOCKS5 RFC1929 user/pass and/or HTTP Basic.

```kotlin
data class ProxySettings(
    val httpPort: Int, val socksPort: Int, val pacPort: Int,
    val httpEnabled: Boolean, val socksEnabled: Boolean, val pacEnabled: Boolean,
    val selectedInterfaceIds: Set<String>,
    val vpnDownStrategy: VpnDownStrategy,
    val blockPrivateEgress: Boolean,           // U3: default false (private-LAN egress allowed)
    val authEnabled: Boolean, val username: String,   // U3: optional, default off
    // U4: performance — preset + per-param overrides (runtime-applied at Start)
    val perfPreset: PerformancePreset,         // default BALANCED (均衡)
    val maxConnections: Int,                   // default 256
    val maxConnectionsPerClient: Int,          // default 128
    val relayParallelism: Int,                 // N; default 32
    val relayBufferBytes: Int,                 // default 32 KiB
    val idleTimeoutSeconds: Int,               // default 300 s
)
```

Notes / edge cases:
- `SettingsDataStore` maps prefs with `.catch { if (it is IOException) emit(emptyPreferences()) else throw it }`.
- `VpnDownStrategy` stored as `name`; `runCatching { valueOf(it) }.getOrDefault(BLOCK)` so an unknown future value degrades to default.
- **Port validation** in the repository setter (1024–65535, no duplicates across the three) — invalid writes rejected. Since ports are now user‑configurable (port‑conflict finding), a minimal port field is exposed in the Diagnostics/error flow rather than forcing the defaults.

---

### 9. Compose screens

#### 9.1 Navigation (`ui/HxmyNavGraph.kt`)

```kotlin
sealed class Route(val path: String) {
    data object Dashboard    : Route("dashboard")
    data object Interfaces   : Route("interfaces")
    data object Diagnostics  : Route("diagnostics")
    data object Settings     : Route("settings")       // U4 presets/overrides + U3 auth/egress
    data object ClientSetup  : Route("client-setup")   // V1 static per-OS recipe sheet
}

@Composable fun HxmyNavGraph(nav: NavHostController) {
    NavHost(nav, startDestination = Route.Dashboard.path) {
        composable(Route.Dashboard.path)   { DashboardScreen(
            onOpenInterfaces  = { nav.navigate(Route.Interfaces.path) },
            onOpenDiagnostics = { nav.navigate(Route.Diagnostics.path) },
            onOpenSettings    = { nav.navigate(Route.Settings.path) },
            onOpenClientSetup = { nav.navigate(Route.ClientSetup.path) }) }
        composable(Route.Interfaces.path)  { InterfacesScreen(onBack = { nav.popBackStack() }) }
        composable(Route.Diagnostics.path) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
        composable(Route.Settings.path)    { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Route.ClientSetup.path) { ClientSetupScreen(onBack = { nav.popBackStack() }) }
    }
}
```

`HxmyApp.kt` wraps it in `HxmyTheme { Scaffold(...) }` with `enableEdgeToEdge()` in `MainActivity.onCreate`. ViewModels obtained via `hiltViewModel()`. **V1 now has 4 screens (Dashboard, Interfaces, Diagnostics, Settings)** plus the ClientSetup recipe sheet; there is **no** clients route in V1. All flows collected with `collectAsStateWithLifecycle()` (*State and Jetpack Compose*).

#### 9.2 Dashboard (`ui/dashboard`)

`DashboardUiState`:
```kotlin
data class DashboardUiState(
    val running: Boolean,
    val startInProgress: Boolean,
    val egress: EgressState,                     // D4 (measured)
    val vpnDownStrategy: VpnDownStrategy,
    val localNet: LocalNetState,                 // U2
    val clientCount: Int,
    val connectionCount: Int,
    val ipEntries: List<ProxyEntry>,             // U1: per-interface IP baseline (always listed)
    val recommendedEntry: ProxyEntry?,           // top-priority usable interface-IP entry
    val recommendedConfigText: String?,          // host:port only (no creds)
    val mdnsEntryText: String?,                  // U1: "hxmyproxy.local:1080" convenience entry, shown
                                                 // WITH the IP fallback beneath; null when not advertised
    val mdnsServiceName: String?,                // U1: actual registered name (post onServiceRegistered)
    val authEnabled: Boolean,                    // U3: drives the unauthenticated-network warning
    val showUnauthWarning: Boolean,              // U3: true when sharing with auth off (warning surface)
    val shareableInterfaces: List<ShareInterfaceRow>,
    val error: ProxyError?,
    val startGate: StartGate                     // composite of the hard gates below
)

// Replaces the soft `canStart` boolean: U2 makes local-net a HARD gate. (U3: auth is NOT a gate —
// no AuthRequiredForUntrusted; the unauthenticated case is a non-blocking warning, not a StartGate.)
sealed interface StartGate {
    data object Ready : StartGate
    data object NoInterfaceSelected : StartGate
    data object LocalNetworkBlocked : StartGate            // U2: blocking rationale, not a hint
}
```

`DashboardViewModel`:
```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    networkRepo: NetworkRepository,
    proxyRepo: ProxyRepository,
    settingsRepo: SettingsRepository,
    private val startSharing: StartSharingUseCase,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        networkRepo.detectedInterfaces,
        networkRepo.localNet,
        networkRepo.egress,
        proxyRepo.shareState,
        settingsRepo.settings,
    ) { ifaces, localNet, egress, share, settings ->
        buildState(ifaces, localNet, egress, share, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.initial())

    fun onAction(a: DashboardAction) = viewModelScope.launch {
        when (a) {
            DashboardAction.Start -> when (val r = startSharing()) {
                is StartResult.Success -> {}
                is StartResult.PortConflict          -> publishError(ProxyError.PortConflict(r.protocol, r.port))
                is StartResult.BindFailed            -> publishError(ProxyError.BindFailed(r.protocol, r.port))
                StartResult.LocalNetworkBlocked      -> publishError(ProxyError.LocalNetworkBlocked)
                StartResult.NoInterfaceSelected      -> publishError(ProxyError.NoInterfaceSelected)
                is StartResult.Failure               -> publishError(ProxyError.Unexpected(r.message))
            }
            DashboardAction.Stop -> proxyRepo.stop()
            is DashboardAction.ToggleInterface -> { settingsRepo.toggleInterface(a.id, a.selected)
                if (uiState.value.running) proxyRepo.applySelection(/* current ids */, uiState.value.vpnDownStrategy) }
            DashboardAction.DismissError -> publishError(null)
            is DashboardAction.CopyEntry -> copyHostPortOnly(a.entry)   // never copies creds (U3)
        }
    }
}
```

`DashboardScreen` composition (all stateless, taking `uiState` + `onAction`):
1. **Status card** — `StatusPill(running)`; rows: **Egress** (D4: "VPN active"/"VPN active (unvalidated)"/"No VPN" measured from the app's own default network, with a caveat that split‑tunnel/per‑app VPN configs may exclude this app), Clients = `clientCount`, Connections = `connectionCount`. When `vpnDownStrategy == BLOCK && egress == NO_VPN`, the card shows an **error tint + "Traffic blocked — VPN is down"** (matches the notification state; VPN‑down finding).
2. **Entries card (U1)** — lists the **per‑interface IP entries** (`ipEntries`, the scenario baseline that always works) — one row per selected interface IP — **plus** the `hxmyproxy.local` convenience entry (`mdnsEntryText`) when advertising, rendered **with the concrete IP shown beneath it as fallback** (never `.local` alone). The top‑priority row shows `recommendedConfigText` + `CopyableField` (copy host:port → `CopyEntry`) + QR from the same string. mDNS rows are hidden when `mdnsEntryText == null`; the IP rows are always present while running.
3. **Shareable subnets list** — `LazyColumn` rows: `Switch` (→ `ToggleInterface`), display name, `cidr`, `ifName`, status icon. Disabled switch when `status != UP`. "See all" → Interfaces.
4. **Unauthenticated‑network warning (U3)** — when `showUnauthWarning` (auth off + sharing on a non‑trusted network), a dismissible warning banner: "未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用。" with a shortcut to Settings → 启用认证. This is **informational, not a Start gate**.
5. **Primary action** — `Start sharing` / `Stop sharing`. When `startGate != Ready` the button is disabled with the specific reason; **`LocalNetworkBlocked` renders a blocking rationale block** ("Grant local‑network permission — without it, LAN clients silently time out"), not a soft caption (U2). Spinner when `startInProgress`.
6. **ErrorBanner** — renders `error` (mapped `ProxyError` → localized string), including the loud port‑conflict message naming the port.
7. Bottom row: **`How to connect a device`** → ClientSetup, `Diagnostics` → Diagnostics, and **`Settings`** → Settings.

Edge cases in state mapping: when running and the active interface disappears (Wi‑Fi drop), `ipEntries`/`recommendedEntries` recompute and the card swaps to the next usable interface‑IP entry or hides; the mDNS convenience entry re‑resolves per active link (a client resolves `hxmyproxy.local` to the IP on its own segment — U1); toggling while running calls `applySelection` (no restart).

#### 9.3 Interfaces (`ui/interfaces`)

`InterfacesUiState` / `InterfaceRow` group rows by `InterfaceType` (WIFI, HOTSPOT, USB, BLUETOOTH, ETHERNET, UNKNOWN — `UNKNOWN` is still selectable). The ViewModel combines `detectedInterfaces` + `settings` (join `isSelected`) + `localNet`. Actions: `Toggle(id, selected)`; `Refresh` → `networkRepo.refresh()`.

`InterfacesScreen`: `TopAppBar` + `LazyColumn` of toggle rows with `SectionHeader`s. Each row: type icon, `displayName`, `cidr` + `ifName` (mono), trailing `Switch` (disabled with "No address"/"Down" caption when `status != UP`). When `localNet == NEEDS_REQUEST` or `DENIED`, a **top banner** prompts the Android 17 `ACCESS_LOCAL_NETWORK` request (button → the Activity's `ActivityResultContracts.RequestPermission` launcher, then `networkRepo.refresh()`), with an explicit rationale that denial makes clients time out. No manual scan button — refresh is event‑driven; pull‑to‑refresh is courtesy.

#### 9.4 Diagnostics‑lite (`ui/diagnostics`)

`DiagnosticsViewModel`: on `init` and `Action.Rerun`, launches `DiagnoseNetworkUseCase()` in `viewModelScope` (internally hops to IO for port probes + the loopback PAC GET), `loading=true` during the run. On‑demand only.

`DiagnosticsScreen`: `TopAppBar` + `LazyColumn` of `DiagnosticItemRow`s, V1‑scoped (**a minimal mDNS row IS included**, U1):
- **Local network permission (Android 17)** — `GRANTED`/`DENIED` (ERROR, with "Request" action) / `NOT_REQUIRED` (pre‑17 devices). Detail names the timeout failure mode on denial (U2); note this permission now also covers mDNS multicast.
- **Notification permission (Android 13)** — OK/WARN; FGS runs regardless when denied (*Notification runtime permission*), so this is informational, not a gate.
- **Egress (VPN)** — measured `EgressState` from the app's own default network (D4); WARN/ERROR colored per `vpnDownStrategy`; detail notes the split‑tunnel caveat.
- **HTTP / SOCKS5 / PAC ports** — `PortState` → level; `CONFLICT` names the occupied port (port‑conflict finding).
- **PAC reachable** — loopback `GET /proxy.pac` 200? (always runs against `127.0.0.1`, unaffected by LAN permission).
- **mDNS / NSD (U1)** — a single **minimal status line**: the **actual registered service name** read back in `onServiceRegistered` (Android may rename on conflict — grounded‑ref §9) when advertising, else "not advertised (proxy stopped)". **Informational only** — never a gate, since the IP entries always work and mDNS may not resolve on some clients.
- **Battery optimization** — WARN + "Open settings" (`isIgnoringBatteryOptimizations`).

Header summary pill = `summary.worst`; bottom "Re‑run diagnostics". When stopped, port rows report `DISABLED` (grey, not error).

#### 9.5 Client setup sheet (`ui/clienthelp`) — folds in confirmed client‑setup finding

A single static Compose screen (no wizard engine) behind **"How to connect a device"**, with 3–4 short copy‑pasteable per‑platform recipes that **interpolate the current selected‑interface IP + port** (the always‑works interface‑IP baseline; the `hxmyproxy.local` convenience name is offered alongside the IP where the platform supports mDNS — U1): Windows (Settings → Proxy → paste `IP:port` or the PAC URL), macOS (Network → Proxies), iOS (Wi‑Fi → Configure Proxy / PAC), Android client (Wi‑Fi advanced proxy). Each shows the live recommended entry + the PAC URL; it is content, not architecture, and it is the cheapest addition that most increases the chance a second device reaches a working state. (This screen also defines the V1 output of the former `GenerateClientConfigUseCase`, which the review noted was undefined.)

#### 9.6 Settings (`ui/settings`) — U4 performance + U3 auth/egress

The fourth V1 screen. Backed entirely by `SettingsRepository`/DataStore (non‑secret) + `CredentialStore` (the optional auth secret). All values are **runtime‑applied at the next Start** (carried into `ProxyConfig`); changing a setting while running does not silently mutate a live dispatcher.

`SettingsUiState`:
```kotlin
data class SettingsUiState(
    // U4 performance
    val perfPreset: PerformancePreset,           // 省电 / 均衡 / 高吞吐 / 自定义(CUSTOM)
    val maxConnections: Int,                     // 32-1024
    val maxConnectionsPerClient: Int,            // 16-512
    val relayParallelism: Int,                   // N: 4-64
    val relayBufferKiB: Int,                     // 8-256 KiB
    val idleTimeoutSeconds: Int,                 // 30-1800 s
    val showAdvanced: Boolean,                   // advanced per-param overrides expander
    // U3 auth + egress
    val authEnabled: Boolean,                    // 启用认证 toggle (OFF by default)
    val username: String,
    val hasPassword: Boolean,                    // CredentialStore presence (never the secret itself)
    val blockPrivateEgress: Boolean,             // EgressGuard private-LAN toggle (OFF by default)
    val showUnauthWarning: Boolean,              // mirror of the Dashboard warning surface
    // ports / protocols (existing settings, now surfaced here)
    val httpPort: Int, val socksPort: Int, val pacPort: Int,
    val httpEnabled: Boolean, val socksEnabled: Boolean, val pacEnabled: Boolean,
    val vpnDownStrategy: VpnDownStrategy,
)

sealed interface SettingsAction {
    data class SelectPreset(val preset: PerformancePreset) : SettingsAction
    data class SetMaxConnections(val v: Int) : SettingsAction
    data class SetMaxConnPerClient(val v: Int) : SettingsAction
    data class SetRelayParallelism(val v: Int) : SettingsAction
    data class SetRelayBufferKiB(val v: Int) : SettingsAction
    data class SetIdleTimeout(val v: Int) : SettingsAction
    data class SetAuthEnabled(val enabled: Boolean) : SettingsAction
    data class SetCredentials(val user: String, val pass: String) : SettingsAction   // → CredentialStore
    data class SetBlockPrivateEgress(val enabled: Boolean) : SettingsAction
    // plus existing port/protocol/vpn-strategy actions
}
```

`SettingsViewModel` reads `settingsRepo.settings` (+ `CredentialStore.hasPassword()`), exposes one immutable `uiState: StateFlow` via `stateIn(WhileSubscribed(5000))`, and forwards each action to the matching `SettingsRepository` setter (which clamps to range and flips the preset to `CUSTOM` on any per‑param override).

`SettingsScreen` sections:
1. **性能 (Performance) — U4.** A segmented control for the three **presets** 省电 / 均衡 / 高吞吐 (selecting one calls `applyPerformancePreset`, which sets the five params). Below it, an **"高级 (Advanced)"** expander exposes the five per‑parameter overrides as sliders/steppers with the documented ranges:
   - 全局最大连接数 (global max connections) — default 256, range 32–1024
   - 每客户端最大连接数 (per‑client max connections) — default 128, range 16–512
   - 中继并行度 N (relay parallelism) — default 32, range 4–64
   - 每连接缓冲 (relay buffer) — default 32 KiB, range 8–256 KiB
   - 空闲超时 (idle timeout) — default 300 s, range 30–1800 s
   Each slider shows an **impact hint** (see the impact table in §10) and a note that a preset is just a named bundle; changing any value switches the chip to 自定义.
2. **认证 (Auth) — U3.** An **"启用认证 (Enable authentication)"** toggle, **OFF by default**. When ON, a username + password entry (password stored **encrypted** via `CredentialStore`/Keystore, never plaintext, never logged) feeds SOCKS5 RFC1929 user/pass and HTTP Basic. When OFF and a non‑trusted network is selected, the **unauthenticated‑network warning** is shown here too: "未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用。"
3. **出口安全 (Egress) — U3.** A **"阻止访问私有局域网 (block private‑LAN egress)"** toggle backing `EgressGuard`, **OFF by default** (RFC1918 egress is allowed by default for broad applicability). Caption clarifies that loopback / link‑local (169.254/fe80) / this‑host / multicast / the app's own listeners are **always** blocked regardless of this toggle.
4. **端口与协议 (Ports & protocols).** The existing port fields (validated 1024–65535, no duplicates) + per‑protocol HTTP/SOCKS5/PAC enable toggles + VPN‑down strategy, surfaced here rather than buried in Diagnostics.

---

### 10. Threading & coroutine model

| Layer | Dispatcher / scope | Notes / source |
|---|---|---|
| Compose collection | `collectAsStateWithLifecycle()` | lifecycle‑aware; *State and Jetpack Compose* |
| ViewModel `uiState` | `viewModelScope` + `WhileSubscribed(5000)` | survives rotation; *ViewModel overview* |
| UseCases / generators | caller scope, `withContext(io)` internally | stateless; pure parsing/PAC unit‑testable |
| Repositories' StateFlows | `appScope` (`SupervisorJob + Dispatchers.IO`) | singleton; `flowOn(io)` for scans |
| ConnectivityObserver / egress | `callbackFlow` / `registerDefaultNetworkCallback(handler)` | off‑main; react in `onCapabilitiesChanged`, *Reading network state* |
| Service truth recompute → ProxyStateHolder | `serviceScope` (`SupervisorJob + Dispatchers.IO`) | notification `sample(2000)`; **NEEDS VERIFICATION** (§13) |
| Proxy listeners / relay (core) | **`Dispatchers.IO.limitedParallelism(N)`** (D6) | blocking `java.net` sockets; coroutine per connection; designed in proxy subsystem |

**Proxy‑core concurrency (D6 — resolves the confirmed *"concurrency never decided"* finding).** V1 uses **Kotlin coroutines + blocking `java.net` sockets on a dedicated `Dispatchers.IO.limitedParallelism(N)` dispatcher** — **not** raw NIO selectors, **not** Netty. Each tunnel is two relay coroutines (read+write), so ~512 connections ≈ ~1024 lightweight coroutines multiplexed onto the bounded pool, **not** 1024 OS threads. `N` is the **user‑adjustable `relayParallelism` setting** (U4): default 32, range 4–64, sized to a phone's load (a hotspot rarely exceeds a few dozen concurrent tunnels). The **global max‑connections** default is now **256** (range 32–1024) and the **per‑client** cap 128 (range 16–512), all user‑configurable via Settings/DataStore (U4). **Head‑of‑line‑blocking trade‑off:** because a blocking read parks one of the `N` slots, sustained saturation at the cap can delay newly admitted tunnels; `N` is therefore sized above the realistic concurrent‑tunnel count, and the relay uses per‑direction `shutdownOutput()` + `finally{}` FD close so slots are reclaimed promptly (relay‑lifecycle finding, designed in the proxy subsystem). `@Qualifier`‑injected dispatchers (`@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`) keep all of the above test‑swappable.

#### 10.1 Performance settings — defaults, ranges, presets & IMPACT (U4)

The five limits are exposed in Settings (§9.6) both as **three presets** and as **advanced per‑parameter overrides**. A preset is only a named bundle of these values; an advanced user can override each.

| Setting | Default (均衡) | Range | 省电 / 高吞吐 | Controls | Symptom if too SMALL | Symptom if too LARGE |
|---|---|---|---|---|---|---|
| Global max connections | 256 | 32–1024 | smaller / larger | total simultaneous tunnels accepted | **REFUSED connections → broken page loads**; can also REDUCE throughput by blocking the client's own parallel connections that fill a high‑BDP 4G/5G link | more RAM/FD pressure; the count itself does not raise per‑stream speed |
| Per‑client max connections | 128 | 16–512 | smaller / larger | cap per single client IP | one device's parallel page loads stall/refuse | one greedy client can starve others |
| Relay parallelism N | 32 | 4–64 | smaller / larger | `Dispatchers.IO.limitedParallelism(N)` blocking‑slot pool | head‑of‑line blocking: new tunnels wait for a free slot under saturation | more threads parked on blocking reads; diminishing returns past realistic tunnel count |
| Per‑connection relay buffer | 32 KiB | 8–256 KiB | smaller / larger | bytes moved per read/write hop | more syscalls/lower per‑stream throughput on a fast link | linear memory growth (see budget below) for little gain past the link's BDP |
| Idle timeout | 300 s | 30–1800 s | shorter / longer | when an idle tunnel is reaped | long‑poll / keep‑alive connections dropped prematurely | idle tunnels hold slots/FDs/memory longer |

**KEY PRINCIPLE — connection count ≠ bandwidth (U4).** A too‑small connection cap causes **REFUSED connections (broken page loads)** AND can **REDUCE effective throughput** by blocking the clients' own parallel connections that fill a high‑BDP 4G/5G link (the user's link is ~100–500 Mbps). **Per‑stream throughput is governed by `N` + buffer + the link itself, not the count cap.** Tune buffer/N first to chase throughput; raising the count cap alone will not. **Memory budget example:** 256 conn × 2 directions × 32 KiB ≈ **16 MiB** of relay buffers (linear in count × buffer). **Real 500 Mbps throughput under this coroutine + blocking‑socket + `limitedParallelism` model remains NEEDS VERIFICATION** (load test on‑device; tune buffer/N before raising counts; NIO is the V2 escape hatch) — see §13.

---

### 11. Android‑version specifics touching this subsystem

- **Android 17 / `ACCESS_LOCAL_NETWORK` (U2 — day‑one mandatory; `targetSdk = 37`).** A **mandatory runtime permission from day one**, required for **BOTH inbound `accept()` AND mDNS multicast** (`224.0.0.251:5353`, the U1 convenience layer). Gates inbound accept and multicast/`NsdManager` deep in the stack — binding `0.0.0.0` cannot evade it (*Local network permission*, grounded‑ref §1/§9). A `DENIED` permission is a **hard `StartGate.LocalNetworkBlocked`** (Dashboard rationale + Diagnostics ERROR + Interfaces banner). Denial failure mode is a **client TCP timeout**, not `ECONNREFUSED`. `NEARBY_DEVICES` group. Request via `MainActivity` `ActivityResultContracts.RequestPermission`, **guarded by `Build.VERSION`** (the permission API is only touched where the OS defines it; `minSdk` stays 29 / Android 10), **before** binding/accepting and before advertising mDNS.
- **Android 14 / FGS type (D2/U5).** `connectedDevice` primary (companion `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` satisfied before `startForeground()`), `specialUse` fallback, **never `dataSync`** (*FGS types required*, *FGS service types*). Base `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` declared. The type is a Play‑Console‑declared label for *why* the service runs long; it is **changeable later** (manifest + Play declaration; may trigger re‑review) — see the plain‑language note in §7.1.
- **Android 13 / `POST_NOTIFICATIONS`.** Requested at first Start (guarded by `SDK_INT >= TIRAMISU`); if denied, **Start still proceeds** — the FGS and its listening sockets run regardless (*Notification runtime permission*); the in‑app UI is the authoritative status surface, Diagnostics shows informational, not blocking.
- **Android 12 / background‑FGS‑start.** Start from a visible Activity/user tap (exempt). `START_STICKY` recreate delivers a **null intent** → rebuild from `SettingsRepository`. Post‑kill auto‑recovery is out of V1 scope unless battery‑optimization‑allowlisted (*Foreground services overview*).
- **Android 15 edge‑to‑edge / M3 dynamic color.** `enableEdgeToEdge()` + `dynamicColorScheme`.

**First‑run permission sequencing (folds in confirmed finding + U2 hard gate):** the V1 first‑run order is (1) `POST_NOTIFICATIONS` (FGS notification), (2) `ACCESS_LOCAL_NETWORK` with a **clear rationale BEFORE starting the proxy**, and denial as a **hard block on the running state** + a blocking explanation (otherwise the user taps Start, sees "Sharing", and clients silently time out — denial manifests as a client‑side TCP timeout, not a refusal, grounded‑ref §1), (3) battery‑optimization as a **soft, dismissible** prompt. All permission‑API use is guarded by `Build.VERSION`. The Diagnostics local‑network row, when not granted, visually blocks/disables Start rather than letting the service open dead ports.

---

### 12. Forward‑compat seams (deferred, not designed here)

- **mDNS / NSD is now IN V1 (U1), as an additive convenience layer.** `MdnsPublisher` advertises `_http._tcp` (HTTP/CONNECT), `_socks._tcp` (SOCKS5), and a PAC service via `NsdManager.registerService`, reading back the actual `serviceName` in `onServiceRegistered` (Android may rename on conflict — grounded‑ref §9). The advertisement lifecycle is bound to the FGS (register on serve, `unregisterService` on stop). It advertises **per active link** so a client resolves `hxmyproxy.local` to the IP on its own segment. `ProxyEntry.mdnsName` carries the convenience name **alongside** the IP everywhere; the per‑interface IP entries remain the primary baseline. Remaining V1.1 mDNS work (e.g. discovery/resolve on the client side, richer conflict UX) is additive.
- `AccessController.clients: StateFlow<List<ClientSession>>` already exists; the deferred rich client page is a pure additive consumer plus expanding `ClientSession` with byte/rate fields **emitted on a throttled aggregate flow**, never raw per‑read (D7 / recomposition finding).
- `core/monitor` (Watchdog/HealthChecker, V1.1) subscribes to the same `portStates`/`shareState` flows Diagnostics reads on‑demand — no re‑plumbing. When it returns it must back off and distinguish recoverable (interface flap) from non‑recoverable (port permanently taken, permission revoked) faults rather than busy‑loop (Watchdog finding).
- **Settings UI is now IN V1 (U4/U3, §9.6)** — performance presets/overrides + the optional auth toggle + the `EgressGuard` private‑LAN toggle, backed by the existing `SettingsRepository`/`CredentialStore`. `Profiles` (named bundles of settings) stay a future additive consumer of the same repositories.

---

### 13. NEEDS VERIFICATION

These cannot be confirmed from the supplied official‑doc reference and must be verified before relying on them:

1. **Custom long‑running Service `CoroutineScope`** — the pattern `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by the service and cancelled in `onDestroy()` (§5, §7.1, §10) is **not** documented by *Kotlin coroutines on Android* (which does not cover `Dispatchers.Default`, `lifecycleScope`, or building a Service scope). Verify against the advanced‑coroutines / coroutines‑best‑practices docs.
2. **`Dispatchers.IO.limitedParallelism(N)` sizing + real 500 Mbps throughput under this model (D6/U4)** — the reference gives no numeric concurrency cap and notes blocking `java.net` reads occupy (do not suspend) an IO thread. Whether this **coroutine + blocking‑socket + `limitedParallelism`** model sustains **real ~500 Mbps** (the user's ~100–500 Mbps link) is **unverified**: load‑test on‑device and **tune buffer/N first** before raising the connection caps (count ≠ bandwidth — §10.1). The chosen defaults (N 32, global 256, per‑client 128, buffer 32 KiB, idle 300 s) and their ranges are starting points to validate; **NIO is the V2 escape hatch** if the blocking model cannot reach the target.
3. **Google Play acceptance of `connectedDevice` vs required `specialUse` (D2/U5)** — no documented FGS type names a proxy/gateway use case; whether Play accepts the `connectedDevice` justification or forces `specialUse` is a policy‑review judgment, determinable only via actual Play Console submission.
4. **`ACCESS_NETWORK_STATE` requirement (D4 egress read)** — *Reading network state* claims no permission is needed to read connectivity state but flags a discrepancy with the `ConnectivityManager` API reference; we declare `ACCESS_NETWORK_STATE` defensively. Confirm the exact requirement.
5. **Inbound LAN reply‑path under an active VPN (D4)** — the claim that inbound replies on accepted server sockets can be black‑holed by VPN routing unless the accepted FD is pinned to the underlying LAN `Network` (`Network.bindSocket()`) is **plausible but disputed** (the review's adversarial verdict notes Android's source‑aware routing usually delivers replies symmetrically). Treat the accept‑side pinning as an on‑device verification task + a real TCP‑handshake Diagnostics probe, not settled fact. **Invariant that is settled:** outbound egress sockets stay **unbound** and `bindProcessToNetwork()` is forbidden (D4), so any inbound pinning must be per‑server‑FD only.
6. **NSD/mDNS runtime‑permission requirement on target SDK 37 (U1/U2)** — the *NSD* page is silent on permissions, but §1/§9 of the grounded reference establish that `ACCESS_LOCAL_NETWORK` covers multicast and `NsdManager` on Android 17 target‑37, and `NEARBY_WIFI_DEVICES` may apply on Android 13/14. We treat `ACCESS_LOCAL_NETWORK` as covering mDNS advertising (registered against the same day‑one grant), but the exact NSD‑specific requirement (and any `NEARBY_WIFI_DEVICES` interaction) must be confirmed on‑device before shipping.

---

## 与文档基准/评审的修订记录

- ~~**mDNS 全面移出 V1 渲染输出 (D1 + 已确认评审"hardcoded hxmyproxy.local broken on arrival").**~~ **【已被 2026-06-22 U1 取代】** 原 D1 将 mDNS 推迟到 V1.1、V1 只渲染 IP。U1 改为：mDNS 纳入 V1 作便利层，`hxmyproxy.local` 与接口 IP **并列**输出（始终带 IP 兜底，绝不单独 `.local`），IP 方案仍为主路径。**仍保留的正确原则**：`generatePac` 在 `.local` 之后必列接口 IP 字面量兜底，避免不可解析主机致客户端挂起或回退 `DIRECT` 绕过 VPN 出口（mDNS 在 Windows/部分路由器/部分 Android 客户端不可靠）。来源：*Local network permission*（NSD 在 17 上受权限约束）。
- **FGS 类型改为 `connectedDevice`，删除 `dataSync` (D2 + 已确认评审；U5 补充通俗说明).** 草稿原为 `connectedDevice|dataSync` 位掩码；`dataSync` 的 6h/24h 上限 + `RemoteServiceException` 击杀对常驻网关致命。改为 `connectedDevice` 为主、`specialUse` 兜底；声明配套 `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` 并在 `startForeground()` **之前**满足，补齐缺失的 `FOREGROUND_SERVICE` 基权限。**【2026-06-22 U5】** 新增 `foregroundServiceType` 的通俗解释（为何代理需 FGS）及"类型事后可改、改动或触发 Play 复审"的说明（见 §7.1）。来源：*FGS types required (14)* / *FGS service types*。
- **`ACCESS_LOCAL_NETWORK` 从软 `canStart` 升级为硬门禁 (D3，门禁部分仍有效；首发目标级由 U2 修订).** 它在网络栈深处拦截**入站 accept**（双向）及组播/`NsdManager`，`0.0.0.0` 绑定无法绕过；拒绝时客户端表现为 **TCP 超时而非 ECONNREFUSED**。引入 `LocalNetState` 与 `StartGate.LocalNetworkBlocked`（阻断式说明页，非提示），按 `Build.VERSION` 在绑定前请求。**【2026-06-22 U2 修订】** 首发 `targetSdk=37`（非 36），故该权限自首日即**强制运行时权限**（入站 accept + mDNS 组播都需要），不再是 opt‑in。来源：*Local network permission*。
- **出口判定改为"本应用自身默认网络"测量 + 失败即关闭 (D4 + 已确认评审).** `EgressState` 经 `registerDefaultNetworkCallback` 读取本 UID 默认网络的 `TRANSPORT_VPN`（并以 `NET_CAPABILITY_VALIDATED` 把关），而非 `getAllNetworks()` 扫描——分流排除本应用时能正确报 `NO_VPN`。`BLOCK` 策略在 VPN 掉线时**原子地拆除在途中继**而非仅拦新连接（消除检测间隙泄漏）。出口 socket 保持 unbound、禁用 `bindProcessToNetwork()`。来源：*Reading network state*。
- ~~**默认安全姿态收紧 (D5).** 跨越非可信网络共享时默认**要求认证**…默认阻断到回环/链路本地/私网的代理出口…~~ **【认证部分被 2026-06-22 U3 取代】** 原 D5 默认要求认证、默认阻断私网出口。U3 改为：**认证可选（默认不开）** + 未认证网络警告面；`EgressGuard` 仍**默认阻断回环/链路本地/本机/组播/自身监听**，但**私网(RFC1918)出口默认放行**、提供可选 toggle 阻断。**仍保留的正确原则**：开启认证时口令经 **Keystore/EncryptedSharedPreferences** 存储、移出明文 DataStore（DataStore 仅存非密设置）；复制入口仅 host:port、凭据走显式 reveal、剪贴板标 `EXTRA_IS_SENSITIVE`；源 IP 网段准入仅为便利过滤、非安全边界（平坦 L2 上可伪造）。
- **代理核并发拍板 (D6 + 已确认"concurrency never decided"；上限值由 U4 修订).** Kotlin 协程 + 阻塞 `java.net` socket + 专用 `Dispatchers.IO.limitedParallelism(N)`，弃用 NIO selector / Netty；512 连接 ≈ 1024 轻量协程而非 1024 线程；记录队头阻塞权衡与 `N` 取值依据。**【2026-06-22 U4 修订】** N 及各上限改为用户可调设置：N 默认 32(4–64)、全局 256(32–1024)、每客户端 128(16–512)、缓冲 32 KiB(8–256)、空闲 300 s(30–1800)，并配 省电/均衡/高吞吐 三档预设（见 §9.6/§10.1）。
- **模块树裁剪、ClientSession 最小化与节流 (D7 + 已确认评审；`MdnsPublisher`/`ui/settings` 范围由 U1/U4 修订).** 移除 Room/`db`、Watchdog/HealthChecker、`ui/clients`、`ClientRepository`；UseCase 由 6 个收敛为 2 个（其余内联），纯 PAC/解析改为可单测的纯函数。`ClientSession` 去除逐字节/速率字段，字节计数在核内用原子累加、仅以 ~1Hz 节流聚合上抛，避免 Compose 重组浪费；`interfaceId` 改用 `Socket.getLocalAddress()` 分桶（重叠 RFC1918 下远端 IP 子网判断会错桶）。**【2026-06-22 U1/U4 修订】** `core/network/MdnsPublisher` 与 `ui/settings/` 不再缺席——分别因 U1（mDNS 便利层）与 U4（性能/认证/出口设置）纳入 V1。
- **代理 CoroutineScope 归属与 UI 状态传输厘清 (已确认评审).** Scope 归 `ProxyForegroundService`（`SupervisorJob`，`onDestroy` 取消），不在 `viewModelScope`；UI 实时状态经 `@Singleton ProxyStateHolder` 的 `StateFlow` 传输，**不**走 service 绑定，绑定/intent 仅发命令；`START_STICKY` 重建时 intent 为 null，从 `SettingsRepository` 重建配置；旋转不触碰 service。
- **端口冲突 / EADDRINUSE 处理补全 (已确认评审).** 先 `startForeground()` 再绑定，`BindException` 作为 `StartResult.PortConflict`/`BindFailed` 进入 `ShareState`（绝不威胁强制的 `startForeground`）；listener 设 `SO_REUSEADDR`（仅缓解 stop→restart 的 `TIME_WAIT` 竞态）；绑定后校验实际端口并写入 `ProxyEntry.port`/PAC/通知；端口可配置。
- **接口类型结构化判定 + tether 检测 (已确认评审).** 类型按"网关式占有子网 + 接口 flags"推断、名称仅作弱提示；热点/USB/BT‑PAN 经 `TetheringManager`/轮询发现（`registerNetworkCallback` 看不到这些下行接口）。
- **VPN 掉线静默断流可观测化 (已确认评审).** `BLOCK` 触发时 Dashboard + 通知显式"VPN down — traffic blocked"；首run 说明 block 语义。
- **首run 权限顺序与客户端配置指引补入 (已确认评审；目标级由 U2 修订).** 明确 POST_NOTIFICATIONS → ACCESS_LOCAL_NETWORK 硬门禁 → 电池优化软提示；新增静态分平台 ClientSetup 配置单（内插当前 IP/端口），并以此定义此前未定义的 `GenerateClientConfig` V1 输出。**【2026-06-22 U2】** 因首发 `targetSdk=37`，`ACCESS_LOCAL_NETWORK` 硬门禁自首日生效（不再限于"37 上"的后续构建）。
- **保留并 grounding 的内容.** UDF/单 `uiState: StateFlow`、`stateIn(WhileSubscribed(5000))`、`collectAsStateWithLifecycle`、ViewModel 不持 `Context`/非 `AndroidViewModel`、仓库前置代理引擎、Hilt 单例引擎、不可变 `List` 而非 `mutableListOf` 入 Compose——均与 *Recommendations for Android architecture* / *ViewModel overview* / *State and Jetpack Compose* 一致，原草稿正确部分予以保留。

### 决策更新 (2026-06-22)

以下用户决策更新 **取代** 先前的 D1/D3/D5/D6，并据此修订本文（保留所有仍正确的内容，仅改必要处）。

- **U1 — mDNS/NSD 纳入 V1，作为 IP 方案之上的便利层（取代 D1）.** 每接口 IP 入口清单 + 手动代理 + PAC 仍是**主路径**（兼容性最广，永远可用）；`hxmyproxy.local` 为**附加**项。`MdnsPublisher` 用 `NsdManager.registerService` 为 HTTP/CONNECT(`_http._tcp`)、SOCKS5(`_socks._tcp`) 与 PAC 注册，并在 `onServiceRegistered` 回读实际 `serviceName`（系统可能改名——grounded‑ref §9）；广告生命周期绑定前台服务（serve 时注册、stop 时 `unregisterService`）。PAC 链与推荐入口**先列 `hxmyproxy.local`、再列具体接口 IP 兜底**（如 `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`），**绝不**在无 IP 兜底时单独输出 `.local`（mDNS 在 Windows/部分路由器/部分 Android 客户端不可靠）。Dashboard 仍列出每接口 IP 基线；多接口按活动链路分别广告，客户端将 `hxmyproxy.local` 解析到自身网段 IP。Diagnostics 新增一行极简 mDNS 状态。mDNS 组播(224.0.0.251:5353)属本地网络访问，归 U2 强制的 `ACCESS_LOCAL_NETWORK` 覆盖。
- **U2 — 首发 `targetSdk = 37`（原 36），`minSdk` 仍 29.** `ACCESS_LOCAL_NETWORK` 自首发即为**强制运行时权限**，**入站 accept 与 mDNS 组播都需要**。V1 必走**硬门禁首run流程**：启动代理前带清晰说明请求权限；被拒则**拒绝进入运行态**并显示阻断说明（被拒表现为客户端 TCP 超时而非拒绝——grounded‑ref §1）。所有权限 API 由 `Build.VERSION` 守卫。逐 API 级清单中 targetSdk 36→37、`ACCESS_LOCAL_NETWORK` 由"deferred/opt‑in"改为首日硬要求。
- **U3 — 认证改回可选（默认不开，恢复"认证可选"）.** 默认无认证；在非可信网络共享时显示明确警告（"未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用"）。保留 anti‑SSRF `EgressGuard`：默认阻断到回环/链路本地(169.254/fe80)/本机/组播/自身监听端口的出口；**私网(RFC1918)出口默认放行**（广覆盖），并提供可选 toggle 阻断。开启认证时（SOCKS5 RFC1929 用户/密码、HTTP Basic），口令经 EncryptedSharedPreferences/Keystore 加密存储、永不明文/记录。源 IP 准入仅便利过滤、非安全边界。**移除**"默认要求认证/超出可信网络强制认证"措辞（含 `StartGate`/`StartResult`/`ProxyError` 中的 `AuthRequiredForUntrusted`）。
- **U4 — 连接/资源上限改为用户可调设置（DataStore 支撑、运行时生效）+ 三档预设.** 设置项/默认/范围：全局最大连接 256（32–1024）、每客户端最大连接 128（16–512）、中继并行度 N 32（4–64）、每连接缓冲 32 KiB（8–256 KiB）、空闲超时 300 s（30–1800 s）。预设：省电(小)/均衡(=上述默认)/高吞吐(大)；预设只是底层数值的命名捆绑，高级用户可逐项覆盖（覆盖即切 自定义）。新增 §10.1 IMPACT 表（各项控制什么 / 过小症状 / 过大症状）与**关键原则：连接数 ≠ 带宽**——过小连接上限会**拒连（页面加载坏）**并**降低有效吞吐**（阻塞客户端填满高 BDP 4G/5G 链路的并行连接，用户链路约 100–500 Mbps）；单流吞吐由 N+缓冲+链路决定，非连接数上限；内存预算示例 256×2×32 KiB ≈ 16 MiB。"本协程+阻塞+limitedParallelism 模型能否真达 500 Mbps"列入 NEEDS VERIFICATION（先压测、先调 buffer/N；NIO 为 V2 退路）。
- **U5 — 前台服务保持 `connectedDevice` 为主**（`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 在 `startForeground` 前满足合格的 `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`），`specialUse` 兜底，`dataSync` 仍排除（6h 上限）。新增 `foregroundServiceType` 的**通俗解释**：FGS 让应用在前台显示常驻通知地长跑用户可见工作而不被系统静默杀死；代理需要 FGS 是因为它要在 UI 退后台/熄屏时仍保持监听 socket 并为已连接设备中继流量；类型**事后可改**（清单 + Play Console 声明；改动可能触发 Play 复审）。Play 接受 `connectedDevice` 还是要求 `specialUse` 仍列 NEEDS VERIFICATION。
- **影响到本文的范围.** 新增第 4 个屏幕 **Settings**（§9.6：U4 预设+高级覆盖、U3 启用认证 toggle+凭据+未认证网络警告、`EgressGuard` 私网 toggle）；导航/模块树/`SettingsRepository`+DataStore schema 增加上述字段；`MdnsPublisher` 进入 `core/network` 并接入 FGS 生命周期；Dashboard 同列每接口 IP 入口与 `hxmyproxy.local` 便利入口（带 IP 兜底）；Diagnostics 增 mDNS 状态行。架构/状态模型/UDF 设计保持不变。

## NEEDS VERIFICATION

汇总见 §13（与正文一致，便于审阅）：

1. 自建常驻 Service `CoroutineScope(SupervisorJob()+Dispatchers.IO)`（`onDestroy` 取消）——官方协程文档未覆盖，需对照 advanced‑coroutines/best‑practices 核实。
2. `Dispatchers.IO.limitedParallelism(N)` 在阻塞 socket 下的并发上限与 `N` 取值、max‑connections 默认、队头阻塞表现，以及本模型能否真达 ~500 Mbps（用户 100–500 Mbps 链路，U4）——文档无数值依据，需设备压测；先调 buffer/N 再提连接上限（连接数≠带宽），NIO 为 V2 退路。
3. Google Play 接受 `connectedDevice` 还是要求 `specialUse`——属政策评审判断，需实际提审验证。
4. 仅读网络状态时 `ACCESS_NETWORK_STATE` 是否可省——*Reading network state* 与 `ConnectivityManager` API 参考有出入，默认防御性声明，需核实。
5. VPN 活动时入站回包是否会被 VPN 路由黑洞、是否需把已 accept 的 server FD 用 `Network.bindSocket()` 钉到底层 LAN `Network`——评审对抗性判定认为 Android 源地址感知路由通常对称回包，故此为设备实测 + 真实 TCP 握手 Diagnostics 探针任务，非既定事实；但出口 socket 保持 unbound、禁 `bindProcessToNetwork()` 是已定不变量。
6. NSD/mDNS 在 target SDK 37 上的运行时权限要求（U1/U2）——*NSD* 页对权限沉默；按 grounded‑ref §1/§9，`ACCESS_LOCAL_NETWORK` 覆盖组播与 `NsdManager`，Android 13/14 上 `NEARBY_WIFI_DEVICES` 或亦适用。本文按"mDNS 广告纳入首日 `ACCESS_LOCAL_NETWORK` 授权"处理，但确切 NSD 专属要求需设备核实后再发布。
