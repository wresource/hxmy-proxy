# hxmy proxy · V1 · Foreground Service, Permissions & Android 10-17 Compatibility

> 隶属 [v1-design.md](./v1-design.md)；Android 行为已对照 [v1-grounded-reference.md](./v1-grounded-reference.md) 核证，记忆性断言已剔除。

This section specifies the V1 implementation of the foreground-service host and the Android 10–17 compatibility/permission layer for **hxmy proxy** (`com.mzstd.hxmyproxy`). It is the runtime container that owns the proxy servers' lifecycle and the gatekeeper for every OS capability the proxy needs (notifications, local-network access, battery exemption). It deliberately does **not** implement a `VpnService` — hxmy proxy is a local proxy gateway that egresses through the phone's existing default network (typically the system/Google VPN). The egress-follows-the-default-network behavior is grounded: with a normally configured full-tunnel VPN, all of hxmy's forwarded outbound sockets ride the VPN automatically and no per-socket binding is required (*VPN (Android connectivity)*).

Primary files this section maps to:

- `service/ProxyForegroundService.kt` — the FGS, lifecycle owner of the proxy `CoroutineScope`.
- `service/ProxyServiceController.kt` — process-side start/stop facade used by UI/UseCases.
- `service/ProxyNotificationFactory.kt` — notification + channel construction.
- `service/ProxyServiceActions.kt` — notification action constants + `PendingIntent` builders.
- `core/net/LocalNetworkPermissionManager.kt` — `ACCESS_LOCAL_NETWORK` (Android 17) abstraction.
- `core/permission/NotificationPermissionManager.kt` — `POST_NOTIFICATIONS` (API 33) abstraction.
- `core/permission/BatteryOptimizationManager.kt` — battery-allowlist guidance.
- `core/permission/PermissionGate.kt` — aggregate readiness gate consumed before start.

> **V1 scope note (D7 / U1):** this subsystem keeps only what V1 needs. **mDNS/NSD is now IN V1** as a *convenience layer on top of* the raw-IP scheme (no longer deferred — supersedes the prior D1 deferral). The per-interface IPv4-literal entry list + manual proxy config + PAC remains the **primary, broadest-compatibility path**; mDNS is additive. The Foreground Service advertises the running listeners via `NsdManager.registerService` and binds the advertisement lifecycle to the FGS (register on serve, `unregisterService` on stop) — see §5.1. Recommended-entry / PAC output lists `hxmyproxy.local` **first**, then the concrete interface IP(s) as fallback, and **never** emits `.local` without the IP fallback underneath (mDNS is unreliable on Windows / some routers / some Android clients). `Watchdog`/`HealthChecker` and the rich per-client list are not part of this subsystem's V1 surface.

---

## 1. Architecture overview & ownership

```
UI (Compose) / StartSharingUseCase
        │  controller.start(config) / controller.stop()
        ▼
ProxyServiceController (process singleton, @ApplicationContext)
        │  ContextCompat.startForegroundService(intent)   ← only from a visible Activity
        ▼
ProxyForegroundService (the ONLY long-lived component)
   ├─ owns serviceScope: CoroutineScope(SupervisorJob + ioDispatcher)
   ├─ startForeground(notification)  ← must fire ≤ 5s after onStartCommand
   ├─ ProxyServerManager (HTTP/SOCKS5/PAC servers; not designed here)
   ├─ ConnectivityObserver subscription → notification + entry refresh
   └─ ProxyNotificationFactory (live notification updates via NotificationManager)
            │ writes
            ▼
ProxyRuntimeStateHolder (@Singleton MutableStateFlow)  ← process-level state bus
            ▲ reads via stateIn(...)
        UI ViewModel (collectAsStateWithLifecycle)
```

**Ownership rule (single-writer).** The proxy servers' root `CoroutineScope` is **owned exclusively by `ProxyForegroundService`**, created in `onCreate()` and cancelled in `onDestroy()`. Repositories/UseCases never hold the scope; they talk to the running proxy only through a **process-level singleton state holder** (`ProxyRuntimeStateHolder`, a `MutableStateFlow` injected by Hilt) that the service **writes** and the ViewModel **reads** via `stateIn(viewModelScope, WhileSubscribed(5000), …)`. State transport is **not** done by Service binding (a bound-only service is destroyed when the last client unbinds). This guarantees: (a) when the service dies, all proxy coroutines die with it (no orphan listeners holding ports); (b) UI rebinding after process recreation re-reads state rather than re-owning sockets. *(Architecture review finding "Ownership of the proxy CoroutineScope … is undefined" — confirmed; this resolves it: the scope belongs to the Service, not the ViewModel.)*

**Lifecycle survival contract (confirmed-finding-driven):**

| Event | Required behavior | Why |
|---|---|---|
| **Config change (rotation)** | Proxy untouched. The scope lives in the Service, not the Activity/ViewModel, so rotation cannot restart it. (A plain ViewModel also survives rotation via `ViewModelStore`, but we do not rely on that for the proxy.) | Service-owned scope is independent of Activity recreation. |
| **App backgrounded / Activity finished** | Proxy keeps running (started, not bound). The ViewModel may be cleared; `ProxyRuntimeStateHolder` survives because it is an app-process singleton. | `viewModelScope` is cancelled on clear; a Service-owned scope is not. |
| **Swipe-away from recents** | Proxy keeps running. `android:stopWithTask="false"`. **OEM caveat:** some OEMs still kill an unbound started FGS on task removal — documented as a known limitation, mitigated by the battery-allowlist flow (§8). | Started FGS survives task removal per AOSP, with OEM variance. |
| **Process death (low memory / OEM kill)** | `START_STICKY` recreation with a **null Intent**; `onStartCommand` re-derives full config from persisted `SettingsRepository`/DataStore. See §2.1. | A recreated sticky service receives a null Intent (*Foreground services overview*). |

The service is **started, not bound** for its primary lifecycle (survives Activity death). UI may *additionally* bind for richer live data, but binding is optional and never starts/stops the proxy and is never the survival mechanism.

---

## 2. `ProxyForegroundService` lifecycle

File: `service/ProxyForegroundService.kt`, package `com.mzstd.hxmyproxy.service`.

```kotlin
@AndroidEntryPoint
class ProxyForegroundService : Service() {

    @Inject lateinit var serverManager: ProxyServerManager
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var notificationFactory: ProxyNotificationFactory
    @Inject lateinit var runtimeState: ProxyRuntimeStateHolder      // process-shared StateFlow
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var localNetworkPermissionManager: LocalNetworkPermissionManager
    @Inject @ProxyIoDispatcher lateinit var ioDispatcher: CoroutineDispatcher  // D6, see §12

    // Lifecycle owner of ALL proxy coroutines.
    // NEEDS VERIFICATION (D6): the custom long-running-Service scope pattern below is best practice
    // NOT covered by the official "Kotlin coroutines on Android" page; verify against the
    // coroutines best-practices / advanced docs.
    private val serviceScope =
        CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineName("proxy-fgs"))

    private val started = AtomicBoolean(false)   // guards double-start
    private val stopping = AtomicBoolean(false)
    @Volatile private var lastStartId = 0

    override fun onCreate() { super.onCreate(); notificationFactory.ensureChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START      -> handleStart(intent)
            ACTION_STOP       -> { handleStop(reason = StopReason.USER); return START_NOT_STICKY }
            ACTION_REFRESH    -> refreshNotification()
            ACTION_COPY_ENTRY -> copyTopEntryToClipboard()
            else              -> { /* sticky restart with null intent → re-derive from persisted state */
                                   if (!started.get()) handleStart(intent = null) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = liveStatsBinder   // optional, nullable in V1

    override fun onDestroy() {
        serviceScope.cancel()                 // tears down every server + relay (structured concurrency)
        connectivityObserver.unregister()
        runtimeState.markStopped()
        super.onDestroy()
    }
}
```

> **Removed (D2 + confirmed `dataSync` finding):** the draft's `onTimeout(startId, fgsType)` override and the `dataSync` anchoring rationale are **deleted**. V1 does not declare `dataSync`, so there is no 6h/24h FGS timeout budget to defend against. `dataSync` (and `mediaProcessing`) are capped at **6 hours per 24-hour window for apps targeting API 35+**, after which `Service.onTimeout()` fires and the app is killed with a fatal `RemoteServiceException` if it does not `stopSelf()` within seconds — this is fatal for an always-on gateway and the cap is **tracked per-type**, so a `connectedDevice|dataSync` bitmask does **not** escape it (*Foreground service types reference*; confirmed review finding).

