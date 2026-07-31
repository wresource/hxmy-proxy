package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 代理层单测的共用脚手架：在 loopback 上起**真实**监听/上游、用**真实** socket 说协议。
 *
 * 为什么不 mock：被测的正是 socket 生命周期（谁关、何时关、关了对端看到什么）——
 * mock 掉 socket 等于把要守护的语义一起 mock 掉。
 */
internal object ProxyTestKit {

    /** 等待 server 绑定端口。超时直接失败，比后续读超时 8s 更容易定位是「没起来」。 */
    fun awaitPort(server: ProxyServer): Int {
        repeat(500) {
            val p = server.boundPort.value
            if (p != null && p > 0) return p
            Thread.sleep(10)
        }
        throw AssertionError("server 未在 5s 内绑定端口")
    }

    /** 轮询等待条件成立；返回最终是否成立（用于异步状态：计数释放、连接被拆等）。 */
    fun await(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    /**
     * 客户端连接，统一设 SO_TIMEOUT：卡住的阻塞读会变成超时异常而不是永久挂起整个测试套件
     * （JUnit 的 @Test(timeout) 靠 interrupt，中断不了阻塞 socket 读）。
     */
    fun client(port: Int, soTimeoutMs: Int = 8000): Socket =
        Socket("127.0.0.1", port).apply { soTimeout = soTimeoutMs }

    fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("期望 $n 字节，只读到 $off")
            off += r
        }
        return b
    }

    /** 读一行（\n 结束、去掉 \r）。EOF 与空行都返回空串——调用方按上下文区分。 */
    fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** 读 HTTP 响应头部（状态行 + 头，保留重复头与顺序）。服务端一字未回就关闭 → 抛 EOF。 */
    fun readHead(input: InputStream): HttpHead {
        val statusLine = readLine(input)
        if (statusLine.isEmpty()) throw EOFException("服务端未回任何响应就关闭了连接")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val l = readLine(input)
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers.add(l.substring(0, i).trim() to l.substring(i + 1).trim())
        }
        return HttpHead(statusLine, status, headers)
    }

    /** 按 Content-Length 读响应体；无 Content-Length 则读到流关闭。 */
    fun readBody(input: InputStream, head: HttpHead): ByteArray {
        val len = head.contentLength
        return if (len >= 0) readN(input, len) else input.readBytes()
    }

    /** 一次性发请求并读回响应头（发完不关写端，隧道/keep-alive 场景可继续用同一 socket）。 */
    fun sendAndReadHead(sock: Socket, raw: String): HttpHead {
        write(sock, raw)
        return readHead(sock.getInputStream())
    }

    fun write(sock: Socket, raw: String) {
        sock.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
        sock.getOutputStream().flush()
    }

    fun write(sock: Socket, bytes: ByteArray) {
        sock.getOutputStream().write(bytes)
        sock.getOutputStream().flush()
    }

    /**
     * 对端是否已关闭（EOF 或 reset 都算）。
     * **读超时必须返回 false**：「挂着不回也不关」正是这些用例要抓的 bug，
     * 若把 SocketTimeoutException 也当成「已关闭」，所有断开类断言都会变成永远通过的空壳。
     */
    fun closedByPeer(sock: Socket): Boolean = try {
        sock.getInputStream().read() < 0
    } catch (e: SocketTimeoutException) {
        false
    } catch (e: IOException) {
        true
    }

    /**
     * 一个**确定没人监听**的本机端口（开一个再立刻关）：用于制造 connection refused。
     * 不用固定端口——固定端口在开发机上可能真的被别的进程占着，测试会变成偶发绿。
     */
    fun deadPort(): Int {
        val s = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val p = s.localPort
        s.close()
        return p
    }

    /** 回显服务：收什么回什么（默认绑 127.0.0.1，IPv6 用例可传 ::1）。 */
    fun startEcho(bind: InetAddress = InetAddress.getByName("127.0.0.1")): ServerSocket {
        val s = ServerSocket(0, 50, bind)
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val inp = c.getInputStream()
                        val out = c.getOutputStream()
                        val buf = ByteArray(4096)
                        while (true) {
                            val r = inp.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r); out.flush()
                        }
                    } catch (e: Exception) {
                        // 测试收尾时对端被关是常态
                    } finally {
                        c.close()
                    }
                }
            }
        }
        return s
    }
}

/** HTTP 响应头部。保留**重复头**（`Connection` 出现几次是被测语义之一，Map 会把它吃掉）。 */
internal class HttpHead(
    val statusLine: String,
    val status: Int,
    val headers: List<Pair<String, String>>,
) {
    fun value(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    fun count(name: String): Int = headers.count { it.first.equals(name, true) }
    fun has(name: String): Boolean = count(name) > 0
    val contentLength: Int get() = value("Content-Length")?.toIntOrNull() ?: -1
}

/** 上游收到的一个请求（请求行 + 头）。 */
internal class RecordedRequest(val line: String, val headers: List<Pair<String, String>>) {
    fun value(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    fun has(name: String): Boolean = headers.any { it.first.equals(name, true) }
}

/**
 * 记录型上游源站：把收到的请求行/头排进队列供断言，并对每个请求回一份带 Content-Length 的
 * 固定响应（不主动关连接，故可承载 keep-alive 复用）。
 */
internal class RecordingOrigin(
    private val body: ByteArray = "ok".toByteArray(),
    private val extraHeaders: List<Pair<String, String>> = emptyList(),
) : Closeable {

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val requests = LinkedBlockingQueue<RecordedRequest>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(c) }
            }
        }
    }

    /** 取下一个收到的请求；超时返回 null（断言里报「上游没收到请求」比挂死好定位）。 */
    fun nextRequest(timeoutMs: Long = 5000): RecordedRequest? =
        requests.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun serve(c: Socket) {
        try {
            val inp = c.getInputStream()
            val out = c.getOutputStream()
            while (true) {
                val line = ProxyTestKit.readLine(inp)
                if (line.isEmpty()) break // EOF / 对端关闭
                val headers = ArrayList<Pair<String, String>>()
                while (true) {
                    val h = ProxyTestKit.readLine(inp)
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers.add(h.substring(0, i).trim() to h.substring(i + 1).trim())
                }
                // 有请求体就读掉，否则会被当成下一个请求行，记录到的东西全乱。
                val cl = headers.firstOrNull { it.first.equals("Content-Length", true) }
                    ?.second?.toIntOrNull() ?: 0
                if (cl > 0) ProxyTestKit.readN(inp, cl)
                requests.add(RecordedRequest(line, headers))
                val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
                for ((n, v) in extraHeaders) sb.append(n).append(": ").append(v).append("\r\n")
                sb.append("Content-Length: ").append(body.size).append("\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        } catch (e: Exception) {
            // 收尾期对端关闭是常态
        } finally {
            runCatching { c.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}

/**
 * 测试资源清单：按注册的逆序释放。端口/线程泄漏会让**后面**的测试莫名失败，
 * 所以每个用例都必须能自行清理，而不是指望 GC。
 */
internal class Resources : Closeable {
    private val actions = ArrayList<() -> Unit>()

    fun <T : Closeable> keep(c: T): T {
        actions.add { c.close() }
        return c
    }

    fun onClose(action: () -> Unit) {
        actions.add(action)
    }

    override fun close() {
        actions.asReversed().forEach { runCatching { it() } }
        actions.clear()
    }
}
