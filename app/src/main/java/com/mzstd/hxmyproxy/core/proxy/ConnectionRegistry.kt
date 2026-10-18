package com.mzstd.hxmyproxy.core.proxy

import java.net.InetAddress
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局与单客户端连接计数及上限（线程安全，跨所有 server 共享）。
 * 上限可运行时调整（来自 [com.mzstd.hxmyproxy.core.model.ConnectionLimits]）。
 */
class ConnectionRegistry(
    @Volatile var maxGlobal: Int = 256,
    @Volatile var maxPerClient: Int = 128,
    /** 活跃连接数变化回调（accept/close/reset 后触发，传入当前在线数）；用于即时刷新 UI。 */
    private val onChange: (Int) -> Unit = {},
) {
    // 准入与释放是一个事务：分别原子更新两个计数仍会删掉刚被新连接复用的计数器。
    // 锁只覆盖连接边界，不进入任何网络读写或字节转发。
    private val lock = Any()
    private var global = 0
    private val perClient = HashMap<InetAddress, Int>()
    private var generation = Any()

    val activeGlobal: Int get() = synchronized(lock) { global }

    fun activeFor(client: InetAddress): Int = synchronized(lock) { perClient[client] ?: 0 }

    /** 成功返回当前会话的租约，调用方负责关闭；拒绝不改变任何计数。 */
    fun tryAcquire(client: InetAddress): Lease? = synchronized(lock) {
        val count = perClient[client] ?: 0
        if (global >= maxGlobal || count >= maxPerClient) return null
        val acquiredIn = generation
        val lease = Lease { release(client, acquiredIn) }
        global++
        perClient[client] = count + 1
        changed()
        lease
    }

    private fun release(client: InetAddress, acquiredIn: Any) = synchronized(lock) {
        // stop/reset 不必等待所有旧 finally；旧租约永远不能扣新会话的连接。
        if (acquiredIn !== generation) return
        val count = checkNotNull(perClient[client])
        if (count == 1) perClient.remove(client) else perClient[client] = count - 1
        global--
        changed()
    }

    /**
     * 清零所有计数（共享会话边界用）。stop/start 时调用——否则停止后在途 relay 要等空闲超时
     * 才释放，残留计数会"卡高"带到下次会话。保留 a950a36 的清零契约，并使旧租约失效。
     */
    fun reset() = synchronized(lock) {
        generation = Any()
        global = 0
        perClient.clear()
        changed()
    }

    /** 同锁发布，避免并发回调把较旧的在线数覆盖到较新的值上。观察者不得执行阻塞操作。 */
    private fun changed() {
        // UI观察失败不应让一个已占用的名额无法交给调用者回收。
        runCatching { onChange(global) }
    }

    class Lease internal constructor(private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
