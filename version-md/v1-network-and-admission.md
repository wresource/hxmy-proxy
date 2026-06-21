# hxmy proxy · V1 · Network Monitoring, Interface Scanning & Admission Control

> 隶属 [v1-design.md](./v1-design.md)；Android 行为已对照 [v1-grounded-reference.md](./v1-grounded-reference.md) 核证，记忆性断言已剔除。

This section specifies the V1 implementation of the connectivity-awareness and admission-control subsystem of **hxmy proxy** (package `com.mzstd.hxmyproxy`). It covers the classes under `core/network` and `core/security` that detect networks, enumerate shareable interfaces, detect the system VPN, gate the **mandatory** `ACCESS_LOCAL_NETWORK` permission, publish the mDNS/NSD service records, enforce VPN-egress safety, and decide — per inbound client connection — whether it is admitted. It does **not** design the proxy listeners/relay themselves (`core/proxy/*`), the auth wire protocol (SOCKS5/HTTP CONNECT), the foreground-service lifecycle, the PAC body, or the client/session list UI; those are owned by other sections. Forward-compatibility hooks for them are noted inline.

> **mDNS/NSD is IN V1 (U1, 2026-06-22) — as a CONVENIENCE LAYER on top of the IP scheme.** The per-scenario IP entry list (one entry per selected interface IP) + manual proxy config + PAC remains the **PRIMARY, broadest-compatibility path**; mDNS is **additive**, never a replacement. The **network-side publish/lifecycle** (`MdnsPublisher` over `NsdManager`, FGS-bound, multi-interface advertising) lives **here** in `core/network` (§16); the **PAC body / recommended-entry content** that lists `hxmyproxy.local` first with the concrete interface IP(s) as fallback lives in [v1-pac-and-sharing.md](./v1-pac-and-sharing.md) (`ProxyEntryComputer`/`GeneratePacUseCase`). The two are coordinated by cross-reference. **No surface may ever emit `.local` without an IP fallback underneath** — mDNS is unreliable on Windows / some routers / some Android clients (grounded reference §9; design.md §17.2). Per *Network service discovery* (§9), `NsdManager` multicast on `224.0.0.251:5353` is local-network access and is therefore gated by the same **mandatory** `ACCESS_LOCAL_NETWORK` (target SDK 37) covered in §10.

### 0. File / package map

```text
com.mzstd.hxmyproxy.core.network
  ConnectivityObserver.kt          // wraps ConnectivityManager default-network callback -> StateFlow<ConnectivityState>
  TetherObserver.kt                // TetheringManager event callback -> tethered iface names (hotspot/USB/BT-PAN)
  InterfaceScanner.kt              // enumerates NetworkInterface (+ tether hints) -> List<ShareInterface>
  VpnEgressGate.kt                 // derives VPN/egress state from the DEFAULT network's NetworkCapabilities
  LocalNetworkPermissionManager.kt // ACCESS_LOCAL_NETWORK (target SDK 37) MANDATORY hard gate — gates accept() AND mDNS multicast
  MdnsPublisher.kt                 // NsdManager registerService for _http._tcp / _socks._tcp / PAC; FGS-bound; multi-interface (§16)
  NetworkModels.kt                 // shared enums/data classes (ConnectivityState, VpnState, etc.)
  NetworkRefreshCoordinator.kt     // debounced event pipeline that recomputes ShareState inputs

com.mzstd.hxmyproxy.core.security
  AccessController.kt              // accepted-socket LOCAL-address -> selected-interface admission decision
  EgressGuard.kt                   // anti-SSRF destination filter (post-DNS-resolution)
```

`ShareInterface`, `ProxyEntry`, `InterfaceType`, `InterfaceStatus`, `ProxyProtocol` exist in the global state model. This subsystem **produces** `ShareInterface` and **consumes** the user's interface selection; `ShareState` assembly lives in `data/repository/NetworkRepository` (referenced, not implemented here).

> **Scope trim (D7).** No live per-client byte-counter `StateFlow`. The coordinator emits **immutable, throttled snapshots** (interface list changes are event-driven and rare; there is no I/O-frequency churn here). Per-client rate/stat storms are the proxy-core's concern and are emitted as throttled aggregates there, never as raw deltas into a Compose-observed flow. **(U1 supersedes the original D7 "No `MdnsPublisher`":** the publisher is now in V1 — see §16 — but it is still event-driven and re-publishes only on interface-list change, so it introduces no recomposition churn.)

### 1. Threading & coroutine model (D6)

- Every public stream is a `SharingStarted.WhileSubscribed(5_000)`-shared `StateFlow`, so system callbacks register only while something observes (ViewModel or Foreground Service) and tear down with a grace period.
- All blocking enumeration (`NetworkInterface.getNetworkInterfaces()` is a synchronous JNI call) runs on `Dispatchers.IO` via `withContext` (main-safe wrapper), consistent with the coroutines guidance that blocking I/O must use `Dispatchers.IO` (*Kotlin coroutines on Android*).
- This subsystem does **not** own the long-lived scope. It receives the **Service-owned** `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (created by `ProxyForegroundService`, cancelled in `onDestroy()`). That custom-Service-scope pattern is **not documented** by the coroutines page — **NEEDS VERIFICATION** against the advanced-coroutines best-practices docs (*Kotlin coroutines on Android* explicitly omits custom long-running Service scopes; see grounded reference §7/§10).
- `ConnectivityManager` callbacks are delivered on a dedicated `Handler` thread (built from a `HandlerThread`), using the `registerDefaultNetworkCallback(callback, handler)` Handler overload (*Reading network state*). **Do not** call synchronous `ConnectivityManager` methods inside `onAvailable` (documented race); react in `onCapabilitiesChanged`, which arrives immediately after `onAvailable` on API 26+ (*Reading network state*). Callback bodies only `trySend` to the flow; they never block.
- `AccessController.decide()` is a pure, lock-free function over an immutable snapshot (`@Volatile` reference swap), callable from any proxy-accept thread with zero contention.

> The proxy-core relay concurrency model is **decided** (D6): Kotlin coroutines + blocking `java.net` sockets on a dedicated `Dispatchers.IO.limitedParallelism(N)` dispatcher — **not** raw NIO selectors, **not** Netty. It is specified in the proxy-core section; this subsystem only consumes the resulting accepted `Socket` and the connectivity/egress state. The head-of-line-blocking trade-off at the parallelism cap and the choice of `N` are documented there.

### 2. Shared models — `core/network/NetworkModels.kt`

```kotlin
enum class InterfaceType { WIFI, HOTSPOT, USB, BLUETOOTH, ETHERNET, UNKNOWN }

enum class InterfaceStatus { UP, DOWN, NO_USABLE_ADDRESS }

/** What the connectivity layer knows about the phone's *egress* (the app's DEFAULT network). */
data class ConnectivityState(
    val hasDefaultNetwork: Boolean,
    val validatedInternet: Boolean,       // NET_CAPABILITY_VALIDATED on the DEFAULT network
    val internetCapable: Boolean,         // NET_CAPABILITY_INTERNET (setup-only; see VALIDATED)
    val egressVpn: VpnState,              // derived from the DEFAULT network's caps ONLY
    val defaultTransports: Set<Transport> // WIFI/CELLULAR/ETHERNET/VPN... informational
) {
    companion object {
        val UNKNOWN = ConnectivityState(false, false, false, VpnState.UNKNOWN, emptySet())
    }
}

enum class Transport { WIFI, CELLULAR, ETHERNET, BLUETOOTH, VPN, USB, OTHER }

enum class VpnState { ACTIVE, INACTIVE, UNKNOWN }