#### 2.1 `onStartCommand` return value

- Returns **`START_STICKY`** for the running state. If the OS kills the process under memory pressure while the user intended sharing to be on, the system restarts the service with a **null intent** (*Foreground services overview*); the `else` branch re-derives config from `SettingsRepository`/DataStore and resumes.
- Returns **`START_NOT_STICKY`** specifically on the `ACTION_STOP` path so an intentional stop is never auto-restarted.
- We **must call `startForeground()` before returning** from `onStartCommand` regardless of branch (see §4). On the null-intent restart path, `handleStart(null)` must still call `startForeground` even if it then decides to stop (post a transient "starting" notification, then `stopSelf`), otherwise the app crashes with `ForegroundServiceDidNotStartInTimeException`.

> **`START_STICKY` is NOT a background-start exemption (grounded correction).** `START_STICKY` lets the *system itself* recreate a killed service, but it does **not** exempt explicit `startForegroundService()` calls from the Android 12 background-start rule (*Foreground services overview*). The OS-driven sticky recreate is the only automatic path; see §4 and §8 for what this means for auto-recovery.

#### 2.2 `handleStart` sequence (the critical path)

```
1. if (!started.compareAndSet(false, true)) { refreshNotification(); return }
2. val config = intent?.toProxyConfig() ?: settingsRepository.snapshotConfig()   // cached/blocking-safe
3. startForeground(NOTIF_ID, notificationFactory.buildStarting(config), fgsType())  // ≤5s budget
4. serviceScope.launch {
       connectivityObserver.register()                    // default-network callback (see VpnStateDetector)
       val interfaces = connectivityObserver.currentInterfaces()
       val bindResult = serverManager.start(config, interfaces, serviceScope)   // binds 0.0.0.0 here
       if (bindResult.isFailure) { onStartFailure(bindResult); return@launch }
       runtimeState.markRunning(bindResult.entries, vpn = connectivityObserver.vpnState())
       notificationFactory.update(NOTIF_ID, buildRunningNotification())
       // ongoing collectors below
       launch { connectivityObserver.state.collect { onNetworkChanged(it) } }
       launch { serverManager.events.collect { onProxyEvent(it) } }
   }
```

**Ordering invariant (grounded, 5s rule):** `startForeground()` in step 3 happens **before** any socket bind, interface enumeration, or DataStore read that could block — never bind before `startForeground()` (*Foreground services overview*). Config snapshot in step 2 uses a cached/blocking-safe read; the real network work (including the `0.0.0.0` listen-socket binds) is deferred into `serviceScope.launch`. This ordering also means a later `BindException` (e.g. `EADDRINUSE`) can be surfaced as a typed start failure **without** threatening the mandatory `startForeground()` call (see edge cases).

`fgsType()` returns the runtime type mask passed to `ServiceCompat.startForeground(this, id, notif, type)`. The runtime mask must be a **subset** of the manifest `android:foregroundServiceType` (Android 14 requirement). The qualifying companion permission for the chosen type must already be satisfied **before** this call (D2; see §3).

`serverManager.start(...)` receives `serviceScope` so every accept-loop and relay coroutine is a child of the service job — clean shutdown is just `serviceScope.cancel()`.

#### 2.3 `handleStop` — clean shutdown

```
1. if (!stopping.compareAndSet(false, true)) return
2. notificationFactory.update(NOTIF_ID, buildStoppingNotification())   // "Shutting down…"
3. serviceScope.launch {
       serverManager.shutdownGracefully(timeout = 3.seconds)  // stop accept loops, drain/half-close
       runtimeState.markStopped()
       withContext(Dispatchers.Main) {
           ServiceCompat.stopForeground(this@…, STOP_FOREGROUND_REMOVE)  // drop notification
           stopSelf(lastStartId)
       }
   }
```

`shutdownGracefully` closes server `ServerSocket`s first (no new clients), signals in-flight relays to finish or hits the 3 s cap, then returns. Because all relays are children of `serviceScope`, `onDestroy → serviceScope.cancel()` is the backstop even if graceful drain times out (structured concurrency: cancellation propagates through the hierarchy). We use `STOP_FOREGROUND_REMOVE` so the persistent notification disappears immediately on stop. `lastStartId` is captured from the most recent `onStartCommand` to let the system reclaim the service correctly.

**Edge cases:**
- *Double start* (user taps Start twice, or START_STICKY restart races): guarded by `started` `AtomicBoolean`; second call only refreshes the notification.
- *Stop during start*: `stopping` flag short-circuits; `serverManager.start` checks `serviceScope.isActive` between bind steps and aborts if cancelled.
- *Port bind failure* (`EADDRINUSE`/`BindException`): `serverManager.start` returns a typed `StartFailure(reason = PORT_IN_USE, port = X)`; service posts an error notification, sets `runtimeState` to `Error`, and calls `handleStop(StopReason.BIND_FAILURE)`. Because `startForeground` already fired in step 3, this is a clean error state, **not** an FGS-timeout/ANR. (A `BindException` does not by itself trigger `ForegroundServiceDidNotStartInTimeException`; that exception only fires when `startForeground()` is never called within the window — which our ordering guarantees cannot happen. Set `SO_REUSEADDR` on listeners so a rapid stop/restart does not race into transient `EADDRINUSE`; a port already held by another app still fails loudly into `PORT_IN_USE`.)
- *Process death while running*: `START_STICKY` restart with null intent re-reads config; if `PermissionGate` now fails (user revoked a permission while killed), it posts a "permission required, tap to fix" notification and stops.

---

## 3. `foregroundServiceType` selection & Play Console justification (D2 / U5)

**What a Foreground Service (FGS) is and why a proxy needs one — plain language (U5).** An FGS is a service Android keeps running with a **user-visible persistent notification**, on the promise that it is doing something the user is aware of and wants to continue even when the app is not on screen. A normal background service would be paused or killed by Doze/background limits within minutes; hxmy proxy's listening sockets must stay open the whole time the user is sharing, so it **must** be an FGS. The **`foregroundServiceType`** is just a required label that tells Android (and Google Play) *which category* of always-on work the service does — e.g. `location`, `mediaPlayback`, `connectedDevice`. Android 14+ refuses to start an FGS that does not declare a type, and each type has its own permission prerequisites and (for some types) time limits. We pick the type whose rules fit an always-on LAN gateway. **The type is changeable later**: it is set in the manifest `android:foregroundServiceType` (plus the runtime mask) and re-declared in the Play Console; switching it (e.g. `connectedDevice` → `specialUse`) is a manifest + Play-Console edit and **may trigger a Play re-review**, but requires no architectural change here.

**Declared type (V1): `connectedDevice` (primary) with a documented `specialUse` fallback. `dataSync` is NOT declared.**

Apps targeting Android 14 (API 34)+ **must** declare `android:foregroundServiceType` for every FGS or `startForeground()` throws `MissingForegroundServiceTypeException`; each type requires the base `FOREGROUND_SERVICE` permission **plus** the matching `FOREGROUND_SERVICE_<TYPE>` permission, and any **per-type runtime prerequisite must be satisfied before `startForeground()`**, or `startForeground()` throws `SecurityException` (*Foreground service types are required (Android 14)*).

| Candidate | Permissions / prerequisites (grounded) | Time limit | Verdict |
|---|---|---|---|
| **`connectedDevice`** | `FOREGROUND_SERVICE_CONNECTED_DEVICE` **plus at least one of** (manifest) `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR`; OR a granted Bluetooth/UWB runtime permission; OR `UsbManager.requestPermission()`. The qualifying permission must be held **before** `startForeground()`. | **No documented time limit.** | **Primary.** We declare `CHANGE_NETWORK_STATE` **and** `CHANGE_WIFI_STATE` (both manifest, install-time) so the prerequisite is unconditionally satisfied before start. |
| **`specialUse`** | `FOREGROUND_SERVICE_SPECIAL_USE` **plus** a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>` child of `<service>`; Play-Console free-text review. | Not documented as time-limited. | **Fallback** if Play rejects the `connectedDevice` justification. |
| **`dataSync`** | `FOREGROUND_SERVICE_DATA_SYNC`; no runtime prerequisite. | **6h / 24h window (target API 35+)** → `onTimeout()` → fatal `RemoteServiceException` if not stopped; cannot start from `BOOT_COMPLETED`. | **Rejected (D2).** The cap is fatal for an always-on gateway and is **per-type**, so it cannot be hidden behind a bitmask. |
| `shortService` | `FOREGROUND_SERVICE` only. | ~3 min. | Rejected — not viable for always-on. |
| `systemExempted` | reserved for system/privileged apps. | — | Not available. |

**Why `connectedDevice` and not `dataSync`.** `connectedDevice` has **no documented timeout** and its runtime gate is satisfiable purely by declaring `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` (no real Bluetooth/USB peripheral required) (*Foreground service types reference*). We pass a **single-type** runtime mask `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` — never a `connectedDevice|dataSync` bitmask — so no `dataSync` constraint is ever in scope.

> **Whether Play accepts `connectedDevice` for a passive LAN listening server (which has no companion peripheral in the policy sense) is NEEDS VERIFICATION (policy review).** The documentation establishes that Android 14+ apps must declare the type in the Play Console with a justification and that **no documented FGS type names a proxy/gateway/server use case**, so acceptance is a policy judgment, not a documented fact. If `connectedDevice` is rejected, switch to `specialUse` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property below and the pre-written justification.

**`specialUse` fallback manifest fragment (kept ready):**

```xml
<service
    android:name=".service.ProxyForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:stopWithTask="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="local_network_proxy_gateway" />
