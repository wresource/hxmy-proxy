package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.EgressHealth
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.stats.EgressKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress

/** JVM 的系统 DNS 替身显式接线；仪器测试继续使用生产 AndroidSystemDnsLookup。 */
internal fun testConnector(
    egressGuard: EgressGuard,
    dnsDispatcher: CoroutineDispatcher = Dispatchers.IO,
    connectDispatcher: CoroutineDispatcher = OutboundConnector.DEFAULT_CONNECT_DISPATCHER,
    underlyingNetworkProvider: UnderlyingNetworkProvider? = null,
    egressClassifier: ((Network?) -> EgressKind)? = null,
    egressHealth: EgressHealth = EgressHealth(),
    systemDns: SystemDnsLookup = SystemDnsLookup { network, host ->
        runInterruptible(dnsDispatcher) {
            (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()
        }
    },
): OutboundConnector = OutboundConnector(
    egressGuard, connectDispatcher, underlyingNetworkProvider, egressClassifier, egressHealth, systemDns,
)
