package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.proxy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import io.mockk.*
import org.junit.After
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayCloseEvidenceTest {
    private var wasLogging = false
    @Before fun rememberLogging() { wasLogging = FileLog.enabled }
    @After fun restoreLogging() { FileLog.enabled = wasLogging; RequestActivity.store.setEnabled(false) }
    private fun pair(): Pair<SocketChannel, SocketChannel> = ServerSocketChannel.open().use {
        it.bind(InetSocketAddress("127.0.0.1", 0))
        val peer = SocketChannel.open(it.localAddress)
        peer to it.accept()
    }

    @Test(timeout = 15000) fun halfCloseThenUpstreamResetIsFailureEvenAfterTransferredBytes() = runBlocking { reset(true) }
    @Test(timeout = 15000) fun blockingFallbackRecordsTheSameHalfCloseThenResetCause() = runBlocking { reset(false) }

    private suspend fun reset(nio: Boolean) = coroutineScope {
        val dir = Files.createTempDirectory("hxmy-relay-cause").toFile()
        FileLog.enabled = true; FileLog.init(dir)
        RequestActivity.store.setEnabled(true); RequestActivity.store.reset()
        val (client, relayClient) = pair()
        val (upstream, relayUpstream) = pair()
        val reactor = NioRelayReactor(1, 20).apply { start() }
        val trace = RequestTrace.open("HTTP", client.socket().localPort)
        trace.rule("reset.example", "PROXY", "DEFAULT")
        if (nio) { relayClient.configureBlocking(false); relayUpstream.configureBlocking(false) }
        val job = launch(Dispatchers.IO) {
            if (nio) reactor.relay(relayClient, relayUpstream, 1024, 3000, "reset.example", trace) { _, _ -> }
            else RelayEngine().relay(relayClient.socket(), relayUpstream.socket(), 1024, 3000, Dispatchers.IO,
                host = "reset.example", trace = trace)
        }
        try {
            client.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
            upstream.socket().soTimeout = 3000
            val bytes = upstream.socket().getInputStream().readNBytes(3)
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
            client.shutdownOutput()
            assertEquals(-1, upstream.socket().getInputStream().read())
            upstream.socket().setSoLinger(true, 0); upstream.close()
            withTimeout(5000) { job.join() }
            val record = RequestActivity.store.snapshot().single { it.requestId == trace.id }
            assertEquals("earlier half-close is not the terminal cause", RequestOutcome.FAILED, record.outcome)
            assertEquals(3L, record.uploadBytes)
            FileLog.flush()
            val log = dir.resolve("app.log").readText()
            assertTrue(log.contains("why=io-error"))
            assertTrue(log.contains("side=upstream"))
            assertTrue(log.contains("op=read"))
            assertTrue(log.contains("firstEof=client"))
            assertEquals(1, Regex("evt=req.closed id=${trace.id} ").findAll(log).count())
        } finally {
            client.close(); upstream.close(); relayClient.close(); relayUpstream.close()
            job.cancelAndJoin(); reactor.stop()
            RequestActivity.store.setEnabled(false)
        }
    }

    @Test(timeout = 10000) fun cancellationDoesNotBecomeIoFailureDuringSocketCleanup() = runBlocking {
        for (nio in listOf(false, true)) {
            RequestActivity.store.setEnabled(true); RequestActivity.store.reset()
            val (client, relayClient) = pair(); val (upstream, relayUpstream) = pair()
            val reactor = NioRelayReactor(1, 20).apply { start() }
            val trace = RequestTrace.open("SOCKS5", client.socket().localPort)
            trace.rule("cancel.example", "PROXY", "DEFAULT")
            if (nio) { relayClient.configureBlocking(false); relayUpstream.configureBlocking(false) }
            val job = launch(Dispatchers.IO) {
                if (nio) reactor.relay(relayClient, relayUpstream, 1024, 0, "cancel.example", trace) { _, _ -> }
                else RelayEngine().relay(relayClient.socket(), relayUpstream.socket(), 1024, 0, Dispatchers.IO,
                    host = "cancel.example", trace = trace)
            }
            try {
                // Prove pumps are running before cancellation, then leave both reads idle.
                client.write(ByteBuffer.wrap(byteArrayOf(42)))
                upstream.socket().soTimeout = 2000
                assertEquals(42, upstream.socket().getInputStream().read())
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(ProxyTestKit.await(2000) { !relayClient.isOpen && !relayUpstream.isOpen })
                val record = RequestActivity.store.snapshot().single { it.requestId == trace.id }
                assertNotEquals(RequestOutcome.FAILED, record.outcome)
                assertEquals("cancelled", record.stage)
            } finally {
                client.close(); upstream.close(); relayClient.close(); relayUpstream.close()
                job.cancelAndJoin(); reactor.stop(); RequestActivity.store.setEnabled(false)
            }
        }
    }

    @Test fun directionalEofNeverMasksFirstTerminalErrorOrGetsReplacedByCleanup() {
        val evidence = RelayCloseEvidence()
        evidence.observedEof("client")
        evidence.terminate("io-error", "write", "upstream", java.net.SocketException("do not log private text"))
        evidence.terminate("cancelled")
        evidence.cleanupFailure("close", "client", java.io.IOException("cleanup"))
        assertEquals("client", evidence.firstEof)
        assertEquals("io-error", evidence.cause().reason)
        assertEquals("write", evidence.cause().operation)
        assertEquals("SocketException", evidence.cause().errorType)
        assertTrue(evidence.cleanup!!.contains("IOException"))
    }

    @Test(timeout = 10000) fun accountingFailureIsNotBlamedOnRemoteWrite() = runBlocking {
        for (nio in listOf(false, true)) {
            RequestActivity.store.setEnabled(true); RequestActivity.store.reset()
            val (client, relayClient) = pair(); val (upstream, relayUpstream) = pair()
            val reactor = NioRelayReactor(1, 20).apply { start() }
            val trace = RequestTrace.open("HTTP", client.socket().localPort)
            trace.rule("callback.example", "PROXY", "DEFAULT")
            if (nio) { relayClient.configureBlocking(false); relayUpstream.configureBlocking(false) }
            val callback: (Long, Long) -> Unit = { _, _ -> error("controlled accounting failure") }
            val job = launch(Dispatchers.IO) {
                if (nio) reactor.relay(relayClient, relayUpstream, 1024, 0, "callback.example", trace, callback)
                else RelayEngine().relay(relayClient.socket(), relayUpstream.socket(), 1024, 0, Dispatchers.IO,
                    callback, "callback.example", trace)
            }
            try {
                client.write(ByteBuffer.wrap(byteArrayOf(42)))
                upstream.socket().soTimeout = 2000
                assertEquals(42, upstream.socket().getInputStream().read())
                withTimeout(2000) { job.join() }
                val record = RequestActivity.store.snapshot().single { it.requestId == trace.id }
                assertEquals(RequestOutcome.FAILED, record.outcome)
                assertEquals("relay-error:accounting", record.stage)
                assertEquals(1L, record.uploadBytes)
            } finally {
                client.close(); upstream.close(); relayClient.close(); relayUpstream.close()
                job.cancelAndJoin(); reactor.stop()
            }
        }
    }

    @Test(timeout = 10000) fun observerFailureCannotPreventSocketCleanupOrContinuationResume() = runBlocking {
        val (client, relayClient) = pair(); val (upstream, relayUpstream) = pair()
        val reactor = NioRelayReactor(1, 20).apply { start() }
        val trace = mockk<RequestTrace>(relaxed = true) {
            every { relayStarted(any(), any()) } throws IllegalStateException("observer failed at open")
            every { tunnelClosed(any(), any<RelayCloseEvidence>(), any(), any()) } throws IllegalStateException("observer failed at close")
        }
        relayClient.configureBlocking(false); relayUpstream.configureBlocking(false)
        val job = launch(Dispatchers.IO) { reactor.relay(relayClient, relayUpstream, 1024, 0, "observer.example", trace) { _, _ -> } }
        try {
            client.shutdownOutput(); upstream.shutdownOutput()
            withTimeout(2000) { job.join() }
            assertFalse(relayClient.isOpen); assertFalse(relayUpstream.isOpen)
        } finally {
            client.close(); upstream.close(); relayClient.close(); relayUpstream.close()
            job.cancelAndJoin(); reactor.stop()
        }
    }

    @Test(timeout = 10000) fun cancellationBeforeExternalCloseIsRecognizedEvenWhenOwnerCannotResumeYet() = runBlocking {
        RequestActivity.store.setEnabled(true); RequestActivity.store.reset()
        val (client, relayClient) = pair(); val (upstream, relayUpstream) = pair()
        val ownerBlocked = CountDownLatch(1); val releaseOwner = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val trace = RequestTrace.open("HTTP", client.socket().localPort)
        trace.rule("stop-race.example", "PROXY", "DEFAULT")
        val job = launch(dispatcher) {
            RelayEngine().relay(relayClient.socket(), relayUpstream.socket(), 1024, 0, Dispatchers.IO,
                host = "stop-race.example", trace = trace)
        }
        try {
            client.write(ByteBuffer.wrap(byteArrayOf(42)))
            upstream.socket().soTimeout = 2000
            assertEquals(42, upstream.socket().getInputStream().read())
            executor.submit { ownerBlocked.countDown(); releaseOwner.await() }
            assertTrue(ownerBlocked.await(2, TimeUnit.SECONDS))
            job.cancel() // parent state changes immediately, continuation is deliberately blocked
            relayClient.close() // TcpProxyServerBase.stop closes accepted sockets after cancel
            assertEquals("pump has observed close and completed cleanup", -1, upstream.socket().getInputStream().read())
            releaseOwner.countDown()
            withTimeout(2000) { job.join() }
            val record = RequestActivity.store.snapshot().single { it.requestId == trace.id }
            assertNotEquals(RequestOutcome.FAILED, record.outcome)
            assertEquals("cancelled", record.stage)
        } finally {
            releaseOwner.countDown(); client.close(); upstream.close(); relayClient.close(); relayUpstream.close()
            job.cancelAndJoin(); dispatcher.close(); executor.shutdownNow()
        }
    }
}
