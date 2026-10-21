package com.mzstd.hxmyproxy.core.rules

import android.icu.text.IDNA
import java.net.URI
import java.util.Locale

/** Shared validation for adding, editing and importing rules. Never performs DNS. */
object RuleInput {
    data class Parsed(val host: String, val scope: RuleScope?, val fromUrl: Boolean = false) {
        val value: String get() = scope?.let { RuleScope.format(it, host) } ?: host
        val isIp: Boolean get() = scope == null
    }

    fun normalize(input: String): String? = parse(input)?.value

    /** The UI's selected scope remains authoritative, including for digit-leading domain names. */
    fun forScope(input: String, scope: RuleScope): Parsed? = parse(input)?.let {
        if (it.isIp) it else it.copy(scope = scope)
    }

    fun parse(input: String): Parsed? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 8192) return null
        val (rangeScope, rangeText) = RuleScope.parse(text)
        if (IpRange.hasRangeSyntax(rangeText)) {
            if (rangeScope != RuleScope.SUFFIX) return null
            return IpRange.parse(rangeText)?.let { Parsed(it.value, null) }
        }
        // IDNA may discard format characters. Reject them before conversion, rather than
        // silently changing a pasted rule to a different target. Edge whitespace stays valid.
        if (text.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
        val (scope, raw) = RuleScope.parse(text)
        if (raw.isEmpty() || raw.startsWith('=') || raw.startsWith('*')) return null
        val hasScope = text.startsWith('=') || text.startsWith("*.")
        fun ipResult(ip: String): Parsed? = if (hasScope) null else Parsed(ip, null)

        numericAddress(raw)?.let { return ipResult(it.first) }
        // A bare numeric address followed by '/' is CIDR, not a URL path. A bad
        // prefix must not silently become a single-host rule.
        if ('/' in raw && !raw.contains("://") && !raw.startsWith("//")) {
            val numeric = numericAddress(raw.substringBefore('/'))
            if (numeric != null) {
                val prefixText = raw.substringAfter('/')
                if (prefixText.isEmpty() || prefixText.any { it !in '0'..'9' }) return null
                val prefix = prefixText.toIntOrNull()?.takeIf { it in 0..numeric.second } ?: return null
                return ipResult("${numeric.first}/$prefix")
            }
        }

        val url = raw.contains("://") || raw.startsWith("//") || raw.any { it in "/:?#" }
        val host = if (url) {
            val uri = runCatching { URI(if (raw.contains("://") || raw.startsWith("//")) raw else "//$raw") }.getOrNull() ?: return null
            if (uri.scheme != null && uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https", "ws", "wss")) return null
            val authority = uri.rawAuthority ?: return null
            if (authority.isEmpty() || authority.any { it in "@%\\" }) return null
            val address: String
            val port: String?
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                if (end < 0) return null
                address = authority.substring(0, end + 1)
                val tail = authority.substring(end + 1)
                if (tail.isNotEmpty() && !tail.startsWith(':')) return null
                port = tail.takeIf { it.isNotEmpty() }?.substring(1)
                if (numericAddress(address)?.second !in listOf(32, 128)) return null
            } else {
                if (authority.count { it == ':' } > 1) return null
                address = authority.substringBefore(':')
                port = if (':' in authority) authority.substringAfter(':') else null
            }
            if (port != null && (port.isEmpty() || port.any { it !in '0'..'9' } || port.toIntOrNull()?.let { it in 1..65535 } != true)) return null
            address
        } else raw
        numericAddress(host)?.let { return ipResult(it.first)?.copy(fromUrl = url) }
        // Numeric-looking malformed IPv4 must not fall through to the domain table.
        if (host.all { it in "0123456789." }) return null
        val domain = canonicalDomain(host) ?: return null
        // A URL host that would normalize into range syntax must not silently become an IP rule.
        if (IpRange.hasRangeSyntax(domain)) return null
        numericAddress(domain)?.let { return ipResult(it.first)?.copy(fromUrl = url) }
        if (domain.all { it in "0123456789." }) return null
        return Parsed(domain, scope, fromUrl = url)
    }

    /** UTS #46, non-transitional: e.g. faß.de must never become the different host fass.de. */
    internal fun canonicalDomain(input: String): String? {
        var host = input.lowercase(Locale.ROOT)
        if (host.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
        if (host.any { it.code > 127 } || host.split('.').any { it.startsWith("xn--") }) {
            val info = IDNA.Info()
            val ascii = StringBuilder()
            IDNA.getUTS46Instance(IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or
                IDNA.CHECK_CONTEXTO or IDNA.NONTRANSITIONAL_TO_ASCII).nameToASCII(host, ascii, info)
            if (info.hasErrors()) return null
            host = ascii.toString()
        }
        // Exactly one trailing root dot is legal; repeated dots are malformed.
        host = host.removeSuffix(".")
        if (host.length !in 1..253) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { label ->
                label.length !in 1..63 || label.first() == '-' || label.last() == '-' ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }) return null
        return host
    }

    /** Pure numeric parsing keeps JVM and Android validation identical without a DNS-capable API. */
    internal fun numericAddress(input: String): Pair<String, Int>? {
        val bracketed = input.startsWith('[') && input.endsWith(']')
        val host = (if (bracketed) input.substring(1, input.length - 1) else input).lowercase(Locale.ROOT)
        fun ipv4(value: String): List<Int>? {
            val octets = value.split('.')
            if (octets.size != 4) return null
            return octets.map { part ->
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                // Leading zeroes are interpreted differently by URL/client parsers.
                // Require an unambiguous literal instead of silently targeting another IP.
                if (part.length > 1 && part.startsWith('0')) return null
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            }
        }
        if (':' !in host) {
            if (bracketed) return null
            return ipv4(host)?.let { it.joinToString(".") to 32 }
        }
        if (host.any { it !in "0123456789abcdef:." }) return null
        val halves = host.split("::")
        if (halves.size > 2) return null
        fun words(side: String, allowIpv4: Boolean): List<Int>? {
            if (side.isEmpty()) return emptyList()
            val parts = side.split(':')
            val result = mutableListOf<Int>()
            parts.forEachIndexed { index, part ->
                if ('.' in part) {
                    if (!allowIpv4 || index != parts.lastIndex) return null
                    val octets = ipv4(part) ?: return null
                    result += (octets[0] shl 8) or octets[1]
                    result += (octets[2] shl 8) or octets[3]
                } else {
                    if (part.length !in 1..4) return null
                    result += part.toIntOrNull(16) ?: return null
                }
            }
            return result
        }
        val left = words(halves[0], halves.size == 1) ?: return null
        val right = if (halves.size == 2) words(halves[1], true) ?: return null else emptyList()
        val count = left.size + right.size
        if (if (halves.size == 1) count != 8 else count >= 8) return null
        val expanded = left + List(if (halves.size == 2) 8 - count else 0) { 0 } + right
        // Android's numeric parser represents IPv4-mapped IPv6 as IPv4 as well.
        if (expanded.take(5).all { it == 0 } && expanded[5] == 0xffff) {
            return listOf(expanded[6] shr 8, expanded[6] and 255, expanded[7] shr 8, expanded[7] and 255).joinToString(".") to 32
        }
        return host to 128
    }

    data class Lines(val rules: List<String>, val invalidLines: List<Int>)
    fun equivalentKey(parsed: Parsed): String = if (parsed.isIp) {
        IpRange.ofRule(parsed.host)?.let { "ip:${it.bits}:${it.startKey}:${it.endKey}" } ?: parsed.value
    } else parsed.value
    fun parseLines(text: String): Lines {
        val rules = linkedMapOf<String, String>()
        val invalid = mutableListOf<Int>()
        text.lineSequence().forEachIndexed { index, line ->
            val value = (if (index == 0) line.removePrefix("\uFEFF") else line).trim()
            if (value.isNotEmpty() && !value.startsWith('#')) {
                val rule = parse(value)
                if (rule == null) invalid += index + 1 else rules.putIfAbsent(equivalentKey(rule), rule.value)
            }
        }
        return Lines(rules.values.toList(), invalid)
    }
}
