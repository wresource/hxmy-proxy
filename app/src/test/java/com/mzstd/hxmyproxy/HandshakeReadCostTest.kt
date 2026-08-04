package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.readAsciiLine
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

/**
 * 握手路径读头部的成本：[readAsciiLine] 是**逐字节 `input.read()`**，
 * 而 `input` 是 `SocketChannel.socket().getInputStream()` —— 它不做用户态缓冲，
 * 于是**每一个字节都是一次 recv() 系统调用**。
 *
 * 这条路径每条连接都要走：CONNECT 请求行+头、明文 HTTP 的请求头、以及**上游响应头**。
 * 浏览器的请求头（含长 UA / Cookie）常在 300~1500 字节量级。
 */
class HandshakeReadCostTest {

    private val chromeConnect = buildString {
        append("CONNECT arxiv.org:443 HTTP/1.1\r\n")
        append("Host: arxiv.org:443\r\n")
        append("Proxy-Connection: keep-alive\r\n")
        append("User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ")
        append("(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    private val bigHeaders = buildString {
        append("GET http://example.com/a/b/c.png HTTP/1.1\r\n")
        append("Host: example.com\r\n")
        append("User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ")
        append("(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36\r\n")
        append("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8\r\n")
        append("Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n")
        append("Accept-Encoding: gzip, deflate, br\r\n")
        append("Cookie: sid=8f3a9c2e1b7d4f60a5c8e91d2b3f4a67; pref=aGVsbG8gd29ybGQgdGhpcyBpcyBhIGNvb2tpZQ==; ")
        append("tracking=1234567890abcdef1234567890abcdef; consent=eyJhZHMiOnRydWUsImFuYWx5dGljcyI6dHJ1ZX0=\r\n")
        append("Referer: http://example.com/index.html\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    @Test fun perByteSocketReadIsExpensive() {
        for ((name, head) in listOf("CONNECT(Chrome)" to chromeConnect, "明文 GET(带 Cookie)" to bigHeaders)) {
            val raw = bench(head, buffered = false)
            val buf = bench(head, buffered = true)
            println(
                String.format(
                    "%-22s %5d 字节  逐字节(现状) %8.1f us/头   加缓冲 %8.1f us/头   倍数 %5.1fx",
                    name, head.size, raw, buf, raw / buf,
                )
            )
        }
    }

    /** 返回读一份头部的平均耗时（微秒）。 */
    private fun bench(head: ByteArray, buffered: Boolean): Double {
        val iters = 2000
        val (w, r) = connectedPair()
        try {
            thread(isDaemon = true, name = "head-writer") {
                val bb = ByteBuffer.wrap(head)
                repeat(iters + 200) {
                    bb.clear()
                    try { while (bb.hasRemaining()) w.write(bb) } catch (e: Exception) { return@thread }
                }
            }
            val base: InputStream = r.socket().getInputStream()
            val inp = if (buffered) BufferedInputStream(base, 8 * 1024) else base
            repeat(200) { readHead(inp) }               // warmup
            val t0 = System.nanoTime()
            repeat(iters) { readHead(inp) }
            return (System.nanoTime() - t0) / 1000.0 / iters
        } finally {
            runCatching { w.close() }; runCatching { r.close() }
        }
    }

    /** 与 HttpProxyServer.handle 完全一致的读法：请求行 + 头，全部走 readAsciiLine。 */
    private fun readHead(inp: InputStream): Int {
        var n = 0
        val line = readAsciiLine(inp) ?: return 0
        n += line.length
        while (true) {
            val h = readAsciiLine(inp) ?: break
            if (h.isEmpty()) break
            n += h.length
        }
        return n
    }

    private fun connectedPair(): Pair<SocketChannel, SocketChannel> {
        val ss = ServerSocketChannel.open()
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val a = SocketChannel.open()
        a.configureBlocking(true)
        a.connect(ss.localAddress as InetSocketAddress)
        val b = ss.accept()
        ss.close()
        a.configureBlocking(true); b.configureBlocking(true)
        return a to b
    }
}
