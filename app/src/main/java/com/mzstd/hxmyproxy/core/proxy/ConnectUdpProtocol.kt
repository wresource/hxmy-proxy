package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.rules.RuleInput
import java.net.URLDecoder
import java.net.URI
import java.util.Locale

/** HTTP/1.1 CONNECT-UDP profile. Origin-form control requests never become self-proxy traffic. */
internal object ConnectUdpProtocol {
    const val CAPABILITIES_PATH = "/__hxmyproxy/capabilities"
    const val UDP_PREFIX = "/.well-known/masque/udp/"
    const val SESSION_HEADER = "X-HxmyProxy-Session"
    const val MAX_PAYLOAD = 65_507
    const val PROTOCOL_PAYLOAD_CEILING = 65_527
    data class Target(val host: String, val port: Int)

    fun target(path: String): Target? {
        if (!path.startsWith(UDP_PREFIX) || path.length > 4096 || '?' in path || '#' in path) return null
        val parts = path.removePrefix(UDP_PREFIX).split('/')
        if (parts.size != 3 || parts[2].isNotEmpty() || parts[0].isEmpty()) return null
        val raw = parts[0]
        if (raw.any { it == '+' || it.code > 127 } || !validEscapes(raw)) return null
        val host = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        // A target is a host, never a rule, URL, CIDR, zone identifier or nested escape.
        if (host.isEmpty() || host.any { it.isWhitespace() || it.isISOControl() || it in "/?#@%\\=*" }) return null
        if (host.contains('\uFFFD')) return null
        val parsed = RuleInput.parse(host) ?: return null
        if (parsed.fromUrl || parsed.host.contains('/') || (parsed.isIp && '-' in host)) return null
        val portText = parts[1]
        if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Target(parsed.host, port)
    }

    private fun validEscapes(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                if (i + 2 >= value.length || value[i + 1].digitToIntOrNull(16) == null || value[i + 2].digitToIntOrNull(16) == null) return false
                i += 3
            } else i++
        }
        return true
    }

    private fun values(headers: List<Pair<String, String>>, name: String) =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second.trim() }

    fun session(headers: List<Pair<String, String>>): String? = values(headers, SESSION_HEADER).singleOrNull()
    fun hasSessionHeader(headers: List<Pair<String, String>>): Boolean = headers.any { it.first.equals(SESSION_HEADER, ignoreCase = true) }

    fun validControlGet(method: String, version: String, headers: List<Pair<String, String>>): Boolean {
        if (method != "GET" || !validControlHost(version,headers)) return false
        if (values(headers, "transfer-encoding").isNotEmpty()) return false
        val lengths = values(headers, "content-length")
        if (lengths.size > 1 || lengths.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || it.toLongOrNull() != 0L }) return false
        return true
    }

    fun validControlHost(version:String,headers:List<Pair<String,String>>):Boolean {
        if(version!="HTTP/1.1")return false
        val host = values(headers, "host").singleOrNull() ?: return false
        if (host.isEmpty() || host.any { it.isWhitespace() || it.isISOControl() || it in "/\\?#@" }) return false
        val origin = runCatching { URI("http://$host/") }.getOrNull() ?: return false
        if (origin.host == null || (origin.port != -1 && origin.port !in 1..65535)) return false
        return true
    }

    fun validUpgrade(method: String, version: String, headers: List<Pair<String, String>>): Boolean {
        if (!validControlGet(method, version, headers)) return false
        val connection = values(headers, "connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
        if ("upgrade" !in connection || values(headers, "upgrade").singleOrNull()?.lowercase(Locale.ROOT) != "connect-udp") return false
        return structuredTrue(values(headers, "capsule-protocol").singleOrNull() ?: return false)
    }

    /** Boolean Structured Field Item, with syntactically valid unknown parameters ignored. */
    private fun structuredTrue(text: String): Boolean {
        if (!text.startsWith("?1")) return false
        var at = 2
        while (at < text.length) {
            if (text[at++] != ';') return false
            while (at < text.length && text[at] == ' ') at++
            if (at >= text.length || !(text[at] in 'a'..'z' || text[at] == '*')) return false
            at++
            while (at < text.length && (text[at] in 'a'..'z' || text[at].isDigit() || text[at] in "_.*-")) at++
            if (at < text.length && text[at] == '=') {
                at++
                if (at >= text.length) return false
                if (text[at] == '"') {
                    at++
                    var closed = false
                    while (at < text.length) {
                        val c = text[at++]
                        if (c == '"') { closed = true; break }
                        if (c == '\\') {
                            if (at >= text.length || text[at++] !in "\\\"") return false
                        } else if (c.code !in 32..126) return false
                    }
                    if (!closed) return false
                } else {
                    val start = at
                    while (at < text.length && text[at] != ';') at++
                    val value = text.substring(start, at)
                    if (!BARE_ITEM.matches(value)) return false
                }
            }
        }
        return true
    }

    private val BARE_ITEM = Regex("(?:\\?[01]|-?[0-9]{1,15}|-?[0-9]{1,12}\\.[0-9]{1,3}|:[A-Za-z0-9+/=]*:|[A-Za-z*][A-Za-z0-9!#$%&'*+.^_`|~:/-]*)")

    fun capabilities(session: String, idleMs: Long, socks: Socks5Capabilities? = null, dns:Boolean=false,connectUdpH1:Boolean=true,
                     echo:EchoMessageQuery?=null, proxyLocalTargets:Boolean?=null, policyRevision:Long=0): String =
        "{\"version\":1,\"session_id\":\"$session\",\"connect_udp_h1\":$connectUdpH1," +
            "\"connect_udp_no_fragment\":$connectUdpH1,"+
            "\"max_udp_payload\":$MAX_PAYLOAD,\"idle_timeout_ms\":$idleMs,\"socks5\":" +
            (socks?.let { "{\"port\":${it.port},\"connect\":true,\"udp_associate\":${it.udpAssociate},\"bind\":${it.bind}," +
                "\"max_udp_wire\":${SocksUdpCodec.MAX_WIRE_BYTES},\"bind_peer_required\":true,\"bind_requires_reachable_address\":true}" } ?: "null") +
            ",\"dns_query\":"+(if(dns) "{\"path\":\"/dns-query\",\"get\":true,\"post\":true,\"udp_transport\":true,\"max_query\":65535}" else "null")+
            ",\"icmp_echo\":"+(if(echo!=null&&(echo.ipv4||echo.ipv6)) "{\"path\":\"/icmp-echo\",\"post\":true,\"max_message\":65507,"+
                "\"ipv4\":${echo.ipv4},\"ipv6\":${echo.ipv6},\"reply_id\":\"original\",\"ipv6_checksum\":\"recompute\",\"timeout_ms\":3000,\"max_concurrent\":4}" else "null")+
            (proxyLocalTargets?.let { ",\"client_policy\":{\"version\":1,\"revision\":$policyRevision,\"proxy_local_targets\":$it}" } ?: "")+"}"
}
