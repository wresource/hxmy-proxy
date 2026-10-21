package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.IpRange
import com.mzstd.hxmyproxy.core.rules.RuleInput
import org.junit.Assert.*
import org.junit.Test

class IpRangeTest {
    @Test fun bothSeparatorsNormalizeToTheSameRange() {
        assertEquals("1.2.3.4-1.2.3.255", RuleInput.normalize("1.2.3.4 1.2.3.255"))
        assertEquals(RuleInput.normalize("1.2.3.4 - 1.2.3.255"), RuleInput.normalize("1.2.3.4\t1.2.3.255"))
        assertTrue(RuleInput.parse("1.2.3.4-1.2.3.255")!!.isIp)
    }
    @Test fun cidrCoverIsExactWithoutAddressExpansion() {
        assertEquals(listOf("1.2.3.4/30", "1.2.3.8/29", "1.2.3.16/28", "1.2.3.32/27", "1.2.3.64/26", "1.2.3.128/25"),
            IpRange.parse("1.2.3.4 1.2.3.255")!!.cidrs())
        assertEquals(listOf("0.0.0.0/0"), IpRange.parse("0.0.0.0 255.255.255.255")!!.cidrs())
        assertEquals(listOf("0:0:0:0:0:0:0:0/0"), IpRange.parse(":: ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")!!.cidrs())
    }
    @Test fun rejectsReversedCrossFamilyAndMalformedRanges() {
        listOf("1.2.3.255 1.2.3.4", "1.2.3.4 ::1", "1.2.3.4-1.2.3.256", "=1.2.3.4-1.2.3.5",
            "*.1.2.3.4-1.2.3.5", "1.2.3.4\n1.2.3.5", "https://1.2.3.4-1.2.3.255/path").forEach { assertNull(it, RuleInput.normalize(it)) }
        assertEquals("ad-server.example.com", RuleInput.normalize("ad-server.example.com"))
    }
    @Test fun intervalQueriesRetainAddressFamilyAndBoundary() {
        val subnet = IpRange.ofRule("1.2.3.99/24")!!
        assertTrue(subnet.contains(IpRange.ofRule("1.2.3.255")!!))
        assertFalse(subnet.overlaps(IpRange.ofRule("1.2.4.0/24")!!))
        assertFalse(subnet.overlaps(IpRange.ofRule("::/0")!!))
        assertEquals(listOf("2001:db8:0:0:0:0:0:1/128"), IpRange.parse("2001:db8::1 2001:db8::1")!!.cidrs())
    }
    @Test fun fileBomIsHandledOnlyAtTheFileBoundary() {
        val lines = RuleInput.parseLines("\uFEFF1.0.1.0/24\n# comment\n1.2.3.4 1.2.3.255\n1.2.3.4-1.2.3.255\n\uFEFF1.0.2.0/24")
        assertEquals(listOf("1.0.1.0/24", "1.2.3.4-1.2.3.255"), lines.rules)
        assertEquals(listOf(5), lines.invalidLines)
        assertNull(RuleInput.normalize("\uFEFF1.0.1.0/24"))
    }
}
