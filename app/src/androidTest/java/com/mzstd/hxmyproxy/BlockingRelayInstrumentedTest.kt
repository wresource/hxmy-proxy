package com.mzstd.hxmyproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.proxy.DuplexSocketStreams
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/** Android's actual SocketAdaptor differs from the host JDK. Never substitute JVM-only proof. */
@RunWith(AndroidJUnit4::class)
class BlockingRelayInstrumentedTest {
    private fun pair(): Pair<Socket, SocketChannel> = ServerSocketChannel.open().use { listener ->
        listener.bind(InetSocketAddress("127.0.0.1", 0))
        val peer = Socket("127.0.0.1", (listener.localAddress as InetSocketAddress).port).apply { soTimeout = 3000 }
        peer to listener.accept()
    }

    @Test fun waitingReaderDoesNotBlockReverseWriteAndHalfCloseStillDrains(): Unit = runBlocking {
        val (peer, channel) = pair()
        try {
            DuplexSocketStreams(channel.socket(), 2000).use { streams ->
                val reader = async(Dispatchers.IO) { streams.input.read() }
                delay(100)
                streams.output.write(byteArrayOf(41, 42, 43))
                val answer = ByteArray(3)
                DataInputStream(peer.getInputStream()).readFully(answer)
                assertArrayEquals(byteArrayOf(41, 42, 43), answer)
                peer.getOutputStream().write(99)
                assertEquals(99, withTimeout(1000) { reader.await() })
                peer.shutdownOutput()
                assertEquals(-1, streams.input.read())
                val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
                val writer = async(Dispatchers.IO) { streams.output.write(payload); channel.shutdownOutput() }
                val received = ByteArray(payload.size)
                DataInputStream(peer.getInputStream()).readFully(received)
                assertArrayEquals(payload, received)
                assertEquals(-1, peer.getInputStream().read())
                withTimeout(3000) { writer.await() }
            }
        } finally { channel.close(); peer.close() }
    }

    @Test fun idleCancellationAndRepeatedCloseReleaseSelectorDescriptors(): Unit = runBlocking {
        val (idlePeer, idleChannel) = pair()
        try {
            DuplexSocketStreams(idleChannel.socket(), 80).use { streams ->
                val started = System.nanoTime()
                assertTrue(runCatching { streams.input.read() }.exceptionOrNull() is SocketTimeoutException)
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
            }
        } finally { idlePeer.close(); idleChannel.close() }
        val before = File("/proc/self/fd").list()!!.size
        repeat(20) {
            val (client, relayClient) = pair(); val (upstream, relayUpstream) = pair()
            val relay = launch(Dispatchers.IO) {
                RelayEngine().relay(relayClient.socket(), relayUpstream.socket(), 1024, 0, Dispatchers.IO)
            }
            try {
                client.getOutputStream().write(87)
                assertEquals(87, upstream.getInputStream().read())
                withTimeout(2000) { relay.cancelAndJoin() }
                assertFalse(relayClient.isOpen); assertFalse(relayUpstream.isOpen)
            } finally {
                client.close(); upstream.close(); relayClient.close(); relayUpstream.close()
                relay.cancelAndJoin()
            }
        }
        val after = File("/proc/self/fd").list()!!.size
        assertTrue("selector descriptors must not accumulate: $before -> $after", after <= before + 4)
    }
}
