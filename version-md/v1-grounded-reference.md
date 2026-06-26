# hxmy proxy · V1 官方文档事实基准 (Grounded Reference)

> 本文所有结论均来自官方 Android 文档与 RFC 的实际抓取（16/16 来源成功），每条均内联标注出处 URL；无法从文档核实的点统一标记 **NEEDS VERIFICATION** 并在文末汇总。请勿以此外的"记忆"补充未核实的行为。
>
> 抓取来源：Local network permission / Android 17 behavior-changes / FGS types required (14) / FGS service-types / FGS overview / VPN / Reading network state / Notification permission / Architecture recommendations / ViewModel / Compose state / Kotlin coroutines / NSD / RFC 1928 / RFC 1929 / HTTP CONNECT。

This document resolves the critical V1 design questions for the hxmy proxy (an Android 10–17 local proxy gateway that binds listening sockets on `0.0.0.0`, accepts inbound connections from LAN/hotspot/USB/Bluetooth/Ethernet peers, and forwards their traffic out the phone's default network egress; it does **not** implement its own `VpnService`). Every claim is grounded only in the supplied documentation facts and cited inline. Unverifiable items are marked **NEEDS VERIFICATION** and collected at the end.

---

### 1. ACCESS_LOCAL_NETWORK (Android 17)

**RULE.** `ACCESS_LOCAL_NETWORK` is a runtime permission in the `NEARBY_DEVICES` group. It gates **all traffic to and from local-network addresses in BOTH directions** — making outgoing TCP connections, **accepting incoming TCP connections**, and sending/receiving UDP unicast, multicast, and broadcast. Enforcement is "implemented deep in the networking stack, and thus they apply to all networking APIs" (direct sockets, raw sockets such as mDNS/SSDP, and framework APIs such as `NsdManager`), so binding/listening on `0.0.0.0` cannot evade it. Internet and mobile/cellular traffic are unaffected; LAN DNS on port 53 is exempt; resolving `.local` services is gated; WebView inherits the host app's state.

- **Version gating:** On Android 16 (SDK 36) it is opt-in and "local network access is open" by default. On **Android 17 with target SDK 37+, "Local network is blocked by default for all apps that update their target SDK"** — mandatory enforcement. Apps targeting Android 16 or lower are not subject to this enforcement.
- **Failure mode when denied:** TCP "will typically result in a timeout error" (not an immediate refusal); UDP/general denials surface as `EPERM`. On the NDK, `android_getnetworkblockedreason()` returns `ANDROID_NETWORK_BLOCKED_REASON_LNP`.
- **Picker exemption does NOT apply:** Connections to IPs obtained via the system device picker (`DiscoveryRequest` + `FLAG_SHOW_PICKER`) don't require the permission, but "broad, persistent access to the local network" requires actually requesting `ACCESS_LOCAL_NETWORK`.

**hxmy implication.** hxmy is squarely in scope: its core function is **accepting inbound LAN connections**, which the page explicitly lists as gated. Action plan:
1. Until target SDK is bumped to 37, hxmy is unrestricted on Android 17 and works today on Android 10–16 without changes.
2. The moment hxmy targets SDK 37+, it must declare `android.permission.ACCESS_LOCAL_NETWORK` in the manifest and **request it at runtime, gated by `Build.VERSION` checks, before binding/accepting sockets or starting the proxy's listening loops.** Check grant state before each use.
3. The mDNS/`FLAG_SHOW_PICKER` exemption is useless here — hxmy needs broad persistent inbound listening for arbitrary unknown peers, exactly the case requiring the full permission.
4. Expect a **timeout (not `ECONNREFUSED`)** signature for peers when denied; surface a clear blocked state in-app. Because it's in the `NEARBY_DEVICES` group, a user who already granted a sibling (e.g. Bluetooth) is not re-prompted.

**Citations:**
- *Local network permission* — https://developer.android.com/privacy-and-security/local-network-permission
- *Behavior changes: Android 17* — https://developer.android.com/about/versions/17/behavior-changes-17

> Note on inbound semantics: the *Behavior changes: Android 17* summary page frames the permission around an app discovering/connecting OUTWARD to LAN devices and does **not** itself enumerate inbound listen/accept. The authoritative *Local network permission* detail page resolves this unambiguously: "accepting incoming TCP connections" and "Inbound LAN Request: Fails" are explicitly gated. The inbound-gating conclusion therefore rests on the detail page, which is the authoritative source.

---

### 2. Correct foregroundServiceType for a long-running local proxy gateway

**RULE.** Apps targeting Android 14 (API 34)+ **must** declare `android:foregroundServiceType` for every FGS, or `startForeground()` throws `MissingForegroundServiceTypeException`. Each type requires the base `FOREGROUND_SERVICE` permission **plus** the matching `FOREGROUND_SERVICE_<TYPE>` permission, or `startForeground()` throws `SecurityException`. Any per-type runtime prerequisite must be satisfied **before** calling `startForeground()`, or `SecurityException` is thrown. Apps targeting Android 14+ must also declare the type in the Play Console (Policy > App content) with a justification.

There is **no documented proxy/gateway/server/hotspot/tethering FGS type.** The realistic candidates:

| Type | Permissions / prerequisites | Time limit | Fit for hxmy |
|---|---|---|---|
| **connectedDevice** | `FOREGROUND_SERVICE_CONNECTED_DEVICE` **plus at least one of**: (manifest) `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR`; OR (granted runtime) `BLUETOOTH_CONNECT`/`BLUETOOTH_ADVERTISE`/`BLUETOOTH_SCAN`/`UWB_RANGING`; OR call `UsbManager.requestPermission()`. Gate must be satisfied **before** `startForeground()`. | **No documented time limit.** | Documented use is "Interactions with external devices that require a Bluetooth, NFC, IR, USB, or **network connection**" — scoped to the phone reaching OUT to an external device, not hosting inbound. Reachable via `CHANGE_WIFI_STATE`/`CHANGE_NETWORK_STATE`, but a justification stretch. |
| **dataSync** | `FOREGROUND_SERVICE_DATA_SYNC`; **no runtime prerequisite** (catch-all). | **6 hours / 24-hour window (target API 35+)**, then `Service.onTimeout()` fires; must `stopSelf()` within seconds or the app is killed with fatal `RemoteServiceException`. Timer resets only on user interaction. Cannot launch from `BOOT_COMPLETED` (API 35+). | Documented use is moving the app's OWN data, not relaying third-party traffic. **The 6h cap breaks an always-on proxy.** |
| **specialUse** | `FOREGROUND_SERVICE_SPECIAL_USE` **plus** a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..."/>` child of `<service>`; reviewed in Play Console. | Not documented as time-limited. | The official catch-all "for cases not covered by other types"; fits a proxy gateway but carries manual Play review of the justification string. |
| **shortService** | Only `FOREGROUND_SERVICE`. | **~3 minute timeout**; cannot be sticky; cannot start other FGS. | **Not viable** for an always-on gateway. |
| **systemExempted** | `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`. | — | **Not available** — reserved for system/privileged apps (Device Owner, VPN role, demo mode). |

**hxmy implication.** Use **`connectedDevice`** as the primary choice: it has **no 6h/24h timeout** (essential for an always-on gateway) and its runtime gate is satisfiable via `CHANGE_WIFI_STATE`/`CHANGE_NETWORK_STATE`, which a LAN/hotspot proxy plausibly needs — declare that qualifying permission and grant it **before** `startForeground()`. Fall back to **`specialUse`** (with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property and a clear "local network proxy gateway for tethered/LAN devices" explanation) if the `connectedDevice` justification is rejected. **Do NOT use `dataSync`** as a lazy default — the 6h cap + `RemoteServiceException` kill + `BOOT_COMPLETED` restriction make it fragile for an always-on proxy. `shortService` and `systemExempted` are out. Because hxmy is not a `VpnService`, it cannot use any VPN-related exemption and is fully subject to these rules on Android 14+.

**Citations:**
- *Foreground service types are required (Android 14)* — https://developer.android.com/about/versions/14/changes/fgs-types-required
- *Foreground service types reference* — https://developer.android.com/develop/background-work/services/fgs/service-types
- *Foreground services overview* — https://developer.android.com/develop/background-work/services/fgs

---

### 3. System VPN active: do other apps' OUTBOUND connections egress THROUGH the VPN?

**RULE.** Yes by default. "If you don't create allowed or disallowed lists, the system sends all network traffic through the VPN"; "When the allowed list is empty, all apps use the VPN." Exclusions:
- **Per-app allowed list (inclusion):** "If the list includes one or more apps, then only the apps in the list use the VPN. All other apps ... use the system networks as if the VPN isn't running."
- **Per-app disallowed list (exclusion):** disallowed apps "use system networking as if the VPN wasn't running."
- Allowed and disallowed lists are **mutually exclusive** ("but not both").
- An app cannot opt itself out: bypass requires the VPN app to call `VpnService.Builder.allowBypass()` **and** the app to explicitly bind its process/socket to a specific network; otherwise "the app's network traffic continues through the VPN."
- **Lockdown** ("Block connections without VPN") blocks any non-VPN path; apps not covered by an allow/disallow list "lose their network connection."
- **Only one VPN at a time:** "Only one app can be the current prepared VPN service ... Starting a new service, automatically stops an existing service." Always-on VPN (API 24+) may be present from boot.

**hxmy implication.** With a normally configured full-tunnel VPN, **all of hxmy's forwarded outbound sockets egress through the VPN automatically** — this is the desired behavior and requires no work. hxmy is correct **not** to register its own `VpnService` (it would tear down the user's VPN and force hxmy into routing/per-app-filtering it intentionally avoids). hxmy **cannot** opt its forwarded traffic out of an active VPN on its own. Whether forwarded traffic rides the VPN depends on the VPN's per-app config **keyed to hxmy's own package/UID**: if hxmy is excluded (not in an allowed list, or in a disallowed list), forwarded traffic uses the underlying system network instead; under **lockdown** that exclusion drops connectivity entirely. V1 should document that egress-follows-VPN is automatic and intended, and surface that the VPN's per-app config + lockdown determine hxmy's behavior.

