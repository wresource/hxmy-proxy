package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.proxy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files

class RelayIdleLifecycleTest {
    private fun channelPair(): Pair<Socket, Socket> = ServerSocketChannel.open().use {
        it.bind(InetSocketAddress("127.0.0.1", 0))
        val peer = SocketChannel.open(it.localAddress)
        peer.socket() to it.accept().socket()
    }
    private fun nativePair(): Pair<Socket, Socket> = ServerSocket(0, 1, ProxyTestKit.loopback).use {
        Socket(ProxyTestKit.loopback, it.localPort) to it.accept()
    }

    @Test(timeout = 12000) fun nativeSocketAndChannelFallbackKeepEitherActiveDirection() = runBlocking {
        for (upload in listOf(false, true)) {
            val (client, c) = channelPair(); val (server, u) = nativePair()
            val source = if (upload) client else server; val sink = if (upload) server else client
            sink.soTimeout = 4000
            val job = launch(Dispatchers.IO) { RelayEngine().relay(c, u, 4096, 400, Dispatchers.IO) }
            val writer = launch(Dispatchers.IO) { repeat(20) { source.getOutputStream().write(it); delay(80) }; source.shutdownOutput() }
            try {
                val actual = withContext(Dispatchers.IO) { sink.getInputStream().readNBytes(20) }
                assertArrayEquals(ByteArray(20) { it.toByte() }, actual)
                writer.join()
            } finally { listOf(client, c, server, u).forEach { runCatching { it.close() } }; writer.cancelAndJoin(); job.cancelAndJoin() }
        }
    }

    @Test(timeout = 10000) fun genuinelyQuietRelayClosesAndRecordsActualIdleWithoutAnError() = runBlocking {
        val dir = Files.createTempDirectory("relay-idle").toFile()
        val wasEnabled = FileLog.enabled
        FileLog.enabled = true; FileLog.init(dir); RequestActivity.store.setEnabled(true); RequestActivity.store.reset()
        val (client, c) = channelPair(); val (server, u) = nativePair()
        val trace = RequestTrace.open("HTTP", client.localPort).also { it.rule("quiet.example", "PROXY", "DEFAULT") }
        try {
            withTimeout(2500) { RelayEngine().relay(c, u, 1024, 180, Dispatchers.IO, host = "quiet.example", trace = trace) }
            assertTrue(c.isClosed); assertTrue(u.isClosed)
            assertEquals(RequestOutcome.CLOSED, RequestActivity.store.snapshot().single().outcome)
            val line = FileLog.snapshot().substringAfter("===== FULL LOG =====").lines().single { "evt=req.closed" in it }
            assertTrue(line, "why=idle" in line)
            assertTrue(line, Regex("idleMs=(\\d+)").find(line)!!.groupValues[1].toLong() >= 180)
            assertTrue(line, "idleLimitMs=180" in line && "clientReadAgoMs=" in line && "upstreamWriteAgoMs=" in line)
            assertFalse(line, "err=" in line)
        } finally {
            listOf(client, c, server, u).forEach { runCatching { it.close() } }
            RequestActivity.store.setEnabled(false); FileLog.clear(); FileLog.enabled = wasEnabled; dir.deleteRecursively()
        }
    }

    @Test(timeout = 10000) fun bothBlockedWritersStillReachIdleAndReleaseTheirPumps() = runBlocking {
        val (client, c) = channelPair(); val (server, u) = channelPair()
        listOf(client, server).forEach { it.receiveBufferSize = 1024 }
        listOf(c, u).forEach { it.sendBufferSize = 1024 }
        val relay = launch(Dispatchers.IO) { RelayEngine().relay(c, u, 32768, 250, Dispatchers.IO) }
        val writers = listOf(client, server).map { socket -> launch(Dispatchers.IO) {
            val chunk = ByteArray(32768)
            runCatching { repeat(1024) { socket.getOutputStream().write(chunk) } }
        } }
        try {
            withTimeout(5000) { relay.join() }
            assertTrue(c.isClosed); assertTrue(u.isClosed)
        } finally {
            listOf(client, c, server, u).forEach { runCatching { it.close() } }
            writers.forEach { it.cancelAndJoin() }; relay.cancelAndJoin()
        }
    }
}
