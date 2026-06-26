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

    @Test fun gatewayLikeDetectsDotOne() {
        assertTrue(InterfaceClassifier.isGatewayLike(byteArrayOf(192.toByte(), 168.toByte(), 43, 1)))
        assertFalse(InterfaceClassifier.isGatewayLike(byteArrayOf(192.toByte(), 168.toByte(), 1, 34)))
        assertFalse(InterfaceClassifier.isGatewayLike(ByteArray(16))) // IPv6 length -> not gateway-like
    }
}
