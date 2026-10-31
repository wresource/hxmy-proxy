package com.mzstd.hxmyproxy.core.proxy

/** RFC 6761 / RFC 6762 / RFC 8375: public backup is not a resolver for local namespaces. */
internal object DnsNamePolicy {
    private val numericHost = Regex("(?:[0-9]+|0x[0-9a-f]+)")
    private fun within(host: String, suffix: String) = host == suffix || host.endsWith(".$suffix")
    fun isInvalid(host: String) = within(host, "invalid")
    fun isLocalhost(host: String) = within(host, "localhost")
    /** Known local names are refused before DNS when LAN forwarding is disabled.
     * Do not infer locality from arbitrary public names or legacy numeric spellings. */
    fun isLocalNetworkName(host: String): Boolean = within(host, "local") || within(host, "home.arpa") ||
        (host.isNotBlank() && !host.contains('.') && !host.contains(':') &&
            !numericHost.matches(host) && !isInvalid(host) && !isLocalhost(host))
    fun allowPublicBackup(host: String): Boolean = host.contains('.') &&
        !isInvalid(host) && !isLocalhost(host) &&
        !within(host, "local") && !within(host, "home.arpa") && !within(host, "test")
}