</service>
```

**Play Console FGS declaration text (ship verbatim; one type only):**

> *connectedDevice (primary):* "hxmy proxy runs a user-initiated local HTTP/HTTPS-CONNECT/SOCKS5/PAC proxy on the phone. Other devices the user owns — connected via the same Wi-Fi LAN, the phone's hotspot, USB tethering, Bluetooth PAN, or Ethernet — connect to these local ports to reach the internet through the phone. The foreground service keeps the proxy ports open while the user is actively sharing connectivity. The app requires a live network connection to those connected devices, declares `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`, the user explicitly starts and stops sharing, and a persistent notification with a Stop action is always shown."
>
> *specialUse (fallback subtype `local_network_proxy_gateway`):* "A long-running local network proxy gateway that keeps listening sockets open so the user's other LAN/tethered devices can route traffic through the phone for the duration the user keeps sharing enabled. This does not match the standard FGS types; it is a persistent local network server."
>
> Data handling: "hxmy proxy does not decrypt TLS, does not perform MITM, and does not log full URLs or request bodies — only connection metadata for diagnostics."

> **mDNS/NSD advertising is bound to this FGS lifecycle (U1).** The `NsdManager.registerService` advertisements for the proxy listeners (§5.1) are registered when the service starts serving and `unregisterService`d on stop, so the `.local` advertisement can never outlive a live listening socket. The advertisement is owned by `ProxyForegroundService` exactly like the listening sockets are. mDNS multicast (`224.0.0.251:5353`) is local-network access and is covered by the same mandatory `ACCESS_LOCAL_NETWORK` (§7.2) — no separate permission.

---

## 4. `startForeground` timing rules across API levels

File: `service/ProxyForegroundService.kt` (timing logic), `service/ProxyNotificationFactory.kt` (notification build).

**The 5-second rule (all API levels we ship).** After `Context.startForegroundService()`, the service **must** call `startForeground()`/`ServiceCompat.startForeground()` within **5 seconds**; otherwise "the system stops the service and declares the app to be ANR" and throws `RemoteServiceException$ForegroundServiceDidNotStartInTimeException` (*Foreground services overview*). Therefore `startForeground()` is the **first heavy thing** `handleStart` does — before any socket bind, interface enumeration, or blocking DataStore read (§2.2). We go through `ServiceCompat.startForeground(this, NOTIF_ID, notification, typeMask)` so the typed overload is dispatched uniformly across API 29–36.

**Android 12 (API 31) background-start restriction (`ForegroundServiceStartNotAllowedException`).** Apps targeting API 31+ "can't start foreground services while the app is running in the background, except for a few special cases" (*Foreground services overview*). The documented general-purpose exemptions include: transition from a user-visible state/activity, user action on an app UI element, the user having disabled battery optimization (Doze allowlist), `SYSTEM_ALERT_WINDOW`, plus boot broadcasts, high-priority FCM, exact alarms, geofencing, system roles, and Companion Device Manager. hxmy proxy mitigations:

- The proxy is **always user-initiated from a visible Activity** (Dashboard "Start sharing" button) → app is foreground → start is allowed. This is the normal path.
- Notification actions (`ACTION_STOP`, `ACTION_REFRESH`, `ACTION_COPY_ENTRY`) target an **already-running** service, so they are not new background starts.
- The **`START_STICKY` system recreate** is performed by the OS itself (not via `startForegroundService()`), so it is not subject to the rule.
- `ProxyServiceController.start()` wraps `startForegroundService()` in `try/catch` matched by class **simple-name** `ForegroundServiceStartNotAllowedException` (API 31+, no compile dependency). On catch it does **not** crash; it sets `ProxyRuntimeState = ForegroundLaunchUnavailable` and the UI shows "Open the app to start sharing." We never attempt to start the proxy from a `BroadcastReceiver` or a backgrounded context.

> **Auto-restart honesty (confirmed finding "Android 12+ restriction breaks auto-recovery").** A `NetworkCallback` firing in the background does **not** grant a background FGS-start exemption, and an in-process watchdog cannot resurrect a **killed** process. Fully-automatic post-process-kill recovery is therefore constrained on Android 12–17 and is possible only when the app is **battery-optimization allowlisted** (Doze allowlist ⇒ exempt from the background-start restriction) and relaunched via a `WorkManager`/exact-alarm/high-priority-FCM trigger, or relaunched by a **user notification tap** (a user-interaction exemption). For non-allowlisted users, V1 documents notification-tap (one-tap) relaunch as the fallback. We do **not** claim silent perpetual background revival. (In-process recovery while the FGS is still alive — e.g. rebinding a dropped listener — is permitted and does not call `startForegroundService()`; that path is not affected by this restriction.)

**Android 14 (API 34).** `startForeground()` with a type whose required permission/prerequisite is missing throws `SecurityException`/`MissingForegroundServiceTypeException`. The `connectedDevice` companion permission (`CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`) is a manifest install-time grant and is always present, and `PermissionGate` (§7) is checked before `controller.start()`, so the typed start succeeds. We pass exactly `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`.

**Per-API timing summary:**

| API | Rule enforced |
|---|---|
| 26–30 (10–11) | 5 s `startForeground` window; typed overload available since 29 (covers our entire range). |
| 31–32 (12/12L) | + background-start restriction; only start from foreground/Activity or a documented exemption. `FLAG_IMMUTABLE` mandatory on PendingIntents. |
| 33 (13) | + notification hidden from the drawer if `POST_NOTIFICATIONS` denied — **but the FGS still runs** (§5). |
| 34 (14) | + runtime type prerequisite validated at `startForeground`; manifest type mandatory; Play-Console FGS justification required. |
| 35 (15) | No `dataSync`/`mediaProcessing` declared ⇒ no FGS runtime budget; nothing to handle here. |
| 36 (16) | `ACCESS_LOCAL_NETWORK` opt-in on Android 16 ("local network access is open" by default). Because **we target SDK 37**, the hard-gate request still runs and is honored on a 16 device that grants it; behavior-parity testing here. |
| 37 (17) | **Target SDK 37 (V1 ships here, U2):** `ACCESS_LOCAL_NETWORK` is **mandatory** and gates both the inbound `accept()` and mDNS multicast (§7.2). The hard-gate first-run flow requests it before start; denial → refuse running state. |

---

## 5. Persistent notification (content + V1 actions)

Files: `service/ProxyNotificationFactory.kt`, `service/ProxyServiceActions.kt`.

**Channel:** id `hxmy_proxy_running`, `IMPORTANCE_LOW` (no sound/vibration, but visible & non-dismissable while FGS). Created in `onCreate` via `ensureChannel()` (minSdk is 29 so always create). Name: "Proxy running", description: "Shown while hxmy proxy is sharing your connection."

**Content (running state) — always carries the raw IPv4 literal; `.local` is shown only as an additive hint (U1):**

```
Title:   hxmy proxy · Sharing
Text:    SOCKS5 192.168.1.34:1080   (top recommended ProxyEntry — concrete IP, always works)
Sub /    VPN: detected · 3 clients          (collapsed line)
Big:     SOCKS5  192.168.1.34:1080   (or hxmyproxy.local:1080)
         HTTP    192.168.1.34:8080   (or hxmyproxy.local:8080)
         PAC     http://192.168.1.34:8899/proxy.pac
         VPN detected · ↓2.4 MB/s ↑320 KB/s