**Citation:** *VPN (Android connectivity)* — https://developer.android.com/develop/connectivity/vpn

---

### 4. startForeground() timing rules + Android 12 background-start restriction

**RULE.**
- After `Context.startForegroundService()`, the app has **5 seconds** to call the service's `startForeground()`/`ServiceCompat.startForeground()` to show the notification. If not, "the system stops the service and declares the app to be ANR," and throws `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()` (crashing the app if uncaught).
- **Android 12 (API 31)+:** Apps targeting API 31+ "can't start foreground services while the app is running in the background, except for a few special cases," or the system throws `ForegroundServiceStartNotAllowedException`. General-purpose exemptions: transition from a user-visible state/activity; user action on an app UI element; user disabling battery optimization; holding `SYSTEM_ALERT_WINDOW` (on Android 15+ this also requires a currently visible overlay window); plus boot broadcasts (`ACTION_BOOT_COMPLETED`/`ACTION_LOCKED_BOOT_COMPLETED`), FCM high-priority messages, exact alarms, geofencing/activity-recognition, system roles, Companion Device Manager, etc.
- `START_STICKY` causes the system to recreate a killed service (with a **null Intent** on recreate) but **does NOT** exempt explicit `startForegroundService()` calls from the Android 12 background-start rule.

**hxmy implication.** Build the notification first, call `startForeground()` **within 5 seconds**, and **only then** bind the `0.0.0.0` listening sockets / start proxy worker threads — never bind before `startForeground()`. Start the service from a **visible Activity / user tap** (the cleanest exempt path). For reboot auto-start, use the `ACTION_BOOT_COMPLETED`/`ACTION_LOCKED_BOOT_COMPLETED` exemption — but verify the chosen `foregroundServiceType` is still allowed from boot (note `dataSync` is **not** allowed from `BOOT_COMPLETED` on API 35+, §2). For robust background restarts, the recommended UX is a settings toggle to **disable battery optimization**. Return `START_STICKY`, but re-derive config from persisted state on recreate (null Intent), and do not assume silent perpetual background revival without an exemption.

