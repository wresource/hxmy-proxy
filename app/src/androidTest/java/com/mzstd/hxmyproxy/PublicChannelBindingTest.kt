package com.mzstd.hxmyproxy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PublicChannelBindingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun productionChannelUsesTheSamePhysicalSourceAsSocketFactory() = runBlocking {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val networks = cm.allNetworks.filter { network -> cm.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true }
        assertTrue("A physical test network is required", networks.isNotEmpty())
        val connector = OutboundConnector(DefaultEgressGuard())
        for (network in networks) {
            val address = cm.getLinkProperties(network)!!.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().first()
            ServerSocket(0, 4, address).use { server ->
                server.soTimeout = 5000
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val echo = executor.submit {
                        repeat(2) { server.accept().use { socket ->
                            socket.soTimeout = 3000
                            val value = socket.getInputStream().read()
                            check(value == 0x5a)
                            socket.getOutputStream().write(value)
                        } }
                    }
                    fun roundTrip(socket: Socket): InetAddress {
                        socket.soTimeout = 3000
                        socket.getOutputStream().write(0x5a)
                        assertEquals(0x5a, socket.getInputStream().read())
                        return socket.localAddress
                    }
                    val factorySource = network.socketFactory.createSocket().use {
                        it.connect(InetSocketAddress(address, server.localPort), 3000)
                        roundTrip(it)
                    }
                    val channelSource = connector.connectAnyChannel(listOf(address), server.localPort, network).use { roundTrip(it.socket()) }
                    assertEquals("The selected physical network must be preserved", factorySource, channelSource)
                    assertEquals(address, channelSource)
                    echo.get(5, TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun unavailableNetworkKeepsTheBlockingFallbackSignalAndDoesNotLeakChannels() = runBlocking {
        val network = Network.fromNetworkHandle((999999L shl 32) or 0xcafed00dL)
        assertFalse(context.getSystemService(ConnectivityManager::class.java).allNetworks.contains(network))
        val connector = OutboundConnector(DefaultEgressGuard())
        suspend fun rejected() {
            val failure = runCatching {
                connector.connectAnyChannel(listOf(InetAddress.getByName("203.0.113.7")), 443, network).close()
            }.exceptionOrNull()
            assertTrue("Keep IOException for the existing socketFactory fallback: $failure", failure is IOException && failure !is ProxyException)
        }
        repeat(5) { rejected() }
        val before = File("/proc/self/fd").list()!!.size
        repeat(50) { rejected() }
        val after = File("/proc/self/fd").list()!!.size
        assertTrue("Failed channel creations must close their descriptors: $before -> $after", after <= before + 4)
    }
}
