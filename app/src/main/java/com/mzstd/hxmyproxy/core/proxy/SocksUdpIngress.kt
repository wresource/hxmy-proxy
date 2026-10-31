package com.mzstd.hxmyproxy.core.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

interface SocksUdpIngress : Closeable {
    val channel: DatagramChannel
    fun send(payload: ByteArray, peer: InetSocketAddress): DatagramSendResult
}
fun interface SocksUdpIngressFactory : Closeable {
    fun open(local: InetAddress): SocksUdpIngress
    override fun close() {}
}

/** The client return path belongs to the ingress network, independently of target routing.
 * Binding only a local IP does NOT bypass an Android VPN (verified with a controlled TUN).
 * Unknown/tether-only interfaces fail explicitly instead of sending client replies into the VPN. */
class AndroidSocksUdpIngressFactory(context: Context) : SocksUdpIngressFactory {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Object()
    private var closed = false
    private val networks = LinkedHashMap<Network, LinkProperties>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = synchronized(lock) {
            if (!closed) { networks[network] = properties; lock.notifyAll() }
        }
        override fun onLost(network: Network) = synchronized(lock) { networks.remove(network); lock.notifyAll() }
    }
    init {
        // Ingress may be a LAN without INTERNET capability. Do not reuse the selected egress.
        val request = NetworkRequest.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) request.clearCapabilities()
        else {
            // Android 10 defaults to exactly NOT_RESTRICTED, TRUSTED and NOT_VPN.
            // clearCapabilities became public in API30; retain the API29 equivalent.
            request.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        }
        connectivity.registerNetworkCallback(request.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }
    private fun ingressNetwork(local: InetAddress): Network? = synchronized(lock) {
        val deadline = System.nanoTime() + 750_000_000L
        while (!closed) {
            networks.entries.firstOrNull { (_, properties) -> properties.linkAddresses.any { it.address == local } }
                ?.let { return@synchronized it.key }
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) break
            try { lock.wait(left) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("UDP ingress lookup interrupted", error)
            }
        }
        null
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; networks.clear(); lock.notifyAll() }
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
    override fun open(local: InetAddress): SocksUdpIngress {
        val network = ingressNetwork(local)
            ?: throw IOException("UDP ingress network unavailable for ${local.hostAddress}; use CONNECT-UDP")
        val v4 = local is Inet4Address
        val channel = DatagramChannel.open(if (v4) StandardProtocolFamily.INET else StandardProtocolFamily.INET6)
        var descriptor: ParcelFileDescriptor? = null
        try {
            network.bindSocket(channel.socket())
            channel.bind(InetSocketAddress(local, 0))
            channel.configureBlocking(false)
            val fd = ParcelFileDescriptor.fromDatagramSocket(channel.socket())
            descriptor = fd
            // SOCKS FRAG fields and ordinary kernel IP fragmentation are different layers.
            // Keep FRAG!=0 unsupported in the codec; permit normal IP fragments so a valid
            // large DNS/UDP reply is not silently dropped on an MTU1500 client LAN.
            if (v4) Os.setsockoptInt(fd.fileDescriptor, OsConstants.IPPROTO_IP, 10, 0)
            else {
                Os.setsockoptInt(fd.fileDescriptor, OsConstants.IPPROTO_IPV6, 62, 0)
                Os.setsockoptInt(fd.fileDescriptor, OsConstants.IPPROTO_IPV6, 23, 0)
            }
            return object : SocksUdpIngress {
                override val channel = channel
                private var closed = false
                @Synchronized override fun send(payload: ByteArray, peer: InetSocketAddress): DatagramSendResult {
                    if (closed) throw ClosedChannelException()
                    if (payload.size > SocksUdpCodec.MAX_WIRE_BYTES) return DatagramSendResult.TOO_LARGE
                    return try {
                        val n = Os.sendto(fd.fileDescriptor, payload, 0, payload.size, 0, peer.address, peer.port)
                        if (n != payload.size) throw IOException("Partial SOCKS UDP datagram send")
                        DatagramSendResult.SENT
                    } catch (error: ErrnoException) {
                        when (error.errno) {
                            OsConstants.EAGAIN -> DatagramSendResult.WOULD_BLOCK
                            OsConstants.EMSGSIZE -> DatagramSendResult.TOO_LARGE
                            else -> throw IOException("SOCKS UDP return failed (errno=${error.errno})", error)
                        }
                    }
                }
                @Synchronized override fun close() {
                    if (closed) return
                    closed = true
                    try { fd.close() } finally { channel.close() }
                }
            }
        } catch (failure: Throwable) {
            runCatching { descriptor?.close() }; runCatching { channel.close() }; throw failure
        }
    }
}
