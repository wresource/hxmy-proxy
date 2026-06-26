package com.mzstd.hxmyproxy.core.network

import android.net.NetworkCapabilities
import com.mzstd.hxmyproxy.core.model.VpnState

/** 将默认网络的 [NetworkCapabilities] 映射为 [VpnState]。 */
object VpnStateDetector {

    fun from(caps: NetworkCapabilities?): VpnState {
        if (caps == null) return VpnState()
        return VpnState(
            detected = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            underlyingTransport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> null
            },
        )
    }
}
