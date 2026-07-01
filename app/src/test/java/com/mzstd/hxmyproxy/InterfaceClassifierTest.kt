package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.core.network.InterfaceClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceClassifierTest {

    @Test fun classifiesByName() {
        assertEquals(InterfaceType.WIFI, InterfaceClassifier.classify("wlan0", gatewayLike = false))
        assertEquals(InterfaceType.HOTSPOT, InterfaceClassifier.classify("wlan0", gatewayLike = true))
        assertEquals(InterfaceType.HOTSPOT, InterfaceClassifier.classify("ap0", gatewayLike = false))
        assertEquals(InterfaceType.HOTSPOT, InterfaceClassifier.classify("swlan0", gatewayLike = false))
        assertEquals(InterfaceType.USB, InterfaceClassifier.classify("rndis0", gatewayLike = false))
        assertEquals(InterfaceType.USB, InterfaceClassifier.classify("usb0", gatewayLike = false))
        assertEquals(InterfaceType.BLUETOOTH, InterfaceClassifier.classify("bt-pan", gatewayLike = false))
        assertEquals(InterfaceType.ETHERNET, InterfaceClassifier.classify("eth0", gatewayLike = false))
        assertEquals(InterfaceType.UNKNOWN, InterfaceClassifier.classify("dummy0", gatewayLike = false))
    }

    @Test fun uplinkOnlyExcludesCellularAndVpn() {
        // 蜂窝
        assertTrue(InterfaceClassifier.isUplinkOnly("rmnet1"))
        assertTrue(InterfaceClassifier.isUplinkOnly("rmnet_data0"))
        assertTrue(InterfaceClassifier.isUplinkOnly("v4-rmnet2"))
        assertTrue(InterfaceClassifier.isUplinkOnly("ccmni0"))
        // VPN 隧道
        assertTrue(InterfaceClassifier.isUplinkOnly("ipsec327"))
        assertTrue(InterfaceClassifier.isUplinkOnly("tun0"))
        assertTrue(InterfaceClassifier.isUplinkOnly("ppp0"))
        // 可共享接口不应被排除
        assertFalse(InterfaceClassifier.isUplinkOnly("wlan0"))
        assertFalse(InterfaceClassifier.isUplinkOnly("ap0"))
        assertFalse(InterfaceClassifier.isUplinkOnly("swlan0"))
        assertFalse(InterfaceClassifier.isUplinkOnly("eth0"))
        assertFalse(InterfaceClassifier.isUplinkOnly("rndis0"))
    }

    @Test fun gatewayLikeDetectsDotOne() {
        assertTrue(InterfaceClassifier.isGatewayLike(byteArrayOf(192.toByte(), 168.toByte(), 43, 1)))
        assertFalse(InterfaceClassifier.isGatewayLike(byteArrayOf(192.toByte(), 168.toByte(), 1, 34)))
        assertFalse(InterfaceClassifier.isGatewayLike(ByteArray(16))) // IPv6 length -> not gateway-like
    }
}
