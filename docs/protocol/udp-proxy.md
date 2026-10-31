# Proxy transport profiles

This development branch adds UDP forwarding to the existing ordinary Android
proxy service. The application does not create a VPN interface, require root,
inject raw IP packets, or replace another VPN application. A selected existing
VPN is an optional egress network, just like a physical network.

## Client interfaces

| Interface | Use | Reply source and lifetime |
| --- | --- | --- |
| HTTP/1.1 CONNECT-UDP | Fixed-target datagrams, carried by HTTP Datagram Capsules | Exact resolved target IP and requested port; carrier closes the flow |
| SOCKS5 UDP ASSOCIATE | Standard UDP clients, including transfer-port protocols such as TFTP | Approved target IP; observed reply ports may be associated with the original socket; TCP control owns the association |
| SOCKS5 BIND | Incoming secondary TCP connections, including proxy-aware active FTP | Two standard replies; known peer address and optional source port; selected Network remains fixed |
| DNS message control | Queries through the phone's selected DNS network | Full DNS wire responses; optional original-client UDP budget |
| ICMP Echo control | IPv4/IPv6 Echo using ordinary Android ping sockets | Original identifier, sequence and payload; IPv6 checksum recomputed by the client |

These interfaces use the existing admission, authentication, rules and egress
selection. A successful association means the socket is ready, not that the
target has replied. Counters account for application UDP payload, excluding
proxy framing. Pending setup, per-client connections, target flows and queued
data are bounded. Stopping sharing cancels owned work and closes sockets.

The HTTP listener uses the existing cleartext LAN transport profile. This is not
a TLS-protected MASQUE deployment or a claim of cryptographic endpoint identity.
Ordinary clients do not need the optional discovery/session extension.

HTTP uses Basic authentication with a UTF-8 challenge; SOCKS uses RFC1929 method
02 when authentication is enabled. Invalid UTF-8 and ambiguous duplicate HTTP
authentication headers are rejected. Credential pairs and policy generations are
published together; a UDP/control or BIND handshake approved before a policy change cannot allocate
resources in the new generation. Credentials are never included in capability data.
HTTP Basic usernames cannot contain `:`. Standard RFC1929 credentials are each
1–255 bytes; UTF-8 byte length can exceed the number of visible characters. The
existing empty-username verifier compatibility remains, but is not an RFC1929
requirement. Long credentials may be usable by HTTP without fitting SOCKS framing.

## HTTP/1.1 interface

Use an origin-form request on the HTTP proxy port:

```http
GET /.well-known/masque/udp/example.com/443/ HTTP/1.1
Host: phone
Connection: Upgrade
Upgrade: connect-udp
Capsule-Protocol: ?1

```

When authentication is enabled, include the existing `Proxy-Authorization`
header. The response is `101 Switching Protocols`; subsequent bytes are
DATAGRAM Capsules with context ID zero. Percent-encode IPv6 colons in the target
path. Empty UDP payloads are valid. Unknown capsule types/contexts are skipped
with bounded memory/work; malformed/truncated frames close the carrier.

`GET /__hxmyproxy/capabilities` is an optional, authenticated discovery endpoint.
Its `session_id` changes when sharing/listeners restart. A client may supply
`X-HxmyProxy-Session`; if supplied, an empty, duplicate or stale value is rejected
with 409 before DNS/socket allocation. Absence is accepted. The companion Mac
adapter uses this extension; a generic client need not.

The additive `socks5` field is null when no SOCKS listener is actually bound. When
present it contains the actual `port`, `connect`, `udp_associate`, `bind`,
`max_udp_wire`, `bind_peer_required`, and `bind_requires_reachable_address` values.
Older v1 fields remain unchanged. This is a capability description, not permission
to silently switch an explicitly selected upstream protocol or guess another port.

### App-owned local-target policy

Version 1.49.68 adds optional applied-policy metadata to the capability response
on both HTTP and SOCKS control routes:

```json
"client_policy": {"version": 1, "revision": 0, "proxy_local_targets": false}
```

The flag is read from the applied egress guard together with its revision under
the sharing-session lock. Revisions are nonnegative and ordered within the
current `session_id`; a new sharing session can restart at revision zero. A
control request spanning a policy change is rejected with 409. Missing metadata or an
unsupported policy version means unknown, not a confirmed false value.

The App switch is off by default, including legacy settings without an explicit
opt-in. Explicit choices survive persistence and backup restore; the old deny
flag is retained for compatibility and a conflicting denial takes precedence.
False makes PAC return DIRECT for private IP literals, simple hostnames, `.local`
and `.home.arpa`. Requests nevertheless sent to the phone are refused: HTTP 403,
SOCKS ruleset denial, or a dropped UDP target, with `local-network-disabled`
diagnostics. The client must arrange its own direct connection. Phone-side
DIRECT egress is not client-side bypass.