```

The recommended entry string is built from `ProxyRuntimeState.recommendedEntries` (current usable interface, top priority). The notification's primary `Text` line always carries the **live selected-interface IPv4 address** so it works even when mDNS does not; the `hxmyproxy.local` host (if NSD registered successfully) is shown only as an additive "or" hint, **never alone** (U1 — `.local` is unreliable on Windows / some routers / some Android clients). When VPN-down policy = block and VPN is absent, the title becomes `hxmy proxy · Blocked (no VPN)` and a high-visibility state is raised the instant `TRANSPORT_VPN` disappears (see VpnStateDetector subsystem). Small icon: `R.drawable.ic_stat_proxy`. `setOngoing(true)`, `setShowWhen(false)`, `setOnlyAlertOnce(true)`. The notification line carrying host:port is rendered with `VISIBILITY_PRIVATE` so the entry is not shown on a secure lock screen by default.

**V1 actions (exactly two — keep focused; "Open diagnostics" and "Block all" are deferred):**

1. **Stop** — `PendingIntent` → service `ACTION_STOP`.
2. **Copy entry** — `PendingIntent` → service `ACTION_COPY_ENTRY` (puts the top `ProxyEntry` **host:port** string on the clipboard via a no-UI `ClipboardManager` call inside the service). The copied entry contains **no credentials** (U3 — even when optional auth is enabled); the clip is marked `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+ to suppress the IME preview. On API < 33 a toast confirms; on 33+ the system shows its own copy confirmation.

Tapping the notification body → `contentIntent` opens the Dashboard `Activity` (`PendingIntent.getActivity`). This tap is also the **user-interaction relaunch path** referenced in §4.

```kotlin
// service/ProxyServiceActions.kt
object ProxyServiceActions {
    const val ACTION_START       = "com.mzstd.hxmyproxy.action.START"
    const val ACTION_STOP        = "com.mzstd.hxmyproxy.action.STOP"
    const val ACTION_REFRESH     = "com.mzstd.hxmyproxy.action.REFRESH"
    const val ACTION_COPY_ENTRY  = "com.mzstd.hxmyproxy.action.COPY_ENTRY"
    const val EXTRA_CONFIG       = "extra.config"

    fun stopIntent(ctx: Context): PendingIntent = service(ctx, ACTION_STOP, reqCode = 1)
    fun copyEntryIntent(ctx: Context): PendingIntent = service(ctx, ACTION_COPY_ENTRY, reqCode = 2)

    private fun service(ctx: Context, action: String, reqCode: Int): PendingIntent =
        PendingIntent.getService(
            ctx, reqCode,
            Intent(ctx, ProxyForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE  // IMMUTABLE req. API 31+
        )
}
```

`FLAG_IMMUTABLE` is mandatory on API 31+; we always set it (harmless on lower). `NOTIF_ID = 0x4859` (non-zero — zero is rejected by `startForeground`).

**Live updates:** notification is refreshed (not recreated) via `NotificationManagerCompat.notify(NOTIF_ID, ...)` on network change, client-count change, and a **throttled (≥1 s) throughput tick** collected in `serviceScope`. Throughput is sampled from atomics, never written per-read (D7). Channel `IMPORTANCE_LOW` keeps refreshes silent.

#### 5.1 NSD / mDNS advertising (U1) — bound to the FGS lifecycle

mDNS is the **convenience layer on top of** the raw-IP scheme, not a replacement (§ scope note). The Foreground Service advertises each running listener via `NsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)` so the user's other LAN/hotspot devices can reach the gateway at `hxmyproxy.local` instead of typing an IP. Per grounded-ref §9:

- **Service types:** `_http._tcp` for the HTTP/CONNECT listener, `_socks._tcp` for SOCKS5, and a service for the PAC endpoint. Each advertised endpoint gets its own `NsdServiceInfo` + `registerService` with `setPort(<that listener's port>)`.
- **Name read-back (mandatory):** in `onServiceRegistered`, read back and store `serviceInfo.serviceName` — **Android may rename on conflict** (e.g. append " (1)") and the requested name is not guaranteed (grounded-ref §9). The UI/entry list shows the actual registered name.
- **Lifecycle binding:** register on serve (after the listening sockets bind in `handleStart`), `unregisterService(registrationListener)` on stop / `onDestroy`, so the advertisement never outlives a live socket. The NSD registration is owned by `ProxyForegroundService` exactly like the sockets and the FGS notification.
- **Multi-interface:** advertise per active link. A client resolves `hxmyproxy.local` to the IP on **its own segment** — mDNS is link-local and reaches only the L2 segment the phone shares with that client (grounded-ref §9), so a client on Wi-Fi and a client on the hotspot each resolve `.local` to the interface IP they can actually reach.
- **Recommended-entry / PAC fallback rule:** the dashboard recommended-entry list and PAC chains list `hxmyproxy.local` **first**, then the concrete interface IP(s) as fallback — e.g. `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`. **Never emit `.local` without the IP fallback underneath.** The dashboard still lists every per-interface IP entry (the scenario-based baseline that always works).
- **Permission:** mDNS multicast (`224.0.0.251:5353`) is local-network access and is covered by the **same mandatory `ACCESS_LOCAL_NETWORK`** (§7.2, U2) — no separate runtime permission for advertising. `ACCESS_LOCAL_NETWORK` is documented to apply to framework APIs such as `NsdManager` and to multicast (grounded-ref §1).

> **NEEDS VERIFICATION:** the exact NSD-specific runtime-permission requirement (whether `NEARBY_WIFI_DEVICES` applies on Android 13/14 in addition to `ACCESS_LOCAL_NETWORK` on target SDK 37+) is not stated by the NSD page; confirm against the local-network / nearby-devices docs (grounded-ref §9).

---

## 6. `AndroidManifest.xml` block

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.mzstd.hxmyproxy">

    <!-- Core networking egress -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

    <!-- Foreground service (base + connectedDevice type, Android 14) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <!-- Fallback type permission, declared so a specialUse switch needs no new release-blocking change: -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

    <!-- connectedDevice qualifying companion permissions: MUST be present before startForeground
         (FGS prerequisite for the connectedDevice type), and also qualify the listener/multicast use. -->
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

    <!-- Notifications (runtime, API 33). FGS runs even if denied; notification is just hidden. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Local network access (runtime). MANDATORY day-one because V1 targets SDK 37 (U2): gates BOTH the
         inbound accept() of the core relay AND the mDNS/NsdManager multicast (224.0.0.251:5353) advertising.
         maxSdk omitted intentionally; inert/ignored on older OS. -->
    <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />

    <!-- Battery-optimization allowlist prompt (guidance flow, §8). Also unlocks background FGS restart. -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".HxmyProxyApp"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.HxmyProxy">

        <service
            android:name=".service.ProxyForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice"
            android:stopWithTask="false" />
        <!-- stopWithTask=false: keep sharing when the user swipes the app from recents (OEM caveat in §1). -->
        <!-- specialUse fallback: change this attr to "specialUse" and add the
             PROPERTY_SPECIAL_USE_FGS_SUBTYPE <property> child shown in §3 if Play rejects connectedDevice. -->

    </application>

    <!-- Hardware features optional so the app installs on devices lacking them. -->
    <uses-feature android:name="android.hardware.wifi" android:required="false" />
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />
    <uses-feature android:name="android.hardware.usb.host" android:required="false" />
</manifest>
```

Notes:
- **`dataSync` is gone (D2):** neither `FOREGROUND_SERVICE_DATA_SYNC` nor `dataSync` appears anywhere. The base `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus the qualifying `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` are the complete FGS permission set, satisfying the Android-14 prerequisite before `startForeground()` (*Foreground service types are required (Android 14)*).
- `ACCESS_LOCAL_NETWORK` is declared unconditionally and is a **day-one mandatory runtime permission** (V1 targets SDK 37, U2): it gates both the inbound `accept()` and the mDNS `NsdManager` multicast advertising (§5.1, §7.2). The platform ignores an unknown permission name at install on older OS, so no `tools:targetApi` is needed in the manifest. The **runtime request** is what must be version-guarded (§7.2).
- We do **not** request `ACCESS_FINE_LOCATION`. V1 interface detection uses `ConnectivityManager`/`NetworkInterface` and does not read SSIDs, so it avoids location-gated Wi-Fi APIs.
- No `BIND_VPN_SERVICE`/VPN `<service>` — by design (no `VpnService` in V1). hxmy cannot opt its forwarded traffic out of an active VPN and does not try to (*VPN (Android connectivity)*).

