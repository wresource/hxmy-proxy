package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 对没有可取消异步 API 的 DoH 阻塞读做有界隔离。取消时调用方立即返回；
 * 忽略 interrupt 的底层调用仍占原工作线程，绝不提前释放容量、增开线程或无限排队。
 * 阻塞块必须自带 socket 超时、finally 关资源；迟到的结果不会交还已取消的请求。
 */
internal class BoundedBlockingExecutor(maxWorkers: Int, name: String) : Closeable {
    private val pool = ThreadPoolExecutor(
        0, maxWorkers, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    suspend fun <T> execute(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val task = FutureTask(Callable {
            val result = runCatching(block)
            if (continuation.isActive) continuation.resumeWith(result)
        })
        continuation.invokeOnCancellation { task.cancel(true) }
        if (continuation.isActive) {
            try {
                pool.execute(task)
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                continuation.resumeWithException(error)
            }
        }
    }

    override fun close() { pool.shutdownNow() }
}
