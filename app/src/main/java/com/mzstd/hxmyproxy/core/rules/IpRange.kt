package com.mzstd.hxmyproxy.core.rules

import java.math.BigInteger

/** Numeric-only address intervals. Never resolves a domain or enumerates individual addresses. */
data class IpRange(val first: BigInteger, val last: BigInteger, val bits: Int) {
    init { require(bits == 32 || bits == 128); require(first.signum() >= 0 && first <= last && last.bitLength() <= bits) }

    val value: String get() = "${address(first, bits)}-${address(last, bits)}"
    val addressCount: BigInteger get() = last.subtract(first).add(BigInteger.ONE)
    fun contains(other: IpRange): Boolean = bits == other.bits && first <= other.first && last >= other.last
    fun overlaps(other: IpRange): Boolean = bits == other.bits && first <= other.last && last >= other.first
    val startKey: String get() = first.toString(16).padStart(32, '0')
    val endKey: String get() = last.toString(16).padStart(32, '0')

    /** At most 2 * bits blocks, even for the complete IPv6 space. */
    fun cidrs(): List<String> = buildList {
        var cursor = first
        while (cursor <= last) {
            val alignment = if (cursor.signum() == 0) bits else cursor.lowestSetBit
            val remaining = last.subtract(cursor).add(BigInteger.ONE).bitLength() - 1
            val power = minOf(alignment, remaining)
            add("${address(cursor, bits)}/${bits - power}")
            cursor = cursor.add(BigInteger.ONE.shiftLeft(power))
        }
    }

    companion object {
        private val space = Regex("[\\t ]+")
        private val v4Candidate = Regex("[0-9.]+")
        private fun candidate(value: String) = ':' in value || v4Candidate.matches(value)

        /** Distinguish malformed numeric ranges from legitimate hyphenated domain names. */
        fun hasRangeSyntax(input: String): Boolean {
            val value = input.trim()
            if ('\n' in value || '\r' in value) return false
            val parts = if ('-' in value) value.split('-').map(String::trim) else value.split(space)
            return parts.size == 2 && parts.all { candidate(it) }
        }

        fun parse(input: String): IpRange? {
            val value = input.trim()
            if (!hasRangeSyntax(value)) return null
            val parts = if ('-' in value) value.split('-').map(String::trim) else value.split(space)
            val a = numeric(parts[0]) ?: return null
            val b = numeric(parts[1]) ?: return null
            if (a.second != b.second || a.first > b.first) return null
            return IpRange(a.first, b.first, a.second)
        }

        /** Also accepts a single IP or CIDR, for coverage/conflict queries. */
        fun ofRule(input: String): IpRange? {
            if (hasRangeSyntax(input)) return parse(input)
            val parts = input.trim().split('/')
            if (parts.size !in 1..2) return null
            val (number, bits) = numeric(parts[0]) ?: return null
            val prefix = if (parts.size == 1) bits else parts[1].takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
                ?.toIntOrNull()?.takeIf { it in 0..bits } ?: return null
            val hostBits = bits - prefix
            val start = number.shiftRight(hostBits).shiftLeft(hostBits)
            return IpRange(start, start.add(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)), bits)
        }

        private fun numeric(text: String): Pair<BigInteger, Int>? {
            val parsed = RuleInput.numericAddress(text) ?: return null
            val host = parsed.first
            if (parsed.second == 32) {
                return host.split('.').fold(BigInteger.ZERO) { n, part -> n.shiftLeft(8).add(BigInteger(part)) } to 32
            }
            val halves = host.split("::")
            fun words(side: String): List<Int> = if (side.isEmpty()) emptyList() else side.split(':').flatMap { part ->
                if ('.' in part) part.split('.').map(String::toInt).let { listOf((it[0] shl 8) or it[1], (it[2] shl 8) or it[3]) }
                else listOf(part.toInt(16))
            }
            val left = words(halves[0]); val right = if (halves.size == 2) words(halves[1]) else emptyList()
            val expanded = left + List(8 - left.size - right.size) { 0 } + right
            return expanded.fold(BigInteger.ZERO) { n, part -> n.shiftLeft(16).add(BigInteger.valueOf(part.toLong())) } to 128
        }

        private fun address(value: BigInteger, bits: Int): String = if (bits == 32) {
            listOf(24, 16, 8, 0).joinToString(".") { value.shiftRight(it).and(BigInteger.valueOf(255)).toString() }
        } else {
            // Expanded hexadecimal is unambiguous, accepted by Android, and stable for deduplication.
            (7 downTo 0).joinToString(":") { value.shiftRight(it * 16).and(BigInteger.valueOf(65535)).toString(16) }
        }
    }
}