---

## 7. Runtime permission flows

Aggregator: `core/permission/PermissionGate.kt` produces a `PermissionGateState` consumed by the Dashboard before enabling "Start sharing."

```kotlin
// core/permission/PermissionGate.kt
data class PermissionGateState(
    val notifications: PermStatus,        // POST_NOTIFICATIONS — advisory
    val localNetwork: PermStatus,         // ACCESS_LOCAL_NETWORK — HARD GATE on target SDK 37+
    val batteryUnrestricted: Boolean,     // advisory only
) {
    // Local-network denial is a HARD block equal in weight to the FGS itself (see §7.2).
    val canStart: Boolean get() = localNetwork != PermStatus.DENIED_BLOCKING
    // Notifications denied does NOT block start (FGS still runs); it's advisory.
}

enum class PermStatus { GRANTED, DENIED, DENIED_BLOCKING, NOT_APPLICABLE }
```

#### 7.1 `POST_NOTIFICATIONS` (API 33+) — `core/permission/NotificationPermissionManager.kt`

```kotlin
class NotificationPermissionManager(private val app: Context) {

    fun status(): PermStatus = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            PermStatus.NOT_APPLICABLE                                   // granted implicitly < 33
        ContextCompat.checkSelfPermission(app, POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED -> PermStatus.GRANTED
        else -> PermStatus.DENIED
    }

    fun requestPermissionOrNull(): String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
}
```

Flow: on first entry to Dashboard (API ≥ 33), if `DENIED` and not previously prompted, launch the system dialog via `ActivityResultContracts.RequestPermission` **in context** (guarded by `SDK_INT >= TIRAMISU`). If permanently denied (`shouldShowRequestPermissionRationale == false` after a denial), show an inline card linking to `Settings.ACTION_APP_NOTIFICATION_SETTINGS`.

**What breaks if denied (grounded).** Apps "don't need to request the `POST_NOTIFICATIONS` permission in order to launch a foreground service" — the FGS, and therefore the `0.0.0.0` listening sockets, **runs regardless** (*Notification runtime permission*). On Android 13+ the FGS notice is **hidden from the notification drawer but still visible in the Task Manager**, and other non-exempt channels are suppressed app-wide. Consequences: (a) the user has no Stop/Copy surface from the shade — they must reopen the app (or use Task Manager) to stop; (b) secondary alerts (errors, egress lost) won't surface. Therefore denial is **advisory, not blocking** (`canStart` stays true), and **the in-app UI is the authoritative status/control surface** as the fallback. The Diagnostics page surfaces "Notifications: off — you won't see the persistent controls."

#### 7.2 `ACCESS_LOCAL_NETWORK` (mandatory day-one — V1 targets SDK 37) — `core/net/LocalNetworkPermissionManager.kt` (U2, supersedes D3)

`ACCESS_LOCAL_NETWORK` is a runtime permission in the **`NEARBY_DEVICES`** group. It gates **all traffic to and from local-network addresses in BOTH directions**, including **accepting incoming TCP connections** AND **mDNS/multicast** (`224.0.0.251:5353`), and is "implemented deep in the networking stack … apply[ing] to all networking APIs" including `NsdManager`, so **binding/listening on `0.0.0.0` cannot evade it** (*Local network permission*; grounded-ref §1, §9). Because hxmy proxy's entire purpose is accepting LAN client connections (LAN peers at `192.168.x.x`, hotspot/USB-tether peers at `192.168.x.x`/`192.168.42.x`, all of which are local-network addresses) **and** advertising itself over mDNS (§5.1), denial breaks the core relay's `accept()`/reply **and** the `.local` discovery. This is therefore a **hard gate equal in weight to the FGS itself** (confirmed critical review finding), not a soft "reachability" boolean.

**Version gating (grounded) and V1 stance (U2):** On Android 16 (SDK 36) it is **opt-in** and "local network access is open" by default. On **Android 17 with target SDK 37+** it is **mandatory** — "Local network is blocked by default for all apps that update their target SDK." Apps targeting Android 16 or lower are unrestricted (*Behavior changes: Android 17* / *Local network permission*). **V1 ships targeting SDK 37 (U2), so `ACCESS_LOCAL_NETWORK` is a mandatory day-one runtime permission** (required for inbound `accept()` AND mDNS multicast); minSdk stays 29. On an Android 16 device it is still requested and honored if granted; on devices below 17 the manager reports `NOT_APPLICABLE` (the permission is inert there). All permission-API use is guarded by `Build.VERSION`. The mDNS/picker (`FLAG_SHOW_PICKER`) exemption is useless here: hxmy needs broad persistent inbound listening for arbitrary unknown peers, exactly the case requiring the full permission.

**Failure mode when denied:** inbound TCP "will typically result in a **timeout** error" (not `ECONNREFUSED`); UDP yields `EPERM`; the NDK `android_getnetworkblockedreason()` returns `ANDROID_NETWORK_BLOCKED_REASON_LNP`. The client just hangs, with no on-phone error unless we surface it — so we map this to a blocked state explicitly.

**Compile-time problem.** `Manifest.permission.ACCESS_LOCAL_NETWORK` and the Android-17 `Build.VERSION_CODES` constant may not exist in an older `compileSdk`. **Resolution:** use the **string literal** for the permission name and a **numeric SDK check** (`>= 37`) instead of named symbols. (Android 16/SDK 36 is the opt-in tier; mandatory enforcement begins at target SDK 37, so the *blocking* gate keys off 37.)

```kotlin
// core/net/LocalNetworkPermissionManager.kt
class LocalNetworkPermissionManager(private val app: Context) {

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"   // string literal, no symbol dep
        const val ANDROID_17_API = 37                                      // numeric, no named constant
    }

    /** Build's compiled target; V1 targets SDK 37 (U2), so this is true in the shipping build. */
    private val targetsSdk37OrAbove: Boolean =
        app.applicationInfo.targetSdkVersion >= ANDROID_17_API

    /** Hard-gate applies on a device running Android 17+ AND when we target SDK 37+ (both true in V1). */
    fun isApplicable(): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17_API && targetsSdk37OrAbove

    fun status(): PermStatus = when {
        !isApplicable() -> PermStatus.NOT_APPLICABLE              // device < 17: permission inert/unrestricted
        ContextCompat.checkSelfPermission(app, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED -> PermStatus.GRANTED
        else -> PermStatus.DENIED_BLOCKING                        // blocks Start; LAN accept + mDNS would fail
    }

    fun requestPermissionOrNull(): String? = if (isApplicable()) PERMISSION else null

    fun openAppDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
               Uri.fromParts("package", app.packageName, null))
}
```

**Hard-gate first-run flow (U2) — rationale → request → refuse-on-deny → diagnostics.** This is a mandatory day-one V1 flow, not a future build's concern:
1. **Rationale before request.** On first run / first entry to Dashboard, before the proxy can be started, if `status()` is `DENIED_BLOCKING`, "Start sharing" is **disabled** and a prominent **pre-permission rationale** card is shown (this permission is non-obvious and easy to deny): "hxmy proxy needs Local network access so other devices on your Wi-Fi/hotspot can connect, and so it can advertise itself on the network (mDNS). Without it, clients silently time out — they never see a refusal." We request **with this rationale, before any socket bind/accept** (grounded-ref §1 mandates request before listening).
2. Tap "Grant" → `RequestPermission` with `PERMISSION`.
3. Granted → re-evaluate gate → Start enabled.
4. **Refuse the running state on denial.** If denied, the gate **refuses to enter the running state at all** (`canStart=false`); the card switches to a **blocking explanation** and, when permanently denied, an "Open settings" action → `openAppDetailsIntent()`. We never start into ports that silently accept nothing.
5. **Diagnostics surfaces the real cause.** Because denial manifests as a **client-side TCP timeout, not an explicit refusal** (grounded-ref §1 — `accept()`/reply is silently dropped, no `ECONNREFUSED`), the Diagnostics page explicitly attributes any unreachable-client symptom to the missing permission: "Local network access: denied — clients will silently time out; nothing is wrong with their config." This turns an otherwise-invisible failure into an actionable message.

We must **request the permission before binding/accepting sockets**, and the gate must **refuse to enter the running state at all** when denied. All permission-API use is guarded by `Build.VERSION`. On devices below Android 17 this manager reports `NOT_APPLICABLE` and never blocks. (Runtime denial detection — e.g. mapping the NDK `LNP` reason or the timeout signature to the blocked error class — is a defensive backstop noted under NEEDS VERIFICATION.)

