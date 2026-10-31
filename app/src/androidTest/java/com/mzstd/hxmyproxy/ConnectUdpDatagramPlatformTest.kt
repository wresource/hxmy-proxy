package com.mzstd.hxmyproxy

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.proxy.AndroidDatagramEndpointFactory
import com.mzstd.hxmyproxy.core.proxy.DatagramSendResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class ConnectUdpDatagramPlatformTest {
    @Test fun publicChannelBindingAndNoFragmentOptions(): Unit {
        assumeTrue("Explicit owned emulator fixture required", Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("controlledUdpFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        for (host in listOf("198.18.0.7", "2001:db8:77::7")) {
            AndroidDatagramEndpointFactory.open(InetAddress.getByName(host), 53000, vpn).use { endpoint ->
                assertEquals(DatagramSendResult.TOO_LARGE, endpoint.send(ByteArray(2000)))
                assertEquals(DatagramSendResult.SENT, endpoint.send(ByteArray(0)))
                val buffer = ByteBuffer.allocate(2048)
                val deadline = System.nanoTime() + 2_000_000_000L
                var received = endpoint.channel.receive(buffer)
                while (received == null && System.nanoTime() < deadline) { Thread.sleep(5); received = endpoint.channel.receive(buffer) }
                assertNotNull("Bound datagram must reach controlled VPN: $host", received)
                buffer.flip()
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                assertArrayEquals((if (':' in host) "VPN6|" else "VPN4|").toByteArray(), bytes)
            }
        }
    }
}