/** One usable IP bound to one local interface; multiple per NIC are emitted as separate rows. */
data class InterfaceAddress(
    val address: InetAddress,
    val prefixLength: Int,
    val isIpv6: Boolean,
    val isLinkLocal: Boolean,   // 169.254/16 or fe80::/10
    val isGlobal: Boolean       // routable scope (informational for entry priority)
)
```

`ShareInterface` carries the parsed subnet (admission display only — see §8) and the **local bind address** clients actually reach:

```kotlin
data class ShareInterface(
    val id: String,                 // stable: "$name#${address.hostAddress}"
    val name: String,               // wlan0, wlan1, ap0, rndis0, usb0, bt-pan, eth0, ...
    val type: InterfaceType,
    val address: InetAddress,       // the phone's own address ON this interface == the local bind/receive address
    val prefixLength: Int,
    val gatewayLike: Boolean,       // phone holds .1/.129-style address => it OWNS this AP/tether segment
    val isSelected: Boolean,        // user choice, joined from SettingsRepository
    val status: InterfaceStatus,
    val cidr: Cidr                  // precomputed network (for display / V1.1, NOT the V1 admission key)
)
```

### 3. `ConnectivityObserver` — `core/network/ConnectivityObserver.kt`

**Responsibility:** Single source of truth for the phone's *egress* (the app's default network) and whether that egress rides a VPN. Wraps **`registerDefaultNetworkCallback`** — the per-UID default network is exactly the one our unbound outbound proxy sockets use (*Reading network state*). Emits a deduplicated `StateFlow<ConnectivityState>` plus an edge signal for the refresh coordinator. It does **not** enumerate `NetworkInterface` and does **not** detect hotspot/tether interfaces (that is `TetherObserver` + scanner; see §4 / finding "NetworkInterface may not expose tether/hotspot, registerNetworkCallback never surfaces them").

> **Correction vs draft (confirmed finding — Android Platform).** The draft also registered a *broad* `requestNetwork(TRANSPORT_VPN)` callback to "see VPN networks that are not the default." That is **removed**. For the V1 safety decision we care **only** whether the **app's own default network** carries the VPN; a VPN that exists but is not this app's default does **not** protect our egress and must **not** read as protected. `registerDefaultNetworkCallback` already returns the per-UID default — a split-tunnel-excluded app correctly sees the underlying non-VPN network there (*Reading network state*; verified finding "VPN detection via TRANSPORT_VPN is necessary but does not prove this app's egress goes through the VPN"). Iterating `getAllNetworks()` for "any VPN" is the documented anti-pattern and is not used.

```kotlin
interface ConnectivityObserver {
    val state: StateFlow<ConnectivityState>
    /** Edge signal: any default-network callback fired. The coordinator debounces re-scans. */
    val changeSignals: SharedFlow<ChangeReason>
}

enum class ChangeReason { DEFAULT_AVAILABLE, DEFAULT_LOST, CAPABILITIES_CHANGED, LINK_PROPERTIES_CHANGED }
```

Implementation (`AndroidConnectivityObserver`, `@Singleton`, Hilt):

```kotlin
val state: StateFlow<ConnectivityState> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val cbHandler = Handler(handlerThread.looper)

    val defaultCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { /* do NOT query synchronously here */ signal(DEFAULT_AVAILABLE) }
        override fun onLost(n: Network)      { trySend(noDefaultSnapshot()); signal(DEFAULT_LOST) }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
            // React here: caps are handed to us; build the snapshot from THIS default network's caps.
            trySend(snapshotFrom(c)); signal(CAPABILITIES_CHANGED)
        }
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { signal(LINK_PROPERTIES_CHANGED) }
    }

    cm.registerDefaultNetworkCallback(defaultCb, cbHandler)   // Handler overload, API 26+; min is API 29
    trySend(primeFromActiveNetwork(cm))                       // initial value via getActiveNetwork()/getNetworkCapabilities
    awaitClose { runCatching { cm.unregisterNetworkCallback(defaultCb) } }
}
.distinctUntilChanged()
.stateIn(serviceScope, SharingStarted.WhileSubscribed(5_000), ConnectivityState.UNKNOWN)
```

`snapshotFrom(caps)` builds `ConnectivityState` from the **default network's** `NetworkCapabilities`:
- `egressVpn` = `VpnEgressGate.evaluate(caps)` (§5).
- `validatedInternet` = `caps.hasCapability(NET_CAPABILITY_VALIDATED)`; `internetCapable` = `caps.hasCapability(NET_CAPABILITY_INTERNET)`. Per *Reading network state*, INTERNET signals setup only; **VALIDATED** signals actual probed public-internet reachability (excludes captive portals). "Gateway up" gates on **VALIDATED**.
- `defaultTransports` mapped from `caps.hasTransport(...)`. A VPN keeps `TRANSPORT_VPN` while swapping its underlying `TRANSPORT_WIFI`/`TRANSPORT_CELLULAR`, so one network can report VPN + the underlying physical transport together (*Reading network state*).
- Null `activeNetwork` / `onLost` → `hasDefaultNetwork=false`, `egressVpn=INACTIVE` (treated by the fail-closed gate as "not protected").

**Permission.** Declare `android.permission.ACCESS_NETWORK_STATE` in the manifest. The *Reading network state* page asserts no permission is needed merely to *read* connectivity state, but it explicitly flags that the `ConnectivityManager` API reference generally requires `ACCESS_NETWORK_STATE` — **NEEDS VERIFICATION** against the `ConnectivityManager` reference; we declare it defensively (it is install-time/normal, no runtime prompt).

**Edge cases / version specifics**
- `registerDefaultNetworkCallback(cb, handler)` overload is API 26+; min is API 29.
- In the **default** callback, `onLost` means loss of *default status*, not necessarily total disconnection (*Reading network state*) — we recompute a full snapshot rather than assuming offline.
- We never assume `onAvailable`→`onCapabilitiesChanged` ordering beyond the documented "caps arrive immediately after available on API 26+"; every relevant callback recomputes an idempotent snapshot.
- We never hold a `Network` reference long-term; the synchronous prime path re-queries `getActiveNetwork()` to avoid acting on a stale handle.
- There is a hard limit on concurrent `NetworkCallback` registrations (*Reading network state*); using a single default callback keeps us far under it.

### 4. `InterfaceScanner` + `TetherObserver` — `core/network/`

**Responsibility:** Enumerate local NICs into the `ShareInterface` list (the *ingress* side: where clients connect to us). `scan()` is a pure function over `NetworkInterface` plus a hint set of currently-tethered interface names; no connectivity callbacks of its own.

```kotlin
interface InterfaceScanner {
    suspend fun scan(tetheredIfaceNames: Set<String>): List<ShareInterface>   // runs on Dispatchers.IO
}
```

> **Correction vs draft (confirmed finding — "vendor-specific interface names unreliable; NetworkInterface may not expose tether/hotspot; registerNetworkCallback never surfaces them").** Two changes:
> 1. **Hotspot/USB/BT-PAN are NOT detected via `ConnectivityManager` callbacks.** When the phone *hosts* a SoftAP / USB-tether / BT-PAN segment, that downstream interface is **not** a framework `Network` and never surfaces through `registerDefaultNetworkCallback` / `registerNetworkCallback`. We obtain tethered interface names from **`TetheringManager.addTetheringEventCallback()` / `getTetheredIfaces()`** (API 30+), and additionally re-scan `NetworkInterface` on a debounced timer/network-change so we still catch tether changes on devices where the callback is unreliable.
> 2. **Name patterns are a weak hint, not a contract.** Type is derived **structurally first**, name only as a tiebreaker.

#### 4.1 `TetherObserver`

```kotlin
class TetherObserver(context: Context, private val scope: CoroutineScope) {
    private val tm = context.getSystemService(TetheringManager::class.java)
    val tetheredIfaces: StateFlow<Set<String>> = /* addTetheringEventCallback -> onTetheredInterfacesChanged */
}
```
- Falls back to `getTetheredIfaces()` on register and to the `ACTION_TETHER_STATE_CHANGED` broadcast where the callback is unavailable. The returned names (e.g. `ap0`, `wlan1`, `rndis0`, `usb0`, `bt-pan`) feed `scan()` as a **trusted "phone owns this segment"** signal — far more reliable than name matching.
- **NEEDS VERIFICATION (on-device):** exact `TetheringManager` availability/visibility for an unprivileged app across OEMs/Android 10–17; the API is the canonical one but OEM behavior varies. Document the `NetworkInterface`-polling fallback as the guaranteed path.

#### 4.2 `scan()` algorithm

1. `NetworkInterface.getNetworkInterfaces()` → `Collections.list(...)` on `Dispatchers.IO`; wrap in `runCatching` (can throw `SocketException` on some OEMs) → empty list on failure, logged as a diagnostics input.
2. For each NIC:
   - Skip if `!nic.isUp` or `nic.isLoopback`.
   - Iterate `nic.interfaceAddresses` (gives **both** the `InetAddress` and its `networkPrefixLength`; do **not** use `inetAddresses`, which loses the prefix).
   - Classify each address scope (link-local, multicast→skip, global).
   - **Never read `getHardwareAddress()` for identity** — Android 11+ nulls MAC for non-privileged apps; identity keys off `name#address` only.
