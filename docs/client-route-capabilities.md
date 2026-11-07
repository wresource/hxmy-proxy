# Client route intent (capabilities extension)

Android 1.49.72 / 312 adds an optional `client_route` object to the existing authenticated `GET /__hxmyproxy/capabilities` response. The capability protocol version stays 1, and existing `client_policy` fields retain their meaning. Older companions can ignore this object.

Relevant fields are shown below; the real response retains the other existing capabilities.

```json
{
  "version": 1,
  "session_id": "00000000-0000-4000-8000-000000000001",
  "client_policy": {"version": 1, "revision": 4, "proxy_local_targets": false},
  "client_route": {
    "version": 1,
    "scope": "source-ip",
    "source_ip": "192.0.2.10",
    "revision": 7,
    "mode": "DIRECT"
  }
}
```

| Field | Meaning |
|---|---|
| `version` | Integer 1 for this extension. |
| `scope` | Literal `source-ip`; this is not a physical device identity. |
| `source_ip` | Canonical numeric address of this connection's actual peer as observed by the phone; IPv4-mapped aliases normalize to IPv4. No hostname lookup or forwarded-header identity. |
| `revision` | Nonnegative signed 64-bit integer from the applied client-mode snapshot. Changes when the set of direct clients changes, including ABA changes. A change affecting another client may increase it without changing this peer's mode. |
| `mode` | `DIRECT`: all this source IP's requests use the phone's configured physical Direct egress. `RULES`: normal phone rules and egress policy apply. |

## Authority and lifetime

The phone reads mode and revision from one immutable runtime snapshot. Saved settings that have not applied are not published. Domain rule rebuilds do not change this revision. The revision is independent of `client_policy.revision` and is meaningful only in the capability session. A new process can reset it; a new listener session in the same process can retain it.

The same authentication/admission checks apply as to the existing capability endpoint, on both HTTP and the HTTP control surface of the SOCKS port. Authentication-enabled installations require the configured credentials. No-auth installations are not implicitly changed into authenticated ones. A server without a runtime rule engine omits the extension instead of inventing an authoritative mode.

No list of other clients or their settings is exposed. The control response retains `Cache-Control: no-store`. NAT is allowed: the phone's `source_ip` need not equal the companion's local `getsockname()` address.

## Companion behavior

A companion must bind accepted intent to the permitted phone endpoint/protocol, credentials context, session, the source/network context of its control connection, and the phone-reported source IP. Retire old sessions, reject late responses and stale/conflicting revisions, and invalidate/re-read after context changes. A revision increase with unchanged mode for this client is not permission to disrupt unrelated flows.

Only a fresh, explicit, valid `DIRECT` intent can select a phone-mediated direct admission policy. It does not authorize computer-native Internet fallback, bypass authentication, override master OFF, or override local-target policy. Missing, unsupported, invalid, expired or unknown intent keeps the companion's existing normal protection. A failed Google/exit check cannot itself grant direct intent.

Candidate endpoint reachability and Google/overseas exit eligibility are separate questions. Otherwise a legitimate direct-mode phone may never be selected. Candidate handling must still respect the user's permitted endpoints/discovery scope and credentials; this extension is not a license to trust arbitrary LAN responses.

The phone continues to enforce the current client mode for every ordinary proxy request and disconnects only the affected source IP when that setting changes. This capability extension does not provide an instantaneous push channel: companions must manage proof age, context invalidation and explicit mode transitions. HTTP, SOCKS5, CONNECT-UDP, raw DNS, Echo and BIND framing is unchanged.
