package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.HttpForwarding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpForwardingTest {

    @Test fun responseFramingRules() {
        assertEquals(HttpForwarding.Framing.NONE, HttpForwarding.responseFraming(204, "GET", emptyList()).first)
        assertEquals(HttpForwarding.Framing.NONE, HttpForwarding.responseFraming(304, "GET", emptyList()).first)
        // HEAD：即便有 Content-Length 也无体
        assertEquals(HttpForwarding.Framing.NONE, HttpForwarding.responseFraming(200, "HEAD", listOf("Content-Length" to "5")).first)
        assertEquals(HttpForwarding.Framing.CHUNKED, HttpForwarding.responseFraming(200, "GET", listOf("Transfer-Encoding" to "chunked")).first)
        val (f, len) = HttpForwarding.responseFraming(200, "GET", listOf("Content-Length" to "42"))
        assertEquals(HttpForwarding.Framing.LENGTH, f); assertEquals(42L, len)
        // 无 CL、无 chunked → 读到关闭
        assertEquals(HttpForwarding.Framing.UNTIL_CLOSE, HttpForwarding.responseFraming(200, "GET", emptyList()).first)
    }

    @Test fun requestFramingRules() {
        assertEquals(HttpForwarding.Framing.NONE, HttpForwarding.requestFraming(emptyList()).first)
        val (f, len) = HttpForwarding.requestFraming(listOf("Content-Length" to "10"))
        assertEquals(HttpForwarding.Framing.LENGTH, f); assertEquals(10L, len)
        assertEquals(HttpForwarding.Framing.CHUNKED, HttpForwarding.requestFraming(listOf("Transfer-Encoding" to "chunked")).first)
    }

    @Test fun clientKeepAliveRules() {
        assertTrue(HttpForwarding.clientKeepAlive("HTTP/1.1", emptyList()))           // 1.1 默认保持
        assertFalse(HttpForwarding.clientKeepAlive("HTTP/1.1", listOf("Connection" to "close")))
        assertFalse(HttpForwarding.clientKeepAlive("HTTP/1.0", emptyList()))          // 1.0 默认关闭
        assertTrue(HttpForwarding.clientKeepAlive("HTTP/1.0", listOf("Connection" to "keep-alive")))
    }

    @Test fun copyLengthCopiesExactBytesBinarySafe() {
        val data = ByteArray(1000) { (it * 13 + 0x80).toByte() } // 含高位/非 ASCII 字节
        val input = ByteArrayInputStream(data + "TAIL".toByteArray())
        val out = ByteArrayOutputStream()
        var counted = 0L
        HttpForwarding.copyLength(input, out, 1000, ByteArray(64)) { counted += it }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(1000L, counted)
        assertEquals("TAIL", String(input.readBytes())) // 只读了恰好 1000 字节
    }

    @Test fun copyChunkedReEmitsVerbatimAndStopsAtTerminator() {
        val body1 = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x89.toByte(), 0x10)
        val body2 = byteArrayOf(0x01, 0x02, 0x03)
        val chunked = ByteArrayOutputStream().apply {
            write("5\r\n".toByteArray()); write(body1); write("\r\n".toByteArray())
            write("3\r\n".toByteArray()); write(body2); write("\r\n".toByteArray())
            write("0\r\n\r\n".toByteArray())
        }.toByteArray()
        val input = ByteArrayInputStream(chunked + "NEXT".toByteArray())
        val out = ByteArrayOutputStream()
        HttpForwarding.copyChunked(input, out, ByteArray(4096)) {}
        assertArrayEquals(chunked, out.toByteArray())              // 逐字节原样转发
        assertEquals("NEXT", String(input.readBytes()))           // 停在终止块之后
    }
}
