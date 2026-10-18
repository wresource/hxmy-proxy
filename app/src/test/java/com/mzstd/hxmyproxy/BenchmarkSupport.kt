package com.mzstd.hxmyproxy

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** 仅用于本地基准的所有权；先关闭socket解除阻塞，再中断并等待工作线程退出。 */
internal class BenchmarkResources : Closeable {
    private val lock = Any()
    private var closed = false
    private val sockets = mutableListOf<Closeable>()
    private val workers = mutableListOf<Thread>()
    fun <T : Closeable> own(resource: T): T = synchronized(lock) {
        if (closed) { resource.close(); error("benchmark already closed") }
        sockets += resource
        resource
    }
    fun launch(name: String, action: () -> Unit): Thread = synchronized(lock) {
        check(!closed)
        thread(start = false, isDaemon = true, name = name, block = action).also {
            workers += it
            it.start()
        }
    }
    override fun close() {
        val threads = synchronized(lock) {
            if (closed) return
            closed = true
            sockets.forEach { runCatching { it.close() } }
            workers.toList()
        }
        threads.forEach { it.interrupt() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        threads.forEach {
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) it.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
        }
        check(threads.none { it.isAlive }) { "benchmark worker leaked: ${threads.filter { it.isAlive }.map { it.name }}" }
    }
}

internal object BenchmarkSupport {
    private val currentResources = ThreadLocal<BenchmarkResources>()

    fun socket(port: Int, resources: BenchmarkResources? = currentResources.get()): Socket {
        val socket = Socket()
        resources?.own(socket)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
            return socket
        } catch (error: Throwable) { socket.close(); throw error }
    }

    fun pingPong(socket: Socket): DoubleArray {
        val out = socket.getOutputStream(); val input = socket.getInputStream()
        fun exchange() {
            out.write(0x41); out.flush()
            val actual = input.read()
            if (actual < 0) throw EOFException("echo closed without a response")
            check(actual == 0x41) { "bad echo byte: $actual" }
        }
        repeat(100) { exchange() }
        val samples = LongArray(500) {
            val start = System.nanoTime(); exchange(); System.nanoTime() - start
        }.sorted()
        return doubleArrayOf(samples[250] / 1000.0, samples[495] / 1000.0)
    }

    fun validate(bytes: ByteArray, count: Int, offset: Long) {
        for (i in 0 until count) check(bytes[i].toInt() == ((offset + i) and 0x7f).toInt()) {
            "payload mismatch at ${offset + i}"
        }
    }

    fun drain(input: InputStream, bytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        while (received < bytes) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), bytes - received).toInt())
            if (n < 0) throw EOFException("received $received of $bytes bytes")
            validate(buffer, n, received)
            received += n
        }
        return received
    }

    fun measure(n: Int, bytesPerConnection: Long, timeoutMs: Long = 120000, task: () -> Long): Double {
        require(n > 0 && bytesPerConnection > 0)
        return BenchmarkResources().use { resources ->
            val ready = CountDownLatch(n); val go = CountDownLatch(1); val done = CountDownLatch(n)
            val failed = AtomicReference<Throwable?>()
            val received = LongArray(n)
            repeat(n) { i -> resources.launch("bench-load-$i") {
                try {
                    currentResources.set(resources)
                    ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                    received[i] = task()
                } catch (error: Throwable) { failed.compareAndSet(null, error) }
                finally { currentResources.remove(); done.countDown() }
            } }
            check(ready.await(5, TimeUnit.SECONDS)) { "load workers not ready" }
            val start = System.nanoTime() // 时钟先启动，再放行工作线程。
            go.countDown()
            check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "load deadline exceeded" }
            val elapsed = System.nanoTime() - start
            failed.get()?.let { throw AssertionError("load failed", it) }
            received.forEach { check(it == bytesPerConnection) { "wrong byte count: $it" } }
            received.sum() / 1048576.0 / (elapsed / 1e9)
        }
    }

    /** 所有背景连接收到且校验第一批字节后才开始测量；任何中途退出都使该样本无效。 */
    fun <T> withBackground(n: Int, open: (BenchmarkResources) -> Socket, block: () -> T): T {
        require(n >= 0)
        val stop = AtomicBoolean(false)
        val ready = CountDownLatch(n)
        val failures = ConcurrentLinkedQueue<Throwable>()
        return BenchmarkResources().use { resources ->
            try {
                repeat(n) { i -> resources.launch("bench-background-$i") {
                    var signaled = false
                    try {
                        open(resources).use { socket ->
                            val input = socket.getInputStream(); val buffer = ByteArray(64 * 1024)
                            var offset = 0L
                            while (!stop.get()) {
                                val count = input.read(buffer)
                                if (count < 0) throw EOFException("background ended during sample")
                                validate(buffer, count, offset); offset += count
                                if (!signaled) { signaled = true; ready.countDown() }
                            }
                        }
                    } catch (error: Throwable) { if (!stop.get()) failures.add(error) }
                    finally { if (!signaled) ready.countDown() }
                } }
                check(ready.await(10, TimeUnit.SECONDS)) { "background never became ready" }
                failures.peek()?.let { throw AssertionError("background setup failed", it) }
                val value = block()
                failures.peek()?.let { throw AssertionError("background failed during sample", it) }
                value
            } finally { stop.set(true) }
        }
    }

    fun median(samples: List<Double>): Double {
        require(samples.isNotEmpty())
        val sorted = samples.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** A/B和B/A交替，输出每轮原始值；不挑各自最快样本。 */
    fun paired(rounds: Int, proxy: () -> Double, direct: () -> Double): Pair<Double, Double> {
        val p = mutableListOf<Double>(); val d = mutableListOf<Double>()
        repeat(rounds) { round ->
            if (round % 2 == 0) { p += proxy(); d += direct() } else { d += direct(); p += proxy() }
            println("round=$round proxy=${p.last()} direct=${d.last()} ratio=${p.last() / d.last()}")
        }
        return median(p) to median(d)
    }
}

/** 一份实例代表一条共享容量；独立流模型应给每个连接各自一份。 */
internal class SharedBandwidth(private val bytesPerSecond: Long) {
    init { require(bytesPerSecond > 0) }
    private var next = System.nanoTime()
    fun transfer(bytes: Int) {
        val end = synchronized(this) {
            val now = System.nanoTime()
            next = maxOf(now, next) + bytes * 1_000_000_000L / bytesPerSecond
            next
        }
        while (true) {
            if (Thread.interrupted()) throw InterruptedException()
            val left = end - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(left)
        }
    }
}
