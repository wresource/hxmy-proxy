# hxmy proxy · V1 · PAC Server & Client Entry Publishing

> 隶属 [v1-design.md](./v1-design.md)；Android 行为已对照 [v1-grounded-reference.md](./v1-grounded-reference.md) 核证，记忆性断言已剔除。

> **Subsystem scope:** the embedded HTTP server that serves `GET /proxy.pac` (and a small help/landing page), the dynamic generation of `FindProxyForURL` from the live set of `ProxyEntry`, the computation of that entry list from selected interfaces × live listening ports, and the client-facing config payloads (manual proxy, PAC URL, copy-to-clipboard).
>
> **V1 名称解析策略（决策 D1，2026-06-22 更新）：** V1 **同时**实现 mDNS / NSD 发布，但定位为 **IP 方案之上的便利层**，**不是**主路径。**主路径（PRIMARY，最广兼容、永远可用）**仍是「每个选中接口一条 IP 入口（per-scenario IP entry list）+ 手动代理配置 + PAC」；mDNS 是**附加（additive）**项。具体语义：
> - PAC 链与推荐入口输出**先列 `hxmyproxy.local`，再列具体接口 IP 作为 fallback**，例如 `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`。
> - **强制不变量：任何 PAC/入口链中，`.local` 之下必须始终带 IP fallback**，绝不单独发出 `.local`。理由：mDNS 在 Windows / 部分路由器 / 部分 Android 客户端上不可靠（grounded-ref §9 / design.md §17.2「必须保留 IP fallback」）。
> - Dashboard 仍逐条列出**每个接口的 IP 入口**（基于场景的基线，永远可用）。
> - 多接口：按每条活动链路分别发布；客户端把 `hxmyproxy.local` 解析为**它自己所在网段**上的那个 IP（见 §11 的解析细节）。
> - mDNS 多播 `224.0.0.251:5353` 属本地网络访问，由 U2 起**强制**的 `ACCESS_LOCAL_NETWORK` 一并覆盖（grounded-ref §1）。
> 此前「mDNS 推迟到 V1.1」的表述**已作废**：mDNS 现在**就在 V1 内实现**（NSD 发布见 §11，已从「Deferred」提升为「V1-IMPLEMENTED」）。`PacServer` / `GeneratePacUseCase` / `GenerateClientConfigUseCase` 的设计保持不变，但「`.local` + IP」链现在是 **V1 行为**，不再是未来项。

---

## 1. Responsibilities & boundaries

This subsystem owns:

1. **`PacServer`** — a tiny single-purpose embedded HTTP/1.1 server that answers three routes: `GET /proxy.pac`, `GET /` (landing/help), `GET /healthz` (liveness probe for the Diagnostics page and the deferred Watchdog). Admission control is delegated to the shared `AccessController`.
2. **`GeneratePacUseCase`** — pure transform: `List<ProxyEntry>` (+ options) → PAC JavaScript text. No I/O, no Android types beyond what `ProxyEntry` already carries.
3. **`GenerateClientConfigUseCase`** — pure transform: `List<ProxyEntry>` (+ a `ShareInterface` or "best") → human/clipboard-ready `ClientConfig` objects (manual host:port per protocol, the per-interface PAC URL).

This subsystem does **not** own: socket lifecycle of the HTTP/SOCKS proxies (that is `HttpProxyServer` / `Socks5ProxyServer`), interface enumeration (`InterfaceScanner`), the egress/VPN state machine (`VpnStateDetector`), or the foreground service. It **consumes** a read-only snapshot of currently-listening ports, selected interfaces, and the egress-reachability flag.

**File / package map** (V1 scope per decision D7; mDNS publishing is now **in V1** per D1/U1, so `MdnsPublisher` is included here — `ui/clients` and Room remain deferred):

| Piece | File |
|---|---|
| Embedded PAC HTTP server | `core/proxy/PacServer.kt` |
| PAC request router/handler | `core/proxy/PacHttpHandler.kt` (internal to PacServer; may live in same file) |
| PAC generation use case | `domain/usecase/GeneratePacUseCase.kt` |
| Client config generation use case | `domain/usecase/GenerateClientConfigUseCase.kt` |
| mDNS / NSD publisher (V1) | `core/network/MdnsPublisher.kt` (NsdManager registerService for `_http._tcp`/`_socks._tcp`/PAC; FGS-bound lifecycle — §11) |
| Entry computation (shared) | `data/repository/ProxyRepository.kt` (exposes `entries: StateFlow<List<ProxyEntry>>`) |
| Entry computation logic | `core/proxy/ProxyEntryComputer.kt` |
| New value types | `domain/model/ClientConfig.kt`, `domain/model/PacOptions.kt`, `domain/model/ListeningPort.kt` |
| Existing types reused | `ProxyEntry`, `ShareInterface`, `ProxyProtocol` (from §5 of design.md) |
| Admission control (reused) | `core/security/AccessController.kt` |

---

## 2. Data structures

`ProxyEntry`, `ShareInterface`, `ProxyProtocol`, `InterfaceType` already exist (design.md §5). This subsystem adds the following.

```kotlin
// domain/model/ProxyProtocol.kt  (assumed already defined; shown for the PAC mapping it must support)
enum class ProxyProtocol(val defaultPort: Int) {
    HTTP(8080),      // PAC token: "PROXY ip:port"   (also serves HTTPS CONNECT on same port)
    SOCKS5(1080),    // PAC token: "SOCKS5 ip:port"
    PAC(8899);       // not a forwardable proxy; never emitted into a FindProxyForURL token
}
```

```kotlin
// domain/model/ListeningPort.kt
// Snapshot of a socket the proxy core has CONFIRMED bound. Produced by Http/Socks5/Pac servers,
// read by ProxyEntryComputer. "bound" is the source of truth for the `reachable` flag's
// port dimension — an entry is never published for a protocol whose socket is not bound.
data class ListeningPort(
    val protocol: ProxyProtocol,
    val port: Int,                 // the ACTUAL bound port, read back post-bind (may differ from the
                                   // configured default if a fallback port was used — see §5).
    val bound: Boolean,            // true only after the listening socket bind() succeeded
    val boundAddress: String       // expected "::" (dual-stack wildcard) in V1 — see §5 bind note
)
```

```kotlin
// domain/model/PacOptions.kt
data class PacOptions(
    // Ordering of protocol tokens inside a single FindProxyForURL return string.
    // Default reproduces the design's "SOCKS5 …; PROXY …; DIRECT".
    val protocolOrder: List<ProxyProtocol> = listOf(ProxyProtocol.SOCKS5, ProxyProtocol.HTTP),
    val appendDirect: Boolean = true,           // append "; DIRECT" terminal fallback
    val includeAllInterfaceIps: Boolean = true, // chain every reachable IP, not just the primary
    val bypassLoopbackAndLocal: Boolean = true, // localhost / 127.* / 169.254.* / .local → DIRECT
    val bypassPlainHostnames: Boolean = true,   // isPlainHostName(host) → DIRECT (intranet shortcut)
    val emitMdnsHostFirst: Boolean = true,      // D1/U1: prepend "hxmyproxy.local" token to each protocol
                                                // chain, ALWAYS followed by the IP fallback chain. Gated on
                                                // MdnsPublisher.isPublished; never emit .local without IP under it.
    // D1 (2026-06-22): V1 emits hxmyproxy.local FIRST then IP literals as the fallback. mDNS is a
    // convenience layer; the IP chain is the mandatory primary/fallback and is never omitted (§11).
)
```

```kotlin
// domain/model/ClientConfig.kt
data class ClientConfig(
    val perInterface: List<InterfaceClientConfig>,   // one block per selected, reachable interface
    val recommended: InterfaceClientConfig?          // the single best block for the notification/dashboard
)

data class InterfaceClientConfig(
    val interfaceId: String,
    val interfaceLabel: String,                 // "Wi-Fi (wlan0)" — already localized upstream
    val ip: String,                             // "192.168.1.34"  (IP literal — the always-works fallback)
    val pacUrl: String,                         // "http://192.168.1.34:8899/proxy.pac" (per-IP, always works;
                                                // recommended block may additionally offer the .local PAC URL — §6/§11)
    val manualEntries: List<ManualProxyLine>,   // ordered, one per enabled protocol on this IP
    val reachable: Boolean
)

data class ManualProxyLine(
    val protocol: ProxyProtocol,                // HTTP or SOCKS5
    val host: String,                           // ip literal
    val port: Int,
    val supportsHttpsConnect: Boolean,          // true for HTTP (CONNECT tunnel), false for SOCKS5 here
    val clipboardText: String                   // canonical copyable form, see §6
)
```

