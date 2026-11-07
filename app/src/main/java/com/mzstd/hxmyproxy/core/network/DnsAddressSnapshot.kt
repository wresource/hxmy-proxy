package com.mzstd.hxmyproxy.core.network

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.RandomAccess

/** Own the Java resolver's data before accepting it. Read-only List alone is not immutable
 * and its generic type does not prevent null/foreign elements arriving through Java. */
internal class DnsAddressSnapshot private constructor(private val values: Array<InetAddress>) :
    AbstractList<InetAddress>(), RandomAccess {
    override val size: Int get() = values.size
    override fun get(index: Int): InetAddress = values[index]

    companion object {
        fun capture(raw: List<*>, source: String, host: String? = null, network: Long? = null,
                    generation: Long? = null): DnsAddressSnapshot {
            if (raw is DnsAddressSnapshot) return raw
            // Capture first, then inspect runtime values; never cast the input to List<InetAddress>.
            val supplied = raw.toTypedArray()
            val valid = supplied.filter { it is Inet4Address || it is Inet6Address }
                .map { it as InetAddress }.toTypedArray()
            if (valid.size != supplied.size) runCatching {
                Ev.e(LogCat.DNS, "dns.invalidAnswer", null, "source" to source, "host" to host,
                    "netId" to network, "generation" to generation, "raw" to supplied.size,
                    "valid" to valid.size, "dropped" to supplied.size - valid.size)
            }
            return DnsAddressSnapshot(valid)
        }
    }
}
