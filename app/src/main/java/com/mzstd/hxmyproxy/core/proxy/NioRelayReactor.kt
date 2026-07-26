package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
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
    private val sweepIntervalMs: Long = 1000,
) {
    // Array 而非 List：worker 意外死亡时按槽位原地换新（见 aliveWorkerAt）。
    private val workers = Array(workerCount.coerceAtLeast(1)) { SelectorWorker("hxmy-nio-relay-$it", sweepIntervalMs) }
    private val rr = AtomicInteger(0)
    @Volatile private var started = false

    @Synchronized fun start() {
        if (started) return
        workers.forEach { it.start() }
        started = true
    }

    @Synchronized fun stop() {
        started = false
        // 先全部发停止信号，再逐个等收尾：worker 的 finally 会 resume 全部在途隧道协程。
        // 等收尾结束再返回，调用方（stopServers）才能安全 shutdownNow relay 线程池——
        // 否则 resume 分发会撞上已关闭的 dispatcher（RejectedExecutionException 竞态）。
        workers.forEach { it.signalStop() }
        workers.forEach { it.awaitStop(STOP_JOIN_MS) }
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
        val worker = aliveWorkerAt((rr.getAndIncrement() and Int.MAX_VALUE) % workers.size)
        worker.relay(client, upstream, bufferBytes, idleMillis.toLong(), onTraffic)
    }

    /** 各槽位上次重启时刻（ms）；冷却期内不再重建，防持续性崩溃源把重启变成风暴。 */
    private val lastRestartAt = LongArray(workers.size)

    /**
     * 取槽位 worker；发现线程已死（selector 异常崩溃）→ 原地换新并落 key.log。
     * 崩溃不再是永久失能：旧模型里 selector 线程死后新隧道全部永久挂起、且全程零日志
     * （7-26 排障确认的最大盲区之一）。以下情形返回死 worker 而不复活——也安全：
     * [SelectorWorker.relay] 的 enqueue 对死线程有调用线程兜底，隧道被立即拆掉（关两 channel +
     * resume）而非挂死：① reactor 已 stop；② 槽位在冷却期内；③ 新 worker 构造/启动失败
     * （Selector.open 需 3 个 FD，FD 耗尽——即最需要复活的时刻——恰恰会失败，不能让它抛到每条连接）。
     */
    private fun aliveWorkerAt(i: Int): SelectorWorker {
        workers[i].let { if (it.isAlive()) return it }
        synchronized(this) {
            val cur = workers[i]
            if (!started || cur.isAlive()) return cur
            val now = System.currentTimeMillis()
            if (now - lastRestartAt[i] < RESTART_COOLDOWN_MS) return cur
            lastRestartAt[i] = now
            val fresh = try {
                SelectorWorker("hxmy-nio-relay-$i", sweepIntervalMs).also {
                    // start 失败（如 pthread OOM）时回收已 open 的 selector（epoll+pipe 约 3 个 FD）——
                    // 该失败恰发生在资源紧张时，泄漏会自我加剧。
                    try { it.start() } catch (e: Throwable) { it.disposeUnstarted(); throw e }
                }
            } catch (e: Throwable) {
                Ev.throttled(
                    LogCat.RELAY, "nio.worker.restart.fail", "wrestart:$i", 10_000L,
                    key = true, kv = arrayOf("idx" to i, "err" to e.toString()),
                )
                return cur
            }
            workers[i] = fresh
            Ev.kw(LogCat.RELAY, "nio.worker.restart", "idx" to i)
            return fresh
        }
    }

    private companion object {
        /** stop 时等单个 worker 收尾的上限；超时也继续（守护线程不会拖住进程）。 */
        const val STOP_JOIN_MS = 500L
        /** 同槽位两次重启的最小间隔：未知持续性崩溃源下，重启速率被钳在每槽每 5s 一次。 */
        const val RESTART_COOLDOWN_MS = 5_000L
    }
}

/** 单 selector 线程：所有 register/改 interest/close 都在本线程内执行（投递任务队列 + wakeup）。 */
private class SelectorWorker(name: String, private val sweepMs: Long) {
    private val selector: Selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val tunnels = ConcurrentHashMap.newKeySet<Tunnel>()
    private val thread = Thread({ loop() }, name).apply { isDaemon = true }
    @Volatile private var running = true
    /**
     * 线程收尾已完成（selector 已关、最终 drain 已跑）。与 [enqueue] 的兜底构成 Dekker 闭合：
     * 仅凭 `thread.isAlive` 有 TOCTOU——崩溃到线程真正终止之间（写 died 日志 + 逐条关隧道，
     * 毫秒到几十毫秒）isAlive 仍为 true，窗口内入队的 register 任务会永久搁浅、隧道协程无限挂起
     * （FD/registry 名额泄漏到会话结束）。producer 先入队再读 flag、consumer 先立 flag 再最终 drain，
     * volatile 全序保证任务必被一侧消费。
     */
    @Volatile private var terminated = false

