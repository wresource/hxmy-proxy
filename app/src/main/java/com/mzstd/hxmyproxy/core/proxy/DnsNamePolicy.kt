package com.mzstd.hxmyproxy.core.proxy

/** RFC 6761 / RFC 6762 / RFC 8375: public backup is not a resolver for local namespaces. */
internal object DnsNamePolicy {
    private fun within(host: String, suffix: String) = host == suffix || host.endsWith(".$suffix")
    fun isInvalid(host: String) = within(host, "invalid")
    fun isLocalhost(host: String) = within(host, "localhost")
    fun allowPublicBackup(host: String): Boolean = host.contains('.') &&
        !isInvalid(host) && !isLocalhost(host) &&
        !within(host, "local") && !within(host, "home.arpa") && !within(host, "test")
}
