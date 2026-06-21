# hxmy proxy · V1 · Proxy Core (HTTP/HTTPS CONNECT, SOCKS5, Relay Engine)

> 隶属 [v1-design.md](./v1-design.md)；Android 行为已对照 [v1-grounded-reference.md](./v1-grounded-reference.md) 核证，记忆性断言已剔除。

## 0. Scope and ownership

Subsystem owner: `com.mzstd.hxmyproxy.core.proxy`. V1 scope. Implements the three listener protocols (plain HTTP forward-proxy, HTTPS `CONNECT` tunnel, SOCKS5 TCP CONNECT) plus a shared bidirectional relay. PAC server, access control, VPN detection, and interface scanning live in sibling packages and are referenced — not redesigned — here.

**V1 cross-cutting decisions that shape this subsystem (grounded in the reference + verified review; updated per the 2026-06-22 decision delta — see 修订记录):**

- **mDNS / NSD is IN V1 as a convenience layer on top of the IP scheme (U1).** The per-interface raw IPv4/IPv6 literal entry list remains the **primary, broadest-compatibility** path; mDNS is **additive**. The Foreground Service advertises the listeners via `NsdManager.registerService` (`_http._tcp`, `_socks._tcp`, and a PAC service), reading back the actual `serviceName` in `onServiceRegistered` (reference §9). PAC chains and recommended-entry output list `hxmyproxy.local` **first** then the concrete interface IP(s) as fallback (e.g. `SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; DIRECT`) — this subsystem and its consumers must **never** emit `.local` **without** the IP fallback underneath (mDNS is unreliable on Windows / some routers / some Android clients). The `BoundPort`/entry model carries the live interface address(es) **and** the actual bound port; the NSD publisher (a sibling, not designed here) consumes them. mDNS multicast (`224.0.0.251:5353`) is local-network access, so it is covered by the same `ACCESS_LOCAL_NETWORK` grant now mandatory under U2/§11.
- **Egress follows the system default network** (the VPN, when a normal full-tunnel VPN is up). Upstream sockets are **never** pinned to a physical `Network`, and `ConnectivityManager.bindProcessToNetwork()` is **forbidden** process-wide (see §6). Source: *VPN (Android connectivity)*; *Reading network state*.
- **Authentication is OPTIONAL — default is no auth (U3).** When auth is enabled it is SOCKS5 RFC 1929 user/pass and/or HTTP Basic, with credentials in `EncryptedSharedPreferences`/Keystore. When sharing with auth **off** on a non-trusted network the UI shows a clear warning (`未开启认证，同一局域网/热点内的设备可能连接你的代理；建议仅在可信网络下使用`). The **anti-SSRF egress filter stays on by default** (loopback/link-local/this-host/multicast/own-listeners blocked; private-LAN allowed by default with an optional block toggle — §6.3). Source-IP admission is a convenience filter, **not** a security boundary (see §6.3, §7, §8). The earlier "auth-required-by-default beyond a trusted network" framing is **removed**.

### 0.1 File / package map

| File | Primary types | Responsibility |
|---|---|---|
| `core/proxy/ProxyServer.kt` | `ProxyServer` (interface), `ProxyProtocol`, `ProxyServerConfig`, `BoundPort` | Common lifecycle contract + shared config for all listeners. |
| `core/proxy/ProxyAcceptLoop.kt` | `ProxyAcceptLoop` | Shared blocking `ServerSocket` accept loop, admission gate, per-connection coroutine launch. |
| `core/proxy/HttpProxyServer.kt` | `HttpProxyServer`, `HttpRequestLine`, `HttpHeaderBlock`, `HttpProxyHandler` | Plain HTTP forward-proxy + HTTPS `CONNECT` tunnel handshake. |
| `core/proxy/Socks5ProxyServer.kt` | `Socks5ProxyServer`, `Socks5Handler`, `Socks5Method`, `Socks5Command`, `Socks5Atyp`, `Socks5Reply` | RFC 1928 negotiation + RFC 1929 auth + CONNECT. |
| `core/proxy/RelayEngine.kt` | `RelayEngine`, `RelaySession`, `RelayResult`, `HalfDuplexPump` | Bidirectional byte pump, per-direction half-close, idle/half-close timeouts, byte accounting. |
| `core/proxy/Connection.kt` | `ProxyConnection`, `ConnState`, `ConnEndpoint` | Per-connection state machine, IDs, metadata, lifecycle hooks. |
| `core/proxy/Outbound.kt` | `OutboundConnector`, `OutboundConnectError`, `EgressPolicy` | Creates the upstream socket on the **default network** (VPN egress) + remote DNS + anti-SSRF egress filter. |
| `core/proxy/ProxyParsers.kt` | pure parsers: `parseSocks5Greeting`, `parseSocks5Request`, `parseHttpRequestLine`, `parseHttpHeaders`, `isDestinationBlocked` | **Pure functions over byte arrays / streams** — unit-testable with `ByteArrayInputStream`, no sockets. |
| `core/proxy/ProxyError.kt` | `ProxyError` (9-category sealed taxonomy), `ProxyErrorCode` | The error taxonomy, mapped to SOCKS5/HTTP reply codes. |
| `core/proxy/ProxyTuning.kt` | `ProxyTuning` (data class snapshot + fixed companion consts) | User-adjustable limits/buffers/N/idle from DataStore (runtime-applied) + fixed framing/timeout constants (§10). |