#### 7.3 Aggregation order

`PermissionGate.refresh()` calls both managers + `BatteryOptimizationManager.isIgnoringOptimizations()` and emits `PermissionGateState`. The Dashboard's Start button observes `state.canStart`. First-run / on-tap request order: **(1) `POST_NOTIFICATIONS` (so the FGS notification can show) → (2) `ACCESS_LOCAL_NETWORK` (day-one mandatory, U2), treated as a HARD block on Start with the rationale card → (3) battery-optimization as a soft, dismissible prompt.** The blocking local-network gate is checked **both before Start and surfaced at runtime**, so V1 (targeting SDK 37) cannot reach a "Sharing" state with unreachable ports or a dead mDNS advertisement (confirmed permission-sequencing finding).

---

## 8. Battery-optimization allowlist guidance

File: `core/permission/BatteryOptimizationManager.kt`.

```kotlin
class BatteryOptimizationManager(private val app: Context) {
    private val pm get() = app.getSystemService(PowerManager::class.java)

    fun isIgnoringOptimizations(): Boolean =
        pm.isIgnoringBatteryOptimizations(app.packageName)

    fun requestAllowlistIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${app.packageName}"))

    fun openSettingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
```

Guidance flow: **advisory, never blocking.** The Diagnostics page shows "Battery optimization: ON — Android or your manufacturer may kill sharing in the background." A "Fix" button launches `requestAllowlistIntent()`; on `ActivityNotFoundException` (some OEMs) fall back to `openSettingsListIntent()`.

