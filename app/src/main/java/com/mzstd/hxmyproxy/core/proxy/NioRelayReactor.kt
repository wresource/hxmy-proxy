package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 非阻塞 relay 反应堆：少量 selector 线程支撑大量隧道，**线程数与隧道数解耦**——替代 [RelayEngine]
 * 的「每隧道 2 个阻塞线程」模型（拉满崩溃的根因）。
 *
 * 每隧道 = client + upstream 两个 **已连接、已切非阻塞** 的 [SocketChannel]，落同一 [SelectorWorker]
 * （免跨 selector 协调 interestOps）。背压双向联动、EOF 半关、idle sweep、协程取消即时拆隧道。
 *
 * 约束：传入 [relay] 的两 channel 必须 `configureBlocking(false)`（否则 register 抛、隧道立即关）。
 */
class NioRelayReactor(
    workerCount: Int = 1,
    sweepIntervalMs: Long = 1000,
) {
    private val workers = List(workerCount.coerceAtLeast(1)) { SelectorWorker("hxmy-nio-relay-$it", sweepIntervalMs) }
    private val rr = AtomicInteger(0)
    @Volatile private var started = false

    @Synchronized fun start() {
        if (started) return
        workers.forEach { it.start() }
        started = true
    }

    @Synchronized fun stop() {
        workers.forEach { it.stop() }
        started = false
    }

    /**
     * 双向转发已连接的非阻塞 [client]/[upstream]，挂起直到隧道结束（双向 EOF / idle 超时 / 出错 / 协程取消）。
     * 结束后两 channel 均被关闭。[onTraffic] 计 (up=client→upstream, down=upstream→client) 实际转发字节。
     */
    suspend fun relay(
        client: SocketChannel,
        upstream: SocketChannel,
        bufferBytes: Int,
        idleMillis: Int,
        onTraffic: (Long, Long) -> Unit,
    ) {
        val worker = workers[(rr.getAndIncrement() and Int.MAX_VALUE) % workers.size]
        worker.relay(client, upstream, bufferBytes, idleMillis.toLong(), onTraffic)
    }
}

/** 单 selector 线程：所有 register/改 interest/close 都在本线程内执行（投递任务队列 + wakeup）。 */
private class SelectorWorker(name: String, private val sweepMs: Long) {
    private val selector: Selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val tunnels = ConcurrentHashMap.newKeySet<Tunnel>()
    private val thread = Thread({ loop() }, name).apply { isDaemon = true }
    @Volatile private var running = true

    fun start() = thread.start()

    fun stop() {
        running = false
        selector.wakeup()
    }

    suspend fun relay(
        client: SocketChannel,
        upstream: SocketChannel,
        bufferBytes: Int,
        idleMs: Long,
        onTraffic: (Long, Long) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val tunnel = Tunnel(client, upstream, bufferBytes, idleMs, onTraffic, cont)
        // 取消（协程取消 / ProxyServer.stop 级联）→ 投递到 selector 线程拆隧道。
        cont.invokeOnCancellation { enqueue { tunnel.close(this) } }
        // 注册必须在 selector 线程（register 与 select 互斥，跨线程直接 register 会卡死）。
        enqueue {
            try {
                tunnel.register(selector)
                tunnels.add(tunnel)
            } catch (e: Throwable) {
                tunnel.close(this)
            }
        }
    }

    private fun enqueue(task: () -> Unit) {
        tasks.add(task)
        selector.wakeup()
    }

    fun untrack(t: Tunnel) = tunnels.remove(t)

    /** 统一重算某 channel 的 interestOps：它同时是一方向的 src（OP_READ）、另一方向的 dst（OP_WRITE）。 */
    fun rebuildInterest(ctx: ChannelCtx) {
        val key = ctx.key ?: return
        if (!key.isValid) return
        var ops = 0
        if (ctx.wantRead) ops = ops or SelectionKey.OP_READ
        if (ctx.wantWrite) ops = ops or SelectionKey.OP_WRITE
        if (key.interestOps() != ops) key.interestOps(ops)
    }

    private fun loop() {
        try {
            var lastSweep = System.nanoTime()
            while (running) {
                drainTasks()
                selector.select(sweepMs)
                if (!running) break
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()                      // 必须手动移除，否则下轮重复处理
                    if (!key.isValid) continue
                    val ctx = key.attachment() as ChannelCtx
                    try {
                        if (key.isReadable) ctx.tunnel.onReadable(ctx, this)
                        if (key.isValid && key.isWritable) ctx.tunnel.onWritable(ctx, this)
                    } catch (e: Throwable) {
                        ctx.tunnel.close(this)       // 对端 reset / 写错等 → 拆隧道
                    }
                }
                val now = System.nanoTime()
                if (now - lastSweep >= sweepMs * 1_000_000L) {
                    sweepIdle(now)
                    lastSweep = now
                }
            }
        } finally {
            tunnels.toList().forEach { it.close(this) }   // 收尾：拆全部隧道（resume 各自协程）
            runCatching { selector.close() }
        }
    }

    private fun drainTasks() {
        var t = tasks.poll()
        while (t != null) { runCatching { t() }; t = tasks.poll() }
    }

    private fun sweepIdle(now: Long) {
        tunnels.toList().forEach { if (it.idleExpired(now)) it.close(this) }
    }
}