Collaborators (other packages, consumed via injected interfaces — **not** designed here):
- `core/security/AccessController` — `admit(srcIp, localIface): AdmissionResult` (subnet allowlist keyed off the **accepted socket's local address**, blacklist, per-client cap).
- `core/security/Authenticator` — `verifyBasic(...)`, `verifySocksUserPass(...)` (credentials read from `EncryptedSharedPreferences` / Keystore, never plaintext DataStore).
- `core/network/VpnStateDetector` — `currentEgress(): EgressState`, drives the VPN-down strategy gate (default-network callback, §6.4).
- `core/network/DefaultNetworkProvider` — `resolver(): RemoteResolver` (default-network DNS, §6.1).
- `service/ProxyForegroundService` — owns the long-lived `CoroutineScope` and the dispatchers; satisfies the FGS prerequisites before `startForeground()`.

### 0.2 Testability seams (designed in)

Per the verified review, protocol parsing and admission are the most bug-prone parts and must be unit-testable without sockets:

1. **SOCKS5 greeting/request parsing, HTTP request-line/header parsing, and PAC generation are pure functions over `InputStream`/`ByteArray`** in `ProxyParsers.kt` — driven by `ByteArrayInputStream` in tests.
2. **`OutboundConnector` and `RemoteResolver` are interfaces**, so `RelayEngine`/handlers run over in-memory `Pipe`s and fake connectors in tests.
3. **The `CoroutineDispatcher` is injected** everywhere (no hard-coded `Dispatchers.IO`), so tests use a `TestDispatcher`.
4. **`isDestinationBlocked(resolvedIp, listenAddrs)` and CIDR/subnet admission are pure functions** of `(InetAddress, config)`.

---

## 1. Concurrency model decision (RESOLVED — D6)

**Chosen and committed for V1: Kotlin coroutines + *blocking* `java.net.Socket` I/O per connection (one suspending coroutine per direction) on a dedicated, bounded `Dispatchers.IO.limitedParallelism(N)` dispatcher. NOT raw NIO selectors. NOT Netty.** The model is kept exactly as decided (D6); only **N is now a user-adjustable setting** (relay parallelism, default 32, range 4–64 — §10), read from settings at runtime, not a compile-time constant.

This closes the "concurrency never decided" review finding (confirmed). Rationale for serving up to the configured global cap (default 256, up to 1024 — §10) of concurrent connections on a phone:

1. **Not thread-per-connection.** 1024 connections × 2 directions ≈ 2048 OS threads at ~512 KB–1 MB stack each ≈ 1–2 GB committed stack plus heavy context-switch cost on 4–8 mobile cores. **Explicitly forbidden** in V1.
2. **Not raw `java.nio` Selector.** A single-selector reactor is the most memory-frugal option but forces a hand-written, callback-driven state machine for every protocol step (partial HTTP header reads, multi-stage SOCKS5 negotiation, half-close bookkeeping, partial-write backpressure) — exactly the class of code that produces subtle framing bugs. Not justified at these connection counts. (NIO remains the documented V2 escape hatch if a load test shows the blocking model cannot reach the link's throughput.)
3. **Not Netty.** ~2–3 MB dependency with its own event-loop thread model that fights coroutine structured cancellation. Overkill for three simple TCP protocols and a byte pump.
4. **Coroutines + blocking sockets wins.** A relay coroutine spends almost all its life *blocked inside a single `read()`*. Blocking reads are wrapped in `runInterruptible(dispatcher)` so structured cancellation (stop, idle, network switch, sibling failure) interrupts the syscall promptly. Each coroutine is a few hundred bytes of heap, not an OS thread; memory scales with the dispatcher cap (relay parallelism N), not with connection count. Straight-line, readable protocol code (`read → parse → connect → relay`).

> Coroutines best-practice note: *Kotlin coroutines on Android* endorses "many coroutines on one thread" and `withContext(Dispatchers.IO)` for blocking I/O, but gives **no numeric concurrency cap** and does not document `limitedParallelism` or a custom long-running Service scope. The specific bounded-dispatcher and Service-scope patterns below are **NEEDS VERIFICATION** against the advanced-coroutines / coroutines-best-practices docs (see §13).

### 1.1 Dispatcher sizing and the head-of-line-blocking trade-off

We do **not** use the global unbounded `Dispatchers.IO`. The service creates a dedicated, bounded dispatcher so a runaway relay cannot starve the rest of the app:

```kotlin
// ProxyForegroundService creates these once (re-created when the relay-parallelism setting changes, §10)
// and passes them into every server. N is read from settings, NOT a compile-time constant.
val proxyIoDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(tuning.ioParallelism)   // default 32, range 4–64 carrier threads
val acceptDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(1)                      // accept never blocked behind relay I/O
```

**How N is chosen, and the trade-off.** Because a relay coroutine is blocked inside one `read()` whenever it is not actively moving bytes, the carrier-thread demand equals the number of connections that *simultaneously have bytes mid-transfer*, not the connection count. Relay parallelism `N` (default 32) means at most N blocking reads/writes occupy a carrier thread at once; the other coroutines are parked at zero thread cost.

The cost of this model is **head-of-line blocking at the parallelism cap**: if more than N directions are *all* genuinely runnable (bytes available to copy) at the same instant, the excess wait for a carrier thread, adding latency to those transfers. We accept this because (a) on a phone hotspot the realistic steady-state is a few dozen active tunnels, and (b) raising N too far re-introduces the thread-cost problem we are avoiding. **N is now a user-adjustable setting** (relay parallelism, default 32, range 4–64; also bumped by the 高吞吐 / lowered by the 省电 preset — §10): per-stream throughput on a high-BDP 4G/5G link is governed by N + buffer size + the link itself, **not** by the connection cap. If profiling shows starvation under a target workload, raise N (or pick the 高吞吐 preset) before switching I/O models — NIO is the V2 escape hatch. The accept loop keeps its own single-thread dispatcher so accept latency is never queued behind relay I/O. The dispatcher is re-created when the relay-parallelism setting changes (existing relays drain on the old dispatcher; new connections use the new one).

### 1.2 Structured-concurrency tree (per server)

```
ProxyForegroundService scope: CoroutineScope(SupervisorJob() + Dispatchers.IO)   // owned by the Service, cancelled in onDestroy()
 └─ server scope: CoroutineScope(SupervisorJob() + acceptDispatcher + CoroutineName)
     └─ acceptLoop (1 coroutine)
         └─ per-connection: launch(proxyIoDispatcher + CoroutineName("conn-N")) {
                coroutineScope {            // a regular Job (NOT Supervisor): one pump failing cancels the sibling
                    handshake (sequential)
                    pump C→U (child)
                    pump U→C (child)
                }
            }
```

Each connection body is a `coroutineScope { }` (a regular Job) so that when either pump fails or a half-close completes, the sibling is cancelled and both sockets are closed deterministically in a `finally`. Cancelling the *server* scope cancels the accept loop and every connection. The **Service owns** this scope (not the ViewModel); it survives configuration change/backgrounding and is cancelled only in `onDestroy()` — see *Architecture recommendations* (data-layer ownership) and §13 NEEDS VERIFICATION for the custom-scope pattern.

---

## 2. Shared contracts

```kotlin
// core/proxy/ProxyServer.kt
enum class ProxyProtocol { HTTP, HTTPS_CONNECT, SOCKS5 } // HTTP + HTTPS_CONNECT share one HttpProxyServer port

data class ProxyServerConfig(
    val protocol: ProxyProtocol,
    /** §6.2 of design: bind the DUAL-STACK wildcard so both IPv4 and IPv6 clients are accepted (see §3.0). */
    val bindAddress: InetAddress? = null,   // null => InetSocketAddress(port) wildcard; IPV6_V6ONLY defaults false on Android/Linux
    val port: Int,
    /** OPTIONAL auth (U3). Default FALSE (no auth). When false and a non-loopback interface is shared,
     *  the UI shows the "未开启认证…" warning; this does NOT force auth on. */
    val authRequired: Boolean = false,
    val socksUserPassEnabled: Boolean = false,  // SOCKS5 RFC 1929 toggle (only meaningful when authRequired)
    val egressPolicy: EgressPolicy,             // anti-SSRF filter, ON by default — independent of auth, §6.3
)

interface ProxyServer {
    val protocol: ProxyProtocol
    val boundPort: StateFlow<BoundPort?>          // null until bound; emits the ACTUAL bound port (supports :0)
    val activeConnections: StateFlow<Int>         // throttled snapshot, §9.4 — NOT per-read updates

    /** Binds + starts the accept loop. Suspends only until the socket is bound; the loop runs in [scope]. */
    suspend fun start(scope: CoroutineScope): Result<BoundPort>

    /** Graceful: stop accepting, let existing relays drain up to [drainTimeoutMs], then force-close. */
    suspend fun stop(drainTimeoutMs: Long = ProxyTuning.STOP_DRAIN_MS)
}

data class BoundPort(val protocol: ProxyProtocol, val address: InetAddress, val port: Int)

// fun interface so handlers are trivially fakeable in tests.
fun interface ConnectionHandler {
    /** Runs the protocol handshake + relay for one already-admitted client socket. Must close [client] on return. */
    suspend fun handle(conn: ProxyConnection, client: Socket)
}
```

---

## 3. Accept loop, admission, and per-connection structure

We use a **blocking `ServerSocket`** (the accept loop is one coroutine; blocking `accept()` is fine and simpler than a channel). Cancellation closes the socket, which unblocks `accept()`.

### 3.0 Binding — dual-stack, SO_REUSEADDR, post-bind port verification

```kotlin
suspend fun bind(): BoundPort = withContext(acceptDispatcher) {
    val ss = ServerSocket()
    ss.reuseAddress = true                                  // SO_REUSEADDR: survive Watchdog stop/restart TIME_WAIT race
    val sa = if (config.bindAddress != null)
                 InetSocketAddress(config.bindAddress, config.port)
             else InetSocketAddress(config.port)            // unspecified wildcard => dual-stack (IPV6_V6ONLY=false default)
    try {
        ss.bind(sa, ProxyTuning.ACCEPT_BACKLOG)
    } catch (e: BindException) {
        throw ProxyError.PortInUse(config.port)             // surfaced as a typed StartFailure; never a silent zombie
    }
    serverSocket = ss
    // Verify the ACTUAL bound port (supports auto-pick :0) instead of trusting the configured constant.
    BoundPort(config.protocol, ss.inetAddress, ss.localPort)
}
```

Rationale (confirmed/partially-correct review findings):
- **Dual-stack bind.** Binding the literal `0.0.0.0` listens on IPv4 only and silently drops native IPv6 clients. Binding the *unspecified* wildcard (`InetSocketAddress(port)` with no address) defaults to `IPV6_V6ONLY=false` on Android/Linux and accepts **both** families. This is the V1 default; the per-interface bind escape hatch (`config.bindAddress`) remains for callers that need it.
- **SO_REUSEADDR** is set on every listener. It only lets us rebind over sockets lingering in `TIME_WAIT` (the stop/wait/restart race), **not** share a live port — a competing app (e.g. another proxy already holding 8080/1080) still fails with `BindException`, which we surface as `PortInUse` (see §8) so the foreground notification/diagnostics name the conflict rather than the service running into unreachable ports.
- **Post-bind port verification:** we publish `ss.localPort`, not the configured constant, so `:0` auto-pick and conflict-fallback both propagate the real port into PAC content, the recommended-entry list, and the notification.

The bind is attempted **after** `startForeground()` has been called by the service (so a bind failure can never cost the mandatory `startForeground()` call and trigger an FGS-timeout) — see *Foreground services overview* (5-second `startForeground` rule). On Android 17 / target SDK 37+, `ACCESS_LOCAL_NETWORK` must already be granted before this bind/accept path runs (see §11).

### 3.1 Accept loop and admission

```kotlin
// core/proxy/ProxyAcceptLoop.kt
class ProxyAcceptLoop(
    private val config: ProxyServerConfig,
    private val accessController: AccessController,
    private val vpnState: VpnStateDetector,
    private val handler: ConnectionHandler,
    private val proxyIoDispatcher: CoroutineDispatcher,
    private val acceptDispatcher: CoroutineDispatcher,
    private val onActiveSnapshot: (Int) -> Unit,   // throttled, §9.4
) {
    private val globalActive = AtomicInteger(0)
    @Volatile private var serverSocket: ServerSocket? = null

    fun run(scope: CoroutineScope) = scope.launch(acceptDispatcher) {
        val ss = serverSocket!!
        try {
            while (isActive) {
                val client: Socket = try { ss.accept() }
                    catch (e: SocketException) { if (isActive) continue else break }  // close() unblocks accept

                val src   = (client.remoteSocketAddress as InetSocketAddress).address
                val local = (client.localSocketAddress  as InetSocketAddress).address  // which interface RECEIVED it

                // --- ADMISSION GATE (cheap, on accept thread, before spawning) ---
                // Caps are read from the live settings snapshot (DataStore-backed, runtime-applied, §10),
                // NOT compile-time constants. A too-small cap REFUSES connections (see §10 IMPACT table).
                if (globalActive.get() >= tuning.maxGlobalConn) {        // default 256, range 32–1024
                    reject(client, ProxyError.ResourceLimit); continue
                }
                // Bucket + allow/deny by the ACCEPTED SOCKET'S LOCAL ADDRESS (the receiving interface),
                // NOT a subnet-contains test on the spoofable remote IP. Remote IP is for display only.
                when (val a = accessController.admit(src, local)) {
                    is AdmissionResult.Denied  -> { reject(client, a.toError()); continue }
                    is AdmissionResult.Allowed -> { /* fallthrough */ }
                }
                // VPN-down strategy gate (default = BLOCK; fails CLOSED, §6.4).
                if (!vpnGate(vpnState.currentEgress())) { reject(client, ProxyError.VpnUnavailable); continue }

                globalActive.incrementAndGet(); publishCount()
                val conn = ProxyConnection.create(config.protocol, src, local, client)

                launch(proxyIoDispatcher + CoroutineName("conn-${conn.id}")) {
                    try {
                        configureSocket(client)                 // §3.2
                        accessController.onConnectionOpened(src) // reserve per-client slot
                        handler.handle(conn, client)            // handshake + relay
                    } catch (ce: CancellationException) {
                        conn.transition(ConnState.CLOSING); throw ce
                    } catch (t: Throwable) {
                        conn.fail(ProxyError.fromThrowable(t))
                    } finally {
                        runCatching { client.close() }          // GUARANTEED FD close exactly once (no leak)
                        conn.transition(ConnState.CLOSED)
                        accessController.onConnectionClosed(src)
                        globalActive.decrementAndGet(); publishCount()
                    }
                }
            }
        } finally { runCatching { ss.close() } }
    }

    private fun vpnGate(s: EgressState): Boolean = when (settings.vpnDownStrategy) {
        VpnDownStrategy.CONTINUE -> true
        VpnDownStrategy.WARN     -> true               // allow; UI/notification warns elsewhere
        VpnDownStrategy.BLOCK    -> s.vpnUp            // default; "up" requires TRANSPORT_VPN + VALIDATED, §6.4
    }
}
```

**Admission keyed off the receiving interface (confirmed finding).** Source-IP-to-subnet membership cannot tell which interface a connection arrived on when selected interfaces have overlapping RFC1918 ranges (e.g. USB `rndis0` 192.168.42/24 and a hotspot also in 192.168.42/24), and is trivially in-range-spoofable on a flat L2 segment. We therefore admit/bucket by `Socket.getLocalAddress()` (the concrete receiving-interface IP the kernel selected on the wildcard listener) and use the remote IP for display only. **Source-IP/subnet admission is documented as a convenience/scoping filter, NOT authentication** — the real boundary is the credential (D5, §7/§8).

`reject(client, error)` writes the protocol-appropriate refusal then closes. For HTTP, a status line can be written before any read; for **SOCKS5 the reject must happen after the greeting is read** (you cannot speak SOCKS framing before knowing it is SOCKS), so SOCKS5 admission/auth errors are surfaced inside the handler (see §7). Pre-read checks (global limit, interface allow/deny, VPN gate) are done here; auth-dependent rejects happen in-handler.

### 3.2 Socket configuration (`configureSocket`)

```kotlin
client.tcpNoDelay = true                       // proxies are latency-sensitive; disable Nagle
client.keepAlive  = true
client.soTimeout  = 0                          // idle is managed by RelayEngine, not SO_TIMEOUT
runCatching { client.setSoLinger(true, 0) }    // on FORCE-close send RST; graceful close uses shutdownOutput (§9)
client.receiveBufferSize = ProxyTuning.SOCK_RCVBUF
client.sendBufferSize    = ProxyTuning.SOCK_SNDBUF
```

> Handshake reads (HTTP request line/headers, SOCKS5 greeting/auth/request) run under `withTimeout(ProxyTuning.HANDSHAKE_TIMEOUT_MS)` around the suspending blocking read, not `SO_TIMEOUT`, so cancellation is structured.

---

## 4. Connection lifecycle state machine

```kotlin
// core/proxy/Connection.kt
enum class ConnState { NEW, HANDSHAKE, CONNECTING, RELAYING, IDLE, CLOSING, CLOSED }
// Legal: NEW → HANDSHAKE → CONNECTING → RELAYING ⇄ IDLE → CLOSING → CLOSED ;  any → CLOSING → CLOSED
```

| State | Entered when | Timeout governing it |
|---|---|---|
| `NEW` | after admission gate, socket configured | — |
| `HANDSHAKE` | reading/parsing (HTTP req+headers, SOCKS5 greeting+auth+request) | `HANDSHAKE_TIMEOUT_MS = 30_000` |
| `CONNECTING` | resolving DNS + opening upstream socket | DNS `5s` + connect `10s` (§6) |
| `RELAYING` | upstream connected, success reply sent, bytes flowing | idle timeout (setting, default `300s`, range 30–1800 s — §10), reset on activity in **either** direction |
| `IDLE` | no bytes either direction for a probe window | rolls to `CLOSING` at the configured `idleTimeoutMs` (§10) |
| `CLOSING` | one half closed, draining the other; or error/stop | `HALF_CLOSE_TIMEOUT_MS = 15_000` |
| `CLOSED` | both sockets closed, slots released (finally) | terminal |

```kotlin
class ProxyConnection private constructor(
    val id: Long,                         // monotonic AtomicLong
    val protocol: ProxyProtocol,
    val clientIp: InetAddress,            // display only
    val localIface: InetAddress,          // receiving interface (admission key)
    val createdAt: Long,
) {
    @Volatile var state: ConnState = ConnState.NEW; private set
    @Volatile var target: ConnEndpoint? = null
    @Volatile var lastError: ProxyError? = null
    val uploadBytes = AtomicLong(0); val downloadBytes = AtomicLong(0)
    @Volatile var lastActivityNs: Long = System.nanoTime()  // touched by EITHER pump

    fun transition(to: ConnState) { /* validate edge; emit throttled metadata snapshot, §9.4 */ }
    fun fail(e: ProxyError) { lastError = e; transition(ConnState.CLOSING) }
    fun touch() { lastActivityNs = System.nanoTime() }
}

data class ConnEndpoint(val host: String, val port: Int, val resolved: InetAddress?)
```

---

## 5. HTTP / HTTPS CONNECT — `HttpProxyServer`

One listener serves both plain HTTP forward-proxy and the HTTPS `CONNECT` tunnel; the request method disambiguates. **No MITM, no TLS decrypt, no certificate injection** — the client's TLS handshake is end-to-end with the destination. Source: *HTTP CONNECT method* (MDN).

### 5.1 Request parsing (pure, bounded)

`parseHttpRequestLine` / `parseHttpHeaders` (in `ProxyParsers.kt`) read **only up to and including the blank line** (`\r\n\r\n`) over a `PushbackInputStream` on the *raw* socket stream, because for `CONNECT` everything after the header block is opaque tunnel payload and must not be consumed by the parser. Hardening: read up to `MAX_REQUEST_LINE = 8 KiB` and `MAX_HEADER_BYTES = 64 KiB`; reject (`414` / `431`) on overflow. Any bytes already buffered past `\r\n\r\n` are captured and **seeded** into the client→upstream pump (§9), never dropped.

### 5.2 `CONNECT host:port` (HTTPS tunnel)

Per *HTTP CONNECT method*: the target is **authority-form only** (`host:port`, no scheme/path), **the port is mandatory with no default**, and any **2XX** switches the proxy to blind two-way relay.

```
HANDSHAKE:
 1. Read request line. method == "CONNECT". target == authority-form "host:port".
    - Port is MANDATORY. Bracket-aware IPv6 authority "[2001:db8::1]:443" must parse.
    - If no port present → 400 Bad Request (do NOT silently assume 443).
 2. If authRequired and Proxy-Authorization missing/invalid:
        write "HTTP/1.1 407 Proxy Authentication Required\r\n
               Proxy-Authenticate: Basic realm=\"hxmy proxy\"\r\n\r\n"  → fail(AuthRequired) → CLOSING
 3. Consume the rest of the header block (honor Proxy-Authorization only).
 4. EGRESS-FILTER + restrict-target check on the resolved destination (§6.3): deny loopback/link-local/
    /private-LAN (unless opted in) and the proxy's own listen addrs/ports; deny ports outside the allowlist.
CONNECTING:
 5. transition(CONNECTING); upstream = outbound.connect(host, port)   // DNS(5s)+connect(10s) on default net (§6)
        on failure → write 502 (refused/DNS/no-route) or 504 (timeout) + short body → fail(mapped ProxyError)
RELAYING:
 6. write "HTTP/1.1 200 Connection Established\r\n\r\n"   (no Via, no extra headers, no body)
 7. relay.run(conn, client, upstream, seed = residualBytesAfterBlankLine)   // blind opaque relay, §9
```

The proxy terminates the CONNECT hop (CONNECT is hop-by-hop); after the 2XX it relays bytes blindly in both directions without inspecting payload.

### 5.3 Plain HTTP forward-proxy (`GET http://host/path`, `POST`, …)

The plain-HTTP path requires real HTTP/1.1 parsing and rewriting (confirmed finding — the draft previously left this unspecified). **V1 scopes plain HTTP to one request per connection, `Connection: close`**, which sidesteps keep-alive body-framing and request-smuggling entirely while still being correct for the overwhelming majority of clients (which use a fresh connection per origin, or `CONNECT` for HTTPS).

```
HANDSHAKE:
 1. Read request line; target is absolute-form "http://host[:port]/path".  (Reject CONNECT here → §5.2.)
 2. Auth gate as in CONNECT (407 on failure).
CONNECTING:
 3. host = URI host, port = URI port ?: 80 ; egress-filter check (§6.3) ; transition(CONNECTING).
 4. upstream = outbound.connect(host, port).
RELAYING (single request, then opaque relay of the response):
 5. Rebuild the request to send upstream:
      - rewrite request-line target from ABSOLUTE-form to ORIGIN-form ("/path?query"),
      - ensure a Host header (derive from the URI authority if the client omitted it),
      - STRIP hop-by-hop headers and NEVER forward them to the origin (RFC 7230 §6.1):
            Connection, Proxy-Connection, Proxy-Authenticate, Proxy-Authorization,
            Keep-Alive, TE, Trailer, Transfer-Encoding, Upgrade,
            plus any header named in the Connection token list,
      - set "Connection: close" on the upstream request (V1 one-request-per-connection).
      Write rebuilt request line + headers + blank line to upstream.
 6. Forward the request body if present (Content-Length / chunked bytes are opaque to us).
 7. relay.run(conn, client, upstream)   // response flows back through the blind relay; closed at response end.
```

**Security:** `Proxy-Authorization` and `Proxy-Connection` are **never** forwarded to the origin (credential-exposure / framing bug). HTTP/1.1 keep-alive request multiplexing over one proxy hop is explicitly out of V1 scope and noted for V1.1; the parser is structured so a `KeepAliveHttpHandler` could later loop the request phase.

### 5.4 HTTP error replies

| Condition | Status |
|---|---|
| Missing/invalid Basic auth | `407 Proxy Authentication Required` |
| Malformed / oversize / CONNECT missing port | `400` / `414` / `431` |
| Method we don't proxy | `405` |
| DNS resolve failure | `502 Bad Gateway` |
| Upstream connect refused / no route | `502 Bad Gateway` |
| Upstream connect timeout | `504 Gateway Timeout` |
| VPN-down + BLOCK strategy | `503 Service Unavailable` (body: "hxmy proxy: upstream VPN is down") |
| Egress denied by SSRF filter / restricted target | `403 Forbidden` |
| Access-control denied (post-read) | `403 Forbidden` |

> The `503` body is deliberately human-readable so a client browser shows an explanatory reason when block-mode trips on a transient VPN drop, rather than a bare hung CONNECT (confirmed UX finding). The phone side also raises a high-visibility "VPN down — traffic blocked" notification state the instant the default network loses `TRANSPORT_VPN`.

---

## 6. Outbound socket creation — VPN egress + anti-SSRF (the critical part)

**Invariant (D4, two confirmed findings):** upstream (origin) sockets are created **without any `Network` binding** so they follow the process **default network**, which a normal full-tunnel VPN captures automatically. Source: *VPN (Android connectivity)* ("If you don't create allowed or disallowed lists, the system sends all network traffic through the VPN"). The following are **forbidden** for upstream sockets because they pin egress to a physical transport and **bypass the VPN**:

- `ConnectivityManager.bindProcessToNetwork(physicalNetwork)` — forbidden **process-wide** (it would silently route every origin connection around the VPN).
- `network.bindSocket(...)` / `Network.getSocketFactory()` on a non-default physical `Network`.

Any inbound reply-path pinning that the network layer may apply (a separate, server-FD-only concern; see §11 / NEEDS VERIFICATION) must be done **per-accepted-socket on the server FD only** and must never touch outbound origin sockets.

```kotlin
// core/proxy/Outbound.kt
interface OutboundConnector {
    /** Resolve [host] via remote DNS on the default network, then open a TCP socket on the
     *  process default network (VPN egress), applying DNS(5s)+connect(10s) and the egress filter. */
    suspend fun connect(host: String, port: Int): Socket
    /** SOCKS5 literal-IP path (ATYP IPv4/IPv6) — skip DNS, still apply the egress filter. */
    suspend fun connect(addr: InetAddress, port: Int): Socket
}

class DefaultNetworkOutboundConnector(
    private val resolver: RemoteResolver,
    private val egress: EgressPolicy,
    private val ioDispatcher: CoroutineDispatcher,
) : OutboundConnector {

    override suspend fun connect(host: String, port: Int): Socket =
        connect(resolveOrThrow(host), port)

    override suspend fun connect(addr: InetAddress, port: Int): Socket {
        // ANTI-SSRF: check the RESOLVED IP, after DNS, to defeat DNS rebinding / names → loopback/RFC1918.
        egress.assertAllowed(addr, port)                  // throws OutboundConnectError.Blocked on deny
        return withContext(ioDispatcher) {
            val s = Socket()                              // NO Network binding → process default network → VPN
            s.tcpNoDelay = true; s.keepAlive = true
            try {
                runInterruptible {
                    s.connect(InetSocketAddress(addr, port), ProxyTuning.CONNECT_TIMEOUT_MS.toInt())
                }
                s
            } catch (e: SocketTimeoutException)  { s.close(); throw OutboundConnectError.Timeout(addr, port) }
              catch (e: ConnectException)        { s.close(); throw OutboundConnectError.Refused(addr, port) }
              catch (e: NoRouteToHostException)  { s.close(); throw OutboundConnectError.NoRoute(addr, port) }
              catch (t: Throwable)               { s.close(); throw OutboundConnectError.Io(addr, port, t) }
        }
    }

    private suspend fun resolveOrThrow(host: String): InetAddress =
        if (host.isLiteralIp()) InetAddress.getByName(host)
        else withTimeout(ProxyTuning.DNS_TIMEOUT_MS) { resolver.resolveFirst(host) }  // §6.1
}
```

> `runInterruptible` lets structured cancellation interrupt the blocking `connect()`; the native `Socket.connect(sa, ms)` timeout is a belt-and-suspenders floor.

### 6.1 Remote DNS resolution (no DNS leak)

Both SOCKS5 `DOMAINNAME` and HTTP hostnames are resolved **on the phone** (remote DNS) so clients can be remote-DNS-friendly. To avoid a DNS leak, resolution must use **the same default network as the egress socket** — do **not** resolve via a path bound to a specific non-default `Network`. Because we never bind the egress socket to a physical network, `InetAddress.getByName(host)` already routes both DNS and TCP through the active default network (the VPN). Where the default-network resolver is needed explicitly, use the active network's resolver (the active network *is* the VPN network when a VPN is up):

```kotlin
interface RemoteResolver { suspend fun resolveFirst(host: String): InetAddress }
```

DNS timeout 5s. Empty result or `UnknownHostException` → `DnsFailure`. V1 takes the first address the resolver returns (respects system Happy-Eyeballs ordering); parallel A/AAAA racing is deferred.

### 6.2 IPv6 origin support

- SOCKS5 `ATYP=0x04` literals and CONNECT bracketed `[v6]:port` authority are parsed and connected directly (no DNS).
- Hostname targets resolving to AAAA records connect on an IPv6-capable egress via system resolution; "IPv6-only origins unreachable" is **not** a V1 limitation for hostname targets — only literal-IPv6 *parsing* needed explicit support, which is provided.

### 6.3 Anti-SSRF egress filter (D5 — confirmed finding)

`EgressPolicy.assertAllowed(resolvedIp, port)` is enforced **after DNS resolution** (resolve first, then check the resolved IP — defeats DNS rebinding and names that resolve to loopback/RFC1918). V1 **default DENY** for destinations that are:

- loopback (`127.0.0.0/8`, `::1`),
- link-local (`169.254.0.0/16`, `fe80::/10`),
- this-host / `0.0.0.0` / unspecified, and multicast,
- the proxy's **own listening addresses and ports** (no loop-back into our own listeners),
- private LAN ranges (`10/8`, `172.16/12`, `192.168/16`, CGNAT `100.64/10`) — **off by default**, gated behind an explicit "allow private-LAN destinations" opt-in toggle (most users want internet egress, not LAN pivoting).

Optionally a port allowlist restricts CONNECT/relay targets to known ports to avoid open-relay abuse (e.g. SMTP spam) — *HTTP CONNECT method* explicitly recommends restricting tunnel targets. A blocked destination maps to HTTP `403` / SOCKS5 `0x02 (not allowed by ruleset)`.

> Note: on Android 16+/17 the `ACCESS_LOCAL_NETWORK` grant that makes hxmy work as a LAN listener also authorizes *outbound* LAN access, and loopback is generally not gated by it — so this application-level egress filter is the actual control, not a platform permission.

### 6.4 VPN-down detection + fail-closed block (D4 — confirmed findings)

`VpnStateDetector` reads capabilities of **the app's own default network** via `registerDefaultNetworkCallback(callback, handler)` from the foreground service — **not** an `getAllNetworks()` scan for "some network with `TRANSPORT_VPN`." The per-UID default network already reflects split-tunnel routing: an app excluded from a per-app VPN sees the underlying non-VPN network as its default (no `TRANSPORT_VPN`), so the block strategy correctly fires for it. Source: *Reading network state*.

- "VPN up" requires `hasTransport(TRANSPORT_VPN)` **and** `NET_CAPABILITY_VALIDATED` (validated = real probed egress; gate "gateway up" on it). React in `onCapabilitiesChanged`, not synchronously in `onAvailable`.
- **`TRANSPORT_VPN` presence is necessary but not sufficient** proof that *this* destination egresses via the VPN (route-based / partial split-tunnel can leave some destinations outside the tunnel while the bit is present). The default block decision is therefore augmented by an **egress self-test** (open an outbound socket exactly as the relay does, hit an IP-echo endpoint, compare against the non-VPN baseline) surfaced in Diagnostics; a "VPN present but this app may be partially excluded (split-tunnel)" caveat is shown as a distinct state.
- **Fail closed atomically.** On any default-network change while in BLOCK mode, the detector flips `vpnUp=false` **and the relay layer tears down all active relayed connections and stops accepting new ones** until VPN-up is re-confirmed — it does not merely gate *new* requests (which would let in-flight sockets keep leaking through the async detection gap). New SOCKS5 requests get `0x03 (network unreachable)`; new HTTP requests get `503` with the explanatory body.

---

## 7. SOCKS5 — `Socks5ProxyServer`

Implements RFC 1928 (greeting/method negotiation, CONNECT, ATYP IPv4/IPv6/DOMAINNAME, reply codes) and RFC 1929 (username/password sub-negotiation). **No `UDP ASSOCIATE`, no `BIND`** — both reply `0x07` (command not supported). Wire layouts below are taken verbatim from the grounded reference §8 (RFC 1928/1929).

```kotlin
enum class Socks5Method(val code: Int) { NO_AUTH(0x00), USER_PASS(0x02), NO_ACCEPTABLE(0xFF) }
enum class Socks5Command(val code: Int) { CONNECT(0x01), BIND(0x02), UDP_ASSOCIATE(0x03) }
enum class Socks5Atyp(val code: Int) { IPV4(0x01), DOMAIN(0x03), IPV6(0x04) }
enum class Socks5Reply(val code: Int) {
    SUCCEEDED(0x00), GENERAL_FAILURE(0x01), NOT_ALLOWED(0x02), NETWORK_UNREACHABLE(0x03),
    HOST_UNREACHABLE(0x04), CONNECTION_REFUSED(0x05), TTL_EXPIRED(0x06),
    COMMAND_NOT_SUPPORTED(0x07), ADDRESS_TYPE_NOT_SUPPORTED(0x08)
}
```

### 7.1 Negotiation sequence (exact byte layout)

```
HANDSHAKE (all multi-byte fields via DataInputStream.readFully under withTimeout(HANDSHAKE_TIMEOUT_MS)):

 G1. Greeting:  VER(1)=0x05 | NMETHODS(1) | METHODS(NMETHODS)
     - Read EXACTLY NMETHODS method octets (never a fixed length).
     - VER != 0x05 → close silently (not SOCKS5).  NMETHODS == 0 → reply [0x05,0xFF], close.
 G2. Server method reply (2 bytes): VER(1)=0x05 | METHOD(1)
       if socksUserPassEnabled (auth is OPTIONAL and the user turned it ON, U3):
            METHODS contains 0x02 → reply [0x05,0x02] → AUTH
            else                  → reply [0x05,0xFF] → CLOSING   (client MUST close after 0xFF)
       else (auth off — the V1 DEFAULT; the UI has warned the user this network is open):
            METHODS contains 0x00 → reply [0x05,0x00] → REQUEST
            else                  → reply [0x05,0xFF] → CLOSING

 AUTH (RFC 1929, only after 0x02 selected; SUB-NEGOTIATION VER = 0x01, NOT 0x05):
       Read:  VER(1)=0x01 | ULEN(1) | UNAME(ULEN) | PLEN(1) | PASSWD(PLEN)     (length-prefixed, each 1..255)
       ok = authenticator.verifySocksUserPass(uname, passwd)
       Reply (2 bytes): VER(1)=0x01 | STATUS(1)   STATUS=0x00 success; ANY non-zero → MUST close.
       if !ok → reply [0x01,0x01] → fail(AuthRequired) → CLOSING

 REQUEST:
       Read:  VER(1)=0x05 | CMD(1) | RSV(1)=0x00 | ATYP(1) | DST.ADDR(var) | DST.PORT(2, big-endian)
       if VER != 0x05 → reply(GENERAL_FAILURE), close.   RSV must be 0x00.
       if CMD != CONNECT(0x01) → reply(COMMAND_NOT_SUPPORTED), close.
       Parse by ATYP:
         IPV4   (0x01) → 4 octets  → InetAddress (literal)
         IPV6   (0x04) → 16 octets → InetAddress (literal)
         DOMAIN (0x03) → LEN(1) then LEN name octets, UTF-8, NO terminating NUL → NOT resolved yet (remote DNS)
                          LEN == 0 → reply(GENERAL_FAILURE), close.
         else → reply(ADDRESS_TYPE_NOT_SUPPORTED 0x08), close.
       Egress-filter the (resolved) destination (§6.3); on deny → reply(NOT_ALLOWED 0x02), close.

 CONNECTING: transition(CONNECTING)
       upstream = when(atyp) {
           DOMAIN -> outbound.connect(domain, port)       // DNS(5s)+connect(10s) on default net (§6)
           else   -> outbound.connect(literalAddr, port)  // connect only
       }   // map OutboundConnectError → Socks5Reply (table in §7.3)

 RELAYING:
       reply(SUCCEEDED 0x00, BND.*)   then  relay.run(conn, client, upstream)   // §9
```

### 7.2 Reply construction

```
Reply frame:  VER(1)=0x05 | REP(1) | RSV(1)=0x00 | ATYP(1) | BND.ADDR(var) | BND.PORT(2, big-endian)
```

- On any **failure** before success: `REP` = the mapped error, `ATYP=0x01 (IPv4)`, `BND.ADDR = 0.0.0.0`, `BND.PORT = 0` (the conventional all-zero reply). Strict clients parse `ATYP`/length, so the family and byte count must match `ATYP` exactly.
- On **success**: `BND` is the upstream socket's *local* address/port (RFC 1928 §6) — informational; most clients ignore it. `ATYP` must match the family of `BND.ADDR`.

### 7.3 `OutboundConnectError` → `Socks5Reply` mapping (grounded in reference §8 / RFC 1928)

| Outbound error | SOCKS5 REP |
|---|---|
| `DnsFailure` / host-unreachable | `HOST_UNREACHABLE (0x04)` |
| `NoRoute` / network unreachable | `NETWORK_UNREACHABLE (0x03)` |
| `Refused` | `CONNECTION_REFUSED (0x05)` |
| **`Timeout` (connect timeout)** | **`GENERAL_FAILURE (0x01)`** |
| `NoNetwork` / VPN-down BLOCK | `NETWORK_UNREACHABLE (0x03)` |
| `Blocked` (egress filter) / admission denied | `NOT_ALLOWED (0x02)` |
| `Io` / unknown | `GENERAL_FAILURE (0x01)` |

> Correction vs the draft: connect timeout maps to **`0x01` general failure**, not `0x06 (TTL expired)`. The reference §8 error mapping assigns `connect timeout → 0x01`; `0x06` is reserved for TTL-expired and is not the standard code for a connect timeout.

### 7.4 SOCKS5 edge cases

- **Short/partial reads:** every multi-byte field uses `readFully`; a premature EOF mid-handshake → close silently (malformed framing — `Protocol` error, no reply, since framing cannot be trusted).
- **`0xFF` no-acceptable-methods** is always sent explicitly so the client does not hang; the client MUST close after it.
- Auth/egress failures send their typed REP/STATUS byte and then close. Auth is **optional** (U3); when the user **has** enabled it, the auth path is the access-control boundary preventing arbitrary LAN/hotspot peers from using the egress and it must **fail closed** (a non-zero STATUS then close), never fail open. When auth is **off** (the default) there is no credential check — the anti-SSRF egress filter (§6.3) still applies and the UI has warned that the network is open.

---

## 8. Error taxonomy (9 categories) and credential storage

```kotlin
// core/proxy/ProxyError.kt
sealed class ProxyError(val code: ProxyErrorCode, val message: String) {
    object VpnUnavailable          : ProxyError(VPN_UNAVAILABLE, "VPN egress unavailable")          // 1
    object LocalNetworkPermission  : ProxyError(LOCAL_NET_PERM,  "Local network permission missing")// 2
    data class PortInUse(val port: Int) : ProxyError(PORT_IN_USE, "Port $port already in use")       // 3
    data class DnsResolveFailed(val host: String) : ProxyError(DNS_FAILED, "DNS failed: $host")      // 4
    data class UpstreamTimeout(val ep: ConnEndpoint?) : ProxyError(UPSTREAM_TIMEOUT, "…")            // 5
    object ClientDisconnected      : ProxyError(CLIENT_DISCONNECT, "Client closed connection")       // 6
    object NetworkSwitched         : ProxyError(NETWORK_SWITCHED, "Phone network changed")           // 7
    data class AccessDenied(val reason: DenyReason) : ProxyError(ACCESS_DENIED, "…")                 // 8
    object BackgroundRestricted    : ProxyError(BG_RESTRICTED, "Background/battery restricted")      // 9

    companion object {
        fun fromThrowable(t: Throwable): ProxyError = when (t) {
            is OutboundConnectError.DnsFailure -> DnsResolveFailed(t.host)
            is OutboundConnectError.Timeout    -> UpstreamTimeout(t.ep())                  // 5
            is OutboundConnectError.Refused,
            is OutboundConnectError.NoRoute,
            is OutboundConnectError.Io         -> UpstreamTimeout(t.ep())                  // bucketed into 5
            is OutboundConnectError.Blocked    -> AccessDenied(DenyReason.EGRESS_FILTER)   // 8
            is SocketException                 -> ClientDisconnected                       // 6
            is BindException                   -> PortInUse(-1)                            // 3
            else                               -> UpstreamTimeout(null)
        }
    }
}
```

Mapping to the design's 9 bullets: ① VPN 不可用 → `VpnUnavailable`; ② 本地网络权限未授权 → `LocalNetworkPermission` (raised when `ACCESS_LOCAL_NETWORK` is missing on target SDK 37+; denial manifests as a client **TCP timeout**, not `ECONNREFUSED` — §11); ③ 端口被占用 → `PortInUse` (from `BindException`, §3.0); ④ DNS 解析失败 → `DnsResolveFailed`; ⑤ 远程连接超时 → `UpstreamTimeout`; ⑥ 客户端主动断开 → `ClientDisconnected`; ⑦ 手机网络切换 → `NetworkSwitched`; ⑧ 被访问控制/出口过滤拒绝 → `AccessDenied`; ⑨ 后台受限 → `BackgroundRestricted`. Each carries the protocol reply mapping from §5.4 / §7.3. Diagnostics record `code` + `clientIp` + `target` only — **never URL/path/payload**.

**Credential storage (U3 — applies only when the user has enabled the optional auth):** the SOCKS5 RFC 1929 / HTTP Basic credentials consumed by `Authenticator` are stored via **`EncryptedSharedPreferences` / Android Keystore-wrapped storage, never plaintext DataStore**, and the credential file is excluded from auto-backup. Credentials are never logged. Auth is **off by default**; when off, no credential exists to store.

---

## 9. RelayEngine — bidirectional byte pump (FD-leak-safe)

```kotlin
// core/proxy/RelayEngine.kt
class RelayEngine(private val ioDispatcher: CoroutineDispatcher) {

    /** Pumps both ways until EOF/half-close/idle/error. The accept-loop finally closes both sockets;
     *  this engine performs per-direction half-close and the half-close timeout.
     *  @param seedClientToUpstream residual bytes read during handshake (data after CONNECT's blank line,
     *         or a forwarded HTTP request body). */
    suspend fun run(
        conn: ProxyConnection, client: Socket, upstream: Socket,
        seedClientToUpstream: ByteArray? = null,
    ): RelayResult = coroutineScope {
        conn.transition(ConnState.RELAYING)
        val watchdog = launch { idleWatch(conn, this@coroutineScope) }   // idle reset on EITHER direction

        val up   = launch { HalfDuplexPump(client, upstream, conn.uploadBytes,   seedClientToUpstream, conn).pump() }
        val down = launch { HalfDuplexPump(upstream, client, conn.downloadBytes, null, conn).pump() }

        // First half to EOF half-closes the peer's write side (FIN) and starts HALF_CLOSE_TIMEOUT on the survivor.
        select<Unit> {
            up.onJoin   { runCatching { upstream.shutdownOutput() }; awaitOrTimeout(down) }
            down.onJoin { runCatching { client.shutdownOutput()   }; awaitOrTimeout(up) }
        }
        watchdog.cancel()
        conn.transition(ConnState.CLOSING)
        RelayResult(conn.uploadBytes.get(), conn.downloadBytes.get(), conn.lastError)
    }
}
```

### 9.1 `HalfDuplexPump`

```kotlin
private class HalfDuplexPump(
    private val srcSock: Socket, private val dstSock: Socket,
    private val counter: AtomicLong, private val seed: ByteArray?, private val conn: ProxyConnection,
) {
    suspend fun pump() {
        val src = srcSock.getInputStream(); val dst = dstSock.getOutputStream()
        val buf = ByteArray(tuning.relayBuffer)                            // settings-backed, default 32 KiB (§10)
        seed?.let { dst.write(it); dst.flush(); counter.addAndGet(it.size.toLong()); conn.touch() }
        while (true) {
            val n = runInterruptible(ioDispatcher) { src.read(buf) }        // interruptible blocking read
            if (n < 0) { runCatching { dstSock.shutdownOutput() }; break }  // EOF → per-direction FIN, keep peer open
            runInterruptible(ioDispatcher) { dst.write(buf, 0, n); dst.flush() }
            counter.addAndGet(n.toLong()); conn.touch()                     // touch resets idle (either direction)
        }
    }
}
```

### 9.2 Backpressure & memory

- **Backpressure is the TCP windows themselves.** Each pump is a synchronous read→write loop: it cannot read faster than it can write, because `dst.write()` blocks when the destination's send buffer + peer receive window are full. No userspace queue grows, so a slow client cannot make us buffer unboundedly. This is why the blocking-loop model is memory-safe even at the maximum configured cap.
- **Per-connection memory:** 2 pumps × the per-connection relay buffer (default 32 KiB, range 8–256 KiB — §10) of userspace heap. Memory scales as `globalMaxConn × 2 × relayBuffer`; at the defaults `256 × 2 × 32 KiB ≈ 16 MiB` of relay buffers, far below thread-per-connection's stack cost. Raising the cap and/or the buffer (e.g. the 高吞吐 preset) raises this ceiling proportionally — a 高吞吐 setting of `1024 × 2 × 256 KiB ≈ 512 MiB` is the extreme upper bound the IMPACT table warns about.

### 9.3 Half-close, idle, and leak invariants (confirmed finding)

- **Half-close per direction:** when `src.read()` returns `-1`, we `shutdownOutput()` on the *peer* (FIN) and keep the reverse direction relaying, so TLS `close_notify` / request-complete-then-await-response still receives the full response. We **never** close both sockets on the first EOF (that would break half-closing protocols), and we **never** leave both open (that would leak). `HALF_CLOSE_TIMEOUT_MS = 15_000` bounds the survivor.
- **Idle reset on either direction:** `idleWatch` ticks every `IDLE_PROBE_MS`; only when `now - lastActivityNs > tuning.idleTimeoutMs` (setting, default `300_000`, range 30 s–1800 s — §10) does it cancel the relay scope. `conn.touch()` fires on every transferred chunk in **either** direction, so a slow-but-alive download is not killed by a single shared deadline.
- **No FD leak under cancellation:** all reads/writes are `runInterruptible`, so scope cancellation interrupts the blocking syscall promptly, and the accept-loop `finally` (§3.1) closes the client FD and releases the slot **exactly once**. On origin-connect failure *after* the client handshake, the handler emits the correct CONNECT/SOCKS5 error reply *then* closes — the client socket is never silently leaked. A Diagnostics gauge exposes the active-connection / FD count so leaks are observable rather than masked by restarts.

### 9.4 Throttled state (D7 — confirmed Compose finding)

Byte counters are accumulated in plain `AtomicLong`s inside the proxy core; they are **never** written into a UI-observed `StateFlow` per read. The dashboard observes **immutable aggregate snapshots emitted at a throttled cadence** (e.g. a 1 Hz ticker / `sample(1000ms)`): client *count* plus aggregate up/down rate. `ClientSession` is kept minimal — no per-client live rate fields streamed into Compose. Per-client rich lists are deferred; the V1 `StateFlow` carries stable, immutable types so Compose recomposition is bounded and the per-client list does not trigger recomposition storms. Source: *State and Jetpack Compose* (use `State<List<T>>` + immutable `listOf()`, never `mutableListOf()`), *ViewModel overview* (single immutable `uiState: StateFlow`).

---

## 10. Tuning — user-adjustable settings + presets (U4)

The connection/resource limits are **user-adjustable settings**, persisted in **DataStore** and **applied at runtime** (no rebuild, no app restart). `ProxyTuning` is no longer a `const` object: it is an **immutable snapshot read from the settings repository** and re-read whenever the user changes a value. The accept loop reads the caps live (§3.1); the relay reads the buffer live (§9.1); the service re-creates the bounded dispatcher when relay parallelism N changes (§1.1). Static framing/timeout constants that are not user-facing stay as compile-time constants.

```kotlin
// core/proxy/ProxyTuning.kt — a VALUE read from DataStore-backed settings, runtime-applied.
data class ProxyTuning(
    // ---- USER-ADJUSTABLE (DataStore-backed; a preset just sets these underlying values) ----
    val maxGlobalConn:  Int  = 256,        // global max connections     · range 32–1024
    val maxPerClientConn: Int = 128,       // per-client max connections · range 16–512
    val ioParallelism:  Int  = 32,         // relay parallelism N = Dispatchers.IO.limitedParallelism(N) · range 4–64
    val relayBuffer:    Int  = 32 * 1024,  // per-connection relay buffer · range 8 KiB–256 KiB
    val idleTimeoutMs:  Long = 300_000L,   // idle timeout (no bytes either way) · range 30 s–1800 s
) {
    // ---- NON-USER-FACING framing/timeout constants (fixed) ----
    companion object {
        const val ACCEPT_BACKLOG       = 128
        const val STOP_DRAIN_MS        = 5_000L
        const val CONNECT_TIMEOUT_MS   = 10_000L     // upstream TCP connect
        const val DNS_TIMEOUT_MS       = 5_000L      // remote resolve
        const val IDLE_PROBE_MS        = 30_000L
        const val HALF_CLOSE_TIMEOUT_MS= 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val SOCK_RCVBUF          = 64 * 1024
        const val SOCK_SNDBUF          = 64 * 1024
        const val MAX_REQUEST_LINE     = 8 * 1024
        const val MAX_HEADER_BYTES     = 64 * 1024
    }
}
```

### 10.1 Settings, defaults, and ranges

| Setting | Default | Range | Applied at runtime by |
|---|---|---|---|
| Global max connections | **256** | 32 – 1024 | accept-loop admission gate (§3.1) |
| Per-client max connections | **128** | 16 – 512 | `AccessController` per-client slot reservation (§3.1) |
| Relay parallelism `N` (`limitedParallelism(N)`) | **32** | 4 – 64 | service re-creates `proxyIoDispatcher` (§1.1) |
| Per-connection relay buffer | **32 KiB** | 8 KiB – 256 KiB | `HalfDuplexPump` buffer (§9.1) |
| Idle timeout | **300 s** | 30 s – 1800 s | `idleWatch` deadline (§9.3) |

### 10.2 Presets (a preset just sets the underlying values; advanced users override each)

| Preset | Global conn | Per-client | N | Buffer | Idle | Intent |
|---|---|---|---|---|---|---|
| **省电 Battery** | 64 | 32 | 8 | 16 KiB | 120 s | Smallest caps/N/buffer; minimize threads/wakeups/heap. |
| **均衡 Balanced** *(= the defaults above)* | 256 | 128 | 32 | 32 KiB | 300 s | The shipped default. |
| **高吞吐 High-throughput** | 1024 | 512 | 64 | 256 KiB | 600 s | Larger caps/N/buffer to fill a high-BDP 4G/5G link. |

### 10.3 IMPACT of each setting (what it controls; too SMALL; too LARGE)

| Setting | Controls | Symptom if TOO SMALL | Symptom if TOO LARGE |
|---|---|---|---|
| **Global max connections** | Hard ceiling on simultaneous tunnels across all clients. | **REFUSED** connections → broken page loads; **and can REDUCE throughput** by blocking the clients' own parallel connections that fill a high-BDP link (see KEY PRINCIPLE). | More heap (buffers) + more FDs; risk of OEM/OOM pressure on a phone. Does *not* by itself raise per-stream speed. |
| **Per-client max connections** | Ceiling per single client/device (fairness). | One device with many parallel streams (a busy browser) gets connections refused mid-page. | A single client can monopolize the global cap, starving other devices. |
| **Relay parallelism N** | How many directions can be *actively copying bytes* at one instant (carrier threads). | Active transfers queue behind the cap → added latency / lower aggregate throughput under load. | More carrier threads → more context-switch / memory cost; past the point of diminishing returns it just wastes CPU. |
| **Per-connection relay buffer** | Bytes moved per `read()`/`write()` syscall per direction. | More syscalls per MB → higher CPU and lower per-stream throughput on a fast link. | `globalConn × 2 × buffer` heap grows fast (256 × 2 × 256 KiB ≈ 256 MiB) — OOM risk. |
| **Idle timeout** | How long a byte-silent tunnel is kept before close. | Long-poll / SSE / interactive SSH-style sessions get killed mid-idle. | Dead/leaked tunnels linger, holding FDs and a global-cap slot longer than necessary. |

### 10.4 KEY PRINCIPLE — connection count ≠ bandwidth

**Raising the connection cap does not raise bandwidth, and a too-small cap can *lower* it.** The count cap only bounds how many tunnels may exist at once. Per-stream and aggregate throughput on a high-BDP link (this user's link is **~100–500 Mbps** 4G/5G) is governed by **relay parallelism N + per-connection buffer + the link itself**, not by the count cap. A too-small global cap produces two distinct failures: (1) **REFUSED** connections → visibly broken page loads, and (2) **reduced effective throughput**, because modern clients open many parallel connections to saturate a fat pipe and the cap starves exactly those parallel streams. So tune for throughput by raising **N and the buffer** (or pick 高吞吐); raise the **count cap** only to admit more concurrent tunnels/clients, accepting the proportional memory cost.

**Memory budget example:** `globalConn × 2 directions × relayBuffer`. At the 均衡 defaults: `256 × 2 × 32 KiB ≈ 16 MiB` of relay buffers, serviced by at most `N` carrier threads. The 高吞吐 extreme `1024 × 2 × 256 KiB ≈ 512 MiB` is the upper bound the IMPACT table warns about — advanced users picking it must accept that heap cost.

> Whether this coroutine + blocking-socket + `limitedParallelism` model actually reaches **real ~500 Mbps** under the user's link is **NEEDS VERIFICATION** (load test; tune buffer/N first; NIO is the V2 escape hatch). See NEEDS VERIFICATION below.

---

## 11. Android-version specifics affecting this subsystem

- **Dual-stack wildcard bind (all versions):** bind the *unspecified* wildcard (accepts IPv4 + IPv6); admission gates by the receiving-interface local address (§3). Source: *Reading network state* / standard socket semantics.
- **`ACCESS_LOCAL_NETWORK` is a DAY-ONE HARD requirement — first release targets SDK 37 (U2; reference §1).** minSdk stays 29 (Android 10). It is a runtime permission in the `NEARBY_DEVICES` group, enforced **deep in the networking stack in BOTH directions**, and it gates **accepting incoming TCP connections** (inbound `accept()`) **AND** mDNS multicast (the NSD advertising under U1) — so binding `0.0.0.0`/wildcard **cannot evade it**. When denied, inbound LAN TCP from peers **times out** (not `ECONNREFUSED`), and the proxy silently accepts nothing. V1 ships the **hard-gate first-run flow**: request the permission with a clear rationale **before** starting the proxy; if denied, **refuse** the running/sharing state and show a blocking explanation. This subsystem requires the grant **before** it binds/accepts; if it is not granted, `start()` must surface `ProxyError.LocalNetworkPermission` (not a soft diagnostics line). All permission-API use is guarded by `Build.VERSION` by the permission manager (not this subsystem); this subsystem only consumes the grant state and surfaces the blocked state. (On Android 10–16 the platform does not yet enforce, but because the first release targets SDK 37 the manifest declaration + runtime request ship from day one.) Sources: *Local network permission*, *Behavior changes: Android 17*.
- **Foreground service type (U5):** the servers only run while `ProxyForegroundService` holds them — and a long-running local proxy *requires* a Foreground Service because Android otherwise kills/throttles background sockets; the user-visible FGS notification is the cost of staying alive. *What `foregroundServiceType` means:* Android 14+ requires every FGS to declare *why* it runs in the foreground (a typed category), each type carrying its own permission + runtime prerequisite, and the type is declared in both the manifest and the Play Console. The FGS uses **`connectedDevice` as primary** — declaring `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` and a qualifying companion permission (`CHANGE_NETWORK_STATE` and/or `CHANGE_WIFI_STATE`) **satisfied before `startForeground()`** — with **`specialUse`** (plus the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property and a Play-Console justification) as the documented fallback. **`dataSync` is rejected** (its 6h/24h cap + `RemoteServiceException` kill is fatal for an always-on gateway). The type is **changeable later** (manifest + Play Console declaration; changing it may trigger Play re-review). The server scope is a child of the service scope, so service stop deterministically cancels accept + all relays. Sources: *Foreground service types are required (Android 14)*, *Foreground service types reference*. (Whether Play accepts `connectedDevice` vs requires `specialUse` is **NEEDS VERIFICATION** — policy review.)
- **`startForeground` ordering:** bind sockets/start accept loops **only after** `startForeground()` (called within 5 s of `startForegroundService()`), so a bind failure can never cost the mandatory `startForeground()` call. Source: *Foreground services overview*.
- **`POST_NOTIFICATIONS` denied:** the FGS — and these listeners — still run; the in-shade status is suppressed but the in-app UI remains the authoritative control surface. Source: *Notification runtime permission*.
- **Background/OEM kills:** a process kill cancels the whole scope (sockets close, slots free). The in-process watchdog can restart listeners *while the FGS is alive* but **cannot** resurrect a killed service from the background on Android 12+ (`ForegroundServiceStartNotAllowedException`) unless the app is battery-optimization allowlisted or relaunched via a user-interaction/exact-alarm/FCM exemption; the core only guarantees clean teardown and surfaces `BackgroundRestricted`. Source: *Foreground services overview*.
- **VPN egress / inbound reply path (D4):** upstream sockets follow the default network and are never pinned (§6). The separate claim that **inbound LAN reply packets can be black-holed by VPN routing unless the accepted server socket is pinned to the underlying LAN network** is **plausible but NEEDS VERIFICATION on-device** (the verified review judged it an edge case, not a near-universal blackhole — Android routing is source-address-aware and comparable LAN-server apps work with a VPN up). The mitigation (per-accepted-socket `Network.bindSocket()` on the **server FD only**, plus a real TCP-handshake diagnostic probe) is documented as a verification task, **not** wired in as settled behavior, and must never touch outbound origin sockets. Source: *VPN (Android connectivity)*, *Reading network state*.

---

## 12. Forward-compat notes (deferred — not designed here)

- `RelayEngine` is TCP-only; the pumps are framing-agnostic so a future **UDP ASSOCIATE** path (V2) can add a datagram relay without touching the TCP pump.
- `OutboundConnector` is the single egress chokepoint; a future **Root transparent / tun2socks** mode (V2) swaps its implementation, leaving the protocol servers unchanged.
- **mDNS / NSD is now IN V1 (U1), as a convenience layer.** `BoundPort`/the entry model carry the live interface address(es) and the **actual bound port**, which the sibling NSD publisher (in the Foreground Service, not designed here) consumes to advertise the listeners (`_http._tcp`, `_socks._tcp`, PAC service). This subsystem itself still emits **only raw IP literals**; the publisher and the PAC/entry generator add `hxmyproxy.local` **first with the concrete interface IP(s) as the mandatory fallback underneath** (`.local` is never emitted alone — §0). Multi-interface: advertise per active link; a client resolves `hxmyproxy.local` to the IP on its own segment.
- HTTP keep-alive request multiplexing over one proxy hop is out of V1 (§5.3); the parser is structured so a `KeepAliveHttpHandler` could later loop the request phase.

Deliverable files: `/Users/wresource/AndroidStudioProjects/hxmyproxy/app/.../core/proxy/{ProxyServer,ProxyAcceptLoop,HttpProxyServer,Socks5ProxyServer,RelayEngine,Connection,Outbound,ProxyParsers,ProxyError,ProxyTuning}.kt` (package `com.mzstd.hxmyproxy.core.proxy`).

---

## 与文档基准/评审的修订记录

### 决策更新 (2026-06-22)

These user decisions **supersede** the prior D1/D3/D5/D6 entries below where they conflict; the historical entries are kept as the record of the earlier revision.

- **U1 — mDNS is now IN V1 as a convenience layer (was: deferred to V1.1).** The per-interface raw-IP entry list + manual config + PAC stays the **primary, broadest-compat** path; mDNS is additive. The Foreground Service advertises `_http._tcp` / `_socks._tcp` / a PAC service via `NsdManager.registerService`, reading back the actual `serviceName` in `onServiceRegistered` (reference §9). PAC chains and recommended-entry output now list `hxmyproxy.local` **first then the concrete interface IP(s) as a mandatory fallback** (`.local` never emitted alone — §0); multi-interface advertises per active link so a client resolves to the IP on its own segment. This subsystem itself still emits only raw IP literals; §0 and §12 updated, the §12 "mDNS = #1 V1.1 item" note replaced.
- **U2 — first release targets SDK 37 (was 36); minSdk stays 29.** `ACCESS_LOCAL_NETWORK` is now a **day-one mandatory** runtime permission for **both** inbound `accept()` and mDNS multicast, with a hard-gate first-run flow (request-before-serve; refuse the running state on denial; denial = client TCP timeout). §11 rewritten: removed "V1 may pin target SDK ≤ 36 to defer enforcement"; moved the permission from deferred/opt-in to a hard day-one requirement; §3.0 / §8 references already say "target SDK 37+".
- **U3 — authentication is OPTIONAL, default OFF (restores 认证可选; supersedes the D5 "auth-required by default beyond a trusted network" framing).** §0/§2/§7/§8 reframed: default no-auth with a clear open-network warning (`未开启认证…`); SOCKS5 RFC 1929 / HTTP Basic stay as **optional** mechanisms; when enabled, credentials stay in `EncryptedSharedPreferences`/Keystore, never plaintext/logged. **Kept unchanged:** the anti-SSRF `EgressGuard` (loopback/link-local/this-host/multicast/own-listeners blocked by default; private-LAN allowed by default with an optional block toggle — §6.3) and source-IP admission as a convenience filter only.
- **U4 — connection/resource limits are now user-adjustable DataStore-backed settings + 3 presets (supersedes the D6 fixed 256 cap framing).** §10 rewritten: 5 settings with defaults/ranges (global conn 256/32–1024, per-client 128/16–512, relay parallelism N 32/4–64, relay buffer 32 KiB/8–256 KiB, idle 300 s/30–1800 s); presets 省电 / 均衡(=defaults) / 高吞吐; an **IMPACT table** (controls / too-small / too-large per setting); and the **KEY PRINCIPLE connection count ≠ bandwidth** (too-small cap → REFUSED loads **and** reduced throughput by starving the clients' parallel streams on a ~100–500 Mbps high-BDP link; per-stream speed = N + buffer + link, not the count cap; memory example `256 × 2 × 32 KiB ≈ 16 MiB`). `ProxyTuning` is now a settings-read snapshot, not a `const` object (§10); §1/§1.1 wire N from settings (default 32) and the dispatcher is re-created on change; §3.1 reads caps live; §9.1/§9.2 read the buffer live. The D6 coroutine + blocking-socket + `limitedParallelism` model itself is **unchanged**.
- **U5 — Foreground service unchanged in substance, clarified.** `connectedDevice` stays primary (FOREGROUND_SERVICE + FOREGROUND_SERVICE_CONNECTED_DEVICE + a qualifying CHANGE_NETWORK_STATE/CHANGE_WIFI_STATE satisfied before `startForeground()`), `specialUse` the documented fallback, `dataSync` rejected (6 h cap). §11 adds a plain-language explanation of what `foregroundServiceType` means and why a proxy needs an FGS, and notes the type is changeable later (manifest + Play Console; may trigger Play re-review). Play acceptance of `connectedDevice` vs `specialUse` stays NEEDS VERIFICATION.

### 历史修订 (earlier revision vs draft/评审)

- **D1 / mDNS — removed every `hxmyproxy.local`.** The draft's §12 forward-compat note advertised `hxmyproxy.local` against bound ports. V1 defers mDNS, so emitting an unresolvable host is "broken on arrival." Replaced with raw selected-interface IP literals everywhere; §0, §9.4, §12 now state the entry/PAC/notification surfaces carry live addresses + the actual bound port, and mDNS is marked the #1 V1.1 item. (Confirmed finding: PAC/UI hardcoding `.local` while mDNS is out of V1 is broken; cross-cutting D1.) **[Superseded by U1 — mDNS is now in V1.]**
- **SOCKS5 wire contract pinned (confirmed finding; reference §8b/§8c).** §7 now specifies exact RFC 1928 greeting/request and RFC 1929 sub-negotiation byte layouts, the 2-byte `0x01|STATUS` auth reply with mandatory close on non-zero, DOMAINNAME length-prefix with **no NUL**, big-endian ports, the `0xFF` no-acceptable-methods close, and the full success/failure reply frame (`ATYP=0x01`, `BND=0.0.0.0:0` on failure; matching-family `ATYP` on success).
- **SOCKS5 connect-timeout REP corrected.** Draft mapped connect timeout to `0x06 (TTL expired)`; reference §8 maps connect timeout → `0x01 (general failure)`. §7.3 fixed; `0x06` reserved for TTL.
- **HTTP CONNECT made spec-exact (reference §8d).** §5.2 now enforces authority-form with **mandatory port** (no `443` default — was a draft edge case), any **2XX** → blind relay, no body, bracket-aware IPv6 authority, and adds the restrict-targets/anti-open-relay step.
- **Plain HTTP forward-proxy fully specified (confirmed finding).** §5.3 added: absolute→origin request-line rewrite, Host reconciliation, RFC 7230 §6.1 hop-by-hop stripping, **`Proxy-Authorization`/`Proxy-Connection` never forwarded**, and V1 scoped to one-request-per-connection `Connection: close` to avoid keep-alive framing/smuggling. (Draft left this unspecified.)
- **IPv6 (partially-correct finding).** §3.0 binds the dual-stack *unspecified* wildcard instead of literal `0.0.0.0`; §6.2 adds `ATYP=0x04` + bracketed `[v6]:port`; admission keyed off interface local address handles IPv6 prefixes. Clarified hostname AAAA origins are reachable (only literal-v6 parsing needed work).
- **Anti-SSRF egress filter added (D5 — confirmed finding).** New §6.3: post-DNS-resolution IP filter denying loopback/link-local/this-host/multicast and the proxy's own listeners by default; private-LAN opt-in; defeats DNS rebinding by checking the resolved IP. Draft had no destination filtering.
- **VPN egress invariant hardened (D4 — two confirmed/partially-correct findings).** §6 now states the explicit unbound-outbound invariant, **forbids `bindProcessToNetwork` process-wide**, adds the egress self-test beyond the `TRANSPORT_VPN` bit, queries the **per-UID default network** (not an all-networks scan), gates on `VALIDATED`, and makes BLOCK **fail closed atomically** by tearing down in-flight relays (closing the async detection-gap leak). Draft used a permissive "active network = VPN" assumption and gated only new requests.
- **Relay half-close / FD-leak invariants (confirmed finding).** §9 rewritten for per-direction `shutdownOutput` on EOF, idle reset on **either** direction, guaranteed single FD close in the accept-loop `finally`, error-reply-then-close on post-handshake origin failure, and a Diagnostics FD gauge. Draft named the timeouts but not the per-direction/leak invariants.
- **EADDRINUSE / SO_REUSEADDR / post-bind port (partially-correct finding).** §3.0 sets `SO_REUSEADDR`, publishes the **actual** bound port, and surfaces `BindException` as typed `PortInUse` so a conflict is named rather than running into unreachable ports. Noted `SO_REUSEADDR` only fixes the restart race, not a competing app holding the port.
- **Admission by receiving-interface local address (confirmed finding).** §3.1 buckets/admits on `Socket.getLocalAddress()`, not a subnet-contains test on the spoofable remote IP; explicitly labels subnet admission a convenience filter, not authentication.
- **Auth-required default + encrypted credential storage (confirmed findings; D5).** §0/§7/§8: auth is the default beyond an explicitly-trusted network; credentials live in `EncryptedSharedPreferences`/Keystore, never plaintext DataStore. **[Superseded by U3 — auth is OPTIONAL, default OFF; the encrypted-credential-when-enabled and anti-SSRF parts remain in force.]**
- **Concurrency decision committed (D6 — confirmed finding).** §1 keeps the draft's coroutines + blocking-sockets + `limitedParallelism` choice, but now documents the head-of-line-blocking trade-off at the cap and how N is chosen, forbids thread-per-connection and raw NIO/Netty, lowers the V1 cap to 256 with a computed buffer ceiling (partially-correct sizing finding), and marks the custom Service scope + `limitedParallelism` as NEEDS VERIFICATION per reference §7/§10. **[U4 keeps this model but makes the caps/N/buffer user-adjustable settings with presets — see §10.]**
- **FGS type corrected to `connectedDevice` primary (D2 — confirmed findings; reference §2).** §11 drops the draft's `connectedDevice|dataSync`, declares the gating permissions satisfied before `startForeground()`, and names `specialUse` as the fallback.
- **`ACCESS_LOCAL_NETWORK` reframed as a hard gate (D3 — confirmed finding; reference §1).** §11 + error taxonomy: it gates inbound `accept()` itself on target SDK 37+, denial → **client TCP timeout**, `start()` must refuse the running state and raise `LocalNetworkPermission`. **[U2 makes this day-one mandatory: first release targets SDK 37, also gates mDNS multicast.]**
- **Throttled UI snapshots (D7 — confirmed Compose finding).** §9.4: no per-read writes into observed `StateFlow`; throttled immutable aggregates; minimal `ClientSession`.
- **Testability seams (partially-correct finding).** §0.2: pure parsers over `ByteArray`/streams, interface seams for connector/resolver, injected dispatcher, pure CIDR/egress predicates.

---

## NEEDS VERIFICATION

1. **Custom long-running Service `CoroutineScope` pattern.** `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by the service and cancelled in `onDestroy()` is **not** covered by *Kotlin coroutines on Android* (reference §7/§10 flags this). Verify against the advanced-coroutines / coroutines-best-practices docs before relying on it.
2. **`Dispatchers.IO.limitedParallelism(N)` sizing and starvation behavior.** The coroutines doc gives no numeric concurrency cap and does not document `limitedParallelism`; the default relay-parallelism `N` (32) and the head-of-line-blocking trade-off at the cap are engineering judgments to validate under a representative workload (reference §7 open item 6). N is now user-adjustable (range 4–64, §10), so users can tune it, but the default and ceiling still need a load test.
3. **Real ~500 Mbps throughput under this coroutine + blocking-socket + `limitedParallelism` model (U4).** The user's link is ~100–500 Mbps; whether the blocking-relay model reaches that on real hardware is unproven. Validate by load test, tuning the per-connection buffer and N (or the 高吞吐 preset) first; NIO is the documented V2 escape hatch if it cannot.
4. **Inbound LAN reply-path black-holing while a VPN is up.** Whether accepted server-socket replies need per-FD pinning to the underlying LAN `Network` (`Network.bindSocket()` on the server FD only) to avoid being routed into the VPN table is **device/VPN-specific and unverified on-device**. The verified review judged it an edge case, not a near-universal failure. Treat the mitigation + a real TCP-handshake diagnostic probe as a verification task, not settled design.
5. **Google Play FGS-type acceptance.** Whether Play accepts the `connectedDevice` justification for a passive LAN proxy or requires `specialUse` is a policy-review judgment not determinable from the docs (reference §2 / open item 7). Changing the type later (U5) may itself trigger Play re-review.
6. **`ACCESS_NETWORK_STATE` requirement.** The VPN/default-network detection in §6.4 reads network state; *Reading network state* says no permission is needed to read state but flags a discrepancy with the `ConnectivityManager` API reference. Declare `ACCESS_NETWORK_STATE` defensively; exact requirement is **NEEDS VERIFICATION** (reference §6 / open item 3).
7. **Egress self-test endpoint.** The IP-echo comparison in §6.4 assumes a reachable, trusted echo/identity endpoint to distinguish VPN vs bare-link egress; the concrete endpoint/timeout/privacy handling for that probe is unspecified and must be designed and verified.
8. **NSD / mDNS runtime-permission + reliability (U1; reference §9).** The NSD page is silent on runtime permissions; advertising via `NsdManager` on target SDK 37+ almost certainly requires `ACCESS_LOCAL_NETWORK` (now declared day-one under U2), and `NEARBY_WIFI_DEVICES` may apply on Android 13/14 — confirm against the local-network-permission / nearby-devices docs. mDNS reliability across Windows / some routers / some Android clients is the reason the IP fallback under every `.local` entry is mandatory (§0); this is a documented reliability caveat, not a settled guarantee.