---

## 3. Computing the `ProxyEntry` list — `ProxyEntryComputer`

**File:** `core/proxy/ProxyEntryComputer.kt`. Pure, synchronous, no coroutines, no Android I/O. Called by `ProxyRepository` on every relevant change and the result cached in a `StateFlow`.

```kotlin
class ProxyEntryComputer {

    /**
     * Cartesian product: (selected interface IPs) × (bound forwardable protocol ports).
     * PAC protocol is NOT forwardable, so it is excluded from entries even though the
     * PacServer's port is exposed separately as the per-interface PAC URL.
     */
    fun compute(
        selectedInterfaces: List<ShareInterface>,   // already filtered to isSelected == true & status == UP
        listeningPorts: List<ListeningPort>,         // live snapshot from proxy core (actual bound ports)
        egressReachable: Boolean                     // see §3.1 — derived from the DEFAULT network's caps
    ): List<ProxyEntry>
}
```

**Algorithm (deterministic, total ordering for stable UI/PAC output):**

1. Filter `listeningPorts` to `bound == true` and `protocol ∈ {HTTP, SOCKS5}`. Call this `forwardable`.
2. For each `iface` in `selectedInterfaces` where `iface.address` is a non-loopback IPv4 (V1: IPv4-only PAC tokens; see §9 edge cases) and `iface.status == UP`:
   - For each `lp` in `forwardable`:
     - `host = iface.address.hostAddress`
     - `reachable = lp.bound && iface.status == UP && (egressReachable || strategy != BLOCK)` — the egress/VPN dimension only flips `reachable` to `false` under `VpnDownStrategy.BLOCK`; under `CONTINUE`/`WARN` it stays `true` (the warn surfaces in UI, not in the entry).
     - `priority = interfacePriority(iface.type) * 100 + protocolPriority(lp.protocol)` (lower = better).
     - emit `ProxyEntry(host, lp.port, lp.protocol, iface.id, priority, reachable)`.
3. Sort by `priority` ascending, then by `host` lexicographically (stable tie-break so the chain order never flickers when two IPs share a priority).

```kotlin
// lower number = higher preference
private fun interfacePriority(t: InterfaceType) = when (t) {
    InterfaceType.WIFI      -> 0
    InterfaceType.ETHERNET  -> 1
    InterfaceType.HOTSPOT   -> 2
    InterfaceType.USB       -> 3
    InterfaceType.BLUETOOTH -> 4
    InterfaceType.UNKNOWN   -> 5
}
private fun protocolPriority(p: ProxyProtocol) = when (p) {
    ProxyProtocol.SOCKS5 -> 0   // remote-DNS friendly → preferred in PAC
    ProxyProtocol.HTTP   -> 1
    ProxyProtocol.PAC    -> 9   // never reached (filtered out)
}
```

The "primary interface" used for the recommended/notification entry is `entries.firstOrNull { it.reachable }` after this sort (i.e., reachable SOCKS5 on the highest-priority interface). **In V1 this is always a concrete IP literal** — it is the mandatory fallback that always works; when mDNS is published, the recommended output additionally fronts it with `hxmyproxy.local`, but the IP literal is never dropped (decision D1/U1, see §11).

> **mDNS synthetic entry (V1, decision D1/U1):** when `MdnsPublisher.isPublished == true`, `ProxyEntryComputer` additionally emits one synthetic `ProxyEntry` per forwardable protocol with `host = "hxmyproxy.local"` and a priority **above** all IP entries, so it sorts to the front of each protocol chain. Its `reachable` is gated on `MdnsPublisher.isPublished` (read back the actual registered `serviceName` from `onServiceRegistered` — Android may rename on conflict, grounded-ref §9). The per-interface IP entries remain in the list underneath as the mandatory fallback; they are never replaced by the `.local` entry.

### 3.1 The `egressReachable` flag — grounded source

`egressReachable` is **not** derived from "does some network have a VPN transport." It must be read from **the app's own default network** so that per-UID routing (split-tunnel, per-app exclusion) is reflected:

- Query `ConnectivityManager.registerDefaultNetworkCallback(callback, handler)` (Handler variant, from the Foreground Service) and read the **default** network's `NetworkCapabilities` (*Reading network state* — developer.android.com/develop/connectivity/network-ops/reading-network-state). An app excluded from a per-app VPN sees the underlying non-VPN network as its default, so `TRANSPORT_VPN` is correctly **absent** for it.
- "Egress up" is gated on `NET_CAPABILITY_VALIDATED` (actual probed internet, not just `NET_CAPABILITY_INTERNET` setup) (*Reading network state*).
- VPN presence for the BLOCK strategy uses `caps.hasTransport(TRANSPORT_VPN)` on that default network (*Reading network state*).
- **Caveat baked into the flag (decision D4):** `TRANSPORT_VPN` presence on the default network does **not** prove a given destination egresses through the tunnel under **route-based / partial split-tunnel** VPN configs. The authoritative signal is a measured egress-IP self-test; this subsystem consumes `egressReachable` as computed by `VpnStateDetector` and does not itself re-derive it. The "block must fail closed atomically across the async-callback detection gap" requirement is owned by `VpnStateDetector` / the relay core, not by PAC generation.

**Reachable flag** is a single boolean per entry combining three dimensions: socket bound, interface up, egress policy. Anything not reachable is still kept in the list (so Diagnostics can render "Wi-Fi entry: not reachable") but is **skipped** by PAC token generation and marked `reachable=false` in `ClientConfig`.

---

## 4. `GeneratePacUseCase`