**Citation:** *Foreground services overview* — https://developer.android.com/develop/background-work/services/fgs

---

### 5. POST_NOTIFICATIONS denied: can the FGS still run?

**RULE.** Yes. "Apps don't need to request the `POST_NOTIFICATIONS` permission in order to launch a foreground service. However, apps must include a notification when they start a foreground service, just as they do on previous versions of Android." On Android 13+ (API 33), if the user denies the permission, FGS notices are **hidden from the notification drawer** but **still visible in the Task Manager** (system FGS list). Denial blocks all non-exempt notification channels app-wide (equivalent to the user disabling notifications in settings). For apps targeting API ≤ 32 the system auto-prompts on first activity after channel creation, and a single "Don't allow" permanently suppresses re-prompting until reinstall/retarget.

**hxmy implication.** The proxy FGS — and therefore the `0.0.0.0` listening sockets — **runs regardless of `POST_NOTIFICATIONS`.** Core functionality is not gated. hxmy must still attach a notification to `startForeground()`. When denied, the in-shade status surface (LAN IP/port, connection count, stop button) is suppressed but the service remains visible/stoppable via Task Manager. Therefore: target Android 13+, declare `android.permission.POST_NOTIFICATIONS`, request it in context (guarded by `SDK_INT >= TIRAMISU`, e.g. when the user starts the proxy), and **make the in-app UI the authoritative status/control surface** as a fallback, since secondary alerts (errors, egress lost) won't surface when denied.

