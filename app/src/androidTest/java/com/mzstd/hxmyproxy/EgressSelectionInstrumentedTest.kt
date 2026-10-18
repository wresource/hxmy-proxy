package com.mzstd.hxmyproxy

import android.net.Network
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.EgressFallback
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.stats.EgressKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class EgressSelectionInstrumentedTest {
    @Test fun missingVpnStrictRejectsAndDegradeUsesAnExplicitPhysicalNetwork() = runBlocking {
        val provider = UnderlyingNetworkProvider(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            provider.start()
            withTimeout(5000) { while (provider.current() == null) delay(50) }
            assumeFalse("此测试要求模拟器没有活动VPN", provider.status().vpn)
            provider.setEgressChoice(EgressNetworkChoice.VPN)
            var reported: Network? = null
            val connector = OutboundConnector(
                object : EgressGuard { override fun isAllowed(addr: InetAddress) = true },
                underlyingNetworkProvider = provider,
                egressClassifier = { reported = it; EgressKind.OTHER },
            )
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                val failure = runCatching { connector.connect("127.0.0.1", server.localPort) }.exceptionOrNull()
                assertTrue(failure is ProxyException)
                assertEquals("egress-unavailable", (failure as ProxyException).stage)
                assertNull(reported)
                connector.egressFallback = EgressFallback.DEGRADE
                connector.connect("127.0.0.1", server.localPort, onEgress = {}).use { assertTrue(it.isConnected) }
                assertNotNull("允许降级也不能用null默认路由", reported)
                assertEquals(provider.fallbackEgress(null)?.networkHandle, reported?.networkHandle)
            }
        } finally {
            provider.stop()
        }
    }
}
