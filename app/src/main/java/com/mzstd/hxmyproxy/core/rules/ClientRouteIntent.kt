package com.mzstd.hxmyproxy.core.rules

/** Effective phone-side mode for one actual socket peer. DIRECT still uses the phone.
 * The companion must bind revision to the capability session and its connection source. */
data class ClientRouteIntent(val sourceIp: String, val revision: Long, val direct: Boolean)