**Citation:** *Notification runtime permission* — https://developer.android.com/develop/ui/views/notifications/notification-permission

---

### 6. Detecting VPN / default-network changes via NetworkCallback + NetworkCapabilities

**RULE.**
- This page asserts that "Using `NetworkCallback` and other ways of finding out about the connectivity state of the device doesn't require any particular permission." `CHANGE_NETWORK_STATE` is required only to bind to a background (non-default) network.
- `registerDefaultNetworkCallback(callback[, handler])` tracks the single system default network; the `Handler` variant runs callbacks on a worker thread. In the **default** callback, `onLost` means loss of *default status*, not necessarily disconnection.
- Detect VPN: `caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)`, equivalently the **absence** of `NET_CAPABILITY_NOT_VPN` (`!hasCapability(NET_CAPABILITY_NOT_VPN)`).
- A VPN keeps `TRANSPORT_VPN` while swapping its underlying `TRANSPORT_WIFI`/`TRANSPORT_CELLULAR`, so one `Network` can report VPN + the underlying physical transport together.
- Confirm real egress with `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED` (INTERNET = setup only; VALIDATED = actual probed public-internet access, excludes captive portals).
- **Do not** call synchronous methods inside `onAvailable` (race conditions); react in `onCapabilitiesChanged`, which arrives immediately after `onAvailable` on Android 8.0 (API 26)+.
- Synchronous query: `getActiveNetwork()` → `getNetworkCapabilities(network)` / `getLinkProperties(network)`.
- Unregister via `unregisterNetworkCallback`; there is a hard limit on concurrent callbacks.

**hxmy implication.** From the **Foreground Service** (not an Activity), call `registerDefaultNetworkCallback(callback, handler)` (Handler variant) to track the single egress the forwarded sockets actually use. Answer "is egress going through the VPN?" via `hasTransport(TRANSPORT_VPN)` / absence of `NET_CAPABILITY_NOT_VPN`, and surface both the VPN status and the underlying physical transport from the same `NetworkCapabilities`. Gate "gateway up" on `INTERNET` + `VALIDATED`. Re-evaluate on every `onCapabilitiesChanged`; register on proxy start, unregister on stop. **Permission caveat:** this page says no permission is needed to *read* state, but it is **not** authoritative that `ACCESS_NETWORK_STATE` can be omitted — declare `ACCESS_NETWORK_STATE` per the `ConnectivityManager` API contract. **NEEDS VERIFICATION** for the exact `ACCESS_NETWORK_STATE` requirement (the page explicitly flags the discrepancy with the API reference).

**Citation:** *Reading network state* — https://developer.android.com/develop/connectivity/network-ops/reading-network-state

---

### 7. Official architecture / ViewModel / Compose / coroutines standards

**RULE (layering & UDF).** Use the recommended layered architecture (separation of concerns, drive UI from data models, single source of truth, unidirectional data flow). The **data layer** "contains the vast majority of your app's business logic" and is fronted by **repositories** (create one even for a single data source); UI/ViewModels must **not** touch data sources directly — and "network connectivity status providers" are explicitly listed as data sources. Use a **domain layer / use cases** only to reuse logic across ViewModels or tame complexity (recommended in big apps). Use **Compose** for UI. Communicate between layers only via **coroutines and flows**. Use **constructor injection** and **Hilt** when the project is complex (multiple ViewModels, WorkManager, back-stack-scoped ViewModels); **scope to a container** types that hold mutable shared data or are expensive to initialize and widely used.

