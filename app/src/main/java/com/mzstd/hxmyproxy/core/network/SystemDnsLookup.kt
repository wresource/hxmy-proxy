package com.mzstd.hxmyproxy.core.network

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 可取消的系统解析边界；JVM 测试显式注入替身，不使用 android.jar 的空实现。 */
fun interface SystemDnsLookup {
    suspend fun resolve(network: Network?, host: String): List<InetAddress>
    /** Custom lookup implementations may already be uncached; Android overrides this boundary. */
    suspend fun resolveFresh(network: Network?, host: String): List<InetAddress> = resolve(network, host)
}

/**
 * minSdk=29，直接使用系统异步解析及 CancellationSignal，避免阻塞 getAllByName
 * 卡住线程和 coroutineScope。API 29 的入口保留用于兼容 Android 10–16。
 * https://developer.android.com/reference/android/net/DnsResolver
 */
object AndroidSystemDnsLookup : SystemDnsLookup {
    private val callbacks = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "hxmy-dns-callback").apply { isDaemon = true }
    }

    override suspend fun resolve(network: Network?, host: String): List<InetAddress> = query(network, host, DnsResolver.FLAG_EMPTY)

    override suspend fun resolveFresh(network: Network?, host: String): List<InetAddress> =
        query(network, host, DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE)

    @Suppress("DEPRECATION")
    private suspend fun query(network: Network?, host: String, flags: Int): List<InetAddress> {
        // localhost 是本机名字，不发送到外部 DNS；仍由后续 EgressGuard 决定是否允许连接。
        if (host.trimEnd('.').equals("localhost", ignoreCase = true)) {
            return listOf(InetAddress.getLoopbackAddress())
        }
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            val completed = AtomicBoolean(false)
            fun finish(result: Result<List<InetAddress>>) {
                if (completed.compareAndSet(false, true)) continuation.resumeWith(result)
            }
            val queryCallbacks = DnsCallbackExecutor(callbacks) { error ->
                // An exception inside the framework's accumulator otherwise escapes on a
                // worker thread and can kill the process or leave the query hanging.
                runCatching { cancellation.cancel() }
                runCatching { Ev.e(LogCat.DNS, "dns.callbackFailed", error, "host" to host,
                    "netId" to network?.networkHandle) }
                finish(Result.failure(UnknownHostException("$host: DNS callback failed").apply { initCause(error) }))
            }
            continuation.invokeOnCancellation {
                completed.set(true)
                cancellation.cancel()
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                DnsResolver.getInstance().query(
                    network, host, flags, queryCallbacks, cancellation,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (completed.get()) return
                            val snapshot = DnsAddressSnapshot.capture(answer, "system-callback", host, network?.networkHandle)
                            if (rcode == 0 && snapshot.isNotEmpty()) finish(Result.success(snapshot))
                            else finish(Result.failure(UnknownHostException("$host: DNS rcode=$rcode, answers=${snapshot.size}")))
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            finish(Result.failure(UnknownHostException("$host: DNS error=${error.code}").apply {
                                initCause(error)
                            }))
                        }
                    },
                )
            } catch (error: Exception) {
                finish(Result.failure(UnknownHostException("$host: DNS query failed to start (${error.javaClass.simpleName})").apply {
                    initCause(error)
                }))
            }
        }
    }
}
