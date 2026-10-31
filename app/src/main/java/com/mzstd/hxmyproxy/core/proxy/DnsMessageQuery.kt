package com.mzstd.hxmyproxy.core.proxy

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface DnsMessageQuery : Closeable {
    suspend fun query(network:Network?,wire:ByteArray):ByteArray
    override fun close() {}
}

/** API29 asynchronous raw DNS, with real fd cancellation and bounded callback work.
 * Native fd result/reaping must never be dropped even when shutdown races a callback. */
class AndroidDnsMessageQuery : DnsMessageQuery {
    private data class Operation(val signal:CancellationSignal,val continuation:CancellableContinuation<ByteArray>,
                                 val finished:AtomicBoolean=AtomicBoolean())
    private val lock=Any()
    private val active=LinkedHashSet<Operation>()
    private var closed=false
    private val executor=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(32),
        { work -> Thread(work,"hxmy-dns-message").apply { isDaemon=true } },
        { work,_ -> work.run() }) // Also run cleanup callbacks after shutdown; never silently discard.

    override suspend fun query(network:Network?,wire:ByteArray):ByteArray = suspendCancellableCoroutine { continuation ->
        val op=Operation(CancellationSignal(),continuation)
        val accepted=synchronized(lock) { if(closed||active.size>=16)false else active.add(op) }
        if(!accepted){continuation.resumeWithException(ProxyException(ProxyError.TooManyConnections,"dns-capacity"));return@suspendCancellableCoroutine}
        fun remove(){synchronized(lock){active.remove(op)}}
        continuation.invokeOnCancellation {
            if(op.finished.compareAndSet(false,true))try{op.signal.cancel()}finally{remove()}
        }
        fun failed(error:Throwable) {
            if(op.finished.compareAndSet(false,true)){
                try{op.signal.cancel()}finally{remove()}
                continuation.resumeWithException(error)
            }
        }
        try {
            // The caller owns the overall deadline/explicit DEGRADE. Default netd retries
            // can outlive CancellationSignal (observed on-device); disable repeated queries.
            // This keeps platform caching and TC-to-TCP handling, unlike a hand-built resolver.
            DnsResolver.getInstance().rawQuery(network,wire,DnsResolver.FLAG_NO_RETRY,executor,op.signal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer:ByteArray,rcode:Int) {
                        if(op.finished.compareAndSet(false,true)){remove();continuation.resume(answer)}
                    }
                    override fun onError(error:DnsResolver.DnsException) {
                        failed(ProxyException(ProxyError.DnsFailure,if(error.code==DnsResolver.ERROR_PARSE)"dns-message-parse" else "dns-message-system"))
                    }
                })
        } catch(error:Exception) {
            if(error is CancellationException)continuation.cancel(error)
            else failed(ProxyException(ProxyError.DnsFailure,"dns-message-system"))
        }
    }
    override fun close() {
        val victims=synchronized(lock){if(closed)return;closed=true;active.toList()}
        victims.forEach{it.continuation.cancel(CancellationException("DNS resolver stopped"))}
        executor.shutdown()
    }
}