Known local names and private literals are refused before DNS or egress selection.
Public names still use the existing DNS and permitted egress-recovery behavior;
their resolved private addresses remain subject to the guard. Loopback and
link-local targets are never forwarded by the phone, even with the switch on.
Incoming client admission is unchanged. This policy is not authority for a
companion to install a global packet capture layer or change OS DNS/routes.

The advertised 65507-byte limit is an endpoint ceiling, not a path MTU guarantee.
HTTP-forwarded UDP must not introduce IP fragmentation; a packet which cannot
fit the outgoing path is dropped. The same target IP/port remains fixed for the
carrier. These restrictions come from [RFC9298 §3.1](https://www.rfc-editor.org/rfc/rfc9298.html#section-3.1).
TCP carriage also entails head-of-line blocking.

## Native SOCKS5 UDP

Use the configured SOCKS5 TCP port and the normal method/authentication handshake,
then command 03. Send datagrams to the returned BND.ADDR/BND.PORT. The server
checks the TCP peer IP and pins the datagram source port; a specified nonzero
client-port hint is enforced. Closing TCP ends all associated UDP targets.

Compatible targets in one association share an unconnected outbound socket for
the same actual Network, address family and route action. This preserves the
socket and local source port used by STUN when contacting another peer. Each target still validates
its own rule/DNS result and owns its own quota, queue and accounting. Different
associations, Networks, address families or route actions never share sockets.
Different domain identities resolving to the same IP retain separate reply and
transfer ownership; IP-only multi-target traffic shares normally. The socket
ends when its final active target closes. This does not promise endpoint-independent
mapping or successful ICE through an external VPN/NAT.

SOCKS datagrams with nonzero `FRAG` are unsupported and dropped. This optional
SOCKS reassembly mechanism is distinct from ordinary kernel IP fragmentation;
native UDP sockets allow the latter so valid large datagrams/replies can cross
a smaller-MTU network. See [RFC1928 §7](https://www.rfc-editor.org/rfc/rfc1928.html#section-7).
SOCKS framing reduces the available payload size below the OS datagram limit.
The native wire ceiling is 65507 bytes including its SOCKS header: IPv4 target
headers leave 65497 payload bytes, and IPv6 target headers leave 65485. Domain-name
headers consume a variable amount of space. Requests over that wire ceiling are
dropped, and replies which cannot be represented within it are not truncated.

A server may reply from a transfer port at the already-approved IP. The actual
source port is returned in the SOCKS header, and a later request to that observed
peer can reuse the original upstream socket and egress. Aliases are bounded and
removed with the flow; ambiguous ownership is rejected. An explicit conflicting
IP rule is enforced, and a transfer does not silently change egress. This does
not relax CONNECT-UDP's exact source-port check or admit arbitrary source IPs.

Client replies use the matching non-VPN ingress Network, separately from the
target's egress. Binding only a local address is insufficient to select that
return path on Android. An interface for which Android exposes no usable Network
is rejected explicitly; it must not silently route client replies into the VPN.
CONNECT-UDP's existing TCP return path is an alternative for such clients.

## SOCKS5 BIND

Send command 02 after the same method/authentication handshake. Supply a known
peer host/IP; a zero destination port allows that peer's otherwise unknown source
port. Unspecified, multicast and disallowed peer addresses are rejected. Domain
peers are resolved under the existing rule/DNS/egress policy.

The first successful reply reports the selected Network's real listening address
and ephemeral port. The second reports the actual accepted peer address/port.
Wrong peers are closed without receiving client data. The client can send bounded
early data, and both TCP half-closes remain meaningful. Waiting is limited to
30 seconds; at most eight BIND connections (also limited by relay capacity and
shared session/client quotas) can be live per SOCKS listener. Policy changes,
network invalidation and sharing OFF revoke owned work, including late setup.

This supports active FTP for clients which use SOCKS BIND and tell their FTP server
the returned rendezvous address. The remote server must be able to route to that
address. A phone cannot manufacture a public mapping through carrier-grade NAT or
a VPN provider that disallows incoming connections. HTTP CONNECT does not provide
this reverse-listener operation, and encrypted FTP commands are not intercepted.

## HTTP control-data budgets

HTTP/PAC lines are limited to 64 KiB, retained header text to 256 KiB and header
count to 256. Client limits return 414/431; oversized upstream metadata returns
502 when no response has already started. These are metadata limits, not limits
on TCP tunnel or streamed body size. Chunk lengths use Long-sized streaming
counters; malformed/truncated chunks and trailers do not count as a completed
keep-alive response. Body bytes are not buffered as one chunk-sized allocation.

## Phone DNS message service

The development branch accepts `GET /dns-query?dns=<base64url>` and `POST /dns-query`
with `Content-Type: application/dns-message`. Both the HTTP port and the same SOCKS
TCP port provide these authenticated control routes. SOCKS still uses its normal
05 handshake for proxy traffic; its HTTP control handler does not open a general
HTTP proxy. That control endpoint reports `connect_udp_h1: false`.

The optional capability object is:

```json
{"dns_query":{"path":"/dns-query","get":true,"post":true,"udp_transport":true,"max_query":65535}}
```

This uses the existing cleartext LAN HTTP profile and the standard DNS-message
representation; it is not an HTTPS DoH deployment. `Proxy-Authorization: Basic`
uses the same endpoint credentials. `X-HxmyProxy-Session` is optional; a supplied
stale, empty or duplicate session value is rejected before resolver work.

The phone applies rules to the question name and chooses its own Network under
DIRECT/PROXY, STRICT and explicit DEGRADE. Android's raw resolver supplies the
complete DNS response, including TXT, SRV, SVCB/HTTPS and negative responses; no
conversion through an A/AAAA-only address cache occurs. Explicit DNS-server
targets sent through ordinary CONNECT or UDP forwarding are not replaced.

Queries must contain one QUERY question and fit 65535 bytes. DNS names, compression
pointers, section bounds and EDNS option lengths are validated without decoding
opaque RR data. Standard HTTP metadata budgets also apply, so use POST for large
queries. Successful and negative DNS answers use HTTP 200 and
`application/dns-message`; a locally blocked name receives DNS REFUSED. Transport,
authentication, capacity and malformed-message failures retain explicit HTTP errors.

The default, or `transport=tcp`, preserves the complete answer. `transport=udp`
uses the original EDNS advertised receive budget (512 without EDNS, clamped to
512–65507). If necessary the phone builds a valid TC response containing the
original ID/question and minimal OPT information, including an extended rcode.
It omits whole answer/authority sections and clears AD instead of slicing bytes.

Resolver work is bounded to 16 active operations and one three-second application
budget, including any allowed DEGRADE. The raw API uses NO_RETRY while preserving
platform caching and TC-to-TCP handling. OFF, rule changes and policy generations
cancel owned work and prevent late publication. Already submitted platform/network
work is not described as physically retractable; the app does not stop Android's
shared resolver or another VPN.

## ICMP Echo control

Both proxy TCP ports accept authenticated `POST /icmp-echo?target=<IP-literal>`
with `Content-Type: application/octet-stream`. The body is an ICMP Echo message,
without an IP header: IPv4 type 8/code 0 or IPv6 type 128/code 0, 8–65507 bytes.
Normal HTTP body limits and the optional session check apply. Other ICMP types,
domain targets, self targets, broadcast and multicast are rejected.

```json
{"icmp_echo":{"path":"/icmp-echo","post":true,"max_message":65507,"ipv4":true,"ipv6":true,"reply_id":"original","ipv6_checksum":"recompute","timeout_ms":3000,"max_concurrent":4}}
```

The capability reports the ping-socket families available on that phone. The
implementation uses public `SOCK_DGRAM` ICMP sockets bound to the selected
Android Network. It does not open raw sockets or create a VPN service. IP/CIDR
rules apply to the supplied IP; no domain identity is inferred from an address.
DIRECT without its selected physical egress remains an error. Configured DEGRADE
may choose another network only before sending. A sent request is never replayed
because of silence, and a silent ping does not mark a whole network unhealthy.

HTTP 200 carries an Echo Reply with the original identifier, sequence and data.
The kernel's socket-specific identifier is checked before restoration. The phone
recomputes the IPv4 message checksum. The IPv6 reply's checksum field is zero:
the receiving capture adapter **must recompute it with the original IPv6
pseudo-header** before delivery. This follows the message fields in
[RFC 792](https://www.rfc-editor.org/rfc/rfc792.html) and the IPv6 checksum definition
in [RFC 4443](https://www.rfc-editor.org/rfc/rfc4443.html).

At most four actual Echo operations and sixteen admitted operations (including
queued work) exist process-wide. Each has a three-second application deadline.
Cancellation closes published sockets; blocked setup retains its real permit
until it finishes and closes, including across OFF/ON. Rule/credential/network
changes and OFF prevent late replies. Errors remain explicit: 403 for denied
targets, 429 for capacity, 501 for unavailable ping sockets, 413 for path/message
size rejection, and 502/504 for transport failure/timeout.

The advertised maximum is an endpoint ceiling, not a promise that every path
carries that size. Ordinary kernel IP fragmentation may be used where supported.
The original IP header, TTL, traceroute behavior and arbitrary ICMP errors are
not transported. Echo support is not general raw-IP forwarding.

## Coverage boundary

Forwarding support does not itself make an application send traffic to a proxy.
Tests with explicit clients do not prove browser/default-OS capture or a specific
commercial VPN's behavior. Raw-IP protocols, root-dependent capture and adding
a production VpnService are outside this project's scope. Public inbound mapping
and automatic capture of every application are not implied by BIND or UDP support.
