package com.mzstd.hxmyproxy.core.proxy

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * HTTP/1.1 正向转发的报文定界与转发助手（支持 keep-alive：一个客户端连接承载多请求）。
 *
 * 二进制安全：仅按 Content-Length / chunked 计数拷贝**原始字节**，绝不做文本解码——
 * 图片等二进制内容原样转发。定界依据 RFC 7230 §3.3.3。
 */
internal object HttpForwarding {

    enum class Framing { NONE, LENGTH, CHUNKED, UNTIL_CLOSE }

    fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, true) }?.second

    fun isChunked(headers: List<Pair<String, String>>): Boolean =
        header(headers, "Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true

    fun contentLength(headers: List<Pair<String, String>>): Long? =
        header(headers, "Content-Length")?.trim()?.toLongOrNull()

    /** 请求体定界（来自客户端头）。GET 等无体请求 → NONE。 */
    fun requestFraming(headers: List<Pair<String, String>>): Pair<Framing, Long> = when {
        isChunked(headers) -> Framing.CHUNKED to 0L
        else -> contentLength(headers)?.let { Framing.LENGTH to it } ?: (Framing.NONE to 0L)
    }

    /** 响应体定界（RFC 7230 §3.3.3）：HEAD/1xx/204/304 无体；否则 chunked > Content-Length > 读到关闭。 */
    fun responseFraming(status: Int, requestMethod: String, headers: List<Pair<String, String>>): Pair<Framing, Long> = when {
        requestMethod.equals("HEAD", ignoreCase = true) -> Framing.NONE to 0L
        status in 100..199 || status == 204 || status == 304 -> Framing.NONE to 0L
        isChunked(headers) -> Framing.CHUNKED to 0L
        else -> contentLength(headers)?.let { Framing.LENGTH to it } ?: (Framing.UNTIL_CLOSE to 0L)
    }

    /** 客户端是否希望保持连接（HTTP/1.1 默认是，除非 Connection: close；HTTP/1.0 需显式 keep-alive）。 */
    fun clientKeepAlive(httpVersion: String, headers: List<Pair<String, String>>): Boolean {
        val conn = header(headers, "Connection")?.lowercase() ?: ""
        if (conn.contains("close")) return false
        if (httpVersion.endsWith("1.1")) return true
        return conn.contains("keep-alive")
    }

    /** 按定界把报文体从 [input] 转发到 [output]，字节增量经 [onBytes] 计量。 */
    fun copyBody(input: InputStream, output: OutputStream, framing: Framing, length: Long, buf: ByteArray, onBytes: (Long) -> Unit) {
        when (framing) {
            Framing.NONE -> {}
            Framing.LENGTH -> copyLength(input, output, length, buf, onBytes)
            Framing.CHUNKED -> copyChunked(input, output, buf, onBytes)
            Framing.UNTIL_CLOSE -> copyUntilClose(input, output, buf, onBytes)
        }
    }

    /** 拷贝恰好 [length] 字节（提前 EOF 抛异常）。 */
    fun copyLength(input: InputStream, output: OutputStream, length: Long, buf: ByteArray, onBytes: (Long) -> Unit) {
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) throw EOFException("unexpected EOF, $remaining bytes remaining")
            output.write(buf, 0, n)
            remaining -= n
            onBytes(n.toLong())
        }
        output.flush()
    }

    /** 读到流关闭（connection-close 定界）。 */
    fun copyUntilClose(input: InputStream, output: OutputStream, buf: ByteArray, onBytes: (Long) -> Unit) {
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
            onBytes(n.toLong())
        }
    }

    /** 原样转发 chunked 报文体（含 chunk 定界），直到 0 长度块（+ 可选 trailer）。 */
    fun copyChunked(input: InputStream, output: OutputStream, buf: ByteArray, onBytes: (Long) -> Unit) {
        while (true) {
            val sizeLine = readAsciiLine(input) ?: return
            val emitted = (sizeLine + "\r\n").toByteArray(Charsets.ISO_8859_1)
            output.write(emitted); onBytes(emitted.size.toLong())
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return
            if (size == 0) {
                // trailer 段（含结束空行）原样转发
                while (true) {
                    val t = readAsciiLine(input) ?: break
                    val tb = (t + "\r\n").toByteArray(Charsets.ISO_8859_1)
                    output.write(tb); onBytes(tb.size.toLong())
                    if (t.isEmpty()) break
                }
                output.flush()
                return
            }
            copyLength(input, output, size.toLong(), buf, onBytes)
            // 块尾 CRLF
            val crlf = ByteArray(2)
            readFully(input, crlf)
            output.write(crlf); onBytes(2)
            output.flush()
        }
    }
}
