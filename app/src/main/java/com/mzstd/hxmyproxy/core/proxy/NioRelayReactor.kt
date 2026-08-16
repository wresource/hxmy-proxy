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
        /** 目标域名与请求追踪，透传给 worker——隧道结束时用来回答「结束的是谁」。 */
        host: String? = null,
        trace: RequestTrace? = null,
        onTraffic: (Long, Long) -> Unit,
    ) {
        val worker = aliveWorkerAt((rr.getAndIncrement() and Int.MAX_VALUE) % workers.size)
        worker.relay(client, upstream, bufferBytes, idleMillis.toLong(), host, trace, onTraffic)
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

/**
 * 把当前线程调到前台优先级（nice -2）。**必须在线程内部调用**——设的是调用者自己。
 *
 * 为什么需要：前台服务保住的是「不被杀、不被 Doze 限网」，**保不住 CPU 时间片**。
 * app 切到后台后进程从 `top-app` 调度组降到 `foreground`，而工作线程此前一直是默认
 * 优先级（nice 0），于是设备繁忙时（比如手机自己在下载）selector 被唤醒的延迟变大，
 * 直接表现为转发变慢——用户侧就是「不打开这个 app 网络就比较慢」。
 *
 * 取 THREAD_PRIORITY_FOREGROUND 而不是更激进的 DISPLAY：这些线程绝大多数时间阻塞在
 * select/read 上并不烧 CPU，要的只是「就绪时能被及时调度」，没必要去和 UI 抢。
 * 单测里 android.os.Process 是 stub（isReturnDefaultValues），runCatching 兜住即可。
 */
internal fun bumpToForegroundPriority() {
    runCatching {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
    }
}

/** 单 selector 线程：所有 register/改 interest/close 都在本线程内执行（投递任务队列 + wakeup）。 */
private class SelectorWorker(name: String, private val sweepMs: Long) {
    private val selector: Selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val tunnels = ConcurrentHashMap.newKeySet<Tunnel>()
    private val thread = Thread({ bumpToForegroundPriority(); loop() }, name).apply { isDaemon = true }
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
        /**
         * 目标域名与请求追踪。**没有这两个，隧道结束时答不出「结束的是谁」** ——
         * 早期只记聚合计数，于是一条被回收的隧道究竟是长思考的 API 请求
         * 还是真正的死链路，无从分辨（0807 日志里 884 行回收，一个域名都定位不了）。
         */
        host: String? = null,
        trace: RequestTrace? = null,
        onTraffic: (Long, Long) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val tunnel = Tunnel(client, upstream, bufferBytes, idleMs, host, trace, onTraffic, cont)
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

    /**
     * 周期清扫。回收路径**只有两条**：空闲老化、对端已被外部关闭。
     *
     * ## 「上游静默判死」为什么被整个删掉（不要再加回来）
     *
     * 曾经有第三条：客户端发过数据而上游 90 秒没回 ⇒ 判定「单向死亡」并拆隧道。
     * 它看起来很合理，实测却是纯粹的误伤，两轮数据连着否掉了它：
     *
     * · **判死时刻的客户端静默时长** `sinceTxSec` 的 p25/p50/p75 **全是 90**——
     *   也就是客户端发完最后一个字节后整整 90 秒什么都没发。那是连接池里躺着的闲置连接，
     *   不是「上游卡住的传输」。真正「正在传输被腰斩」(sinceTxSec<5s)只有 0.1~0.3%。
     * · 改成**只标记不拆**之后拿到了零推断的铁证：`flag=revived` 两台合计 **264 条**
     *   （13.5% / 7.8%）——这些连接被判死之后**又收到了上游字节**，它们一直是活的。
     * · 而剩下那 86% 也证明不了自己死了：`flag=silent` 的终局 96% 是 `idle`、其余是 `eof`，
     *   **没有一条是异常关闭**（`peer-closed` 是会落盘的，所以这不是「没测到」）。
     *   换句话说，2290 次判死里没有任何一次抓到过真实故障。
     * · 收益侧也不成立：silent 存活 p50=91.8s，idle 存活 p50=121.6s——不拆的话
     *   idle(120s) 30 秒后必然接手，这些连接照样被回收。代价却是每天约 1900 次无谓的
     *   TLS 重建，外加把 key 环塞满假的「故障信号」。
     *
     * 保留告警的理由（「链路半死时唯一的早期信号」）同样被数据推翻：它从未发出过有效信号。
     * 判据、告警、以及为它服务的 `lastUpstreamRx`/`awaitingUpstream`/`flag` 全部移除。
     */
    private fun sweepIdle(now: Long) {
        // externallyClosed：channel 被 selector 线程之外裸 close（如准入收缩 evict）不产生事件，
        // 静默隧道会悬死到 idle 超时——sweep 兜底检出并拆干净（resume 协程、释放对端）。
        tunnels.toList().forEach {
            when {
                it.idleExpired(now) -> { it.markClosing("idle"); it.close(this) }
                it.externallyClosed() -> { it.markClosing("peer-closed"); it.close(this) }
            }
        }
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

    /** 本方向进入「写不动」的时刻（0=当前不处于 stall）。见 [RelayStallStats]。 */
    var stallSinceNs = 0L
    /** 本方向累计「写不动」时长。 */
    var stallTotalNs = 0L

    /** 进入 stall（幂等：已在 stall 中不重置起点，否则长时间拥塞会被切成碎片而少算）。 */
    fun enterStall(now: Long) {
        if (stallSinceNs == 0L) stallSinceNs = now
    }

    /** 退出 stall 并结算（幂等：不在 stall 中则无操作）。 */
    fun exitStall(now: Long) {
        if (stallSinceNs != 0L) {
            stallTotalNs += now - stallSinceNs
            stallSinceNs = 0L
        }
    }
}

private class Tunnel(
    client: SocketChannel,
    upstream: SocketChannel,
    bufferBytes: Int,
    private val idleMs: Long,
    private val host: String?,
    private val trace: RequestTrace?,
    onTraffic: (Long, Long) -> Unit,
    private val cont: CancellableContinuation<Unit>,
) {
    private val cCtx = ChannelCtx(client)
    private val uCtx = ChannelCtx(upstream)
    /** 本隧道自己的收发累计——[onTraffic] 只往全局记账走，回答不了「这一条搬了多少」。 */
    @Volatile private var upBytes = 0L
    @Volatile private var downBytes = 0L
    // up: client→upstream（计 up）；down: upstream→client（计 down）
    private val up = Pipe(cCtx, uCtx, ByteBuffer.allocate(bufferBytes)) { upBytes += it; onTraffic(it, 0) }
    private val down = Pipe(uCtx, cCtx, ByteBuffer.allocate(bufferBytes)) { downBytes += it; onTraffic(0, it) }
    @Volatile private var lastActivity = System.nanoTime()

    /**
     * 拆除原因。默认 `eof` = 正常收尾（任一端读到 -1 且两向都排空）。
     * sweep 的两条回收路径各自在 close 前 [markClosing]，于是 `req.closed` 的 `why=`
     * 直接分开「正常结束」「空闲老化」「对端已关」。
     */
    @Volatile private var closeReason = "eof"

    fun markClosing(reason: String) { closeReason = reason }

    /** 隧道建立时刻，用于给 stall 时长算占比分母（见 [RelayStallStats]）。 */
    private val startNs = System.nanoTime()
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
            // 这就是背压的定义时刻：**dst 那一端堵了**。哪个方向堵，直接指认哪一段是瓶颈
            // （down 堵=写客户端写不动=入口段；up 堵=写上游写不动=出口段）。见 RelayStallStats。
            pipe.enterStall(System.nanoTime())
        } else {
            pipe.buf.clear()
            pipe.draining = false
            // **先判状态再取时间戳**：参数在调用点求值，写成 exitStall(System.nanoTime()) 会让
            // nanoTime 在每次 drain 都被调用——而 RelayStallStats 的说明写的是「只在状态翻转时取」，
            // 实现与自述不符。单次约 20~30ns（vDSO），高吞吐下每秒数千次，量级上可忽略，
            // 但没有理由为一个绝大多数时候用不到的值买单。
            if (pipe.stallSinceNs != 0L) pipe.exitStall(System.nanoTime())
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
        // **关闭时仍在 stall 中要补结算**：客户端拔网线/断电正是这个场景——一直写不动直到隧道被拆，
        // 那段时长恰恰是最能说明问题的，不补就整段丢失（只有「进入」没有「退出」）。
        val endNs = System.nanoTime()
        up.exitStall(endNs)
        down.exitStall(endNs)
        // up=client→upstream(写上游=出口段)，down=upstream→client(写客户端=入口段)。
        RelayStallStats.record(endNs - startNs, stallInNanos = down.stallTotalNs, stallOutNanos = up.stallTotalNs)
        // 隧道终局落盘。此前 RequestTrace.tunnelClosed 只有定义、**全仓零调用点**——
        // 于是「跑一天再看 req.closed」这个排查计划从一开始就取不到数据。
        trace?.tunnelClosed(host, closeReason, upBytes, downBytes)
        cCtx.key?.cancel(); uCtx.key?.cancel()
        cCtx.channel.closeQuietly(); uCtx.channel.closeQuietly()
        w.untrack(this)
        if (cont.isActive) cont.resume(Unit)
    }
}
