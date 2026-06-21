package com.mzstd.hxmyproxy.core.proxy

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream

/** 安静关闭，吞掉异常（用于 finally / 清理路径）。 */
internal fun Closeable.closeQuietly() = try { close() } catch (_: Throwable) {}

/** 读满 [buf]；若流提前结束抛 [EOFException]。 */
internal fun readFully(input: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n < 0) throw EOFException()
        off += n
    }
}

/**
 * 从原始流逐字节读一行（以 \n 结束，去掉结尾 \r）。不做缓冲，
 * 因此 header 之后的 body 仍留在流里，可交给 RelayEngine。
 * 返回 null 表示流已结束且无内容。
 */
internal fun readAsciiLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val c = input.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
    }
    return sb.toString()
}
