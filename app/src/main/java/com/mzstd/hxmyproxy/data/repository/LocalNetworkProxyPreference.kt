package com.mzstd.hxmyproxy.data.repository

/** An old default-allow value is not consent to the new explicit opt-in.
 * An older app may still write its deny flag; retain that restriction until
 * the user enables the current switch, which atomically writes both values. */
internal object LocalNetworkProxyPreference {
    fun isBlocked(explicitlyAllowed: Boolean?, legacyBlocked: Boolean?): Boolean =
        explicitlyAllowed != true || legacyBlocked == true
}
