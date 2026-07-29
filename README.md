# hxmy proxy

> **English** · [简体中文](README.zh-CN.md)

**A local proxy gateway for Android.** Run HTTP / HTTPS CONNECT, SOCKS5 and PAC proxies on your
phone, and let other devices on the same network — a laptop, a tablet, another phone — go online
through your phone's current uplink, including any system/Google VPN it happens to be on.

It is **not a traditional VPN app**: it builds no `VpnService` and no tunnel of its own. Think of
it as turning your phone into a network relay — point a device's proxy at the phone and you're done.

## Two modes (works with or without a VPN)

- **Phone on a VPN → shared egress.** Other devices borrow the phone's VPN tunnel — connect once,
  share to many, no per-device VPN setup.
- **Phone with no VPN → filtering gateway.** The uplink is a plain network, but downstream devices
  get a free rule engine: ad/tracker blocking, 65 built-in app/service rule sets, whitelist direct-connect,
  per-domain routing and per-connection monitoring. A root-free, hardware-free whole-home ad blocker
  that works at the **proxy layer** (HTTP CONNECT / SOCKS) — it blocks the real connection target,
  so encrypted DNS (DoH/DoT) can't slip past it. That's the fundamental difference from DNS-layer
  blockers like Pi-hole.

## Highlights

- **Separate proxy & direct egress pickers** — route proxied traffic via Auto / VPN / Wi-Fi / Cellular / Ethernet-USB, and give DIRECT (bypass) traffic its own path (Auto: Ethernet/USB → Wi-Fi → Cellular, or pinned). Per-socket bound and **fail-closed** — DIRECT never leaks to the VPN. Cellular is selectable even while on Wi-Fi (permission-free SIM check).
- **Rule engine** — Block / Allow modules; wildcard domains, IP literals and **CIDR**; per-host 3-state override (proxy / direct / block); **65 built-in groups** (incl. Apple / App Store direct) with one-tap category & master switches.
- **Rule scope in three tiers** — how you write it decides how deep it reaches: `apple.com` (all levels) · `*.apple.com` (one level) · `=apple.com` (exact). Conflicts resolve **most-specific-wins**, not last-write-wins, so a broad rule added later can never silently erase a narrow one you added months ago.
- **Ad-block false-positive rescue** — public blocklists are auto-aggregated and sweep up vendors' own content domains (mini-program assets, video CDNs); a curated, individually-audited allowlist un-blocks them, so "the app broke and you can't tell why" stops happening. Every entry is checked by tests against the shipped rule data.
- **Toggle rules without deleting** — every block/allow entry has its own on/off switch and an Active/Off badge; disabled entries drop out of matching (they do *not* flip to the opposite action) and sort below the active ones.
- **Survives updates and reboots** — if sharing was on, it comes back by itself after an app update or a device restart (and only then — stopping it yourself keeps it off), so clients don't hit a dead proxy port.
- **Client link latency** — measures the *client → phone* hop (the one that degrades first when the phone acts as a gateway, and the one nothing used to measure), shown as p50 with p95 alongside and colour-coded. Probed every 10 s, and only while a client is actually connected.
- **Traffic history** — cross-session usage by period (today / week / month / year / all time) **and by egress**: VPN tunnel, Wi-Fi, cellular, Ethernet-USB. Cellular is broken out on its own — it's the one that costs money. Kept per day on-device; cleared only when you say so, never automatically.
- **Settings backup** — export/import your whole configuration as JSON, so nothing is lost on reinstall or a new phone (system cloud backup is deliberately off — see Privacy).
- **Diagnostic log you can actually read** — structured `evt=… k=v` events tagged by subsystem, plus a separate `key.log` ring for lifecycle / admission / rejection / crash events so they're never rotated away by high-frequency noise. One master switch in Settings.
- **Self-diagnosing when clients can't connect** — a 60 s heartbeat (ports / accepts / admission / RSSI / link), accept-success logging, a loopback+LAN self-probe pair and client-probe loss events make an exported log tell you *which layer* failed; a one-tap **Refresh service** button fully resets the app-side stack, probes recent clients (refreshing on-path ARP entries) and, if they still don't answer, points you at the system Wi-Fi panel — the one thing apps can't reset themselves.
- **Protection tab** — session block count, blocked-host detail by hit count, one-tap undo for mis-blocks.
- **DNS resilience** — dual-path system resolve + **DoH backup** (8.8.8.8 / 1.1.1.1) when the network's own DNS misbehaves.
- **Fail-closed admission** — with no network selected, no connections are accepted.
- **NIO non-blocking relay** — 2 selectors replace 128 threads.
- **100% local** — no account, no cloud, no tracking SDK.

## The five screens

| Screen | What it does |
|---|---|
| **Dashboard** | Sharing \| Protection dual-status tiles (Stopped / Not ready / Sharing); live speed + **client link latency**; proxy address (HTTP/PAC + copy + QR); inbound interfaces (ingress); **proxy egress** and **direct egress** pickers. |
| **Protection** | Session block total, ad-block toggle, blocked-host detail by hit count, 3-state per-host override — works with or without a VPN. |
| **Monitor** | Diagnostics grid, latency probes, client list, top domains (full list on its own page — tap any domain to add it straight to your block/allow rules, no typing), **traffic history** (period × egress), history & error logs (filterable by level). |
| **Rules** | 🛡️ Block / 🌐 Allow modules — quick block (domain/IP/CIDR, master toggle), whitelist, per-entry on/off switches, app/service rule sets with per-category & master switches. **65 built-in groups** (incl. Apple / App Store). |
| **Settings** | Language, theme, performance presets, ports, protocol toggles, backup DNS (DoH), auth, diagnostics. |

## Privacy

Runs entirely on-device — no account, no login, no cloud, no analytics/tracking SDK. It only relays
traffic between local devices and the phone's network; settings, rules and diagnostic logs never
leave the device.

This is enforced, not just promised: logs live in `noBackupFilesDir` and **system cloud backup is
disabled** (`allowBackup=false`), so nothing — not even via Android Auto Backup — is uploaded
anywhere. Because that also removes cloud restore, **Settings → Settings backup** lets you export
and re-import your configuration yourself. Release builds additionally strip verbose/debug/info
logcat output, so no visited domain or client IP is written to the system log.

## Requirements

Android 10+ (minSdk 29 / targetSdk 37). Namespace `com.mzstd.hxmyproxy`, publisher **mzstd**.
Latest release: **1.21.0**.

## More

- Full overview, architecture and version history: **[简体中文 README](README.zh-CN.md)**
- Technical deep-dives: [`version-md/`](./version-md/)