/** 一个 channel 的 selector 上下文：既是 [asSrc] 方向的源（读入），又是 [asDst] 方向的目的（写出）。 */
private class ChannelCtx(val channel: SocketChannel) {
    var key: SelectionKey? = null
    lateinit var tunnel: Tunnel
    lateinit var asSrc: Pipe       // 以本 channel 为源的方向
    lateinit var asDst: Pipe       // 以本 channel 为目的的方向

    /** 想读：作为源、未在背压(draining)、未 EOF。 */
    val wantRead: Boolean get() = !asSrc.draining && !asSrc.srcEof
    /** 想写：作为目的、有待写字节(draining)。 */
    val wantWrite: Boolean get() = asDst.draining
}

/** 单方向字节流 src→dst，持一个 ByteBuffer。draining=缓冲有数据待写出（背压信号）。 */
private class Pipe(
    val src: ChannelCtx,
    val dst: ChannelCtx,
    val buf: ByteBuffer,
    val onBytes: (Long) -> Unit,
) {
    var draining = false       // buf 处于读模式、尚有字节待写到 dst（src 暂停读、dst 需 OP_WRITE）
    var srcEof = false         // src 读到 EOF
    var done = false           // 本方向彻底结束（EOF 且 buf 排空、已 shutdownOutput dst）
}

private class Tunnel(
    client: SocketChannel,
    upstream: SocketChannel,
    bufferBytes: Int,
    private val idleMs: Long,
    onTraffic: (Long, Long) -> Unit,
    private val cont: CancellableContinuation<Unit>,
) {
    private val cCtx = ChannelCtx(client)
    private val uCtx = ChannelCtx(upstream)
    // up: client→upstream（计 up）；down: upstream→client（计 down）
    private val up = Pipe(cCtx, uCtx, ByteBuffer.allocate(bufferBytes)) { onTraffic(it, 0) }
    private val down = Pipe(uCtx, cCtx, ByteBuffer.allocate(bufferBytes)) { onTraffic(0, it) }
    @Volatile private var lastActivity = System.nanoTime()
    private val closed = AtomicBoolean(false)

    init {
        cCtx.tunnel = this; cCtx.asSrc = up; cCtx.asDst = down
        uCtx.tunnel = this; uCtx.asSrc = down; uCtx.asDst = up
    }

    /** 在 selector 线程注册两 channel（初始都想读）。 */
    fun register(selector: Selector) {
        cCtx.key = cCtx.channel.register(selector, SelectionKey.OP_READ, cCtx)
        uCtx.key = uCtx.channel.register(selector, SelectionKey.OP_READ, uCtx)
    }

    fun onReadable(ctx: ChannelCtx, w: SelectorWorker) {
        val pipe = ctx.asSrc
        if (pipe.draining || pipe.srcEof) return        // 背压中/已 EOF（理论不应就绪），防御
        val n = ctx.channel.read(pipe.buf)
        when {
            n == -1 -> { pipe.srcEof = true; finishIfDrained(pipe, w) }
            n > 0 -> {
                touch()
                pipe.buf.flip()
                pipe.draining = true
                drain(pipe, w)
            }
            // n == 0：无数据，保持 OP_READ
        }
    }

    fun onWritable(ctx: ChannelCtx, w: SelectorWorker) {
        val pipe = ctx.asDst
        if (pipe.draining) drain(pipe, w)
    }

    /** 把 [pipe].buf 写入 dst；写满→保持背压，排空→解背压（并在 src 已 EOF 时半关）。 */
    private fun drain(pipe: Pipe, w: SelectorWorker) {
        val wrote = pipe.dst.channel.write(pipe.buf)
        if (wrote > 0) { touch(); pipe.onBytes(wrote.toLong()) }
        if (pipe.buf.hasRemaining()) {
            // 没写完：dst 发送缓冲满 → 保持 draining（dst OP_WRITE on、src OP_READ off）。
        } else {
            pipe.buf.clear()
            pipe.draining = false
            if (pipe.srcEof) finishIfDrained(pipe, w)
        }
        w.rebuildInterest(pipe.src)
        w.rebuildInterest(pipe.dst)
    }

    /** src EOF 且 buf 已排空 → 半关 dst 写端（对端读到 EOF），本方向 done；两向皆 done → 整条关闭。 */
    private fun finishIfDrained(pipe: Pipe, w: SelectorWorker) {
        if (!pipe.srcEof || pipe.draining || pipe.done) return
        runCatching { pipe.dst.channel.shutdownOutput() }
        pipe.done = true
        w.rebuildInterest(pipe.src)
        w.rebuildInterest(pipe.dst)
        if (up.done && down.done) close(w)
    }

    fun idleExpired(now: Long): Boolean = idleMs > 0 && (now - lastActivity) > idleMs * 1_000_000L

    private fun touch() { lastActivity = System.nanoTime() }

    /** 幂等关闭：cancel key + close 两 channel + untrack + resume 协程（仅一次）。在 selector 线程调用。 */
    fun close(w: SelectorWorker) {
        if (!closed.compareAndSet(false, true)) return
        cCtx.key?.cancel(); uCtx.key?.cancel()
        cCtx.channel.closeQuietly(); uCtx.channel.closeQuietly()
        w.untrack(this)
        if (cont.isActive) cont.resume(Unit)
    }
}