**RULE (ViewModel).** Expose UI state as a **single immutable `uiState: StateFlow`** (`private val _uiState = MutableStateFlow(...)`; `val uiState = _uiState.asStateFlow()`); use `stateIn(viewModelScope, WhileSubscribed(5000), initial)` for streamed data. Follow UDF: ViewModels receive actions via method calls and **do not send events to the UI** (process the event, emit a state update). **Never** hold `Context`/`Activity`/`Resources`/views/lifecycle types; **do not** subclass `AndroidViewModel`. Use ViewModels only at **screen level** (not reusable UI), and **do not pass them** to other classes/components. ViewModels survive configuration changes but are cleared when the `ViewModelStoreOwner` disappears and on process death; `viewModelScope` coroutines auto-cancel on clear. Use `SavedStateHandle` for state that must survive process death. Interact with lower layers via Kotlin flows (data) + suspend functions (actions) within `viewModelScope`.

**RULE (Compose).** Collect flows with `collectAsStateWithLifecycle()` (the recommended Android API; requires `androidx.lifecycle:lifecycle-runtime-compose`). Follow state hoisting (state down, events up via `onValueChange: (T) -> Unit`), hoist to the lowest common parent of readers / highest writer, prefer stateless composables, and **never use non-observable mutable collections** (`ArrayList`/`mutableListOf`) as state — use `State<List<T>>` + immutable `listOf()`. Use `rememberSaveable` only for transient UI input (it does not survive full activity dismissal). Extract a state holder class as logic grows.

**RULE (coroutines).** Use `Dispatchers.IO` for blocking I/O (`withContext(Dispatchers.IO)`, mark wrappers `suspend` for main-safety); the implicit default is Main (never run blocking work there). Use **structured concurrency** (run within a scope); cancellation propagates automatically through the hierarchy. `viewModelScope` auto-cancels on ViewModel destruction. The page **does not** document `Dispatchers.Default`, `lifecycleScope`, or how to build a custom Service scope.