    fun start() = thread.start()

    fun stop() {
        signalStop()
        awaitStop(500)
    }

    fun signalStop() {
        running = false
        runCatching { selector.wakeup() }
    }

    fun awaitStop(timeoutMs: Long) {
        runCatching { thread.join(timeoutMs) }
    }

    fun isAlive(): Boolean = thread.isAlive

    /** 仅供「构造成功但 start 失败」的回收：线程从未运行，finally 不会执行，需手动关 selector。 */
    fun disposeUnstarted() {
        terminated = true
        runCatching { selector.close() }
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
        runCatching { selector.wakeup() }
        // 线程收尾完成或已死 → 队列永远无人消费，注册的隧道协程会永久挂起。调用线程兜底 drain：
        // task 自带失败路径（register 对已关 selector 抛 → close → resume），不会卡死；
        // close 幂等（CAS），多个调用线程并发兜底也安全。保留 isAlive 兜住「线程从未 start」的极端情形。
        if (terminated || !thread.isAlive) drainTasks()
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
        } catch (e: Throwable) {
            // 意外崩溃（select 抛系统错误 / sweep 里 resume 抛等）。此前这里**零日志、零恢复**：
            // 线程静默死亡后新隧道全部永久挂起、idle sweep 一并失效——正是「服务挂了但没有任何痕迹」
            // 的完美形态（7-26 排障教训）。落 key.log；复活由 aliveWorkerAt 在下次 relay 时按槽位换新。
            // 60s 内重复死亡只记单行不带栈（栈 1-2KB/条会加速冲洗 256KB key 环，重复栈零信息增量）。
            val now = System.currentTimeMillis()
            if (now - lastDiedFullLogAt > DIED_FULL_LOG_WINDOW_MS) {
                lastDiedFullLogAt = now
                Ev.e(LogCat.RELAY, "nio.worker.died", e, "name" to Thread.currentThread().name, "tunnels" to tunnels.size)
            } else {
                Ev.throttled(
                    LogCat.RELAY, "nio.worker.died", "wdied", 10_000L, key = true,
                    kv = arrayOf("name" to Thread.currentThread().name, "tunnels" to tunnels.size, "err" to e.toString()),
                )
            }
        } finally {
            // 每条单独兜底：一条隧道 close/resume 抛错（如 dispatcher 已关）不能中断其余隧道的收尾——
            // 否则剩余协程永不 resume（连接与 registry 计数一起泄漏）。
            tunnels.toList().forEach { runCatching { it.close(this) } }
            runCatching { selector.close() }
            // 顺序关键：先关 selector 再立 flag 再最终 drain——晚到任务两种结局都安全：
            // 被这次 drain 消费（register 对已关 selector 抛 → close → resume），
            // 或读到 terminated=true 由调用线程自己 drain。不存在第三种结局。
            terminated = true
            drainTasks()
        }
    }

    private fun drainTasks() {
        var t = tasks.poll()
        while (t != null) { runCatching { t() }; t = tasks.poll() }
    }

    private fun sweepIdle(now: Long) {
        // externallyClosed：channel 被 selector 线程之外裸 close（如准入收缩 evict）不产生事件，
        // 静默隧道会悬死到 idle 超时——sweep 兜底检出并拆干净（resume 协程、释放对端）。
        tunnels.toList().forEach { if (it.idleExpired(now) || it.externallyClosed()) it.close(this) }
    }

    private companion object {
        /** died 全栈日志的最小间隔；窗口内的重复死亡只记单行（class+message）。 */
        const val DIED_FULL_LOG_WINDOW_MS = 60_000L
        /** 上次全栈 died 日志时刻（跨 worker 实例共享——重复崩溃通常同源）。 */
        @Volatile var lastDiedFullLogAt = 0L
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

    /** 任一端已被外部关闭（准入收缩 evict 等）——隧道应拆，由 sweep 周期兜底调用。 */
    fun externallyClosed(): Boolean = !cCtx.channel.isOpen || !uCtx.channel.isOpen

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
