package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class ConnectUdpProtocolTest {
    @Test fun capabilityExtensionKeepsV1FieldsAndSeparatesNativeWireLimit() {
        val absent=ConnectUdpProtocol.capabilities("session",2000)
        assertTrue(absent.contains("\"version\":1"));assertTrue(absent.contains("\"socks5\":null"))
        val ready=ConnectUdpProtocol.capabilities("session",2000,Socks5Capabilities(1080,true,true))
        for (field in listOf("\"session_id\":\"session\"","\"connect_udp_h1\":true","\"max_udp_payload\":65507",
            "\"idle_timeout_ms\":2000","\"port\":1080","\"bind\":true","\"max_udp_wire\":65507")) assertTrue(ready.contains(field))
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytes(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also { buffer.get(it) }
    private fun decode(wire: ByteArray, chunk: Int): List<ByteArray> {
        val decoder = UdpCapsuleDecoder(); val out = mutableListOf<ByteArray>()
        for (start in wire.indices step chunk) decoder.consume(ByteBuffer.wrap(wire, start, minOf(chunk, wire.size - start)), emit = out::add)
        decoder.finish(); return out
    }

    @Test fun goldenFramesPreserveEmptyZeroByteAndBinaryPayloadsAtEveryBoundary() {
        val examples = listOf("" to "000100", "00" to "00020000", "6869" to "0003006869", "00ff01" to "00040000ff01")
        for ((payload, wire) in examples) {
            assertArrayEquals(hex(wire), bytes(UdpCapsuleEncoder.datagram(hex(payload)).bytes))
            for (chunk in 1..hex(wire).size) assertArrayEquals(hex(payload), decode(hex(wire), chunk).single())
        }
    }

    @Test fun varintTransitionBoundariesHaveIndependentGoldenEncodings() {
        for ((number, encoded) in listOf(0L to "00", 63L to "3f", 64L to "4040", 16383L to "7fff", 16384L to "80004000", 1073741823L to "bfffffff", 1073741824L to "c000000040000000", 4611686018427387903L to "ffffffffffffffff"))
            assertArrayEquals(encoded, hex(encoded), UdpCapsuleEncoder.varint(number))
    }

    @Test fun coalescedSplitAndNonMinimalVarintsKeepAllDatagramBoundaries() {
        val wire = hex("000100000300686940004002400000040000ff01")
        for (chunk in 1..wire.size) {
            val result = decode(wire, chunk)
            assertEquals(4, result.size)
            assertArrayEquals(byteArrayOf(), result[0]); assertArrayEquals("hi".toByteArray(), result[1])
            assertArrayEquals(byteArrayOf(), result[2]); assertArrayEquals(hex("00ff01"), result[3])
        }
    }

    @Test fun workLimitLeavesBufferedFramesForTheNextTurnWithoutLoss() {
        val wire = ByteBuffer.wrap(hex("000100000100000100"));val decoder = UdpCapsuleDecoder();var count = 0
        repeat(3) { assertEquals(1, decoder.consume(wire, 1, { count++ })); assertEquals((2 - it) * 3, wire.remaining()) }
        decoder.finish(); assertEquals(3, count)
    }

    @Test fun unknownCapsulesAndContextsDoNotBecomeUdpPayload() {
        val decoder = UdpCapsuleDecoder();val out = mutableListOf<ByteArray>();var drops = 0
        for (b in hex("0103aabbcc0003014243000100")) decoder.consume(ByteBuffer.wrap(byteArrayOf(b)), emit = out::add, drop = { drops++ })
        decoder.finish();assertEquals(1, drops);assertEquals(1, out.size);assertEquals(0, out[0].size)
    }

    @Test fun advertisedSizeGapIsDiscardedButProtocolOverflowAborts() {
        val decoder = UdpCapsuleDecoder();val out = mutableListOf<ByteArray>();var drops = 0
        val gap = byteArrayOf(0) + UdpCapsuleEncoder.varint(65509) + byteArrayOf(0) + ByteArray(65508) + hex("000100")
        decoder.consume(ByteBuffer.wrap(gap), emit = out::add, drop = { drops++ });decoder.finish()
        assertEquals(1, drops);assertEquals(0, out.single().size)
        val excessive = byteArrayOf(0) + UdpCapsuleEncoder.varint(65529) + byteArrayOf(0)
        assertTrue(runCatching { UdpCapsuleDecoder().consume(ByteBuffer.wrap(excessive), emit = {}) }.exceptionOrNull() is CapsuleProtocolException)
        val huge = byteArrayOf(1) + UdpCapsuleEncoder.varint(1L shl 40)
        assertTrue(runCatching { UdpCapsuleDecoder().consume(ByteBuffer.wrap(huge), emit = {}) }.exceptionOrNull() is CapsuleProtocolException)
    }

    @Test fun maximumPayloadRoundTripsWithoutTruncation() {
        val input = ByteArray(65507) { (it % 251).toByte() }
        val encoded = bytes(UdpCapsuleEncoder.datagram(input).bytes)
        assertArrayEquals(input, decode(encoded, 997).single())
    }

    @Test fun partialFrameOrMissingContextCannotBeTreatedAsCleanEof() {
        for (wire in listOf("00", "0000", "0002", "000200", "000140", "010201", "40")) {
            val failure = runCatching { val d = UdpCapsuleDecoder();d.consume(ByteBuffer.wrap(hex(wire)), emit = {});d.finish() }.exceptionOrNull()
            assertTrue("$wire must fail", failure is CapsuleProtocolException)
        }
    }

    @Test fun targetParsingUsesOneDecodeAndSharedHostNormalization() {
        assertEquals(ConnectUdpProtocol.Target("example.com", 53), ConnectUdpProtocol.target("/.well-known/masque/udp/ExAmPlE.com./53/"))
        val v6 = ConnectUdpProtocol.target("/.well-known/masque/udp/2001%3Adb8%3A%3A7/65535/")!!
        assertEquals(InetAddress.getByName("2001:db8::7"), InetAddress.getByName(v6.host));assertEquals(65535, v6.port)
        for (target in listOf("example.com/0/", "example.com/65536/", "example.com/-1/", "example.com/+53/", "example.com/53", "example.com/53/?x=1", "example.com/53/#x", "%252F/53/", "example.com%2Fpath/53/", "foo%3A443/53/", "a%40b/53/", "%00/53/", "%ED%A0%80/53/", "127.0.0.1-127.0.0.2/53/"))
            assertNull(target, ConnectUdpProtocol.target(ConnectUdpProtocol.UDP_PREFIX + target))
    }

    @Test fun upgradeValidatesFramingHeadersAndSessionIsNeverAmbiguous() {
        val headers = listOf("Host" to "phone:8079", "Connection" to "keep-alive, Upgrade", "Upgrade" to "connect-udp", "Capsule-Protocol" to "?1; extension=\"a,b;c\"", "X-HxmyProxy-Session" to "session")
        assertTrue(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers))
        assertEquals("session", ConnectUdpProtocol.session(headers))
        assertFalse(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.0", headers))
        assertFalse(ConnectUdpProtocol.validUpgrade("POST", "HTTP/1.1", headers))
        assertFalse(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers + ("Content-Length" to "1")))
        assertFalse(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers + ("Transfer-Encoding" to "chunked")))
        assertFalse(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers + ("Capsule-Protocol" to "?1")))
        assertNull(ConnectUdpProtocol.session(headers + ("x-hxmyproxy-session" to "other")))
        assertFalse(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers.filterNot { it.first == "Host" }))
        assertFalse(ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers + ("host" to "other")))
        for (host in listOf("", "a b", "user@phone", "phone:99999", "phone/another"))
            assertFalse(host, ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers.filterNot { it.first == "Host" } + ("Host" to host)))
        for (value in listOf("?0", "?1,?1", "true", "?1; bad=", "?1; x=\"unterminated", "?1; x=\"bad\\n\""))
            assertFalse(value, ConnectUdpProtocol.validUpgrade("GET", "HTTP/1.1", headers.filterNot { it.first == "Capsule-Protocol" } + ("Capsule-Protocol" to value)))
    }
}