3. **Address filtering & multiplicity:**
   - Emit **one `ShareInterface` row per usable address**; a NIC with IPv4 + IPv6 (or multiple IPv4s) yields multiple rows sharing `name` but distinct `id`/`address`. UI groups by `name`.
   - **IPv4 and IPv6 are both emitted.** IPv4 rows get higher entry priority (hotspot/USB/BT clients are predominantly RFC1918 IPv4). IPv6 **link-local** (`fe80::`) is flagged `isLinkLocal=true` and is generally not advertised as an entry (clients can't easily target a scoped `fe80::%wlan0` literal); IPv6 **global/ULA** is kept and may produce entries.
   - **Link-local IPv4 (`169.254/16`):** kept with `isLinkLocal=true`, surfaced for Ethernet/USB diagnostics; not recommended as a default entry.
   - **Multicast / wildcard / unspecified** addresses are dropped.
   - A NIC that is `UP` but has zero usable addresses → single row with `status = NO_USABLE_ADDRESS` (greyed in UI; never matched by admission).
4. **Type classification (structural, then name):**
   - If `name ∈ tetheredIfaceNames` → the phone **owns** this segment → `HOTSPOT` (Wi-Fi SoftAP), `USB` (`rndis*`/`usb*`/`ncm*`), or `BLUETOOTH` (`bt-pan`/`bnep*`) by sub-name; default `HOTSPOT`/`UNKNOWN`. Set `gatewayLike=true` (phone typically holds `.1`/`.129`).
   - Else if the phone's address looks like a gateway (`.1`/`.129`) on a `/24`-ish broadcast NIC → likely an owned segment even if `TetheringManager` didn't report it.
   - Else use the **name hint** (§4.3) for `WIFI`/`ETHERNET`, falling back to `UNKNOWN`.
5. Build `Cidr` (display) and stable `id = "$name#${address.hostAddress}"`.
6. Sort deterministically: by `InterfaceType` rank (WIFI, HOTSPOT, USB, ETHERNET, BLUETOOTH, UNKNOWN), then name, then IPv4 before IPv6 — stable order across scans (prevents UI flicker).

#### 4.3 Name-hint table (weak signal only)

```kotlin
private val NAME_HINTS: List<Pair<Regex, InterfaceType>> = listOf(
    Regex("^(ap\\d+|wlan1|swlan\\d+|softap.*)$") to InterfaceType.HOTSPOT,   // SoftAP names vary by OEM
    Regex("^wlan0$")                              to InterfaceType.WIFI,
    Regex("^(rndis\\d+|usb\\d+|ncm\\d+)$")        to InterfaceType.USB,
    Regex("^(bt-pan|bnep\\d+)$")                  to InterfaceType.BLUETOOTH,
    Regex("^(eth\\d+|ethernet\\d*)$")             to InterfaceType.ETHERNET,
)
fun nameHint(name: String): InterfaceType =
    NAME_HINTS.firstOrNull { it.first.matches(name) }?.second ?: InterfaceType.UNKNOWN
```

Caveats (in code comments + Diagnostics): `wlan1` is SoftAP on most Qualcomm/MediaTek builds but a second STA radio on some dual-Wi-Fi devices; SoftAP also appears as `swlan0`/`ap0`/`softap0`; USB tether as `rndis0`/`usb0`/`ncm0`; `rmnet*` is the **cellular modem, not** USB tether and must stay `UNKNOWN`. Misclassification only changes the **label/icon**, never admission (admission keys off the accepted socket's local address, §8), so a mislabel never wrongly admits/rejects a client.

### 5. `VpnEgressGate` — `core/network/VpnEgressGate.kt`

**Responsibility:** Pure derivation of `VpnState` from the **default network's** `NetworkCapabilities`, plus the V1 **VPN-down strategy** evaluation, plus the **fail-closed** semantics consumed by the accept path. No Android lifecycle.

```kotlin
object VpnEgressGate {
    /** ACTIVE iff the DEFAULT network carries the VPN transport. */
    fun evaluate(caps: NetworkCapabilities?): VpnState = when {
        caps == null -> VpnState.UNKNOWN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VpnState.ACTIVE
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) -> VpnState.ACTIVE  // equivalent per docs
        else -> VpnState.INACTIVE
    }
}

enum class VpnDownStrategy { CONTINUE, WARN, BLOCK }   // persisted; default = BLOCK

sealed interface VpnGate {
    data object Allow : VpnGate
    data class Reject(val reason: String) : VpnGate
}

fun vpnGate(state: ConnectivityState, strategy: VpnDownStrategy): VpnGate = when (strategy) {
    VpnDownStrategy.CONTINUE -> VpnGate.Allow
    VpnDownStrategy.WARN     -> VpnGate.Allow            // UI/notification warns; traffic flows
    VpnDownStrategy.BLOCK    ->
        if (state.egressVpn == VpnState.ACTIVE && state.validatedInternet) VpnGate.Allow
        else VpnGate.Reject("VPN egress not confirmed")
}
```

**Grounding (D4).**
- VPN detection per *Reading network state*: `hasTransport(TRANSPORT_VPN)`, equivalently the **absence** of `NET_CAPABILITY_NOT_VPN`. We evaluate **only** the app's default network's caps, so a split-tunnel-excluded app correctly shows `INACTIVE` (it sees the underlying non-VPN network as its default).
- **Outbound (upstream) sockets MUST follow the system default network.** The proxy core creates origin sockets **without** any `Network` binding and **never** calls `bindProcessToNetwork()` / `Network.bindSocket()`. Per *VPN (Android connectivity)*, an unbound socket on a normal full-tunnel VPN egresses through the VPN automatically; this is the desired behavior and requires no work. This invariant is stated explicitly so a developer fixing inbound delivery (§7) does not accidentally pin the *whole process* to Wi-Fi and silently bypass the VPN (verified finding "VPN-egress guarantee is asserted but never enforced").
- `VpnState.UNKNOWN` and unvalidated default → `BLOCK` rejects: **fail-closed**, matching the product default.

**Fail-closed atomicity (confirmed finding "block must fail closed atomically").** `BLOCK` is not just a new-connection gate. On any default-network change that transitions egress to "not protected," the proxy must **immediately tear down all active relayed connections** and stop accepting new ones until egress is re-confirmed — there must be no leak during the asynchronous detection gap between the real VPN drop and the callback. This subsystem exposes the transition as an edge signal (`changeSignals` + a derived `egressProtected: StateFlow<Boolean>`); the proxy-core relay subscribes and closes in-flight sockets. (The teardown action itself lives in proxy-core; the *signal* and the fail-closed contract are defined here.)

**Egress self-test is the authoritative signal — NEEDS VERIFICATION as a runtime task.** `TRANSPORT_VPN` on the default network is **necessary but not sufficient**: a **route-based / partial split-tunnel** VPN (`VpnService.Builder.addRoute()` covering only some prefixes) can leave `TRANSPORT_VPN` present while traffic to non-routed destinations egresses in the clear (verified finding "VPN-egress sharing is undermined by per-app VPN routing / split-tunnel"). V1 therefore:
- Surfaces a distinct diagnostic state **"VPN present on device, but this app may be excluded (split-tunnel/route-restricted)."**
- Specifies an **outbound egress IP self-test** (open an outbound socket exactly as the relay does, hit an IP-echo endpoint, compare against the non-VPN baseline) as the signal that *drives* the `BLOCK` decision, not the capability bit alone. The self-test wire details and cadence are **NEEDS VERIFICATION** (no doc covers an egress probe); the capability bit remains the cheap fast-path gate.

### 6. CIDR primitive (display / V1.1) — `core/security/AccessController.kt`

`Cidr` is retained for **display** (showing each entry's subnet) and for forward-compat (V1.1 may re-introduce subnet-scoped policy). It is **not** the V1 admission key (see §8). Byte-array masking handles IPv4 and IPv6 uniformly; v4/v6 cross-matches are rejected up front.

```kotlin
data class Cidr(val network: ByteArray, val prefixLength: Int, val isIpv6: Boolean) {
    fun contains(ip: InetAddress): Boolean {
        val a = ip.address
        if ((a.size == 16) != isIpv6) return false
        if (a.size * 8 < prefixLength) return false
        var bits = prefixLength; var i = 0
        while (bits >= 8) { if (a[i] != network[i]) return false; i++; bits -= 8 }
        if (bits > 0) {
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (network[i].toInt() and mask)) return false
        }
        return true
    }
    companion object {
        fun of(addr: InetAddress, prefixLength: Int): Cidr {
            val raw = addr.address; val net = raw.copyOf()
            var bits = prefixLength; var i = 0
            while (bits >= 8) { i++; bits -= 8 }
            if (bits > 0 && i < net.size) net[i] = (net[i].toInt() and (0xFF shl (8 - bits))).toByte()
            for (j in (if (bits > 0) i + 1 else i) until net.size) net[j] = 0
            return Cidr(net, prefixLength, raw.size == 16)
        }
    }
}
```

### 7. Listener binding & inbound-reply routing

**Bind a single dual-stack wildcard listener per protocol port** (confirmed finding "No IPv6 strategy"). Binding the **unspecified wildcard** — `ServerSocket()/bind(InetSocketAddress(port))` with no explicit address (or a `ServerSocketChannel` bound the same way) — accepts **both** IPv4 and IPv6 on Android/Linux, where `IPV6_V6ONLY` defaults to `false`. Do **not** bind the explicit `0.0.0.0` literal — that is the IPv4 wildcard only and silently rejects native IPv6 clients. We keep one wildcard listener (no per-interface rebinds; admission filters in user space, §8).

> **Correction vs draft (D4 + verified finding).** The draft said listeners stay bound to `0.0.0.0`. V1 binds the **dual-stack wildcard** instead, and adds two robustness items the draft omitted:
> - **`SO_REUSEADDR` on every listening socket** (partially-correct finding on EADDRINUSE): mitigates the watchdog stop/wait/restart rebind race against sockets lingering in `TIME_WAIT`. It does **not** let two live listeners share a port (that is `SO_REUSEPORT`), so a competing app already holding the port must still be handled by a fallback-port path or a loud "port in use" diagnostic — the actually-bound port is verified post-bind, never assumed from the constant.
> - **Inbound-reply routing is an explicit verification task, not an assumption.**

**Inbound LAN reply routing — NEEDS VERIFICATION (D4).** The inbound SYN from a same-subnet client is always delivered to a locally-bound socket regardless of routing. The open question is the **reply path** while a system VPN is up: a VPN installs per-UID rules, and the claim is that an accepted socket's reply could be steered into the VPN table (which has no LAN `/24` route) and black-holed unless the accepted FD is pinned to the underlying LAN `Network` via `Network.bindSocket()`.

Per the verified review, this is **plausible but not a near-universal failure**: Android routing is source-address-aware, an accepted socket replies from the physical interface's local IP, and per-network source/oif rules usually route that to the physical interface's table (which holds the connected LAN route) ahead of the per-UID VPN rule — which is why comparable LAN server apps work with a VPN up. V1 therefore treats reply-pinning as a **documented mitigation to verify on-device**, not settled fact:
- **Verification task:** a Diagnostics probe that completes an actual TCP handshake from a LAN-side address (not merely `isListening`) with the system VPN active, on representative devices.
- **Conditional mitigation:** if the probe shows black-holing, pin **only the accepted (server-facing) FD** to the matching underlying LAN `Network` via `Network.bindSocket()` before replying. **Critically, this pinning is per-accepted-socket and must NEVER touch outbound origin sockets** (those must stay unbound to follow the VPN, §5) and must **never** use `bindProcessToNetwork()`.

### 8. `AccessController` — `core/security/AccessController.kt`

**Responsibility:** Decide accept/reject for an inbound client. Combined at the accept site with the VPN gate (§5), the anti-SSRF egress guard (§9), and **optional** auth (U3, owned by proxy-core — §8.1). Allocation-free and lock-free on the hot path.

> **Correction vs draft (confirmed finding "Admission control by source-IP subnet is spoofable and mis-buckets under overlapping private ranges").** Two changes:
> 1. **Admission and interface-attribution key off the accepted socket's LOCAL address (`Socket.getLocalAddress()`), not the remote source IP.** The local address is the concrete interface IP the kernel selected to receive the connection — authoritative for *which interface a client arrived on*. The remote IP cannot disambiguate when two selected interfaces have **overlapping** RFC1918 ranges (e.g. USB `rndis0` 192.168.42.x and a hotspot also on 192.168.42/24). The remote IP is used **for display only**.
> 2. **Source-IP / subnet admission is explicitly a convenience/scoping filter, NOT a security boundary** (U3; confirmed finding "Source-IP admission control is trivially spoofable on a flat L2 segment"). Any on-link device can self-assign an in-range address and pass it; it authenticates nothing. The only real boundary, **when the user enables it**, is **auth** (U3, OPTIONAL — see §8.1), enforced in proxy-core. UI copy must never present interface selection as a security guarantee.

#### 8.1 Auth is OPTIONAL (U3, 2026-06-22 — restores "认证可选")

> **Supersedes the prior D5 "auth-required-by-default / mandatory-auth-beyond-trusted-network" framing, which is REMOVED.**

- **Default = no auth.** The proxy is usable immediately without configuring credentials (broad applicability is the product stance).
- **Non-trusted-network warning.** When the user starts sharing on a network that is not marked trusted (open Wi-Fi / hotspot with other clients / unknown LAN), the UI shows a clear, non-blocking warning: **"未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用"**. It informs, it does not gate — the user may proceed.
- **When auth IS enabled** (SOCKS5 RFC 1929 user/pass and/or HTTP Basic — wire format owned by proxy-core, grounded reference §8), credentials are stored in **EncryptedSharedPreferences / Keystore**, never plaintext DataStore, never logged.
- The anti-SSRF `EgressGuard` (§9) hardening is **kept regardless of the auth setting** — it is orthogonal to who connects; it limits *where* the proxy can dial.

```kotlin
class AccessController {
    // Set of the phone's own local bind addresses for currently-selected, UP interfaces.
    @Volatile private var allowedLocalAddrs: Set<InetAddress> = emptySet()

    /** Called by NetworkRefreshCoordinator on every recompute. Atomic immutable-set swap. */
    fun updateSelectedLocalAddrs(addrs: Set<InetAddress>) { allowedLocalAddrs = addrs }

    /** Hot path: any proxy accept thread. `localAddr` = accepted socket's local address. */
    fun decide(localAddr: InetAddress, remoteForDisplay: InetAddress): AdmissionDecision {
        if (localAddr.isLoopbackAddress) return AdmissionDecision.Allow(LOOPBACK)
        val allowed = allowedLocalAddrs
        if (allowed.isEmpty()) return AdmissionDecision.Reject(NO_INTERFACE_SELECTED)
        return if (normalize(localAddr) in allowed) AdmissionDecision.Allow(SELECTED_INTERFACE)
               else AdmissionDecision.Reject(INTERFACE_NOT_SELECTED)
    }
}

sealed interface AdmissionDecision {
    data class Allow(val matchedBy: AllowReason) : AdmissionDecision
    data class Reject(val reason: RejectReason) : AdmissionDecision
}
enum class AllowReason { LOOPBACK, SELECTED_INTERFACE }
enum class RejectReason { NO_INTERFACE_SELECTED, INTERFACE_NOT_SELECTED }
```

**Usage at the accept site (in `core/proxy`, shown for contract clarity):**

```kotlin
val client = serverSocket.accept()
val local  = client.localAddress                                   // authoritative: which interface received it
val remote = (client.remoteSocketAddress as InetSocketAddress).address
when (val d = accessController.decide(local, remote)) {
    is AdmissionDecision.Reject -> { metrics.rejected(d.reason); client.closeQuietly() }
    is AdmissionDecision.Allow  -> when (vpnGate(connectivity.state.value, strategy)) {
        is VpnGate.Reject -> { metrics.vpnBlocked(); replyVpnDownThenClose(client) }   // see §10 client-side signalling
        VpnGate.Allow     -> handOffToProxy(client)   // proxy-core then runs OPTIONAL auth (U3, §8.1) before relay
    }
}
```

**Edge cases**
- **Loopback (`127.0.0.1`, `::1`):** always allowed — supports the phone's own apps / `adb forward`. V1 hardcodes allow; the anti-SSRF guard (§9) still blocks the *destination* loopback.
- **IPv4-mapped IPv6 (`::ffff:192.168.1.5`):** `accept()` on a dual-stack socket may surface a mapped local address; `normalize()` unwraps the last 4 bytes to an `Inet4Address` before comparison so v4/v6 representations of the same local interface compare equal.
- **Empty selection / all selected NICs down:** reject-all (fail-closed). The coordinator excludes `NO_USABLE_ADDRESS`/`DOWN` interfaces on the next recompute.
- **Race during swap:** a client accepted microseconds before `updateSelectedLocalAddrs` sees the old set; benign (one extra connection on a just-deselected interface). The immutable-set reference write is atomic; no locking. A documented policy for in-flight connections on interface loss: they are allowed to drain, except in `BLOCK` mode on egress-loss where they are torn down (§5).

### 9. `EgressGuard` (anti-SSRF) — `core/security/EgressGuard.kt`

**Responsibility (U3; confirmed finding "SOCKS5 / HTTP CONNECT can reach phone-local and private-LAN services (SSRF)").** The proxy must not become a pivot into the phone's own loopback services. (Private-LAN egress is now allowed by default under U3 — see below — but the loopback/link-local/this-host/own-listener blocks are retained unconditionally.) `EgressGuard` runs in proxy-core **after DNS resolution** (resolve the client-supplied host first, then check the **resolved IP**) so attacker-supplied names that resolve to loopback/RFC1918 (DNS-rebinding-style) cannot bypass a literal string check.

```kotlin
object EgressGuard {
    /** Called with the RESOLVED destination IP, on the same default network the relay will use. */
    fun isBlocked(dest: InetAddress, ownListenPorts: Set<Int>, blockPrivateLan: Boolean): Boolean = when {
        dest.isLoopbackAddress                                  -> true   // 127.0.0.0/8, ::1
        dest.isLinkLocalAddress                                 -> true   // 169.254/16, fe80::/10
        dest.isAnyLocalAddress                                  -> true   // 0.0.0.0, ::
        dest.isMulticastAddress                                 -> true
        isProxyOwnAddress(dest, ownListenPorts)                 -> true   // loop back into our own ports
        blockPrivateLan && isPrivate(dest)                      -> true   // 10/8, 172.16/12, 192.168/16, fc00::/7
        else                                                    -> false
    }
}
```

- **Default: BLOCK** loopback, link-local, this-host (`0.0.0.0`/`::`), multicast, and the proxy's **own listen addresses/ports** — these are the anti-SSRF core and are blocked unconditionally (U3 keeps this hardening).
- **Private RFC1918/ULA LAN egress is ALLOWED by default (U3, 2026-06-22, restores the original "认证可选" applicability stance).** "Block private-LAN destinations" is an **explicit opt-in toggle, off by default** (the `blockPrivateLan` flag) — broad applicability wins, since many legitimate uses (a client reaching a printer/NAS/router admin on its own segment via the proxy) need LAN egress. Users who want strict SSRF isolation flip the toggle on. **This reverses the prior D5 default** (which blocked RFC1918 by default with allow as opt-in).
- The grounded reference (§1) confirms `ACCESS_LOCAL_NETWORK` does **not** mitigate this — that permission gates the app's *own* local-network access (and is **mandatory** for the app to function as a LAN proxy at all on target SDK 37, §10); it does not distinguish "accept inbound from LAN" from "dial outbound to LAN." Loopback to the device's own services is open across Android 10–17 regardless of permission state. The fix is application-level, as above.
- DNS resolution and the subsequent connect both use the **default network** (no explicit `Network` binding) so DNS and TCP ride the same VPN egress — avoiding a DNS-leak-vs-egress split (cross-ref proxy-core SOCKS5 remote-DNS handling).

### 10. `LocalNetworkPermissionManager` — `core/network/LocalNetworkPermissionManager.kt`

**Responsibility (U2; confirmed CRITICAL finding "ACCESS_LOCAL_NETWORK gates the INBOUND listening socket itself").** Encapsulate the `ACCESS_LOCAL_NETWORK` runtime permission and treat it as a **mandatory, day-one hard gate equal in weight to the FGS** — not a diagnostics nicety. **First release targets SDK 37 (U2), so this permission is required from the very first build** and gates **BOTH** the core relay's inbound `accept()` **AND** the §16 mDNS multicast.

> **Updated for U2 (2026-06-22): targetSdk 37, permission MANDATORY day one.** Per the grounded reference (§1, *Local network permission*): the permission gates **all local-network traffic in BOTH directions**, including **"accepting incoming TCP connections"** and multicast (so it covers `NsdManager`/mDNS on `224.0.0.251:5353`, §16). Enforcement is "implemented deep in the networking stack, so it applies to all networking APIs" — **binding the wildcard cannot evade it.** When denied, inbound TCP from LAN/hotspot/USB peers **times out** (not `ECONNREFUSED`); UDP/multicast returns `EPERM`. On **Android 17 with target SDK 37** (our first-release target, minSdk stays 29), "local network is blocked by default" — denial does not merely degrade discovery, **it breaks the core relay's `accept()` and silences mDNS.** It is in the **`NEARBY_DEVICES`** group (a user who granted a sibling like Bluetooth is not re-prompted). Guard every permission API call by `Build.VERSION` (the permission constant only exists / only enforces on the enforcing `SDK_INT`).

```kotlin
enum class LocalNetworkPermissionStatus { NOT_REQUIRED, GRANTED, DENIED, NEEDS_RATIONALE }

interface LocalNetworkPermissionManager {
    val status: StateFlow<LocalNetworkPermissionStatus>
    fun refresh()                 // re-check on resume / after request / from the service callback path
    fun requiresPermission(): Boolean    // device SDK_INT >= LOCAL_NET_API (app targetSdk is fixed at 37, U2)
    fun permissionName(): String? // android.permission.ACCESS_LOCAL_NETWORK, or null when not required
}
```

Implementation & policy:
- **Version gating (U2).** First release **targets SDK 37**, so `ACCESS_LOCAL_NETWORK` is **mandatory from day one** ("local network is blocked by default for all apps that update their target SDK" — *Local network permission* / *Behavior changes: Android 17*). minSdk stays **29 / Android 10**; on devices below the enforcing `SDK_INT` the permission is not enforced and `requiresPermission()` returns false. `requiresPermission()` returns true when the device API reaches the enforcing version (the app `targetSdk` is already 37). Centralize the threshold in one constant (`LOCAL_NET_API`) so it is a one-line bump if the final `SDK_INT` differs from current previews (do **not** hardcode "36 == Android 17" as fact — **NEEDS VERIFICATION** of the exact enforcing `SDK_INT`). **Guard all permission API use by `Build.VERSION`** so older devices neither reference the constant nor request it.
- **Hard gate on Start — refuse the running state if denied (U2).** If `requiresPermission()` and status != `GRANTED`, the app **refuses to enter the running/sharing state** and shows a **blocking** pre-permission rationale screen (this permission is non-obvious and easily denied), not a diagnostics line. The request is made **with a clear rationale BEFORE starting the proxy**; if denied, the running state is refused and a blocking explanation is shown (denial manifests as a client-side TCP timeout, not an immediate refusal — grounded reference §1). The runtime request (`ActivityResultContracts.RequestPermission`) lives in the UI layer; this manager only computes status (`ContextCompat.checkSelfPermission`) and rationale (`shouldShowRequestPermissionRationale`, via a transiently-passed Activity, never held).
- **Manifest.** `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK"/>`.
- **Denial detection at runtime.** Map the **timeout** signature (and, on the NDK, `android_getnetworkblockedreason()` → `ANDROID_NETWORK_BLOCKED_REASON_LNP`) to the existing "本地网络权限未授权" error class so Diagnostics shows the true cause rather than a generic timeout.
- **Revocation while running.** `refresh()` is called from the service's network-callback path and on `ON_RESUME`, so revocation is reflected without a restart.
- **First-run sequencing (confirmed finding "Permission-request sequencing is unspecified").** Order: (1) `POST_NOTIFICATIONS` (API 33, for the FGS notification — note the FGS itself runs even if denied, per grounded reference §5); (2) `ACCESS_LOCAL_NETWORK` (mandatory under targetSdk 37) with denial = **hard block** on Start; (3) battery-optimization as a soft, dismissible prompt. Gate the Start action visually disabled while the local-network permission is not granted.

### 11. `NetworkRefreshCoordinator` — `core/network/NetworkRefreshCoordinator.kt`

**Responsibility:** The event-driven, debounced pipeline; the only place that re-runs `InterfaceScanner`. Joins connectivity change signals, tether changes, and the user's selection into a recomputed `ShareInterface` list and an `AccessController` snapshot — **without** tearing down any callback/listener.

```kotlin
class NetworkRefreshCoordinator(
    private val connectivity: ConnectivityObserver,
    private val tether: TetherObserver,
    private val scanner: InterfaceScanner,
    private val settings: SettingsRepository,       // selectedInterfaceIds: Flow<Set<String>>, vpnStrategy
    private val accessController: AccessController,
    private val scope: CoroutineScope,              // the Service-owned scope (§1)
) {
    val interfaces: StateFlow<List<ShareInterface>>
}
```

**Pipeline:**

```kotlin
val interfaces: StateFlow<List<ShareInterface>> =
    merge(
        connectivity.changeSignals.map { Unit },
        tether.tetheredIfaces.map { Unit },         // hotspot/USB/BT-PAN appear/disappear (NOT via NetworkCallback)
        flowOf(Unit)                                // initial scan
    )
    .debounce(400.milliseconds)                     // collapse VPN/Wi-Fi reconnection storms (4-8 callbacks in <300ms)
    .mapLatest { scanner.scan(tether.tetheredIfaces.value) }   // cancel an in-flight scan if a newer change lands
    .combine(settings.selectedInterfaceIds) { scanned, selectedIds ->
        scanned.map { it.copy(isSelected = it.id in selectedIds) }
    }
    .distinctUntilChanged()
    .onEach { ifaces ->
        accessController.updateSelectedLocalAddrs(
            ifaces.filter { it.isSelected && it.status == InterfaceStatus.UP }
                  .map { it.address }                // LOCAL bind addresses, not subnets (§8)
                  .toSet()
        )
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

`ProxyEntry` recomputation (host = **live interface IPv4 literal**, port, protocol), notification text, PAC content, and the §16 `MdnsPublisher` re-publish are downstream collectors in `NetworkRepository`; they refresh reactively. The proxy **listeners are never restarted** — only the admission snapshot, advertised IP entries, and the mDNS records change.

**Notes**
- `debounce(400ms)` + `mapLatest`: exactly one rescan after a transition settles; a newer change cancels a stale scan.
- Selection toggles bypass the scan (no new `changeSignals`) but flow through `combine`, so they are near-instant while still atomically updating `AccessController`.
- **`hxmyproxy.local` is now emitted FIRST with the live IP(s) as fallback (U1, supersedes D1's "no `.local` anywhere").** mDNS is in V1 (§16) as a convenience layer; the per-interface IP entries remain the always-listed baseline. The PAC/recommended-entry (owned by pac-and-sharing) puts `.local` first then the concrete IP(s), e.g. `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT` — **never `.local` without the IP fallback.** If a selected interface goes down, its IP entry disappears on the next recompute and the mDNS record is re-published; the IP fallback always backstops the unreliable `.local`, closing the original "broken-on-arrival unresolvable host" risk.

### 12. Client-side signalling of blocked states (confirmed finding "block strategy produces a silent, unexplained outage")

When admission/VPN-gate rejects, the **remote client** must get an explanatory signal, not a silent drop:
- **HTTP / CONNECT:** return a synthetic `503` (e.g. `503 hxmy proxy: upstream VPN is down` / `…access blocked`) at connection-setup time, so the client's browser shows a reason. (An already-established CONNECT tunnel that drops mid-session can only be cleanly closed — the synthetic status applies at setup, which is when the block decision is made.)
- **SOCKS5:** return RFC 1928 reply `REP=0x02` (not allowed by ruleset) for an admission/policy denial, or `0x03` (network unreachable) for VPN-down, rather than dropping the socket. (Exact REP mapping is owned by proxy-core; this subsystem supplies the *reason* — admission vs VPN-down.)
- **Phone side:** the instant egress protection is lost, raise a high-visibility notification state ("VPN down — traffic blocked") with a one-tap switch to warn/continue (the `WARN` strategy already exists). First-run flow explains that `BLOCK` means traffic stops when the VPN drops.

### 13. End-to-end sequence (startup → steady state → network change)

```text
1. Foreground Service: startForeground() FIRST (within 5s), THEN this subsystem starts on the Service scope.
2. Gate (MANDATORY, targetSdk 37 per U2): if ACCESS_LOCAL_NETWORK not granted -> refuse running state, show blocking rationale (§10).
   This gate covers BOTH accept() and mDNS multicast.
3. ConnectivityObserver registers the DEFAULT-network callback (Handler thread). TetherObserver registers TetheringManager callback.
4. Coordinator initial flowOf(Unit) -> InterfaceScanner.scan(tetheredIfaces) on IO.
5. scan() enumerates NICs + tether hints -> ShareInterface rows (one per usable address), structurally typed, dual-stack-aware.
6. combine with selectedInterfaceIds -> isSelected filled.
7. accessController.updateSelectedLocalAddrs(selected & UP local addrs) -> @Volatile swap.
8. NetworkRepository assembles ShareState (per-interface IP entries always listed, egressVpn from ConnectivityObserver.state,
   localNetworkPermissionGranted) -> ViewModel/UiState -> Compose; notification + PAC update.
8b. MdnsPublisher.publish(ports) -> registerService(_http._tcp / _socks._tcp / PAC); read back serviceName in
    onServiceRegistered. PAC/recommended-entry (in pac-and-sharing) lists hxmyproxy.local FIRST then the IP fallback (§16, U1).
9. Client connects to the wildcard listener -> accept() -> AccessController.decide(localAddr, remote)
   -> VpnEgressGate.vpnGate(state, strategy) -> proxy-core OPTIONAL auth (U3, §8.1) -> EgressGuard (anti-SSRF) -> relay or explanatory reject.

--- Wi-Fi -> VPN handoff (network change) ---
10. Default-network callback fires onCapabilitiesChanged x N within ~300ms; state recomputed each time (distinctUntilChanged dedups).
11. Coordinator.debounce(400ms) -> mapLatest cancels stale scan -> rescan.
12. New ShareInterface list -> updateSelectedLocalAddrs (atomic) -> entries/PAC/notification refresh; MdnsPublisher re-publishes per active link (§16).
13. If egress transitions to "not protected" and strategy=BLOCK: fail-closed -> tear down active relays + stop accepting (§5).
14. Listeners are NEVER unregistered or rebound; only the admission snapshot + advertised entries (IP + mDNS records) change.
15. On stop: MdnsPublisher.unpublish() -> unregisterService, so no advertisement outlives a live socket (§16).
```

### 14. Testability & determinism

- `InterfaceScanner.nameHint()`, structural typing, `Cidr.contains/of`, `EgressGuard.isBlocked`, `VpnEgressGate.evaluate/vpnGate`, `AccessController.decide` are **pure** → fast JVM unit tests (no Android, no real sockets):
  - name hints over `wlan0`/`wlan1`/`ap0`/`swlan0`/`rndis0`/`usb0`/`ncm0`/`bt-pan`/`bnep0`/`eth0`/`rmnet_usb0`(→UNKNOWN)/random;
  - structural typing over (tethered-name-set × gateway-like × flags);
  - admission by local address incl. overlapping-range disambiguation and v4-mapped-v6 normalization;
  - egress guard over loopback/link-local/any/multicast/own-ports/RFC1918 (`blockPrivateLan` off→allowed default, on→blocked)/global;
  - vpn gate table-driven over 3 strategies × {ACTIVE, INACTIVE, UNKNOWN} × {validated, unvalidated}.
- `ConnectivityObserver`, `TetherObserver`, `LocalNetworkPermissionManager` are interfaces → Robolectric/fakes. The coordinator is tested with fakes emitting bursts to assert exactly one rescan per debounce window and atomic `AccessController` updates.

### 15. Out-of-scope hooks (forward-compat only, not implemented in V1)

- **`MdnsPublisher` is now IN V1 (U1) — see §16**, no longer a forward-compat hook. (It already follows the shape described here: subscribes to `NetworkRefreshCoordinator.interfaces` and re-publishes on each change.)
- **`Watchdog`/`HealthChecker`:** will read `ConnectivityObserver.state` and the egress-protected signal; this subsystem deliberately never restarts listeners. (Per the verified review, a watchdog can only restart in-process listener sockets while the FGS is alive; it cannot resurrect a killed process from the background on Android 12+ without a battery-optimization allowlist or a user-interaction/alarm/FCM trigger — out of scope here.)
- **Rich per-client sessions / rate-limit / blacklist / daily quota:** build on `AdmissionDecision` by adding reasons/branches; the `@Volatile` snapshot pattern extends to a blacklist set without locks. Live per-client stats must be emitted as **throttled immutable snapshots** (D7), never raw deltas into a Compose-observed flow.
- **Per-network Profiles:** feed a different `selectedInterfaceIds` source into `combine` — no structural change.

### 16. `MdnsPublisher` (mDNS/NSD advertising) — `core/network/MdnsPublisher.kt` (U1)

**Responsibility (U1, 2026-06-22 — promoted from V1.1 deferral to V1 convenience layer).** Advertise the running proxy on the local network via **`NsdManager.registerService`** so capable clients can resolve **`hxmyproxy.local`** instead of typing a raw IP. This is **additive convenience only** — the per-interface IP entry list + manual config + PAC (the scenario-based baseline, always-works path) remains primary and the Dashboard still lists **every** per-interface IP entry. `MdnsPublisher` owns the **network-side publish/lifecycle**; the **PAC body / recommended-entry ordering** (`hxmyproxy.local` first, concrete interface IP(s) as fallback) is owned by [v1-pac-and-sharing.md](./v1-pac-and-sharing.md) (`ProxyEntryComputer` / `GeneratePacUseCase`). The two coordinate by cross-reference: this subsystem publishes the records and exposes an `isPublished`/`registeredServiceName` signal that pac-and-sharing reads to gate the synthetic `.local` entry.

**Why the home is here.** This file already owns `core/network` connectivity lifecycle (§3/§4/§11), the FGS-owned scope (§1), the `NetworkRefreshCoordinator.interfaces` stream the publisher must follow, and the mandatory `ACCESS_LOCAL_NETWORK` gate (§10) that mDNS multicast needs. The PAC *content* stays in pac-and-sharing. So: **publish/lifecycle here, advertised PAC content there.**

```kotlin
interface MdnsPublisher {
    /** Latest registered service names (Android may rename on conflict — read back, never assume). */
    val registeredServiceNames: StateFlow<Map<NsdServiceKind, String>>
    val isPublished: StateFlow<Boolean>
    fun publish(port: ProxyPorts)      // register on serve (after startForeground + permission granted)
    fun unpublish()                    // unregisterService on stop
}

enum class NsdServiceKind { HTTP, SOCKS5, PAC }   // _http._tcp, _socks._tcp, PAC service
```

**Service records (grounded reference §9, *Network service discovery*):**
- `_http._tcp` for the HTTP/CONNECT listener, `_socks._tcp` for SOCKS5, plus a PAC service (e.g. `_hxmyproxy-pac._tcp`). Each advertised endpoint is its own `NsdServiceInfo` + `registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)` with `setPort(<listening port>)`.
- **Read back `serviceInfo.serviceName` in `onServiceRegistered`** and store it — Android may rename on conflict (e.g. append " (1)"); the requested name is **not** guaranteed (grounded reference §9). Surface the actual name to the UI.

**Lifecycle — bound to the Foreground Service (grounded reference §9):**
- `publish()` is called **on serve**, only **after** `startForeground()` (§13 ordering) **and** after `ACCESS_LOCAL_NETWORK` is granted (§10) — mDNS multicast on `224.0.0.251:5353` is local-network access and is gated by the same mandatory permission (U2). If the permission is not granted, the publisher must not register (it would fail with `EPERM`); the IP/PAC baseline still works for clients already pointed at an IP.
- `unpublish()` calls `unregisterService(listener)` on stop, so an advertisement never outlives a live listening socket.
- The publisher subscribes to `NetworkRefreshCoordinator.interfaces` (§11) and **re-publishes on interface-list change** so the advertisement tracks the active links. This is event-driven and rare (no recomposition churn — consistent with the D7 snapshot stance).

**Multi-interface advertising:**
- **Advertise per active link.** When the phone owns multiple segments (Wi-Fi + hotspot + USB), the record is published so a client **resolves `hxmyproxy.local` to the IP on its own segment** — each client gets the interface IP reachable from where it sits. Note this per-segment resolution detail: there is no single global IP; the name resolves locally per L2 segment the phone shares with the client.
- mDNS is **link-local** — it only reaches the L2 segment the phone shares with the client (grounded reference §9).

**The `.local`-without-IP-fallback prohibition (U1, design.md §17.2):**
- mDNS is unreliable on **Windows / some routers / some Android clients**. Therefore **no surface may ever emit `hxmyproxy.local` without the concrete interface IP(s) underneath as fallback.** PAC chains and the recommended-entry output (owned by pac-and-sharing) list `.local` **first**, then the IP(s), e.g. `"SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT"`. This subsystem enforces the contract by only ever exposing `isPublished=true` when a real registration succeeded; pac-and-sharing must keep the IP fallback regardless.

**NEEDS VERIFICATION:**
- `NsdManager` advertising under the **mandatory** `ACCESS_LOCAL_NETWORK` (targetSdk 37) — grounded reference §9 establishes the permission applies to `NsdManager` and multicast, but the exact NSD-specific runtime behavior (and any `NEARBY_WIFI_DEVICES` interaction on Android 13/14) must be confirmed on-device.
- Per-interface / multi-segment `hxmyproxy.local` resolution behavior across OEM mDNS responders and client OSes (the multi-link "resolves to the IP on its own segment" claim).

## 与文档基准/评审的修订记录

- **D1 / mDNS removed everywhere (review: "PAC/UI hardcode hxmyproxy.local" + brief).** ~~Deleted `MdnsPublisher` from the V1 package map and all `hxmyproxy.local` occurrences~~ **— SUPERSEDED by U1 (2026-06-22), see 决策更新 below.** The prior D1 stance (mDNS deferred, raw IP only, no `MdnsPublisher`) is reversed: mDNS is now in V1 as an additive convenience layer (§16), `.local` is emitted FIRST with the IP fallback underneath. The still-valid D1 principle "never advertise an unresolvable host alone" is preserved as the IP-fallback rule.
- **D3 / ACCESS_LOCAL_NETWORK upgraded from soft status to HARD GATE (confirmed CRITICAL finding; *Local network permission*, *Behavior changes: Android 17*).** Rewrote §10: the permission gates **accepting inbound TCP in both directions**, enforced deep in the stack (wildcard bind cannot evade it); denial = **TCP timeout** (not `ECONNREFUSED`), UDP `EPERM`. **AMENDED by U2 (2026-06-22):** first release now **targets SDK 37**, so the permission is **mandatory day one** (no longer "`targetSdk=36` for first release / deferred enforcement"), and it gates **BOTH** `accept()` **AND** mDNS multicast (§16). Added (retained): refuse the running state when denied, pre-permission rationale, `NEARBY_DEVICES` group note, NDK `android_getnetworkblockedreason()` denial mapping, first-run sequencing.
- **D4 / VPN egress invariant + fail-closed (confirmed/partially-correct findings; *VPN*, *Reading network state*).** §5: outbound origin sockets stay **unbound** and **never** use `bindProcessToNetwork()`/`Network.bindSocket()` (so they follow the VPN automatically). VPN/egress is evaluated **only on the app's default network's caps** (removed the draft's broad `requestNetwork(TRANSPORT_VPN)` callback). `BLOCK` is now **fail-closed atomically** — tear down in-flight relays on egress loss, no detection-gap leak. Added the split-tunnel caveat and an **egress IP self-test** as the authoritative signal (capability bit alone insufficient). Inbound LAN reply-pinning is documented as a **NEEDS VERIFICATION** mitigation (per-server-FD only), not asserted fact.
- **Admission keyed off LOCAL socket address, not remote IP (confirmed finding).** §8 rewrite: `AccessController.decide(localAddr, …)`; remote IP is display-only. Resolves mis-bucketing under overlapping RFC1918 ranges and the reply-pinning cascade. Stated explicitly that **subnet/source-IP admission is a convenience filter, not a security boundary** (spoofable on flat L2).
- **D5 / security defaults (confirmed findings: open-proxy default, SSRF, plaintext creds).** Added `EgressGuard` (§9): post-DNS-resolution destination filter. **AMENDED by U3 (2026-06-22):** auth is now **OPTIONAL** (restores "认证可选" — the "auth-required-by-default / mandatory-auth-beyond-trusted-network" framing is **REMOVED**, replaced by a non-blocking non-trusted-network warning, §8.1); and `EgressGuard` now **ALLOWS private-LAN (RFC1918) egress by default** with an optional `blockPrivateLan` toggle (was default-deny). **RETAINED hardening:** unconditional block of loopback/link-local/any/multicast/own-listen-ports; credentials (when auth enabled) must use **EncryptedSharedPreferences / Keystore**, never plaintext, never logged; source-IP admission remains a convenience filter, not a security boundary.
- **Interface typing made structural; tether detection via `TetheringManager` (confirmed finding).** §4: hotspot/USB/BT-PAN are detected via `TetheringManager.addTetheringEventCallback()`/`getTetheredIfaces()` + `NetworkInterface` polling — **never** `registerNetworkCallback` (which never surfaces hosted segments). Names demoted to a weak hint behind tether-ownership and gateway-like structural signals; MAC never read for identity (Android 11+ nulls it).
- **IPv6 / dual-stack (confirmed finding).** §7: bind the **unspecified dual-stack wildcard** (not the `0.0.0.0` literal) so native IPv6 clients are accepted on Android/Linux (`IPV6_V6ONLY=false`). Admission and egress logic are address-family-generic.
- **D6 / concurrency decided (confirmed finding "concurrency never decided").** §1 records the proxy-core decision: coroutines + blocking `java.net` sockets + `Dispatchers.IO.limitedParallelism(N)` (not NIO selectors, not Netty); details in proxy-core. The Service-owned `CoroutineScope(SupervisorJob()+Dispatchers.IO)` is flagged **NEEDS VERIFICATION** (undocumented in *Kotlin coroutines on Android*).
- **D7 / scope trim + no recomposition storm (confirmed findings).** Removed over-engineered structure; coordinator emits immutable throttled snapshots; live per-client stats explicitly excluded from this subsystem's Compose-observed flow.
- **`ACCESS_NETWORK_STATE` declared defensively (*Reading network state*).** §3: the page claims no permission is needed to read state but flags the API-reference discrepancy — declared and marked **NEEDS VERIFICATION**.
- **Client-side blocked-state signalling + `SO_REUSEADDR` (confirmed/partially-correct findings).** §12 adds explanatory `503`/SOCKS5 REP on reject; §7 adds `SO_REUSEADDR` and post-bind port verification (with the `SO_REUSEPORT` nuance).
- **Notes on refuted/uncertain findings (not acted on as defects).** The "PAC hardcodes hxmyproxy.local → broken on arrival" was *refuted against the original design's own roadmap* (PAC and mDNS shipped together there). **Under U1 (2026-06-22) mDNS is back in V1**, and the original concern is addressed not by deferral but by the **mandatory IP-fallback rule** (`.local` is always backed by the concrete interface IP underneath, §16), so a client where `.local` fails still resolves via the IP token.

### 决策更新 (2026-06-22)

These updates SUPERSEDE the prior D1/D3/D5 (and amend D6) for this subsystem. Prior content kept where still correct; only the deltas below changed.

- **U1 — mDNS/NSD is NOW IN V1 (was deferred), as a CONVENIENCE LAYER.** Added §16 `MdnsPublisher` (`core/network/MdnsPublisher.kt`): `NsdManager.registerService` for `_http._tcp` (HTTP/CONNECT), `_socks._tcp` (SOCKS5), and a PAC service; read back the actual `serviceName` in `onServiceRegistered` (Android may rename on conflict, grounded ref §9); FGS-bound lifecycle (`publish` on serve after `startForeground` + permission, `unregisterService` on stop); subscribes to `NetworkRefreshCoordinator.interfaces` and re-publishes per active link (multi-interface — a client resolves `hxmyproxy.local` to the IP on its own L2 segment). **Home split:** publish/lifecycle lives HERE; the PAC body / recommended-entry ordering (`hxmyproxy.local` FIRST, then concrete IP fallback) lives in pac-and-sharing — coordinated by cross-reference. The per-interface IP entry list + manual config + PAC stays the PRIMARY path; Dashboard still lists every per-interface IP. **Hard rule:** never emit `.local` without the IP fallback underneath. Added/updated §0 package map, §11 downstream note, §13 sequence, and the header note. mDNS multicast (`224.0.0.251:5353`) is local-network access → gated by the mandatory `ACCESS_LOCAL_NETWORK` (U2).
- **U2 — first release targets SDK 37 (was 36); `ACCESS_LOCAL_NETWORK` MANDATORY day one.** Rewrote §10: the permission is required from the first build and **hard-gates the running state** — request with a clear rationale BEFORE starting the proxy; if denied, REFUSE the running state and show a blocking explanation (denial manifests as a client-side TCP timeout, not a refusal — grounded ref §1). It gates **BOTH** inbound `accept()` **AND** mDNS multicast. minSdk stays 29 / Android 10. Guard all permission API use by `Build.VERSION`. Removed the "`targetSdk=36` for first release / defer enforcement" line; `requiresPermission()` now keys on device `SDK_INT` only (app targetSdk is fixed at 37). Updated §0, §13 sequence.
- **U3 — auth is OPTIONAL (restores "认证可选"); other hardening kept.** Added §8.1: default = no auth; non-trusted-network shows a clear non-blocking warning ("未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用"); when auth IS enabled (SOCKS5 RFC1929 / HTTP Basic) credentials go in EncryptedSharedPreferences/Keystore, never plaintext/logged. `EgressGuard` (§9) now **ALLOWS private-LAN (RFC1918) egress by default** with an optional `blockPrivateLan` toggle (renamed from `allowPrivateLan`), while **unconditionally blocking** loopback / link-local / this-host / multicast / own-listeners. Source-IP admission stays a convenience filter only. Removed all "auth-required-by-default / mandatory-auth-beyond-trusted-network" framing.
- **U4 — connection/resource limits (DataStore-backed, user-adjustable, 3 presets) are owned by proxy-core, not this subsystem.** No change to the classes here; cross-referenced only. The `limitedParallelism(N)` decision noted in §1 stays, with the numeric `N`/buffer/caps sized in proxy-core (NEEDS VERIFICATION item 7 already covers the `N`/memory ceiling). Documented here only that this subsystem consumes the accepted `Socket` and does not own the caps.
- **U5 — foreground service `connectedDevice` primary / `specialUse` fallback / `dataSync` rejected is owned by service-permissions-compat.** No change to the classes here; cross-referenced (NEEDS VERIFICATION item 8). This subsystem only requires that the FGS is alive and its companion permission satisfied before it binds/accepts/publishes.

## NEEDS VERIFICATION

1. **`ACCESS_LOCAL_NETWORK` enforcing `SDK_INT`.** That "Android 17 == SDK 37 enforcing" matches the final release numbering (first release targets SDK 37 — U2). Centralized in `LOCAL_NET_API`; confirm the exact enforcing `SDK_INT` against the shipped *Local network permission* / *Behavior changes: Android 17* docs.
2. **`ACCESS_NETWORK_STATE` requirement for reading network state.** *Reading network state* says no permission is needed but flags that the `ConnectivityManager` reference generally requires it; verify against the `ConnectivityManager` API reference. Declared defensively meanwhile.
3. **Inbound LAN reply routing under an active system VPN.** Whether accepted server-socket replies are black-holed by per-UID VPN routing (requiring `Network.bindSocket()` pinning of the server FD) or route symmetrically via source-address-aware rules. On-device probe across representative devices/VPNs (real TCP handshake from a LAN address, not `isListening`). Mitigation designed (per-server-FD pin only), not enabled until verified.
4. **VPN egress self-test.** Wire format, endpoint, and cadence of the outbound IP-echo egress check that drives `BLOCK` (no doc covers an egress probe). Needed to detect route-based/partial split-tunnel leaks that `TRANSPORT_VPN` alone misses.
5. **`TetheringManager` availability for an unprivileged app** across OEMs and Android 10–17 (`addTetheringEventCallback`/`getTetheredIfaces` visibility). `NetworkInterface` polling is the guaranteed fallback; confirm the callback path where possible.
6. **Custom long-running Service `CoroutineScope`** (`SupervisorJob()+Dispatchers.IO`, cancelled in `onDestroy()`). Not covered by *Kotlin coroutines on Android*; verify against the advanced-coroutines best-practices docs.
7. **Proxy-core parallelism cap `N`.** The numeric `limitedParallelism(N)` value and the head-of-line-blocking trade-off at the cap (decision is coroutines+blocking sockets; the exact `N` and buffer-memory ceiling are sized in proxy-core, not here).
8. **Google Play FGS-type acceptance** (`connectedDevice` vs `specialUse`) — policy review; out of this subsystem but cross-referenced because the FGS must be alive (and its companion permission satisfied) before this subsystem binds/accepts.
9. **mDNS/NSD runtime behavior under the mandatory `ACCESS_LOCAL_NETWORK` (now V1, U1/§16).** Grounded reference §9 establishes the permission applies to `NsdManager` and multicast (`224.0.0.251:5353`), but the exact NSD-specific runtime behavior under targetSdk 37 — and any `NEARBY_WIFI_DEVICES` interaction on Android 13/14 — must be confirmed on-device before shipping. (Promoted from "before V1.1" to a V1 verification item.)
10. **Multi-segment `hxmyproxy.local` resolution (U1/§16).** That a single advertised name resolves, per client, to the interface IP on the client's own L2 segment across OEM mDNS responders and client OSes (Windows/macOS/Android/iOS). The IP-fallback chain backstops failures, but the per-segment resolution claim itself is **NEEDS VERIFICATION** on-device.