**hxmy implication.**
1. Put the **proxy engine** (socket listeners, CONNECT/SOCKS5/PAC handling, connection bookkeeping, the connectivity-status provider from §6) in the **data layer behind a `ProxyServerRepository`**; the Foreground Service delegates to it. Business logic does not live in the Service or ViewModel.
2. Proxy runtime state (running/stopped, bound address/port, active protocols, connection count/list, egress/VPN status, last error) is a **single source of truth** in the engine, flowing up; the UI sends actions (start/stop, change port, toggle SOCKS5/PAC) via method calls.
3. The control-screen ViewModel exposes one immutable `uiState: StateFlow<ProxyUiState>` via `stateIn(viewModelScope, WhileSubscribed(5000), Loading)`; the UI collects with `collectAsStateWithLifecycle`. Model errors ("port in use", "bind on 0.0.0.0 failed") as **state, not events**.
4. The ViewModel holds **no `Context`/Service reference** and is not `AndroidViewModel`; things needing `Context` (starting the FGS, `ConnectivityManager`, `NotificationManager`, `WifiManager` for LAN IP, sockets) live in the data layer, injected via constructor injection.
5. Use **Hilt**; **scope the engine/repository as a singleton** because it holds live mutable state (listeners/connections) and is expensive to init; inject it into both the Service and the ViewModel.
6. The real proxy lifecycle (socket binding, accept loops, relay) runs in the **Foreground Service**, **not** `viewModelScope` (which is cancelled when the UI owner disappears). Use a **custom Service-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`**, cancelled in `onDestroy()`. (This custom-scope detail is **not** covered by the coroutines page — see §10.) Persist user config (port, enabled protocols, PAC, bind toggle) via `SavedStateHandle`/DataStore. Model live data (client list, throughput) as observable `State<List<Client>>`/immutable `listOf()` emitted from a Flow — never push `mutableListOf()` to Compose.

**Citations:**
- *Recommendations for Android architecture* — https://developer.android.com/topic/architecture/recommendations
- *ViewModel overview* — https://developer.android.com/topic/libraries/architecture/viewmodel
- *State and Jetpack Compose* — https://developer.android.com/develop/ui/compose/state
- *Kotlin coroutines on Android* — https://developer.android.com/kotlin/coroutines

---

### 8. SOCKS5 (RFC 1928/1929) and HTTP CONNECT wire format the proxy core must implement

#### 8a. SOCKS5 method negotiation (RFC 1928)
- **Client greeting:** `VER(1)=0x05 | NMETHODS(1) | METHODS(1..255)`. No-auth client sends `05 01 00`. The server must read exactly `NMETHODS` method octets before replying.
- **Server method reply (2 bytes):** `VER(1)=0x05 | METHOD(1)`. `0x00` = NO AUTHENTICATION REQUIRED; `0x02` = USERNAME/PASSWORD; `0xFF` = NO ACCEPTABLE METHODS, after which the client **MUST** close.

#### 8b. SOCKS5 request / reply (RFC 1928)
- **Request:** `VER(1)=0x05 | CMD(1) | RSV(1)=0x00 | ATYP(1) | DST.ADDR(variable) | DST.PORT(2, network byte order / big-endian)`.
- **CMD:** `0x01` CONNECT, `0x02` BIND, `0x03` UDP ASSOCIATE.
- **ATYP:** `0x01` IPv4 (4 octets); `0x03` DOMAINNAME (**1 length-prefix octet + that many name octets, NO terminating NUL**); `0x04` IPv6 (16 octets).
- **Reply (mirrors request):** `VER(1)=0x05 | REP(1) | RSV(1)=0x00 | ATYP(1) | BND.ADDR(variable) | BND.PORT(2, network byte order)`.
- **REP codes:** `0x00` succeeded; `0x01` general failure; `0x02` not allowed by ruleset; `0x03` network unreachable; `0x04` host unreachable; `0x05` connection refused; `0x06` TTL expired; `0x07` command not supported; `0x08` address type not supported; `0x09`–`0xFF` unassigned.
- `VER` is always `0x05` in greeting/request/reply; `RSV` must be `0x00`.

#### 8c. SOCKS5 username/password sub-negotiation (RFC 1929)
- Begins **only after** method selection picks `0x02`, on the **same TCP connection**, before the SOCKS request.
- **Sub-negotiation VER = `0x01`** (distinct from SOCKS `0x05` — a common bug).
- **Client request:** `VER(1)=0x01 | ULEN(1) | UNAME(1..255) | PLEN(1) | PASSWD(1..255)` (length-prefixed, each 1–255 bytes).
- **Server response (2 bytes):** `VER(1)=0x01 | STATUS(1)`. `STATUS=0x00` = success; **any non-zero STATUS → the server MUST close the connection** (mandatory).

#### 8d. HTTP CONNECT (MDN)
- **Request line (authority-form):** `CONNECT host:port HTTP/1.1` — target is **only** `host:port` (no scheme/path); **port is mandatory, no default.** Host may be a hostname or IPv4/IPv6.
- **Success:** any **2XX** status switches the proxy to "tunnel mode" (conventionally `200 Connection established`); afterward the proxy **blindly relays bytes in both directions** without inspecting payload, until the tunnel closes. The client's TLS handshake is end-to-end with the destination (no certificates/MITM needed).
- CONNECT is **hop-by-hop**; the proxy terminates it. Proxy credentials use `Proxy-Authorization`. No request body, no success-response body; not safe/idempotent/cacheable.
- **Security:** restrict tunnel targets to known ports or a configurable allowlist to avoid being abused as an open relay (e.g. SMTP spam).

**hxmy implication.**
1. **Handshake state machine:** `NEW → HANDSHAKE → CONNECTING → RELAYING` maps onto the RFC 1928 exchanges; read exactly `NMETHODS` octets, never a fixed length.
2. **Auth:** offer `0x00` when auth is off, `0x02` when on; if no acceptable method, reply `0xFF` and **close**. After selecting `0x02`, run the **RFC 1929** sub-negotiation with `VER=0x01`, length-prefixed UNAME/PLEN/PASSWD, reply `0x01 + STATUS`, and on any failure send non-zero STATUS and **immediately close** — this is the **primary access control** preventing arbitrary hotspot/LAN peers from using the egress; failing open would expose it.
3. **Request parsing:** support all three ATYP; for `0x03` read 1 length octet then that many name bytes with **no NUL** (remote-DNS-friendly); parse `DST.PORT` as unsigned 16-bit big-endian.
4. **CMD scope:** V1 supports only CONNECT (`0x01`); reply `REP=0x07` (command not supported) for BIND/UDP ASSOCIATE rather than dropping.
5. **Relay entry:** on successful outbound TCP connect (egressing via the phone's default network, which carries the system VPN), reply `REP=0x00` and start bidirectional relay; `VER=0x05`, `RSV=0x00`. Conventional CONNECT reply: `ATYP=0x01`, `BND.ADDR=0.0.0.0`, `BND.PORT=0`.
6. **Error mapping:** host-resolution/unreachable → `0x04`; network unreachable → `0x03`; connection refused → `0x05`; connect timeout → `0x01`; access-control denial → `0x02`; unsupported ATYP → `0x08`.
7. **HTTP CONNECT handler:** parse authority-form `CONNECT host:port HTTP/1.1`, open TCP to `host:port` on the default egress, reply 2XX, then blind two-way byte relay (no payload inspection, no certs). Terminate CONNECT as the proxy hop; handle `Proxy-Authorization` and strip proxy-scoped headers; **restrict targets/ports** since hxmy binds `0.0.0.0` and is effectively an open relay otherwise. Relay runs inside the Foreground Service so it survives while sockets are open.

**Citations:**
- *RFC 1928 — SOCKS Protocol Version 5* — https://www.rfc-editor.org/rfc/rfc1928
- *RFC 1929 — Username/Password Authentication for SOCKS5* — https://www.rfc-editor.org/rfc/rfc1929
- *HTTP CONNECT method* — https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Methods/CONNECT

---

### 9. NSD / mDNS publishing (NsdManager) — now in V1 (mDNS pulled forward per v1-design.md §2 D1)

**RULE.**
- NSD (DNS-SD over mDNS) publishes/discovers services on the local network via the `NsdManager` system service.
- **Service type:** `_<protocol>._<transportlayer>` (e.g. `_http._tcp`); a custom type should be reserved with IANA.
- **Publish:** build `NsdServiceInfo` with `serviceName`, `serviceType`, and `setPort(port)`, then `nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)`.
- **Name conflicts:** Android may rename to resolve conflicts; in `onServiceRegistered` the app **must** read back and store `serviceInfo.serviceName` — the requested name is not guaranteed.
- **Port:** avoid hardcoding; prefer `ServerSocket(0)` and register the resolved `localPort`.
- **Discovery:** `discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)`; resolve a found service via `resolveService(service, resolveListener)`; read `serviceInfo.host`/`serviceInfo.port` only in `onServiceResolved`.
- **Lifecycle/cost:** discovery is expensive; stop/teardown when inactive via `unregisterService(registrationListener)` and `stopServiceDiscovery(discoveryListener)`.
- **Permission:** this page says **nothing** about runtime permissions for NSD/mDNS (the word "permission" appears only as a nav link). This is an absence, **not** a statement that none is required.

**hxmy implication (now V1 — mDNS pulled forward).** Replace manual IP/port entry: the Foreground Service advertises the running proxy via `registerService` so companion devices auto-discover the gateway. Use a stable `serviceName` (e.g. "hxmy proxy"), a `serviceType` (e.g. `_http._tcp` for the HTTP/CONNECT listener, plus a custom/`_socks._tcp` type for SOCKS5/PAC), and `setPort(<listening port>)`. **Read back the actual `serviceName` in `onServiceRegistered`** (Android may append " (1)") and show it in the UI. Pass the configured listen port to `setPort()` (or follow `ServerSocket(0)` + `localPort` if auto-picking). Each advertised protocol/endpoint needs its own `NsdServiceInfo` + `registerService`. Bind the advertisement lifecycle to the **Foreground Service** (register on serve, `unregisterService` on stop) so it never outlives a live listening socket. mDNS is link-local — it reaches only the L2 segment the phone shares with clients.

**PERMISSION GAP / ROADMAP RISK — NEEDS VERIFICATION:** This page is silent on permissions. mDNS on `224.0.0.251:5353` is exactly the local-network access gated by newer Android permissions. Since §1 establishes that `ACCESS_LOCAL_NETWORK` (Android 17) applies to "framework-level APIs such as `NsdManager`" and to multicast, **NSD advertising/discovery on Android 17 with target SDK 37+ almost certainly requires `ACCESS_LOCAL_NETWORK`**, and `NEARBY_WIFI_DEVICES` may apply on Android 13/14. The exact NSD-specific requirement must be confirmed against the local-network-permission / nearby-devices docs before shipping V1; do not assume NSD is permission-free based on the NSD page alone.

**Citation:** *Network service discovery (NSD / mDNS)* — https://developer.android.com/develop/connectivity/wifi/use-nsd (with cross-reference to *Local network permission*, §1)

---

## 未能从文档核实的点 (Open / Needs Verification)

1. **Sources with `fetched=false`:** **None.** Every one of the 14 supplied documentation facts has `fetched: true`. No source failed to load.

2. **Inbound listen/accept gating on the Android 17 *behavior-changes* page (Q1):** The *Behavior changes: Android 17* page does **not** itself state that binding a `0.0.0.0` listening socket or accepting inbound LAN connections requires `ACCESS_LOCAL_NETWORK` — its wording centers on discovering/connecting OUTWARD. The inbound-gating answer is fully supported by the authoritative *Local network permission* detail page (which explicitly lists "accepting incoming TCP connections" and "Inbound LAN Request: Fails"), so the question is **answered**, but note the answer rests on the detail page, not the summary page.

3. **`ACCESS_NETWORK_STATE` requirement for reading network state (Q6):** The *Reading network state* page asserts no particular permission is needed to read connectivity state, but it explicitly flags that the `ConnectivityManager` API reference generally requires `ACCESS_NETWORK_STATE`. The provided facts are **not authoritative** on whether `ACCESS_NETWORK_STATE` may be omitted. **NEEDS VERIFICATION** against the `ConnectivityManager` API reference. Recommendation: declare `ACCESS_NETWORK_STATE` defensively.

4. **NSD/mDNS runtime-permission requirement (Q9):** The *Network service discovery* page contains **no** statement about runtime permissions (`NEARBY_WIFI_DEVICES`, `ACCESS_LOCAL_NETWORK`, or any Android 13/14/16/17 interaction). Whether advertising/discovering via `NsdManager` requires a runtime grant on Android 13/14 (`NEARBY_WIFI_DEVICES`) and on Android 17 target SDK 37+ (`ACCESS_LOCAL_NETWORK`, which the §1 source says applies to `NsdManager` and multicast) is **NEEDS VERIFICATION** against the local-network-permission / nearby-devices documentation before shipping V1.

5. **`Dispatchers.Default`, `lifecycleScope`, and custom long-running Service `CoroutineScope` (Q7):** The *Kotlin coroutines on Android* page explicitly does **not** document `Dispatchers.Default`, does **not** document `lifecycleScope`, and gives **no** guidance on building a custom scope for a long-running Service/component. The recommended pattern for the proxy's Service-owned scope (`CoroutineScope(SupervisorJob() + Dispatchers.IO)` cancelled in `onDestroy()`) is **best practice not grounded in the supplied facts** — **NEEDS VERIFICATION** against the linked coroutines best-practices / advanced docs (`/kotlin/coroutines/coroutines-best-practices`, `/kotlin/coroutines/coroutines-adv`).

6. **Thread-pool exhaustion under many concurrent blocking-socket connections (Q7/Q8):** The coroutines page endorses "many coroutines on one thread" but gives **no numeric concurrency cap**, and classic blocking `java.net` socket reads block (do not suspend) an `Dispatchers.IO` thread. Whether to use non-blocking/suspending socket I/O or bound concurrency (`limitedParallelism` / a semaphore) to avoid pool exhaustion is **not resolved** by the supplied facts — **NEEDS VERIFICATION** against the advanced-coroutines best-practices docs.

7. **Google Play review outcome for the chosen `foregroundServiceType` (Q2):** The facts establish that Android 14+ apps must declare the FGS type in Play Console with a justification, and that **no** documented type names a proxy/gateway/server use case. Whether Play will accept `connectedDevice` (justification stretch) or require `specialUse` for hxmy is a **policy-review judgment not determinable from the documentation** — **NEEDS VERIFICATION** via actual Play Console submission/review.