**File:** `domain/usecase/GeneratePacUseCase.kt`. Pure function, no suspension required (fast string build), exposed as `operator fun invoke` for call-site uniformity. Output is deterministic given inputs → trivially testable (a pure function over the entry list, per decision D7's testability seam — no sockets, no Android types).

```kotlin
class GeneratePacUseCase {
    operator fun invoke(
        entries: List<ProxyEntry>,
        options: PacOptions = PacOptions()
    ): String
}
```

**Generation steps:**

1. Keep only `reachable` entries. If none remain → emit a **DIRECT-only** PAC (valid, never errors a client): body returns `"DIRECT"`.
2. Group reachable entries by `protocol`. Within each protocol, the IP fallback chain is the sorted list of `host:port` for that protocol (already sorted by §3). When `includeAllInterfaceIps == false`, keep only the first IP per protocol.
3. Build the single return string by concatenating, in `options.protocolOrder`:
   - for `SOCKS5`: the `hxmyproxy.local` host (when `emitMdnsHostFirst` and mDNS is published) as `"SOCKS5 hxmyproxy.local:port"` **first**, then each IP as `"SOCKS5 ip:port"`,
   - for `HTTP`: `"PROXY hxmyproxy.local:port"` first (same condition), then each IP as `"PROXY ip:port"`,
   joined by `"; "`. Append `"; DIRECT"` if `appendDirect`.
   Example with mDNS published + two Wi-Fi/Hotspot IPs: `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; SOCKS5 192.168.216.1:1080; PROXY hxmyproxy.local:8080; PROXY 192.168.1.34:8080; PROXY 192.168.216.1:8080; DIRECT`.
   **D1/U1 (mandatory invariant):** the `hxmyproxy.local` token, when present, is **always followed by the concrete IP fallback chain** in the same string. The PAC **never** emits `.local` without IP fallback underneath it (mDNS is unreliable on Windows / some routers / some Android clients — grounded-ref §9 / design.md §17.2). When mDNS is not published, the chain is IP-only and still valid.
4. Wrap in `FindProxyForURL` with bypass guards (when enabled): plain hostnames, loopback, link-local `169.254.*`, `*.local`, and the RFC1918 ranges of the *serving interfaces themselves* return `"DIRECT"` so a client never proxies traffic to the phone or to its own LAN peers through the phone. The phone's own IPs and their `/prefix` subnets are baked in as literals computed from the entries (no runtime `isInNet` DNS surprises).
5. Emit a header comment with product name, generation timestamp (epoch millis), and entry count for cache-busting/debugging. The timestamp is in a comment only — it does **not** change semantic output, so identical entry sets still produce byte-identical bodies *after* stripping the comment (used by `PacServer` ETag, §5).

> **Note on the `*.local` bypass vs. the `hxmyproxy.local` proxy host (D1/U1):** the `shExpMatch(host, "*.local")` guard returns `DIRECT` for **destination** hosts ending in `.local` (so a client doesn't tunnel its own Bonjour traffic through the phone). This is the `host` *argument* of `FindProxyForURL` (where the browser wants to go) and is **completely independent** of `hxmyproxy.local` appearing as a **proxy host** in the *return string* (where to send the traffic). The destination-bypass guard never affects the proxy-host token, and the two never conflict — the guard stays exactly as-is even though V1 now emits `hxmyproxy.local` as a proxy host.

**Canonical generated body (representative — mDNS host FIRST then IP fallback chain, decision D1/U1):**

```javascript
// hxmy proxy PAC — generated; entries=4; gen=1750579200000; mdns=published
function FindProxyForURL(url, host) {
  if (isPlainHostName(host)
      || shExpMatch(host, "*.local")
      || isInNet(host, "127.0.0.0", "255.0.0.0")
      || isInNet(host, "169.254.0.0", "255.255.0.0")
      || isInNet(host, "192.168.1.0", "255.255.255.0")
      || isInNet(host, "192.168.216.0", "255.255.255.0")) {
    return "DIRECT";
  }
  return "SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; SOCKS5 192.168.216.1:1080; PROXY hxmyproxy.local:8080; PROXY 192.168.1.34:8080; PROXY 192.168.216.1:8080; DIRECT";
}
```

When mDNS is **not** published (publish failed / `ACCESS_LOCAL_NETWORK` not yet effective), the body degrades to the IP-only chain (`SOCKS5 192.168.1.34:1080; …; DIRECT`) — still valid, since the IP fallback was already mandatory underneath the `.local` token.

`shExpMatch`/`isInNet`/`isPlainHostName` are standard PAC built-ins available in all consuming clients (Chrome/Firefox/system proxy resolvers), so no helper functions are injected.

> **Per-interface scoping (security — confirmed finding "PAC on 0.0.0.0 leaks topology"):** the generator can be invoked with the **full** entry set (for the dashboard) or with entries **filtered to a single requesting interface** (for the network-served PAC body, see §5/§7). `PacServer` serves the *scoped* body so a client on one subnet does not learn the phone's IPs on the other subnets it cannot see. The dashboard uses the full set because it is a local, authenticated surface.

---

## 5. `PacServer` — embedded HTTP server

**File:** `core/proxy/PacServer.kt`. Constructed and lifecycle-driven by `ProxyForegroundService` alongside the other servers.

```kotlin
class PacServer(
    private val proxyRepository: ProxyRepository,     // supplies entries StateFlow
    private val generatePac: GeneratePacUseCase,
    private val accessController: AccessController,
    private val pacPort: Int = 8899,
    private val ioDispatcher: CoroutineDispatcher,    // injected for testability (decision D7);
                                                      // the limitedParallelism dispatcher from §5.1
) {
    val listening: StateFlow<ListeningPort>           // exposes its own bound state (and ACTUAL port) to
                                                      // ProxyEntryComputer/Diagnostics
    suspend fun start()      // bind + accept loop; suspends until cancelled
    fun isBound(): Boolean
    suspend fun stop()       // close server socket, cancel children, await drain
}
```

### 5.1 Implementation model & concurrency (decision D6)

Use **blocking `java.net.ServerSocket` / `Socket`** accept+read loops driven by **Kotlin coroutines** on a **dedicated, bounded dispatcher** — *not* raw `java.nio` `Selector` multiplexing, and *not* Netty. This is consistent with the project-wide proxy-core decision D6 (Dispatchers.IO is endorsed for blocking I/O — *Kotlin coroutines on Android*, developer.android.com/kotlin/coroutines).

- The dispatcher is the shared proxy-core `Dispatchers.IO.limitedParallelism(N)` (PacServer is a low-traffic tenant on it; PAC handlers are short-lived, so they rarely occupy a slot for long).
- **Head-of-line-blocking trade-off:** because handlers run blocking reads, at most `N` requests progress at once; the rest queue. For PAC this is a non-issue (sub-millisecond responses, `Connection: close`), but the cap is shared with the proxy relay, so a Slowloris client must not hold a slot — hence the per-handler `withTimeout` below. `N` is chosen project-wide in the proxy-core spec; PacServer does not pick its own.
- **NEEDS VERIFICATION (D6):** the custom long-running Service-owned scope `CoroutineScope(SupervisorJob() + ioDispatcher)` cancelled in `onDestroy()` is **not** covered by the *Kotlin coroutines on Android* page (it documents `viewModelScope` only, not a custom Service scope). Mark as a best-practice to verify against the advanced-coroutines docs. PacServer's own child scope is parented under this Service scope.

### 5.2 Threading / coroutine model

- Owns a child `CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineName("PacServer"))`, parented under the Service scope (above).
- One **acceptor** coroutine: blocking `accept()` loop. For each accepted `Socket`, launch a **handler** coroutine on the same dispatcher.
- Each handler is short-lived (parse one request, write one response, close — `Connection: close`, no keep-alive needed for PAC). A `withTimeout(5_000)` guards slow/garbage clients (Slowloris).
- PAC content is **not** regenerated per request from scratch on the hot path. PacServer collects `proxyRepository.entries` in the server scope and recomputes the cached **per-interface** bodies **only when entries change** (`distinctUntilChanged`), storing them keyed by interface IP in a `@Volatile` immutable map plus per-body `etag`. Handlers read the volatile map — lock-free. ETag = hex of a stable hash over the *comment-stripped* body (§4.5) so a no-op network blip that yields identical entries returns `304` to clients that sent `If-None-Match`.

### 5.3 Request routing (`PacHttpHandler`)

| Method + path | Response |
|---|---|
| `GET /proxy.pac` (or `/proxy.pac?...`) | `200`, `Content-Type: application/x-ns-proxy-autoconfig`, `Cache-Control: no-cache`, `ETag: "<etag>"`, body = cached PAC **scoped to the interface the request arrived on** (§5.4). If `If-None-Match` matches → `304` no body. |
| `GET /` | `200`, `text/html; charset=utf-8`, the landing/help page (§7), scoped to the arrival interface. |
| `GET /healthz` | `200`, `text/plain`, body `ok` (used by Diagnostics + deferred Watchdog). Same admission rules apply; loopback always allowed. |
| `HEAD /proxy.pac` | `200` headers only (some OS proxy validators probe with HEAD). |
| any other method | `405 Method Not Allowed`, `Allow: GET, HEAD`. |
| unknown path | `404 Not Found`, tiny text body. |

**MIME correctness is load-bearing:** the PAC route MUST send exactly `Content-Type: application/x-ns-proxy-autoconfig`. Some clients (older Windows WPAD, certain Android proxy resolvers) reject `text/plain` or `application/x-javascript`. This is the single most failure-prone header in the subsystem and is asserted by an integration test.

### 5.4 Admission control (corrected: bucket by LOCAL address, not remote subnet)

Before producing any body, the handler determines **which interface the connection arrived on** and whether that interface is selected:

- **Determine arrival interface by the accepted socket's LOCAL address** — `socket.localAddress` (`Socket.getLocalAddress()`), **not** a "remote-IP falls in some selected subnet" test. *(Confirmed finding: source-IP subnet-contains is both spoofable and mis-buckets under overlapping RFC1918 ranges, e.g. USB rndis `192.168.42.x` overlapping a hotspot. The kernel-chosen local address of the accepted socket is the authoritative receiving-interface signal.)*
- If the arrival interface is **selected** → serve the body **scoped to that interface's IP only**.
- **Loopback** (`127.0.0.1` / `::1`) → always allowed (lets the phone's own browser/diagnostics fetch the PAC; sees the full set).
- Otherwise → `403 Forbidden`, short body, event counted (not logged with content) for Diagnostics.
- The **remote** peer IP is used only for display/diagnostics, never for the allow/deny decision.

**Security framing (decision D5/U3 + confirmed findings):** this admission check is a **convenience / scoping filter, NOT a security boundary.** On a flat L2 segment any on-link device can self-assign an in-range source IP, so admission cannot authenticate a peer. **Auth is OPTIONAL in V1 (default = no auth — decision U3); it is not required-by-default.** When the user does enable it, the boundary is the proxy core's auth (SOCKS5 RFC1929 user/pass + HTTP Basic); when sharing on a non-trusted network with auth off, the UI shows a clear warning ("未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用"). The UI copy must never present "subnet selection" as protection. Serving the PAC scoped-per-interface also prevents the cross-subnet topology disclosure flagged by the confirmed "PAC on 0.0.0.0 leaks topology" finding.

> **ACCESS_LOCAL_NETWORK note (decision D3/U2):** this application-layer admission runs only **after** the OS has already accepted the inbound TCP connection. **V1 targets SDK 37 (U2)**, so that accept is gated by `ACCESS_LOCAL_NETWORK` deep in the networking stack from day one — binding `::`/`0.0.0.0` does **not** evade it (*Local network permission*, developer.android.com/privacy-and-security/local-network-permission). The same permission also covers mDNS multicast (§11.5). See §9.1 for how a missing grant is handled (it is a **day-one hard gate**, not a soft reachability hint).

### 5.5 Minimal HTTP/1.1 parser

Read request line + headers until CRLFCRLF, cap header bytes at 8 KiB (reject `431` if exceeded), ignore request body (PAC is GET-only). No chunked decode needed. Robust against partial reads via a buffered read loop with the `withTimeout` guard. Parsing is a **pure function over an `InputStream`** (testable with `ByteArrayInputStream`, no sockets — decision D7 testability seam).

### 5.6 Start/stop sequence (driven by `ProxyForegroundService`)

> **Ordering invariant (grounded — *Foreground services overview*, developer.android.com/develop/background-work/services/fgs):** the service builds its notification and calls `startForeground()` **within 5 seconds** of `startForegroundService()`, and the FGS-type runtime prerequisite (decision D2) is satisfied **before** `startForeground()`. **Only then** are the listening sockets (including PAC) bound. PacServer must never bind before `startForeground()`. A PAC bind failure must never threaten the mandatory `startForeground()` call — surface it as state.

1. Service calls `pacServer.start()` inside the service's supervisor scope, after `startForeground()`.
2. Bind the listening socket. **Bind to the dual-stack wildcard** (`new ServerSocket()` then `bind(new InetSocketAddress(pacPort))` with no address, or `InetSocketAddress("::", pacPort)`), which on Android/Linux defaults to `IPV6_V6ONLY=false` and accepts both families. **Set `SO_REUSEADDR` before bind** so a rapid stop→restart does not hit transient `EADDRINUSE` from a listen port lingering in `TIME_WAIT` *(low-severity confirmed finding; note `SO_REUSEADDR` does NOT help when a competing app already owns the port — that needs the fallback/diagnostic path below)*.
3. **On `BindException` (port in use):** set `listening` to `bound=false`, surface `ProxyError.PortInUse(PAC, pacPort)` (design.md §11.3), and either probe a fallback port (e.g. `pacPort+1`) if configured or leave PAC down. PAC port being down does **not** kill HTTP/SOCKS — they are independent.
4. **On success → read back the ACTUAL bound port** (`serverSocket.localPort`) rather than assuming the configured constant, and emit `ListeningPort(PAC, actualPort, bound=true, "::")`. This flips Diagnostics "PAC: reachable" and lets `GenerateClientConfigUseCase` produce PAC URLs with the real port.
5. Launch acceptor; collect entries → maintain the per-interface `cachedBody` map.
6. `stop()`: cancel acceptor (interrupts `accept()` via socket close), cancel handler children, `join()` with a 2 s budget, close the socket, emit `bound=false`.

---

## 6. `GenerateClientConfigUseCase`

**File:** `domain/usecase/GenerateClientConfigUseCase.kt`. Pure transform consumed by the Dashboard ("recommended entry" card, copy/clipboard), the Interfaces page, the **client-setup sheet** (§7.1), and the notification builder.

```kotlin
class GenerateClientConfigUseCase {
    operator fun invoke(
        entries: List<ProxyEntry>,
        selectedInterfaces: List<ShareInterface>,
        pacPort: Int,                  // the ACTUAL bound PAC port from ListeningPort
        pacReachable: Boolean
    ): ClientConfig
}
```

**Steps:**

1. Group reachable `entries` by `sourceInterface`/`host`.
2. For each interface IP, build an `InterfaceClientConfig`:
   - `pacUrl = "http://$ip:$pacPort/proxy.pac"` — the per-interface `ip` is always an **IP literal**, the always-works form. Only `reachable=true` when `pacReachable`. When the PAC server failed to bind, `pacUrl` is still produced for display but the block carries `reachable=false` for the PAC dimension and the UI greys the "Use PAC" affordance.
   - `manualEntries` = one `ManualProxyLine` per protocol present on that IP, ordered SOCKS5 then HTTP.
3. `clipboardText` canonical forms (what "copy" puts on the clipboard):
   - SOCKS5 → `socks5://192.168.1.34:1080`
   - HTTP/HTTPS-CONNECT → `http://192.168.1.34:8080` (annotated `supportsHttpsConnect=true` so UI can show "works for HTTP and HTTPS").
   - PAC copy action (separate UI button) copies the raw `pacUrl`.
   - **Credential hygiene (decision D5/U3):** auth is **optional** (default off — U3). When the user *does* enable proxy auth (SOCKS5 RFC1929 user/pass and/or HTTP Basic), credentials are stored in `EncryptedSharedPreferences`/Keystore (never plaintext, never logged), and the copy action must **not** embed `user:pass@` in the copied entry string. Copy host:port only; offer the password via a deliberate reveal action. On Android 13+ mark sensitive clip data with `ClipDescription.EXTRA_IS_SENSITIVE`. *(On Android 10+ arbitrary background apps cannot read the clipboard anyway; the residual surface is the IME/clipboard-preview.)*
4. `recommended` = the `InterfaceClientConfig` of the primary interface (§3) with its SOCKS5 line promoted; this is what the notification's "推荐: …:1080" line and the Dashboard recommended card render. **Its concrete IP literal is the mandatory fallback; when mDNS is published, the recommended block additionally carries a `hxmyproxy.local` PAC URL / host (`pacUrl = "http://hxmyproxy.local:$pacPort/proxy.pac"`) fronting the IP form** (decision D1/U1). The IP-literal value is never dropped from the recommended block — it is the line that always works.

**Client consumption paths (documented for UI/help copy):**

- **Manual proxy config** — user copies `socks5://ip:1080` or `http://ip:8080` into the device's app/system proxy settings. SOCKS5 is recommended because it does remote DNS (design.md §7.2).
- **PAC URL per interface IP** — user pastes `http://ip:8899/proxy.pac` into the OS "automatic proxy configuration URL" field. Each selected interface IP gets its own PAC URL; the served body is scoped to that interface (§5.4).
- **Copy-to-clipboard** — every line and PAC URL has a copy button (Dashboard + Interfaces page + client-setup sheet). QR is available in V1 (design.md §16 lists "QR Code + PAC" as a V1 sharing mechanism); it encodes the same IP-literal entry string.

---

## 7. Landing / help page (`GET /`) and client-setup guidance

A static-ish HTML page (rendered from the same `cachedBody`-style per-interface snapshot) so a user who simply opens `http://<phone-ip>:8899/` in a browser on the client device gets:

- product title "hxmy proxy";
- the **PAC URL** for the IP they reached the page on (derived from the **accepted socket's local address**, §5.4 — the exact IP they can reach), as a clickable link and copy-friendly text;
- the manual SOCKS5 / HTTP host:port lines for that same IP (IP literals — the always-works form; when mDNS is published the page may also show the `hxmyproxy.local` form, with the IP shown as fallback per D1/U1);
- a one-line note: "Set your system proxy to the PAC URL, or configure SOCKS5/HTTP manually. If hxmyproxy.local does not resolve on your device, use the IP address shown."

It is intentionally minimal (no JS framework, inline CSS) and is subject to the same per-interface admission scoping (§5.4). It reuses `GenerateClientConfigUseCase` output filtered to the requesting interface IP.

### 7.1 In-app client-setup sheet (folded-in confirmed finding)

The Dashboard `[配置客户端]` button must map to a defined V1 output, not a stub. Behind it, render a **static, copy-pasteable per-platform recipe sheet** (a single Compose screen, content not a wizard engine) that interpolates the **current selected-interface IP and the actual bound ports**:

- **Windows:** Settings → Network & Internet → Proxy → either "Use setup script" (paste the PAC URL) or "Manual proxy" (paste `ip` + port).
- **macOS:** System Settings → Network → (service) → Details → Proxies → "Automatic Proxy Configuration" (PAC URL) or per-protocol host:port.
- **iOS/iPadOS:** Wi-Fi → (network) → Configure Proxy → Automatic (PAC URL) or Manual (host:port). Note: many iOS apps honor only HTTP/HTTPS proxy, not SOCKS.
- **Android client:** Wi-Fi → (network) → Advanced → Proxy → Manual (host:port) or Proxy Auto-Config (PAC URL). Note: this is per-network and system-wide only for HTTP/HTTPS-aware apps; there is no OS-level SOCKS and no per-app proxy without extra apps.

This is the cheapest single addition that most increases the chance the user reaches a working second device. All recipes show the **IP literal** value (the always-works form) and surface the PAC URL as an alternative; when mDNS is published the recipes additionally mention the `hxmyproxy.local` convenience form with the explicit caveat "if it doesn't resolve, use the IP" (decision D1/U1).

---

## 8. Sequences

**8.1 Cold start (service start):**
```
ProxyForegroundService.startForeground()  // notification first, FGS-type prereq satisfied (D2), within 5s
  → Http/Socks5/PacServer.start() (after startForeground; sockets bound here)
  → each reads back its ACTUAL bound port → emits ListeningPort(bound=true, port=actual)
  → MdnsPublisher.registerService(_http._tcp / _socks._tcp / PAC) per active link; read back serviceName in onServiceRegistered
  → ProxyRepository combines(selectedInterfaces, listeningPorts, egressReachable, mdnsPublished)
       → ProxyEntryComputer.compute() → entries StateFlow (synthetic hxmyproxy.local entry first when published, then IP literals)
  → PacServer collects entries → GeneratePacUseCase (per-interface scoped, .local-first then IP fallback) → cachedBody map/etag
  → Dashboard ViewModel collects entries + GenerateClientConfigUseCase → UiState
  → Notification shows recommended entry (IP literal always; .local form when published)
```

**8.2 Client fetches PAC:**
```
client GET http://192.168.1.34:8899/proxy.pac
  → acceptor accepts → handler coroutine (withTimeout 5s)
  → parse request line/headers
  → arrival interface = socket.localAddress → selected? no → 403
  → If-None-Match == etag? → 304
  → else 200 + Content-Type: application/x-ns-proxy-autoconfig + body scoped to THIS interface
  → close
```

**8.3 Network change (Wi-Fi → new subnet):**
```
ConnectivityObserver fires (registerDefaultNetworkCallback)
  → InterfaceScanner re-enumerates → selectedInterfaces updates (new IP)
  → ProxyRepository recomputes entries (old IP entries drop, new appear)
  → PacServer.distinctUntilChanged sees change → regenerates per-interface cachedBody + new etags
  → MdnsPublisher re-advertises per active link (new IP) → hxmyproxy.local resolves to the new on-segment IP
  → next client PAC fetch returns updated chain (hxmyproxy.local first, then updated IP fallback)
  → ClientConfig/notification update with new recommended IP (and the stable .local form)
```
The PAC *server socket is never rebound* on network change — only the cached bodies (and the mDNS advertisement) change.

> **Two URL forms in V1 (decision D1/U1):** the **per-IP PAC URL** (`http://192.168.1.34:8899/proxy.pac`) is a raw IP that **moves** when the phone changes subnet (home `192.168.1.34` → office `192.168.31.x` → hotspot `192.168.x.1`); a client that pasted the IP form will need it re-entered after a subnet change. The **mDNS PAC URL** (`http://hxmyproxy.local:8899/proxy.pac`) is the convenience form that **stays stable across subnet changes** — when mDNS resolves on the client, no reconfig is needed. **But mDNS is unreliable on Windows / some routers / some Android clients** (grounded-ref §9 / design.md §17.2), so the IP form is always offered alongside as the mandatory fallback, and UI copy must present `.local` as "convenience, may not resolve everywhere; if it doesn't work, use the IP". V1 UI copy must not over-promise that either form is universally stable.

**8.4 Protocol socket dies (e.g., SOCKS5 port lost):**
```
Socks5ProxyServer emits ListeningPort(SOCKS5, bound=false)
  → entries for SOCKS5 become reachable=false → filtered out of PAC
  → PAC body now emits only "PROXY ip:8080; DIRECT" (graceful degrade)
  → Diagnostics shows "SOCKS5 port: down"
```

---

## 9. Edge cases & Android-version specifics

- **No reachable entries:** PAC returns valid `return "DIRECT";` body (HTTP `200`, never `204`/`500`) so configured clients keep browsing directly instead of erroring. Dashboard shows "no usable entry".
- **PAC port (8899) in use:** independent failure; HTTP/SOCKS keep running. `SO_REUSEADDR` is set; on `EADDRINUSE` from a competing app, surface `PortInUse(PAC)` and optionally probe `pacPort+1`. Read back and publish the **actual** bound port. `ClientConfig.pacUrl` blocks marked unreachable while down.
- **IPv4 / IPv6:** V1 **binds the dual-stack wildcard** so IPv6 clients are accepted at the socket layer, but V1 **emits IPv4-only PAC tokens and IPv4-only manual lines** (the `isInNet` literals and recommended host are IPv4). IPv6 interface addresses are skipped by `ProxyEntryComputer`. Bracketed `[v6]:port` PAC tokens and IPv6 `isInNet6` guards are deferred. *(Per the IPv6 finding: the dominant client-acceptance gap is shared Wi-Fi LANs; hotspot/USB/BT-PAN tether interfaces are predominantly IPv4 anyway.)*
- **Slowloris / garbage clients:** per-handler `withTimeout(5s)`, 8 KiB header cap → `431`, body ignored. Critically, the timeout also protects the **shared** `limitedParallelism(N)` dispatcher slot (D6) from being held.
- **Same IP, both protocols:** both a `SOCKS5` and a `PROXY` token are emitted for that IP; SOCKS5 first (priority).
- **Two interfaces, same subnet (USB rndis overlapping hotspot/Wi-Fi):** because admission buckets by the **accepted socket's local address** (§5.4), the correct interface is identified even when remote subnets overlap; `distinctUntilChanged` + lexicographic tie-break keep ordering stable.
- **Admission vs PAC readability:** a client whose connection arrives on an *unselected* interface gets `403` on `/proxy.pac` (cannot self-serve config) — intentional, matches the proxy-port admission model, and (per-interface scoping) it also cannot enumerate the phone's other subnets.

### 9.1 `ACCESS_LOCAL_NETWORK` — DAY-ONE HARD GATE (decision D3/U2, confirmed critical finding)

**V1 targets SDK 37 (decision U2; minSdk stays 29 / Android 10).** `ACCESS_LOCAL_NETWORK` is therefore a **mandatory runtime permission from day one**, required for **BOTH** inbound `accept()` **AND** mDNS multicast (§11.5). This is corrected from the draft's "treat missing permission as a reachability problem, not a crash." The grounded reality is harsher:

- `ACCESS_LOCAL_NETWORK` gates **accepting incoming TCP connections** from local-network addresses **and** multicast (the mDNS `224.0.0.251:5353` advertisement), enforced deep in the networking stack; binding `::`/`0.0.0.0` cannot evade it (*Local network permission* / grounded-ref §1). The source IPs of every client this subsystem serves (LAN `192.168.x`, hotspot, USB-tether `192.168.42.x`) are exactly the gated "local network" set.
- **Mandatory because V1 targets SDK 37 (Android 17).** On Android 10–16 / target ≤ 36 the app would be unrestricted, but V1 ships at target 37, so the gate is in force on Android 17 devices from the first release (*Behavior changes: Android 17* / *Local network permission*). (Pre-17 devices down to minSdk 29 are unaffected by the OS gate; guard the permission API use by `Build.VERSION`.)
- **Failure mode when denied = TCP timeout, not `ECONNREFUSED`** (NDK `android_getnetworkblockedreason()` → `ANDROID_NETWORK_BLOCKED_REASON_LNP`). To the client, `GET /proxy.pac` simply hangs.
- It is in the `NEARBY_DEVICES` permission group — a user who already granted a sibling is not re-prompted.

**Subsystem behavior (target SDK 37, day-one hard-gate first-run flow — U2):**
1. The permission must be **declared in the manifest** and **requested at runtime, guarded by `Build.VERSION`, with a clear rationale, BEFORE binding/accepting or starting the proxy** (owned by the service/permission layer; PacServer's bind and `MdnsPublisher`'s register are downstream of it).
2. If the permission is **not granted**, the app **refuses to enter the running/sharing state** and shows a blocking explanation — PacServer is not even started and mDNS is not advertised. This is a **hard gate equal in weight to the FGS itself**, not a Diagnostics line. *(A boolean "localNetworkPermissionGranted" with a diagnostics hint badly understates it: denial silently makes every PAC/HTTP/SOCKS inbound accept time out, and mDNS multicast fails too.)*
3. Loopback fetches (the phone's own browser) are unaffected by this permission, so the on-device landing page still works.

**Per-API-level checklist (updated for U2):**
- `targetSdk = 37` (was 36); `minSdk = 29` (Android 10).
- `ACCESS_LOCAL_NETWORK`: **day-one hard requirement** (moved from the prior "deferred / opt-in" status) — required for inbound `accept()` and mDNS multicast.
- All permission API use guarded by `Build.VERSION` so pre-17 devices (down to minSdk 29) are not prompted for a gate that does not apply to them.

### 9.2 Android 14 FGS type (decision D2/U5 — corrected from draft)

> **Plain-language (U5): what `foregroundServiceType` means and why a proxy needs an FGS.** An Android app that must keep doing work while the user is looking at other apps (or the screen is off) has to run a **foreground service (FGS)** — a service with a persistent notification that tells the OS "this is user-visible ongoing work, don't kill it." Since Android 14 every FGS must also declare a **type** (`foregroundServiceType`) that categorizes *why* it runs; the OS and Google Play use the type to police battery/abuse and apply per-type limits. hxmy proxy needs an FGS because it must hold its listening sockets open and relay traffic continuously while the user uses the client device — a normal background service would be killed and the proxy would die. **The type is changeable later** (it lives in the manifest plus a Play Console declaration); changing it post-launch may trigger a **Play re-review**, so pick the most defensible type now (below) and treat a later change as a release event, not a hot-swap.

PacServer runs only inside `ProxyForegroundService`; it never spawns a thread outside the service scope, so it cannot leak past `stop()`. The hosting service's foreground type is, per decision D2 and the grounded reference (§2 of v1-grounded-reference.md):

- **Primary: `foregroundServiceType="connectedDevice"`** — declare `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` **plus a qualifying companion permission** (`CHANGE_NETWORK_STATE` and/or `CHANGE_WIFI_STATE`), satisfied **before** `startForeground()`. No documented time limit (essential for an always-on gateway).
- **Fallback: `"specialUse"`** — with a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` child and a Play-Console justification ("local network proxy gateway for tethered/LAN devices").
- **Do NOT use `"dataSync"`** — the 6h/24h cap (target API 35+) plus `Service.onTimeout()` → `RemoteServiceException` kill is fatal for an always-on gateway (*FGS types reference* / *FGS timeout*).
- Whether Google Play accepts `connectedDevice` vs. requires `specialUse` is a **policy-review judgment — NEEDS VERIFICATION** (§NEEDS VERIFICATION).

### 9.3 Other

- **Android 10–12 hotspot interface naming:** irrelevant to PacServer (it binds the wildcard); only `InterfaceScanner` cares. PAC reflects whatever IPs the selected interfaces report.
- **Notification "copy proxy address" action:** pulls `ClientConfig.recommended` clipboard text (host:port only, no credentials — §6); if null (no reachable entry) the action is disabled. Lock-screen visibility for any sensitive line should be `VISIBILITY_PRIVATE` (a LAN host:port is low-sensitivity, but credentials, if ever shown, must not appear on the lock screen).

---

## 10. Egress / anti-SSRF interaction (decision D5/U3 — boundary note)

PAC tokens point clients at the phone's proxy ports, but the **PAC/sharing subsystem does not itself dial upstream** — that is the proxy core. For consistency, this subsystem's PAC `isInNet → DIRECT` guards already keep a client from proxying traffic *to the phone or its own LAN peers through the phone*. The complementary **server-side** anti-SSRF control (the `EgressGuard`) lives in the proxy core / `RelayEngine` per decision D5/U3 and is referenced here only so the two halves stay aligned. Per **U3**:

- **By default BLOCK** proxy egress to **loopback / link-local (`169.254`/`fe80`) / this-host / multicast / the app's own listeners** (enforced **after** DNS resolution to defeat rebinding).
- **Private-LAN (RFC1918) egress is ALLOWED by default** for broad applicability, with an **optional toggle to block it**.

So PAC says "don't route loopback/your-own-LAN-peers/the-phone through me," and the relay refuses to dial loopback / link-local / this-host / multicast / its own ports even if a client ignores the PAC — while still permitting RFC1918 destinations by default unless the user opts to block them.

---

## 11. mDNS / `hxmyproxy.local` publishing — V1-IMPLEMENTED (convenience layer)

> **Status (decision D1/U1, 2026-06-22):** mDNS / NSD publishing is **now implemented in V1**, no longer deferred. It is a **convenience layer on top of the IP scheme**: the per-interface IP entries + manual config + PAC remain the **primary, broadest-compatibility, always-works path**; mDNS is **additive** and stabilizes the `.local` URL across subnet changes when it resolves. The IP fallback is **mandatory** and never omitted from any PAC/entry chain.

**File:** `core/network/MdnsPublisher.kt`. Constructed and lifecycle-driven by `ProxyForegroundService` alongside the listening servers; exposes `isPublished: StateFlow<Boolean>` and the read-back `serviceName`(s) consumed by `ProxyEntryComputer`/Diagnostics.

### 11.1 What gets published (NsdManager.registerService)

Register one `NsdServiceInfo` per advertised endpoint via `nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)` (grounded-ref §9 / *Network service discovery*):

| Endpoint | serviceType | port (actual bound) |
|---|---|---|
| HTTP/CONNECT listener | `_http._tcp` | 8080 (HttpProxyServer's read-back `localPort`) |
| SOCKS5 listener | `_socks._tcp` | 1080 (Socks5ProxyServer's read-back `localPort`) |
| PAC service | PAC type (e.g. `_hxmyproxy-pac._tcp`) | 8899 (PacServer's read-back `localPort`) |

- **Use the ACTUAL bound port** from the corresponding `ListeningPort` read-back (never the configured constant), consistent with §5.6 / `ServerSocket(0)` + `localPort` (grounded-ref §9 "avoid hardcoding").
- **serviceName read-back is mandatory:** in `onServiceRegistered`, read and store `serviceInfo.serviceName` — **Android may rename on conflict** (e.g. append " (1)"); the requested name is not guaranteed (grounded-ref §9). Surface the actual name in the UI / Diagnostics.

### 11.2 FGS-bound lifecycle

Bind the advertisement lifecycle strictly to the **Foreground Service** so it never outlives a live listening socket (grounded-ref §9):

- **Register on serve:** after `startForeground()` and after each listener reports `bound=true` with its actual port (the same ordering invariant as §5.6 — never advertise a port before its socket is bound).
- **`unregisterService(registrationListener)` on stop:** in the service's stop path, before/with closing the sockets. Each protocol/endpoint has its own `NsdServiceInfo` + listener, so each is registered/unregistered independently.
- A publish failure (registration error) is **non-fatal**: it flips `isPublished=false`, the `.local` synthetic entry drops out, and PAC/clients degrade to the IP-only chain (§4). It must never threaten `startForeground()` or the IP path.

### 11.3 Multi-interface resolution

Advertise **per active link**. mDNS is link-local — each advertisement reaches only the L2 segment the phone shares with clients on that link (grounded-ref §9). A client on a given segment resolves `hxmyproxy.local` to **the phone's IP on that same segment** (the on-segment A-record), so the name transparently maps to the correct interface IP for each client without the client knowing the topology. This is why the IP fallback chain in the PAC must still list the concrete per-interface IPs underneath the `.local` token (§4): a client whose mDNS does not resolve, or that is bridged across segments, falls back to the literal IP for its own segment.

### 11.4 Integration points (already wired into the V1 design above)

1. **`ProxyEntryComputer`** emits one synthetic entry per forwardable protocol with `host = "hxmyproxy.local"`, priority **above** all IP entries, `reachable` gated on `MdnsPublisher.isPublished` (§3). The per-interface IP entries remain underneath as the mandatory fallback.
2. **`GeneratePacUseCase`** prepends the `hxmyproxy.local` token to each protocol chain (`emitMdnsHostFirst`), **always followed by the IP fallback chain** → `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; …; DIRECT` (§4). Never emits `.local` without IP fallback (design.md §17.2 "必须保留 IP fallback"). The `*.local` *destination*-bypass guard is unrelated and stays.
3. **`GenerateClientConfigUseCase`** `recommended` block carries `pacUrl = "http://hxmyproxy.local:8899/proxy.pac"` fronting the per-IP blocks, which stay as the mandatory fallback (§6). This is what stabilizes the PAC URL across subnet changes (§8.3).
4. **`PacServer`** is unchanged: it already binds the wildcard, so the `hxmyproxy.local` A-record (the on-segment interface IP) resolves to the same listening socket. Only the advertised *records* and the generated *content* differ.

### 11.5 Permission (decision U2 — covered by the mandatory ACCESS_LOCAL_NETWORK)

mDNS multicast on `224.0.0.251:5353` is exactly the local-network access gated by `ACCESS_LOCAL_NETWORK` on Android 17 (target SDK 37+) — which grounded-ref §1 says applies to framework APIs such as `NsdManager` and to multicast. Under **U2 this permission is already a day-one mandatory runtime grant** (required for inbound `accept()` too — §9.1), so mDNS adds **no new permission** beyond what V1 already hard-gates. Guard all NSD API use by `Build.VERSION` consistent with the rest of the permission flow.

> **NEEDS VERIFICATION:** the NSD doc itself is **silent** on runtime permissions (grounded-ref §9). The exact NSD-specific requirement (and whether `NEARBY_WIFI_DEVICES` additionally applies on Android 13/14) must be confirmed against the local-network-permission / nearby-devices docs. Do not assume NSD is otherwise permission-free.

---

## 与文档基准/评审的修订记录

- **D1 — ~~全面剔除 `hxmyproxy.local`（mDNS 推迟到 V1.1）~~ → 已被 2026-06-22 的 U1 取代（见下方「决策更新」）。** （历史记录）此前版本把所有用户可见输出硬约束为 IP 字面量、`.local` 仅作 V1.1 前瞻常量。**现行决策（U1）：mDNS 现在就在 V1 内实现**，作为 IP 方案之上的便利层；PAC/入口链先 `.local` 再 IP fallback，IP fallback 为强制项、永不省略。下方「决策更新」给出完整 delta。
- **D3 — `ACCESS_LOCAL_NETWORK` 从"可达性提示"升级为"硬门禁"。** 草稿 §9 写"missing permission 是 reachability 问题、不是 crash，PacServer 仍 bind"。改为：该权限在网络栈深处门控**入站 accept**，绑定 `::`/`0.0.0.0` 无法绕过（*Local network permission*）；仅在 target SDK 37+ 强制；拒绝表现为 **TCP 超时**而非 `ECONNREFUSED`。target SDK 37+ 且未授权时**拒绝进入运行态并展示阻断式说明**，不只是诊断行（评审 confirmed critical）。
- **D2 — FGS 类型修正。** 草稿多处写 `connectedDevice`/`dataSync`。改为 **`connectedDevice` 为主**（声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 限定伴随权限 `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`，在 `startForeground()` **之前**满足），**`specialUse` 为备选**，**禁用 `dataSync`**（6h/24h 上限 + `RemoteServiceException` 致命，*FGS types reference*/*FGS timeout*，评审 confirmed critical）。Play 是否接受 `connectedDevice` 列入 NEEDS VERIFICATION。
- **D6 — 并发模型落定。** 草稿写"Java NIO 阻塞模式 + 协程"。统一为项目级 D6 决策：**阻塞 `java.net` socket + 协程 + 专用 `Dispatchers.IO.limitedParallelism(N)`**，非 raw NIO selector、非 Netty；记录 head-of-line-blocking 取舍（PAC 短连接基本无影响，但与中继共享 N，故每请求 `withTimeout` 保护槽位）。自定义 Service 作用域 `CoroutineScope(SupervisorJob()+Dispatchers.IO)` 在文档中无依据，列入 NEEDS VERIFICATION。
- **采纳确认项 — 入站归属按 accepted socket 的 LOCAL 地址判定。** 草稿用"remote IP 落在某选中网段"做 admission/bucket。改为用 `Socket.getLocalAddress()` 判定到达接口（评审 confirmed：remote-IP subnet-contains 既可伪造又在 RFC1918 重叠时误判，如 USB rndis 与热点同段）。remote IP 仅用于显示。
- **采纳确认项 — admission 是便利过滤、非安全边界（D5）。** 明确写出 flat L2 上源 IP 可自取，admission 不能认证对端；真正边界是代理核心的认证（默认在超出可信网络时要求认证）。UI 文案不得把"网段选择"当作保护（评审 confirmed/partially-correct）。
- **采纳确认项 — PAC 按请求接口分域，防拓扑泄露（D5）。** 网络侧 PAC body 仅含到达接口对应 IP，客户端无法枚举手机在其它子网的地址（评审 confirmed low "PAC on 0.0.0.0 leaks topology"）。Dashboard 本地面用全集。
- **采纳确认项 — 端口绑定健壮性。** 加 `SO_REUSEADDR`、绑定后**回读实际端口**、`BindException` 回退/诊断、独立于 HTTP/SOCKS（评审 partially-correct low）。并明确 `SO_REUSEADDR` 不解决"他 app 已占端口"，需回退端口或显式诊断。
- **采纳确认项 — 客户端配置缺位。** `[配置客户端]` 在 V1 映射到已定义输出：新增 §7.1 静态可复制的各平台（Windows/macOS/iOS/Android client）配置单，插值当前接口 IP 与实际端口（评审 partially-correct，已修正 QR 实为 V1 内含）。
- **D4 — egress 可达性取自默认网络、非"某网络有 VPN"。** §3.1 改为读 app 自身默认网络的 `NetworkCapabilities`（`registerDefaultNetworkCallback`），`VALIDATED` 门控"up"，并记录 route-based split-tunnel 下 `TRANSPORT_VPN` 不能证明逐目的地走隧道（*Reading network state*；评审 partially-correct）。fail-closed/检测间隙归 `VpnStateDetector`，本子系统仅消费该 flag。
- **D7 — 裁剪与可测性。** 文件/包图删去 `ui/clients`、Room 等推迟件（**注意：`MdnsPublisher` 在 U1 后已纳入 V1，不再删去**——见 2026-06-22 决策更新）；PAC 解析与生成保持纯函数、注入 dispatcher，便于单测；不向 UI StateFlow 推 per-client 高频字节流（与本子系统的不可变快照取向一致）。
- **IPv4/IPv6（评审 partially-correct）。** 监听改为双栈通配（接受 v6 客户端），但 V1 PAC token/手动行仍为 IPv4-only；`[v6]:port` 与 v6 `isInNet` 推迟。
- **剪贴板/通知凭证卫生（评审 partially-correct，前瞻加固）。** 复制只含 host:port，不嵌 `user:pass@`；Android 13+ 标 `EXTRA_IS_SENSITIVE`；锁屏 `VISIBILITY_PRIVATE`。

### 决策更新 (2026-06-22)

以下更新由用户在 2026-06-22 做出，**取代**先前的 D1/D3/D5/D6 相关表述。本文件按「保留所有仍正确内容、仅改必要处」原则就地修订。

- **U1 — mDNS 现纳入 V1（不再推迟），定位为 IP 方案之上的便利层。** §11 从「Deferred to V1.1」整体重写为「V1-IMPLEMENTED」：用 `NsdManager.registerService` 发布 `_http._tcp`(8080)/`_socks._tcp`(1080)/PAC(8899) 三类服务，端口取回读的实际 `localPort`；`onServiceRegistered` 中**回读实际 `serviceName`**（Android 冲突时可能改名，grounded-ref §9）；发布生命周期**绑定前台服务**（serve 时 register、stop 时 `unregisterService`）；多接口**按活动链路分别发布**，客户端把 `hxmyproxy.local` 解析为其所在网段的 IP。改写顶部 D1 决策块：mDNS 在 V1 内，IP 方案为主、IP fallback **强制**出现在每条 PAC/入口链（`hxmyproxy.local` 在前、IP 在后，绝不单发 `.local`）。`PacServer`/`GeneratePacUseCase`/`GenerateClientConfigUseCase` 设计保留，但「`.local`+IP」链现为 V1 行为：更新了 §2 `PacOptions`(`emitMdnsHostFirst`)/`InterfaceClientConfig`、§3 合成入口、§4 生成步骤与示例 body、§6 recommended 块、§7/§7.1 文案、§8.1/§8.3 时序，并把 §1 文件图的 `MdnsPublisher` 重新纳入。
- **U2 — 首发 targetSdk 37（原 36），minSdk 仍 29。** `ACCESS_LOCAL_NETWORK` 自首发即为**强制运行时权限**，**入站 `accept()` 与 mDNS 多播都需要**。§9.1 改为「day-one 硬门禁」并加首次运行硬门禁流程（启动代理前带说明请求；拒绝则拒绝进入运行态、展示阻断式说明；拒绝表现为客户端 TCP 超时而非拒绝——grounded-ref §1）；所有权限 API 以 `Build.VERSION` 守卫；新增 per-API-level 清单（targetSdk 36→37、`ACCESS_LOCAL_NETWORK` 从「deferred/opt-in」改为「day-one 硬需求」）。§5.4 与 §11.5 同步。
- **U3 — 认证「可选」（恢复原立场，非默认强制）。** §5.4 安全框架与 §6 凭证卫生改为：默认无认证；在非可信网络下分享且未开认证时显示明确警告（"未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用"）。**保留 EgressGuard 反 SSRF**（§10）：默认 BLOCK 出站到 loopback/link-local(169.254/fe80)/this-host/multicast/本应用自身监听端口；**RFC1918 私网出站默认放行**（广覆盖），提供可选开关阻断。启用认证时（SOCKS5 RFC1929/HTTP Basic）凭证存 `EncryptedSharedPreferences`/Keystore，绝不明文、绝不记录。移除任何「默认要求认证/超出可信网络强制认证」措辞。源 IP 准入仍仅为便利过滤、非安全边界。
- **U4 — 连接/资源上限成为用户可调设置（DataStore 持久化、运行时生效）+ 三档预设。** 主要落在 proxy-core/设置子系统，对本 PAC/分享子系统**无直接结构改动**（PAC 短连接是 §5.1 `limitedParallelism(N)` 上的低流量租户，N 由 proxy-core 统一选定）。此处仅记录：默认值/范围为 全局最大连接 256(32–1024)、单客户端 128(16–512)、中继并行 N 32(4–64)、单连接缓冲 32 KiB(8–256 KiB)、空闲超时 300s(30–1800s)；预设 省电/均衡(=默认)/高吞吐 仅设置底层值，可逐项覆盖；关键原则「连接数 ≠ 带宽」，过小连接上限会导致连接被拒（页面加载断裂）并可能因阻塞客户端并行连接而降低高 BDP 4G/5G 链路(~100–500 Mbps)的有效吞吐；内存预算示例 256 conn × 2 dir × 32 KiB ≈ 16 MiB。「该协程+阻塞+limitedParallelism 模型下能否真达 ~500 Mbps」仍列 NEEDS VERIFICATION（压测；先调 buffer/N；NIO 为 V2 逃生口）。
- **U5 — 前台服务类型说明补强。** §9.2 保持 `connectedDevice` 为主（`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 在 `startForeground()` 前满足的 `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`），`specialUse` 为文档化备选，`dataSync` 仍排除（6h 上限）。新增**通俗解释**：`foregroundServiceType` 是什么、代理为何需要 FGS；并注明类型**日后可改**（manifest + Play Console 声明，改动可能触发 Play 复审）。Play 接受 `connectedDevice` vs 要求 `specialUse` 仍列 NEEDS VERIFICATION。

---

## NEEDS VERIFICATION

1. **Custom long-running Service `CoroutineScope`（D6）。** `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 在 `onDestroy()` 取消的模式，*Kotlin coroutines on Android* 页未覆盖（只讲 `viewModelScope`）。需对照 advanced/best-practices 协程文档核实。PacServer 的子作用域 parent 于此。
2. **Google Play 对 `connectedDevice` vs `specialUse` 的裁定（D2）。** 文档确认 Android 14+ 必须在 Play Console 声明类型并给理由，且**无**任何文档化的 proxy/gateway/server 类型。Play 是否接受 `connectedDevice`（理由略牵强）或强制 `specialUse`，属策略评审判断，需实际提审验证。
3. **`limitedParallelism(N)` 的 N 取值（D6）。** 文档不给并发上限数值；N 由 proxy-core 规格统一选定，本子系统作为低流量租户共享之。其具体取值与 PAC 短连接对中继槽位的影响需在 proxy-core 规格中核实/压测。
4. **VPN 路由黑洞入站回包（D4）。** "系统 VPN 活动时，accepted server socket 的回包是否会被 VPN per-UID 规则引入无 LAN /24 路由的 VPN 表而被黑洞，除非把 server socket pin 到底层 LAN network" —— 评审判为 partially-correct 且证据指向"通常源地址感知路由会对称回包、像 Every Proxy 等同类应用可用"。因此把"在 accepted FD 上 `Network.bindSocket()` 到底层物理网络 + 用完成 TCP 握手的诊断探针"作为**待设备验证的缓解任务**记录，而非既定事实。注意：出站(上游)socket 绝不可如此 pin，也不可 `bindProcessToNetwork()`（D4）。
5. **V1 NSD/mDNS 运行时权限（U1 后 mDNS 已在 V1）。** NSD 文档本身对权限保持沉默（grounded-ref §9）；Android 17 / target 37 下 `NsdManager` 发布与多播很可能需 `ACCESS_LOCAL_NETWORK`（U2 已将其列为 day-one 强制，故大概率被覆盖），但 NSD 专属要求、以及 Android 13/14 是否额外需 `NEARBY_WIFI_DEVICES`，仍需对照 local-network-permission / nearby-devices 文档核实**后再发 V1**。勿因 NSD 页沉默就假定无需权限。
6. **`ACCESS_NETWORK_STATE` 声明（§3.1 依赖）。** *Reading network state* 称读连接状态"无需特定权限"，但自承与 `ConnectivityManager` API 参考有出入。防御性声明 `ACCESS_NETWORK_STATE`，确切要求待核实。
7. **~500 Mbps 实际吞吐（U4，proxy-core 跨子系统）。** 在「协程 + 阻塞 socket + `Dispatchers.IO.limitedParallelism(N)`」模型下能否达到用户链路 ~100–500 Mbps 的有效吞吐，需负载压测确认；先调 buffer/N，NIO 为 V2 逃生口。PAC 子系统作为该 N 的低流量租户，其短连接对中继槽位的影响一并在 proxy-core 压测中核实。
