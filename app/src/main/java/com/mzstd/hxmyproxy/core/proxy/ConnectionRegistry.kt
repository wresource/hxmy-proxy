package com.mzstd.hxmyproxy.core.proxy

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val global = AtomicInteger(0)
    private val perClient = ConcurrentHashMap<InetAddress, AtomicInteger>()

    val activeGlobal: Int get() = global.get()

    fun activeFor(client: InetAddress): Int = perClient[client]?.get() ?: 0

    /** 尝试占用一个名额；成功返回 true（须与 [release] 配对）。 */
    fun tryAcquire(client: InetAddress): Boolean {
        while (true) {
            val g = global.get()
            if (g >= maxGlobal) return false
            if (global.compareAndSet(g, g + 1)) break
        }
        val counter = perClient.computeIfAbsent(client) { AtomicInteger(0) }
        while (true) {
            val c = counter.get()
            if (c >= maxPerClient) {
                global.decrementAndGet()
                return false
            }
            if (counter.compareAndSet(c, c + 1)) break
        }
        onChange(global.get())
        return true
    }

    fun release(client: InetAddress) {
        val now = global.updateAndGet { if (it > 0) it - 1 else 0 }
        perClient[client]?.let { counter ->
            if (counter.decrementAndGet() <= 0) perClient.remove(client, counter)
        }
        onChange(now)
    }

    /**
     * 清零所有计数（共享会话边界用）。stop/start 时调用——否则停止后在途 relay 要等空闲超时
     * 才 release()，残留计数会"卡高"带到下次会话，表现为连接数像累计而非在线。
     */
    fun reset() {
        global.set(0)
        perClient.clear()
        onChange(0)
    }
}
