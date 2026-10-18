package com.mzstd.hxmyproxy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BenchmarkSupportTest {
    private fun <T> peer(serve: (Socket) -> Unit, test: (Int) -> T): T =
        BenchmarkResources().use { resources ->
            val listener = resources.own(ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")))
            resources.launch("bench-test-peer") {
                try { resources.own(listener.accept()).use(serve) } catch (_: IOException) { }
            }
            test(listener.localPort)
        }

    @Test fun genuineEchoProducesLatency() = peer({ s ->
        while (true) {
            val byte = s.getInputStream().read(); if (byte < 0) break
            s.getOutputStream().write(byte); s.getOutputStream().flush()
        }
    }) { port ->
        BenchmarkSupport.socket(port).use {
            val times = BenchmarkSupport.pingPong(it)
            assertTrue(times.all { value -> value.isFinite() && value > 0 })
        }
    }

    @Test fun halfClosedEchoCannotLookLikeFastLatency() = peer({ s ->
        s.shutdownOutput()
        while (s.getInputStream().read() >= 0) { }
    }) { port ->
        BenchmarkSupport.socket(port).use { assertThrows(EOFException::class.java) { BenchmarkSupport.pingPong(it) } }
        Unit
    }

    @Test fun wrongEchoCannotBecomeAValidSample() = peer({ s ->
        s.getInputStream().read(); s.getOutputStream().write(0x42)
    }) { port ->
        BenchmarkSupport.socket(port).use { assertThrows(IllegalStateException::class.java) { BenchmarkSupport.pingPong(it) } }
        Unit
    }

    @Test fun downloadsValidateCountAndContent() {
        val correct = ByteArray(256) { (it and 127).toByte() }
        assertEquals(256L, BenchmarkSupport.drain(ByteArrayInputStream(correct), 256))
        assertThrows(EOFException::class.java) { BenchmarkSupport.drain(ByteArrayInputStream(correct), 257) }
        correct[99] = 0
        assertThrows(IllegalStateException::class.java) { BenchmarkSupport.drain(ByteArrayInputStream(correct), 256) }
    }

    @Test fun failedBackgroundPreventsMeasurement() {
        var ran = false
        assertThrows(AssertionError::class.java) {
            BenchmarkSupport.withBackground(2, { throw IOException("cannot connect") }) { ran = true }
        }
        assertFalse(ran)
    }

    @Test fun backgroundThatDiesDuringMeasurementInvalidatesResult() {
        val closeNow = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        peer({ s ->
            s.getOutputStream().write(ByteArray(128) { it.toByte() }); s.getOutputStream().flush()
            check(closeNow.await(5, TimeUnit.SECONDS))
        }) { port ->
            assertThrows(AssertionError::class.java) {
                BenchmarkSupport.withBackground(1, { resources ->
                    worker.set(Thread.currentThread()); BenchmarkSupport.socket(port, resources)
                }) {
                    closeNow.countDown()
                    worker.get().join(3000)
                    assertFalse(worker.get().isAlive)
                    42
                }
            }
        }
    }

    @Test fun foregroundFailureAndDeadlineAreVisibleAndWorkersAreReclaimed() {
        assertThrows(AssertionError::class.java) { BenchmarkSupport.measure(2, 10) { throw IOException("load failed") } }
        val accepted = CountDownLatch(1)
        peer({ s -> accepted.countDown(); while (s.getInputStream().read() >= 0) { } }) { port ->
            assertThrows(IllegalStateException::class.java) {
                BenchmarkSupport.measure(1, 1, timeoutMs = 200) {
                    BenchmarkSupport.socket(port).use { it.getInputStream().read(); 1L }
                }
            }
            assertEquals(0L, accepted.count)
        }
    }

    @Test fun statisticsUseMedianAndAlternateOrder() {
        assertEquals(2.0, BenchmarkSupport.median(listOf(1.0, 100.0, 2.0)), 0.0)
        val order = mutableListOf<String>()
        val p = listOf(1.0, 2.0, 3.0, 100.0).iterator()
        val result = BenchmarkSupport.paired(4, { order += "P"; p.next() }, { order += "D"; 4.0 })
        assertEquals(listOf("P", "D", "D", "P", "P", "D", "D", "P"), order)
        assertEquals(2.5, result.first, 0.0)
    }

    @Test fun delayFixtureTransfersCheckedDataAndClosesAllWorkers() {
        BenchmarkOrigin(16384, 1).use { origin ->
            BenchmarkDelayLink(origin.port, 2, 4096).use { link ->
                BenchmarkSupport.socket(link.port).use {
                    it.getOutputStream().write("GO\n".toByteArray())
                    assertEquals(8192L, BenchmarkSupport.drain(it.getInputStream(), 8192))
                }
            }
        }
    }
}
