package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol

/**
 * 由可用入口生成 PAC（`FindProxyForURL`）。
 *
 * **iOS 友好排序**（grounded：iOS CFNetwork 的 PAC 解析器只认 `PROXY`/`DIRECT`/`SOCKS`，
 * 忽略 `HTTPS`/`HTTP`、不保证字面 `SOCKS5`）：
 * - `PROXY`(HTTP，iOS+桌面都最稳) 优先于 SOCKS；
 * - SOCKS 同时给 `SOCKS5`(桌面)与裸 `SOCKS`(iOS 把裸 SOCKS 当 SOCKS5)；
 * - 末尾恒 `DIRECT`。
 *
 * 曾经还在末尾附一条 `.local` 便利名兜底，随 mDNS 一并删除——
 * 那个名字从未被通告过 A 记录，客户端根本解析不到。纯函数，便于单测。
 */
object PacGenerator {
    fun generate(entries: List<ProxyEntry>, proxyLocalTargets: Boolean = false): String {
        val socks = entries.filter { it.protocol == ProxyProtocol.SOCKS5 }
        val http = entries.filter { it.protocol == ProxyProtocol.HTTP }
        val tokens = ArrayList<String>()
        // ① HTTP 代理(具体 IP)——iOS 与桌面都稳认 PROXY，放最前。
        http.forEach { tokens.add("PROXY ${it.ipEndpoint}") }
        // ② SOCKS(具体 IP)——桌面用 SOCKS5；iOS 的 CFNetwork 把裸 SOCKS 当 SOCKS5，两条都给。
        socks.forEach { tokens.add("SOCKS5 ${it.ipEndpoint}") }
        socks.forEach { tokens.add("SOCKS ${it.ipEndpoint}") }
        // ③ 兜底直连。
        tokens.add("DIRECT")
        if (entries.isEmpty()) return "function FindProxyForURL(url, host) { return \"DIRECT\"; }\n"
        return localTargetFunctions + "\nfunction FindProxyForURL(url, host) {\n" +
            "  var kind = hxmyLocalKind(host);\n" +
            "  if (kind === 1 || (kind === 2 && !$proxyLocalTargets)) return \"DIRECT\";\n" +
            "  return \"${tokens.joinToString("; ")}\";\n}\n"
    }

    // ES5 only: works in platform PAC engines without DNS lookups or isInNetEx.
    // 1 = client loopback/link-local, never a phone destination; 2 = private LAN.
    // These are destination checks, not the removed/unresolvable .local proxy endpoint.
    private val localTargetFunctions = """
function hxmyV4(host) {
  if (!/^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+${'$'}/.test(host)) return null;
  var parts = host.split('.'), bytes = [], i;
  for (i = 0; i < 4; i++) {
    if (parts[i].length > 1 && parts[i].charAt(0) === '0') return null;
    var n = Number(parts[i]); if (n > 255) return null; bytes.push(n);
  }
  return bytes;
}
function hxmyV4Kind(a) {
  if (a[0] === 127 || (a[0] === 169 && a[1] === 254)) return 1;
  return a[0] === 10 || (a[0] === 172 && a[1] >= 16 && a[1] <= 31) ||
    (a[0] === 192 && a[1] === 168) || (a[0] === 100 && a[1] >= 64 && a[1] <= 127) ? 2 : 0;
}
function hxmyV6(host) {
  if (host.indexOf('.') !== -1) {
    var split = host.lastIndexOf(':'), v4 = hxmyV4(host.substring(split + 1));
    if (!v4) return null;
    host = host.substring(0, split + 1) + (v4[0] * 256 + v4[1]).toString(16) + ':' + (v4[2] * 256 + v4[3]).toString(16);
  }
  var halves = host.split('::'); if (halves.length > 2) return null;
  var left = halves[0] ? halves[0].split(':') : [];
  var right = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  var count = left.length + right.length, words = [], i;
  if ((halves.length === 1 && count !== 8) || (halves.length === 2 && count >= 8)) return null;
  for (i = 0; i < left.length; i++) {
    if (!/^[0-9a-f]{1,4}${'$'}/.test(left[i])) return null;
    words.push(parseInt(left[i], 16));
  }
  if (halves.length === 2) while (words.length < 8 - right.length) words.push(0);
  for (i = 0; i < right.length; i++) {
    if (!/^[0-9a-f]{1,4}${'$'}/.test(right[i])) return null;
    words.push(parseInt(right[i], 16));
  }
  return words;
}
function hxmyLocalKind(host) {
  if (typeof host !== 'string' || !host || /[\s\/@]/.test(host)) return 0;
  host = host.toLowerCase();
  if (host.charAt(0) === '[' && host.charAt(host.length - 1) === ']') {
    host = host.substring(1, host.length - 1); if (host.indexOf(':') === -1) return 0;
  }
  if (host.indexOf('[') !== -1 || host.indexOf(']') !== -1) return 0;
  if (host.indexOf(':') !== -1) {
    var zone = host.indexOf('%');
    if (zone !== -1) {
      if (!/^[a-z0-9_.-]+${'$'}/.test(host.substring(zone + 1))) return 0;
      host = host.substring(0, zone);
    }
    var w = hxmyV6(host); if (!w) return 0;
    if ((w[0] & 65472) === 65152) return 1;
    if (zone !== -1) return 0;
    if (w[0] === 0 && w[1] === 0 && w[2] === 0 && w[3] === 0 && w[4] === 0) {
      if (w[5] === 0 && w[6] === 0 && w[7] === 1) return 1;
      if (w[5] === 65535) return hxmyV4Kind([w[6] >> 8, w[6] & 255, w[7] >> 8, w[7] & 255]);
    }
    return (w[0] & 65024) === 64512 || (w[0] & 65472) === 65216 ? 2 : 0;
  }
  if (host.indexOf('%') !== -1) return 0;
  if (host.charAt(host.length - 1) === '.') host = host.substring(0, host.length - 1);
  if (!host || host.charAt(host.length - 1) === '.' || host.indexOf('..') !== -1) return 0;
  var a = hxmyV4(host); if (a) return hxmyV4Kind(a);
  if (host.length > 253) return 0;
  var labels = host.split('.');
  for (var i = 0; i < labels.length; i++) {
    if (labels[i].length > 63 || !/^[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?${'$'}/.test(labels[i])) return 0;
  }
  if (host === 'localhost' || /\.localhost${'$'}/.test(host)) return 1;
  if (host === 'home.arpa' || /\.(local|home\.arpa)${'$'}/.test(host)) return 2;
  return host.indexOf('.') === -1 && !/^([0-9]+|0x[0-9a-f]+)${'$'}/.test(host) ? 2 : 0;
}
""".trimIndent()
}
