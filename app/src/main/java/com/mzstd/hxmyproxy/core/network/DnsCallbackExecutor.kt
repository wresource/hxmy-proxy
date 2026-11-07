package com.mzstd.hxmyproxy.core.network

import java.util.ArrayDeque
import java.util.concurrent.Executor

/** One queue per query, shared worker pool. DnsResolver aggregates A/AAAA inside the
 * supplied executor, before our final callback, so serializing only onAnswer is too late.
 * Queued work is retained on cancellation so the framework can finish its FD cleanup. */
internal class DnsCallbackExecutor(
    private val workers: Executor,
    private val onFailure: (Throwable) -> Unit,
) : Executor {
    private val queue = ArrayDeque<Runnable>()
    private var running = false
    private var rejected = false

    override fun execute(command: Runnable) {
        val start = synchronized(queue) {
            if (rejected) return
            queue.addLast(command)
            if (running) false else { running = true; true }
        }
        if (start) try {
            workers.execute { drain() }
        } catch (error: Throwable) {
            // This query cannot run. The owner cancels native work before reporting failure.
            synchronized(queue) { rejected = true; running = false; queue.clear() }
            report(error)
        }
    }

    private fun drain() {
        while (true) {
            val task = synchronized(queue) {
                queue.pollFirst() ?: run { running = false; return }
            }
            try { task.run() } catch (error: Throwable) { report(error) }
        }
    }

    private fun report(error: Throwable) { runCatching { onFailure(error) } }
}
