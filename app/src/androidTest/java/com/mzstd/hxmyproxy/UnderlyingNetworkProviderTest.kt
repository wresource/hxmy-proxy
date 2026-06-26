package com.mzstd.hxmyproxy

import android.net.Network
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证出口分流的底层网络获取机制:registerNetworkCallback(NOT_VPN + WIFI)能拿到 Network。
 * 需设备在 WiFi 上。无法验证「绕过 VPN」本身(需真实 VPN 环境),只证明 provider 机制工作。
 */
@RunWith(AndroidJUnit4::class)
class UnderlyingNetworkProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun acquiresNonVpnWifiNetwork() {
        val p = UnderlyingNetworkProvider(context)
        p.start()
        var net: Network? = null
        for (i in 0 until 24) {
            net = p.current()
            if (net != null) break
            Thread.sleep(250)
        }
        p.stop()
        assertNotNull("expected a non-VPN WiFi network (device must be on WiFi)", net)
    }
}