This allowlist does double duty: per the *Foreground services overview* exemption list, an app the **user has exempted from battery optimization is exempt from the Android 12 background-FGS-start restriction**. So the allowlist is the supported path that lets a `WorkManager`/exact-alarm/FCM trigger legally relaunch the FGS after an OEM/process kill (§4) — it is the closest V1 gets to automatic post-kill recovery for non-root devices. We surface it because Play policy restricts `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to legitimate long-running cases (the §3 justification covers our continuously-relaying proxy). We do **not** auto-prompt aggressively; we show it once after the first successful start and thereafter only in Diagnostics. (OEM-specific auto-start managers — Xiaomi/Huawei/etc. — are deferred beyond V1's generic flow.)

---

## 9. `ProxyServiceController` (process-side facade)

File: `service/ProxyServiceController.kt`. Injected (`@Singleton`) into UseCases/UI so callers never touch `Intent`/`startForegroundService` directly.

```kotlin
@Singleton
class ProxyServiceController @Inject constructor(
    @ApplicationContext private val app: Context,
    private val permissionGate: PermissionGate,
) {
    sealed interface StartResult {
        data object Started : StartResult
        data class Blocked(val state: PermissionGateState) : StartResult        // local-network denied (hard)
        data object ForegroundLaunchUnavailable : StartResult                   // API31 bg restriction
    }

    fun start(config: ProxyConfig): StartResult {
        val gate = permissionGate.refresh()
        if (!gate.canStart) return StartResult.Blocked(gate)                     // ACCESS_LOCAL_NETWORK hard gate
        val intent = Intent(app, ProxyForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_CONFIG, config.toBundle())
        return try {
            ContextCompat.startForegroundService(app, intent)   // typed startForeground happens inside service
            StartResult.Started
        } catch (e: Exception) {       // ForegroundServiceStartNotAllowedException on API 31+
            if (Build.VERSION.SDK_INT >= 31 &&
                e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException")
                StartResult.ForegroundLaunchUnavailable
            else throw e
        }
    }

    fun stop() = app.startService(
        Intent(app, ProxyForegroundService::class.java).setAction(ACTION_STOP))
}
```

`ForegroundServiceStartNotAllowedException` is matched by **class simple-name** to avoid a compile dependency on the API-31 type; `start()` is always invoked from a visible Activity so the catch is a safety net, not the norm.

---

## 10. minSdk / targetSdk recommendation

- **minSdk = 29** (Android 10). Matches the "Android 10–17" promise; the typed-FGS `startForeground` overload and modern `NetworkCallback` ergonomics are present from 29.
- **targetSdk = 37 for the V1 release** (Android 17, U2). This makes `ACCESS_LOCAL_NETWORK` a **mandatory day-one runtime permission** (required for both the inbound `accept()` and the mDNS multicast, §7.2). V1 ships the **hard-gate first-run flow** (rationale → request → refuse running state on deny → diagnostics attributes the TCP-timeout cause) so a runtime denial cannot silently break the core relay or the `.local` advertisement. §7.2 is fully wired and `Build.VERSION`-guarded.
- **compileSdk = latest available (≥ 37).** The code does not depend on Android-17 permission/constant **symbols** (string literal + numeric `>= 37` checks in `LocalNetworkPermissionManager`), so it compiles on any `compileSdk ≥ 33` and behaves correctly at runtime; build with `compileSdk ≥ 37` so the SDK-37 target resolves.

---

## 11. Per-API-level checklist (10–17)

| API (Android) | Required handling in this subsystem |
|---|---|
| **29–31 (10–12)** | FGS base flow: `FOREGROUND_SERVICE`, 5 s `startForeground` window, typed `ServiceCompat.startForeground`. On **API 31 (12)**: foreground-only start (catch `ForegroundServiceStartNotAllowedException`; `FLAG_IMMUTABLE` on all PendingIntents). `POST_NOTIFICATIONS`/`ACCESS_LOCAL_NETWORK` report `NOT_APPLICABLE`. Battery-allowlist guidance (also unlocks background restart). |
| **33 (13)** | Request `POST_NOTIFICATIONS` in context (advisory). FGS runs even if denied; notification hidden from drawer (visible in Task Manager) → diagnostics warning; in-app UI is authoritative. Notification channel mandatory (already created). |
| **34 (14)** | `android:foregroundServiceType="connectedDevice"` mandatory; runtime mask = `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`; companion `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` present before `startForeground`. Play-Console FGS justification submitted (§3). **No `dataSync`.** |
| **35 (15)** | Nothing FGS-timeout-related to handle (no `dataSync`/`mediaProcessing` declared, so no 6h/24h budget). |
| **36 (16)** | `ACCESS_LOCAL_NETWORK` is opt-in at the OS level, but because **V1 targets SDK 37** the hard-gate request still runs and is honored if the user grants it on a 16 device. Behavior-parity testing; verify socket reachability and mDNS advertising. |
| **37 (17)** | **V1 targets SDK 37 (U2).** `ACCESS_LOCAL_NETWORK` is **mandatory day-one**: request it at runtime (string literal + numeric `>= 37` guard) **before** binding/accepting **and before NSD/mDNS multicast advertising** (both gated by this one permission). Treat denial as **blocking** (`canStart=false`, refuse running state, rationale card) — LAN clients otherwise time out silently; Diagnostics attributes the timeout to the missing permission. Verify the typed FGS start, notification, NSD registration, and bind path end-to-end. |

---

## 12. Threading / coroutine model summary (D6)

The proxy-core concurrency model **is decided** for V1: **Kotlin coroutines + blocking `java.net` sockets + a dedicated `Dispatchers.IO.limitedParallelism(N)` dispatcher** — **not** raw NIO selectors, **not** Netty. This is the dramatically simpler, idiomatic, battery-acceptable choice for a phone serving at most a few dozen concurrent tunnels (confirmed "concurrency never decided" finding).

- **One root scope**, `serviceScope = SupervisorJob() + ioDispatcher`, owned by `ProxyForegroundService`, cancelled in `onDestroy()`. Every accept-loop, relay, collector, and timed notification refresh is a child → cancellation is total and deterministic on stop/destroy (structured concurrency).
- **Dispatcher.** `ioDispatcher = Dispatchers.IO.limitedParallelism(N)` is injected (`@ProxyIoDispatcher`) so the proxy's blocking socket I/O runs on a **bounded** pool, isolated from the rest of the app's `Dispatchers.IO` usage. Each tunnel is two `launch{}` copy loops (read direction + write direction), so a 512-connection cap maps to ~1024 lightweight coroutines suspended onto the small bounded pool — **not** 1024 OS threads.
- **Choosing N and the head-of-line trade-off.** Because each relay loop does a **blocking** `socket.read()` that occupies a pool thread for the duration of the read (it does not suspend mid-read), N is the real ceiling on simultaneously-active reads. N is now a **user-adjustable, DataStore-backed, runtime-applied setting** (U4): default **32**, range **4–64**, exposed via the 省电/均衡/高吞吐 presets; the explicit trade-off is **head-of-line blocking** at the cap: when all N threads are parked in blocking reads, additional ready relays wait. Pair N with the configurable relay buffer (default 32 KiB, range 8–256 KiB) and the configurable connection caps (global default **256**, range 32–1024; per-client default 128, range 16–512). Memory budget example: 256 conn × 2 dir × 32 KiB ≈ 16 MiB. **Key principle: connection count ≠ bandwidth** — a too-small connection cap causes *refused* connections (broken page loads) and can *reduce* effective throughput by blocking the clients' own parallel connections that fill a high-BDP link; per-stream throughput is governed by N + buffer + the link itself, not the count cap. (Full settings/impact table and presets are specified in the proxy-core / settings doc under U4.) This avoids both thread-explosion and dispatcher starvation.
- **`SupervisorJob`** so one server's failure (e.g., SOCKS bind error) does not cancel HTTP/PAC; failures are reported via `serverManager.events` and surfaced in the notification/diagnostics.
- **`startForeground`/`stopForeground`** run on the main thread (service callbacks already run there); the heavy bind work is dispatched off-main inside `serviceScope.launch`, keeping the 5 s window safe.
- **`AtomicBoolean` guards** (`started`, `stopping`) make the `START_STICKY` restart race and double-tap idempotent without locks.
- **Process-shared state.** `ProxyRuntimeStateHolder` (a `@Singleton MutableStateFlow`) is the only channel from service → UI; it survives Activity recreation and is the single source the Dashboard/notification both read. **Throttled snapshots (D7):** per-client byte/rate counters are accumulated in plain atomics inside the proxy core; the UI-observed state emits **immutable aggregate snapshots at ≥1 Hz** (sampled via a ticker), never per-read deltas, to avoid Compose recomposition churn. The V1 `ShareState` exposes **client count + aggregate up/down rate**, not a per-client live rate list (the rich per-client list is deferred); any client collection uses immutable `listOf()` with stable types.

> **NEEDS VERIFICATION (D6):** the custom long-running-Service scope (`CoroutineScope(SupervisorJob() + Dispatchers.IO)` cancelled in `onDestroy()`) and the `limitedParallelism(N)`-vs-pool-exhaustion sizing are **not** documented by the *Kotlin coroutines on Android* page (which does not cover custom Service scopes or a concurrency cap). Verify against the advanced/best-practices coroutines docs before relying on the exact pattern and N.

---

## 13. Cross-cutting egress & security invariants touching this subsystem

These belong primarily to the VpnStateDetector and proxy-core subsystems, but the FGS owns the lifecycle hooks, so they are stated here for consistency:

- **VPN egress (D4).** Outbound (upstream) sockets are created **without any `Network` binding** and the process **never** calls `ConnectivityManager.bindProcessToNetwork()`; with a normal full-tunnel VPN this makes egress ride the VPN automatically (*VPN (Android connectivity)*). Any per-socket pinning that may be needed for **inbound** LAN reply symmetry is applied **only to the accepted server FD**, never process-wide — pinning the process would silently bypass the VPN. A runtime egress check inspects the **default network's** `NetworkCapabilities.hasTransport(TRANSPORT_VPN)` and gates "up" on `NET_CAPABILITY_VALIDATED`; query the app's own default network via `registerDefaultNetworkCallback`, not an "any network has VPN" scan.
- **Fail-closed block (D4).** VPN-down strategy default = **block**, and it must **fail closed atomically**: on any default-network change while in block mode, immediately tear down all active relays and stop accepting new ones until VPN-up is re-confirmed — gating only new connections leaks in-flight traffic during the async-callback detection gap (confirmed finding). The instant `TRANSPORT_VPN` disappears, the notification flips to the high-visibility "Blocked (no VPN)" state (§5).
- **Inbound-reply pinning is NEEDS VERIFICATION (D4).** The claim that inbound LAN reply packets can be black-holed by VPN routing unless accepted server sockets are pinned to the underlying LAN `Network` is plausible but unconfirmed on-device (Android routing is source-address aware and comparable LAN apps work). The lifecycle hook (pin per-accepted-FD only) is reserved; whether it is required is a **verification task**, not settled fact.
- **Auth is OPTIONAL (U5/U3, supersedes D5).** Default = **no auth** (restores the original "认证可选" stance). When the user shares on a non-trusted network, show a clear warning: "未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用。" When auth **is** enabled (SOCKS5 RFC 1929 user/pass and/or HTTP Basic), credentials are stored via **EncryptedSharedPreferences / Android Keystore**, never plaintext, never logged. The persistent-notification "Copy entry" action copies **host:port only**, never credentials (§5). Source-IP / subnet admission is a **convenience filter, not a security boundary** (trivially spoofable on a flat L2 segment) and is described as such in UI copy — no "auth-required-beyond-trusted-network / mandatory-auth" framing.
- **Anti-SSRF EgressGuard is KEPT (U3).** By default, proxy egress to **loopback / link-local (169.254/fe80) / this-host / multicast / the app's own listeners** is **blocked**. Private-LAN (RFC 1918) egress is **allowed by default** for broad applicability, with an optional toggle to block it.

---

## 与文档基准/评审的修订记录

- **Dropped `dataSync` entirely; primary FGS type = `connectedDevice`, fallback `specialUse`** (was `connectedDevice|dataSync`). The `dataSync` 6h/24h cap (target API 35+) ends in `Service.onTimeout()` and a fatal `RemoteServiceException`, is tracked per-type so a bitmask cannot escape it, and forbids `BOOT_COMPLETED` — fatal for an always-on gateway. *(Source: Foreground service types reference; confirmed review finding "dataSync … capped at 6 hours". D2.)* Removed the draft's `onTimeout` override and dataSync-timeout notes (§2, §3, §4, §6, §11).
- **Declared the full `connectedDevice` prerequisite set and satisfy it before `startForeground()`:** added `CHANGE_NETWORK_STATE` + `CHANGE_WIFI_STATE` (qualifying companion permissions) and `FOREGROUND_SERVICE` to the manifest; the runtime mask is the single type `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`. *(Source: Foreground service types are required (Android 14); partially-correct finding on `connectedDevice` prerequisites + missing `FOREGROUND_SERVICE`. D2.)*
- **Reframed `ACCESS_LOCAL_NETWORK` from a soft reachability boolean to a HARD gate on the inbound accept itself.** It gates accepting incoming TCP in both directions, is enforced deep in the stack (cannot be evaded by binding `0.0.0.0`), is mandatory at target SDK 37+, denial surfaces as a **TCP timeout** (not `ECONNREFUSED`), and it is in the `NEARBY_DEVICES` group. `PermissionGate.canStart` now blocks on it. *(Source: Local network permission; Behavior changes: Android 17; confirmed critical finding. D3.)*
- **~~Pinned V1 target SDK to 36 to defer mandatory local-network enforcement~~ (SUPERSEDED by U2, 2026-06-22 — V1 now targets SDK 37 with a mandatory day-one hard gate)** and keyed the blocking gate to numeric SDK `>= 37` + `targetSdkVersion >= 37`, with a pre-permission rationale and runtime detection. *(Confirmed critical finding recommendation; §7.2, §10. The numeric `>= 37` gate logic is retained; only the target-SDK pin changed.)*
- **~~Removed all `hxmyproxy.local` occurrences; V1 uses raw selected-interface IPv4 literals; mDNS/NSD noted as the #1 V1.1 item only~~ (SUPERSEDED by U1, 2026-06-22 — mDNS/NSD is now IN V1 as an additive layer; `.local` is listed first with the raw IP as mandatory fallback).** The "always carry a raw IP, never emit `.local` alone" hardening is retained. *(was D1; "lead with IP" hardening preserved.)*
- **Corrected `START_STICKY` semantics:** it lets the system recreate a killed service with a **null Intent** but does **not** exempt explicit `startForegroundService()` from the Android 12 background-start rule. *(Source: Foreground services overview. §2.1, §4.)*
- **Made auto-recovery honest:** in-process listener restart while the FGS is alive is permitted; full post-process-kill restart requires the battery-optimization allowlist (Doze exemption ⇒ background-FGS-start exemption) or a notification-tap relaunch. *(Source: Foreground services overview exemption list; partially-correct finding "Android 12+ restriction breaks Watchdog". §4, §8.)*
- **Clarified `POST_NOTIFICATIONS` denial:** the FGS (and listening sockets) still run; the notice is hidden from the drawer but visible in Task Manager; the in-app UI is the authoritative control surface. Kept it advisory/non-blocking. *(Source: Notification runtime permission. §7.1.)*
- **Committed the concurrency model (coroutines + blocking sockets + `Dispatchers.IO.limitedParallelism(N)`), documented the head-of-line-blocking trade-off and N selection, lowered the default connection cap to 128–256, and added a relay buffer budget.** *(Confirmed "concurrency never decided" finding + partially-correct 512-cap finding. D6, §12.)*
- **Defined scope ownership and lifecycle survival** (Service owns `SupervisorJob` scope; process-level `@Singleton MutableStateFlow` for state transport, not Service binding; rotation/swipe-away/process-death behaviors tabulated). *(Confirmed finding "Ownership of the proxy CoroutineScope … is undefined". §1.)*
- **Throttled state emission (D7):** byte counters accumulate in atomics; UI sees immutable aggregate snapshots at ≥1 Hz; V1 `ShareState` carries client count + aggregate rate, not a per-client live-rate list. *(Partially-correct "recomposition storm" finding. §5, §12.)*
- **Hardened port-bind failure handling:** `startForeground` first, then bind, so `EADDRINUSE`/`BindException` surfaces as a typed `PORT_IN_USE` start failure and never causes an FGS-timeout ANR; `SO_REUSEADDR` on listeners. *(Partially-correct port-conflict / FGS-timeout findings. §2.3.)*
- **Folded in the cross-cutting egress/security invariants** (unbound outbound sockets, no `bindProcessToNetwork`, fail-closed block, ~~default auth~~ **auth now OPTIONAL per U3 (2026-06-22)**, EncryptedSharedPreferences, anti-SSRF, subnet-admission-is-not-security, no credentials on clipboard, private lock-screen visibility). *(D4 + U3 + confirmed VPN-leak, open-proxy, SSRF, credential-storage, and partially-correct admission findings. §5, §13.)*
- **Trimmed over-engineering (D7):** `Watchdog`/`HealthChecker` and the rich client list are out of this subsystem's V1 surface; package path normalized to `core/net/LocalNetworkPermissionManager.kt`. *(Note: the mDNS publisher is now IN V1 per U1 below — see the 2026-06-22 update.)*

### 决策更新 (2026-06-22)

These updates **supersede** the prior D1/D3/D5 framing where noted; all still-correct content above is preserved.

- **U1 — mDNS/NSD is now IN V1 (no longer deferred to V1.1)** as an *additive convenience layer* on top of the raw-IP scheme. Promoted the V1 scope note; added §5.1 (NSD advertising via `NsdManager.registerService` for `_http._tcp` / `_socks._tcp` / PAC, mandatory `serviceName` read-back in `onServiceRegistered` per grounded-ref §9, advertisement lifecycle bound to the FGS, per-active-link multi-interface advertising where a client resolves `hxmyproxy.local` to the IP on its own segment). Recommended-entry / PAC output now lists `hxmyproxy.local` **first** then the concrete interface IP(s) as fallback (e.g. `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`) and **never** emits `.local` without the IP fallback; the dashboard still lists every per-interface IP entry. The notification primary line stays a raw IP, with `.local` only as an additive hint. *(Supersedes the D1 "remove all `hxmyproxy.local` / IP-only" stance.)*
- **U2 — V1 now targets SDK 37 (was 36).** `ACCESS_LOCAL_NETWORK` is a **mandatory day-one runtime permission**, required for BOTH the inbound `accept()` AND mDNS multicast (`224.0.0.251:5353`). Added/strengthened the **hard-gate first-run flow** in §7.2 (rationale → request-before-bind → refuse running state on denial → Diagnostics attributes the client-side **TCP-timeout** cause per grounded-ref §1). Updated the §10 targetSdk recommendation 36→37 and the §11 / §4 per-API checklists (36 and 37 rows). Manifest: `ACCESS_LOCAL_NETWORK` comment updated to mandatory + mDNS; `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` re-noted as the FGS prerequisite that also qualifies the listener/multicast use. All permission-API use is `Build.VERSION`-guarded. *(Supersedes the D3 "pin to target ≤ 36 to defer enforcement".)*
- **U3 — Auth is OPTIONAL (restored "认证可选").** §13 default is now **no auth**, with a plain-language LAN-sharing warning; kept credential storage in EncryptedSharedPreferences/Keystore when auth is enabled, and kept the anti-SSRF EgressGuard (block loopback/link-local/this-host/multicast/own-listeners; RFC 1918 egress allowed by default with optional block toggle). Removed mandatory-auth framing. *(Supersedes D5.)*
- **U4 — Connection/resource limits are user-adjustable (DataStore-backed) settings + three presets.** §12 updated: N, relay buffer, global/per-client connection caps are now configurable (defaults 32 / 32 KiB / 256 / 128) with the 省电/均衡/高吞吐 presets, the "connection count ≠ bandwidth" key principle, and the ~16 MiB memory-budget example. The full impact table lives in the proxy-core/settings doc.
- **U5 — Foreground service: added a plain-language explanation** of what an FGS / `foregroundServiceType` is and why a proxy needs one, and that the type is changeable later (manifest + Play Console; may trigger re-review). Kept `connectedDevice` primary + `specialUse` fallback + `dataSync` rejected. Added the note that NSD/mDNS advertising is bound to this FGS lifecycle.

---

## NEEDS VERIFICATION

1. **Google Play acceptance of `connectedDevice` for a passive LAN listening server.** No documented FGS type names a proxy/gateway/server use case; whether Play accepts the `connectedDevice` justification or forces `specialUse` is a policy-review judgment determinable only via actual Play Console submission. The `specialUse` fallback (manifest + justification) is kept ready. *(Reference §2 / open item 7.)*
2. **Custom long-running-Service `CoroutineScope` pattern and `limitedParallelism(N)` sizing (D6).** `CoroutineScope(SupervisorJob() + Dispatchers.IO)` cancelled in `onDestroy()`, and the choice of N against blocking-socket pool exhaustion, are best practices **not** covered by the *Kotlin coroutines on Android* page. Verify against the advanced/best-practices coroutines docs. *(Reference §7 / open items 5–6.)*
3. **Inbound LAN reply-pinning while a VPN is active (D4).** Whether accepted server sockets must be pinned (per-FD `Network.bindSocket()`) to the underlying LAN network so replies route symmetrically, or whether Android's source-address-aware routing already delivers them, is unverified on-device. The per-FD-only hook is reserved; treat as a verification task, not settled fact.
4. **`ACCESS_NETWORK_STATE` requirement for reading connectivity state.** The *Reading network state* page asserts no permission is needed to read state but flags a discrepancy with the `ConnectivityManager` API reference; we declare `ACCESS_NETWORK_STATE` defensively. *(Reference §6 / open item 3.)*
5. **Runtime detection of `ACCESS_LOCAL_NETWORK` denial.** Mapping the NDK `android_getnetworkblockedreason()` (`ANDROID_NETWORK_BLOCKED_REASON_LNP`) or the inbound-TCP-timeout signature to the in-app blocked-state error class is a defensive backstop whose exact behavior should be confirmed on an Android 17 / target-SDK-37 device.
6. **OEM swipe-away / background-kill behavior.** AOSP keeps a `stopWithTask="false"` started FGS alive on task removal, but several OEMs still kill it; the battery-allowlist mitigation's effectiveness varies by manufacturer and should be device-matrix tested.
7. **NSD/mDNS runtime-permission requirement (U1).** The NSD page is silent on permissions; whether advertising via `NsdManager.registerService` requires only `ACCESS_LOCAL_NETWORK` on target SDK 37+ or also `NEARBY_WIFI_DEVICES` on Android 13/14 must be confirmed against the local-network / nearby-devices docs (grounded-ref §9). We assume `ACCESS_LOCAL_NETWORK` covers the mDNS multicast (grounded-ref §1 lists `NsdManager` and multicast as gated by it).
8. **Play re-review on `foregroundServiceType` switch (U5).** Changing the declared type later (e.g. `connectedDevice` → `specialUse`) is a manifest + Play-Console edit that **may trigger a Play re-review**; the review timing/outcome is not documented and is determinable only via actual Play Console submission.
9. **Real ~500 Mbps throughput under the coroutines + blocking-sockets + `limitedParallelism(N)` model (U4).** On the user's ~100–500 Mbps 4G/5G link, achieving full per-stream throughput at the chosen N + buffer is unverified; load-test and tune buffer/N first. NIO is the V2 escape hatch.
