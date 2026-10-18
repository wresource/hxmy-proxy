package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.BoundedBlockingExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class BoundedBlockingExecutorTest {
    @Test(timeout = 10000) fun cancellationReturnsWhileUninterruptibleWorkerStillOccupiesItsSlot() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val pool = BoundedBlockingExecutor(1, "uninterruptible-test")
        try {
            val caller = async {
                withTimeoutOrNull(250) {
                    pool.execute {
                        started.countDown()
                        // 模拟无视 interrupt 的底层读；调用方仍必须按预算返回。
                        try {
                            while (finish.count != 0L) {
                                try { finish.await() } catch (_: InterruptedException) { }
                            }
                            "late answer"
                        } finally { finished.countDown() }
                    }
                }
            }
            val value = caller.await()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertNull("不能等底层工作完成才报告超时", value)
            assertEquals("底层读确实仍被我们控制在阻塞态", 1L, finished.count)
            val saturated = runCatching { pool.execute { "must not queue" } }.exceptionOrNull()
            assertTrue("取消不能提前释放实际线程容量", saturated is RejectedExecutionException)
        } finally {
            finish.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            pool.close()
        }
    }
}
