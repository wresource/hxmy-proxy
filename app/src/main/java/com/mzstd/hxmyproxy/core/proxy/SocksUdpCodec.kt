package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.rules.RuleInput
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/** RFC1928 datagrams. Fragmentation is explicitly unsupported: every FRAG != 0 is discarded. */
internal object SocksUdpCodec {
    const val MAX_WIRE_BYTES = 65507
    data class Packet(val host: String, val port: Int, val payload: ByteArray)

    fun decode(bytes: ByteBuffer): Packet? {
        if (bytes.remaining() > MAX_WIRE_BYTES) return null
        if (bytes.remaining() < 4 || bytes.get().toInt() != 0 || bytes.get().toInt() != 0 || bytes.get().toInt() != 0) return null
        val host = when (bytes.get().toInt() and 255) {
            1 -> address(bytes, 4)
            4 -> address(bytes, 16)
            3 -> {
                if (!bytes.hasRemaining()) return null
                val size = bytes.get().toInt() and 255
                if (size == 0 || bytes.remaining() < size + 2) return null
                val name = ByteArray(size).also { bytes.get(it) }
                if (name.any { (it.toInt() and 255) !in 33..126 }) return null
                val text = String(name, Charsets.US_ASCII)
                if (text.any { it in "/?#@%\\=*:" }) return null
                val parsed = RuleInput.parse(text) ?: return null
                if (parsed.fromUrl || parsed.domainSuffix || parsed.host.contains('/') || (parsed.isIp && '-' in text)) return null
                parsed.host
            }
            else -> null
        } ?: return null
        if (bytes.remaining() < 2) return null
        val port = bytes.short.toInt() and 65535
        if (port == 0 || bytes.remaining() > ConnectUdpProtocol.MAX_PAYLOAD) return null
        return Packet(host, port, ByteArray(bytes.remaining()).also { bytes.get(it) })
    }

    private fun address(bytes: ByteBuffer, length: Int): String? {
        if (bytes.remaining() < length + 2) return null
        return InetAddress.getByAddress(ByteArray(length).also { bytes.get(it) }).hostAddress
    }

    fun encode(peer: InetSocketAddress, payload: ByteArray): ByteArray {
        val address = peer.address.address
        return ByteBuffer.allocate(6 + address.size + payload.size).apply {
            putShort(0); put(0); put((if (address.size == 4) 1 else 4).toByte())
            put(address); putShort(peer.port.toShort()); put(payload)
        }.array()
    }

    fun boundReply(peer: InetSocketAddress): ByteArray = encode(peer, ByteArray(0)).also { it[0] = 5 }
}
