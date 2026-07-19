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
  get a free rule engine: ad/tracker blocking (64 built-in lists), whitelist direct-connect,
  per-domain routing and per-connection monitoring. A root-free, hardware-free whole-home ad blocker
  that works at the **proxy layer** (HTTP CONNECT / SOCKS) — it blocks the real connection target,
  so encrypted DNS (DoH/DoT) can't slip past it. That's the fundamental difference from DNS-layer
  blockers like Pi-hole.

## Highlights

- **Egress network picker** — send proxied traffic out via Auto / VPN / Wi-Fi / Cellular / Ethernet-USB (per-socket bound).
- **Rule engine** — Block / Allow modules; wildcard domains, IP literals and **CIDR**; per-host 3-state override (proxy / direct / block).
- **Protection tab** — session block count, blocked-host detail by hit count, one-tap undo for mis-blocks.
- **DNS resilience** — dual-path system resolve + **DoH backup** (8.8.8.8 / 1.1.1.1) when the network's own DNS misbehaves.
- **Fail-closed admission** — with no network selected, no connections are accepted.
- **NIO non-blocking relay** — 2 selectors replace 128 threads.
- **100% local** — no account, no cloud, no tracking SDK.

## The five screens

| Screen | What it does |
|---|---|
| **Dashboard** | Sharing \| Protection dual-status tiles (Stopped / Not ready / Sharing); entry config (HTTP/PAC address + copy + QR); shareable interfaces (ingress); egress network picker. |
| **Protection** | Session block total, ad-block toggle, blocked-host detail by hit count, 3-state per-host override — works with or without a VPN. |
| **Monitor** | Diagnostics grid, latency probes, client list, top domains, history & error logs. |
| **Rules** | 🛡️ Block / 🌐 Allow modules — quick block (domain/IP/CIDR), whitelist, app/service rule sets. 64 built-in groups, 5437 domains. |
| **Settings** | Language, theme, performance presets, ports, protocol toggles, backup DNS (DoH), auth, diagnostics. |

## Privacy

Runs entirely on-device — no account, no login, no cloud, no analytics/tracking SDK. It only relays
traffic between local devices and the phone's network; settings, rules and diagnostic logs never
leave the device.

## Requirements

Android 10+ (minSdk 29 / targetSdk 37). Namespace `com.mzstd.hxmyproxy`, publisher **mzstd**.
Latest release: **1.9.0**.

## More

- Full overview, architecture and version history: **[简体中文 README](README.zh-CN.md)**
- Technical deep-dives: [`version-md/`](./version-md/)
