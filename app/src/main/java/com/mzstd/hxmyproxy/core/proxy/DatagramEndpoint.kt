package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

enum class DatagramSendResult { SENT, WOULD_BLOCK, TOO_LARGE }

/** Explicit platform boundary: JVM tests inject real JVM datagrams, never Android no-op stubs. */
interface DatagramEndpoint : Closeable {
    val channel: DatagramChannel
    val peer: InetSocketAddress
    /** Native association transport only; fixed HTTP datagrams remain peer-restricted. */
    val supportsMultipleTargets: Boolean get() = false
    fun send(payload: ByteArray): DatagramSendResult
    fun sendTo(payload: ByteArray, destination: InetSocketAddress): DatagramSendResult {
        require(destination == peer) { "Endpoint does not support transfer-port handoff" }
        return send(payload)
    }
}

fun interface DatagramEndpointFactory {
    fun open(address: InetAddress, port: Int, network: Network?): DatagramEndpoint
    /** Native SOCKS UDP is not constrained by RFC9298's HTTP datagram no-fragment rule. */
    fun openNativeSocks(address: InetAddress, port: Int, network: Network?): DatagramEndpoint = open(address, port, network)
}
internal class DatagramSetupException(val phase: String, cause: Throwable) : IOException("UDP $phase setup failed", cause)

/** Only public APIs. The duplicate fd is owned for the flow lifetime and counted in its budget. */
object AndroidDatagramEndpointFactory : DatagramEndpointFactory {
    override fun open(address: InetAddress, port: Int, network: Network?): DatagramEndpoint = open(address, port, network, false)
    override fun openNativeSocks(address: InetAddress, port: Int, network: Network?): DatagramEndpoint = open(address, port, network, true)
    private fun open(address: InetAddress, port: Int, network: Network?, nativeSocks: Boolean): DatagramEndpoint {
        val v4 = address is Inet4Address
        val channel = DatagramChannel.open(if (v4) StandardProtocolFamily.INET else StandardProtocolFamily.INET6)
        var descriptor: ParcelFileDescriptor? = null
        var phase = "network-bind"
        try {
            if (network != null) network.bindSocket(channel.socket())
            phase = "socket-options"
            channel.setOption(StandardSocketOptions.SO_BROADCAST, false)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, 256 * 1024)
            phase = "bind-local"
            // Android's InetSocketAddress(0) defaults to IPv6 even for an INET channel.
            // null lets the channel choose its own family-correct wildcard/ephemeral port.
            channel.bind(null)
            phase = "nonblocking"
            channel.configureBlocking(false)
            phase = "duplicate-fd"
            descriptor = ParcelFileDescriptor.fromDatagramSocket(channel.socket())
            // Linux UAPI socket options, used through public android.system.Os. No hidden fd API.
            // IPv4 PMTUDISC_DO sets DF; IPv6 DONTFRAG rejects local fragmentation.
            phase = "fragment-policy"
            if (v4) {
                Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_IP, IP_MTU_DISCOVER, if (nativeSocks) 0 else IP_PMTUDISC_DO)
            } else {
                Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_IPV6, IPV6_DONTFRAG, if (nativeSocks) 0 else 1)
                if (nativeSocks) Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_IPV6, IPV6_MTU_DISCOVER, 0)
            }
            return AndroidEndpoint(channel, InetSocketAddress(address, port), descriptor, nativeSocks)
        } catch (error: Throwable) {
            runCatching { descriptor?.close() }
            runCatching { channel.close() }
            throw DatagramSetupException(phase, error)
        }
    }

    private class AndroidEndpoint(
        override val channel: DatagramChannel,
        override val peer: InetSocketAddress,
        private val descriptor: ParcelFileDescriptor,
        override val supportsMultipleTargets: Boolean,
    ) : DatagramEndpoint {
        private var closed = false
        // Send is nonblocking. Synchronization prevents close/fd reuse between check and syscall.
        override fun send(payload: ByteArray): DatagramSendResult = sendTo(payload, peer)
        @Synchronized override fun sendTo(payload: ByteArray, destination: InetSocketAddress): DatagramSendResult {
            if (closed) throw ClosedChannelException()
            require(destination.port in 1..65535 && (destination.address == peer.address ||
                (supportsMultipleTargets && (destination.address is Inet4Address)==(peer.address is Inet4Address)))) { "UDP destination address changed" }
            if (payload.size > ConnectUdpProtocol.MAX_PAYLOAD) return DatagramSendResult.TOO_LARGE
            return try {
                val n = Os.sendto(descriptor.fileDescriptor, payload, 0, payload.size, 0, destination.address, destination.port)
                if (n != payload.size) throw IOException("Partial UDP datagram send")
                DatagramSendResult.SENT // including a real zero-byte send; no connected-socket shortcut
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EAGAIN -> DatagramSendResult.WOULD_BLOCK // EWOULDBLOCK aliases EAGAIN on Android/Linux
                    OsConstants.EMSGSIZE -> DatagramSendResult.TOO_LARGE
                    else -> throw IOException("UDP send failed (errno=${error.errno})", error)
                }
            }
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            try { descriptor.close() } finally { channel.close() }
        }
    }

    private const val IP_MTU_DISCOVER = 10
    private const val IP_PMTUDISC_DO = 2
    private const val IPV6_DONTFRAG = 62
    private const val IPV6_MTU_DISCOVER = 23
}

/** UDP adds broadcast/self-target restrictions; TCP's existing LAN policy is left intact. */
internal object DatagramTargetGuard {
    fun isAllowed(address: InetAddress): Boolean {
        if (address is Inet4Address && address.address.all { it == 0xff.toByte() }) return false
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            interfaces.toList().none { iface ->
                iface.inetAddresses.toList().any { it == address } || iface.interfaceAddresses.any { it.broadcast == address }
            }
        }.getOrDefault(false)
    }
}
