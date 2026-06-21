package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class EgressGuardTest {

    private fun addr(s: String) = InetAddress.getByName(s)

    @Test fun blocksLoopback() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("127.0.0.1")))
    }

    @Test fun blocksLinkLocal() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("169.254.10.10")))
    }

    @Test fun blocksMulticast() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("224.0.0.251")))
    }

    @Test fun allowsPublicByDefault() {
        assertTrue(DefaultEgressGuard().isAllowed(addr("8.8.8.8")))
    }

    @Test fun allowsPrivateByDefault() {
        val g = DefaultEgressGuard()
        assertTrue(g.isAllowed(addr("192.168.1.5")))
        assertTrue(g.isAllowed(addr("10.0.0.5")))
        assertTrue(g.isAllowed(addr("172.16.5.5")))
        assertTrue(g.isAllowed(addr("100.64.0.1"))) // CGNAT treated as private
    }

    @Test fun blocksPrivateWhenConfigured() {
        val g = DefaultEgressGuard(blockPrivateLan = true)
        assertFalse(g.isAllowed(addr("192.168.1.5")))
        assertFalse(g.isAllowed(addr("10.0.0.5")))
        assertTrue(g.isAllowed(addr("8.8.8.8"))) // public still allowed
    }

    @Test fun blocksIpv6LoopbackAndLinkLocal() {
        val g = DefaultEgressGuard()
        assertFalse(g.isAllowed(addr("::1")))
        assertFalse(g.isAllowed(addr("fe80::1")))
    }
}
