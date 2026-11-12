# Optional proxy failure diagnostics

Introduced in Android 1.5.0 (versionCode 315). This is an additive diagnostic contract agreed with the optional Mac companion. It does not change routing, admission, authentication, health, retries, HTTP status, existing error bodies, `Retry-After`, or `X-Proxy-Rule`.

When hxmyproxy itself generates a failure response from a known `ProxyException`, it may include:

| Header | Meaning |
|---|---|
| `X-Hxmyproxy-Error` | Existing stable `ProxyError.code`: e.g. `dns`, `dns-busy`, `timeout`, `unreachable`, `refused`, `sidelined`, `direct-egress-unavailable`, `dns-local-address`, `local-network-disabled`, `denied`, `vpn`, `perm`, `port`, `limit`, `other`. |
| `X-Hxmyproxy-Stage` | The exception's existing stage, only when its complete value matches `[a-z0-9][a-z0-9_.-]{0,63}`. Null, empty, overlong, non-ASCII or otherwise invalid stages are omitted, never truncated or guessed. |

Values are single bounded ASCII tokens. They carry no host, credentials, exception message, or arbitrary body text. Each header is emitted at most once. A missing field means unavailable metadata, not a successful request or a particular inferred cause.

Only local failure serialization adds the fields. Successful CONNECT responses and subsequent tunnel bytes, UDP upgrades, successful API responses, and forwarded origin responses are unchanged. Plain HTTP and HEAD local failures can include the fields; HEAD remains bodyless. Authentication failures, malformed requests, and direct rule rejections without a `ProxyException` do not invent upstream failure metadata; existing `Proxy-Authenticate` and `X-Proxy-Rule` remain their own evidence.

Consumers must tolerate old peers with neither field. The Mac companion reads only its existing bounded non-200 CONNECT response header block. It accepts each field only if unique, not folded, and fully matching the same token grammar; duplicate or invalid metadata is discarded independently of the original status. It does not read or wait for an error body to obtain diagnostics, and never consumes successful tunnel payload as headers. Accepted fields may be retained in a downstream error response and diagnostics but must not select routes, bypass a refusal, change qualification requirements, or alter retry/health decisions.

An HTTP status alone is insufficient attribution: 403 may be a rule or address-policy refusal; 503 may be local DNS capacity, missing DIRECT egress, or deliberate egress isolation; 502/504 alone do not establish DNS or VPN responsibility. The fields describe only facts already known at the phone's failure boundary.
