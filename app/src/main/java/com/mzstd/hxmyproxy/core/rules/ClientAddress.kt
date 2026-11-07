package com.mzstd.hxmyproxy.core.rules

import java.net.InetAddress

/** Source-IP identity, shared by persisted client settings and actual socket peers. Never resolves DNS. */
object ClientAddress {
    fun key(address: InetAddress): String = requireNotNull(parse(address.hostAddress.orEmpty().substringBefore('%')))

    fun parse(value: String): String? = RuleInput.numericAddress(value)?.first?.let { numeric ->
        // Validation above excludes every hostname; getByName only parses this numeric literal.
        InetAddress.getByName(numeric).hostAddress?.substringBefore('%')
    }

    fun normalize(values: Set<String>, strict: Boolean = true): Set<String> {
        require(values.size <= 1024) { "Too many client routing settings" }
        return values.mapNotNull { value ->
            val key = parse(value)
            if (strict) requireNotNull(key) { "Client routing requires an IP address" }
            key
        }.toSet()
    }
}
