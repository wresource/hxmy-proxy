package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface EchoMessageQuery : Closeable {
    suspend fun query(network:Network?,target:InetAddress,wire:ByteArray):ByteArray
    val ipv4:Boolean get()=true
    val ipv6:Boolean get()=true
    override fun close() {}
}
data class EchoReceived(val bytes:Int,val peer:InetAddress)
interface EchoSocket : Closeable {
    val identifier:Int
    fun send(bytes:ByteArray):DatagramSendResult
    fun receive(buffer:ByteArray):EchoReceived?
}
fun interface EchoSocketFactory {
    fun open(target:InetAddress,network:Network?):EchoSocket
    fun supported(ipv6:Boolean):Boolean=true
}

/** Only SOCK_DGRAM ping sockets, never SOCK_RAW/IP_HDRINCL. */
object AndroidPingSocketFactory : EchoSocketFactory {
    override fun supported(ipv6:Boolean):Boolean = runCatching {
        val fd=Os.socket(if(ipv6)OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_DGRAM or OsConstants.SOCK_CLOEXEC,if(ipv6)OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP)
        Os.close(fd);true
    }.getOrDefault(false)
    override fun open(target:InetAddress,network:Network?):EchoSocket {
        val v6=target !is Inet4Address
        val fd=try{Os.socket(if(v6)OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
            if(v6)OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP)}
        catch(error:ErrnoException){throw ProxyException(ProxyError.RemoteUnreachable,"echo-unavailable")}
        try {
            network?.bindSocket(fd)
            if(v6){Os.setsockoptInt(fd,OsConstants.IPPROTO_IPV6,62,0);Os.setsockoptInt(fd,OsConstants.IPPROTO_IPV6,23,0)}
            else Os.setsockoptInt(fd,OsConstants.IPPROTO_IP,10,0)
            Os.connect(fd,target,0)
            return PingSocket(fd,(Os.getsockname(fd) as InetSocketAddress).port)
        }catch(error:Throwable){runCatching{Os.close(fd)};if(error !is Exception||error is CancellationException)throw error;throw ProxyException(ProxyError.RemoteUnreachable,"echo-setup")}
    }
    private class PingSocket(private val fd:FileDescriptor,override val identifier:Int):EchoSocket {
        private var closed=false
        @Synchronized override fun send(bytes:ByteArray):DatagramSendResult {
            if(closed)throw IOException("Echo socket closed")
            return try{val n=Os.write(fd,bytes,0,bytes.size);if(n!=bytes.size)throw IOException("Partial Echo message");DatagramSendResult.SENT}
            catch(error:ErrnoException){when(error.errno){OsConstants.EAGAIN,OsConstants.EINTR->DatagramSendResult.WOULD_BLOCK;OsConstants.EMSGSIZE->DatagramSendResult.TOO_LARGE;else->throw IOException("Echo send errno=${error.errno}",error)}}
        }
        @Synchronized override fun receive(buffer:ByteArray):EchoReceived? {
            if(closed)throw IOException("Echo socket closed")
            val peer=InetSocketAddress(0)
            return try{EchoReceived(Os.recvfrom(fd,buffer,0,buffer.size,0,peer),peer.address)}
            catch(error:ErrnoException){if(error.errno==OsConstants.EAGAIN||error.errno==OsConstants.EINTR)null else throw IOException("Echo receive errno=${error.errno}",error)}
        }
        @Synchronized override fun close(){if(closed)return;closed=true;Os.close(fd)}
    }
}

/** Four actual operations process-wide; at most sixteen admitted including queued work.
 * Cancellation closes a published socket immediately. A late blocked factory retains
 * its real permit until it returns and closes, including across sharing OFF/ON. */
class AndroidEchoMessageQuery(private val factory:EchoSocketFactory=AndroidPingSocketFactory):EchoMessageQuery {
    override val ipv4=factory.supported(false)
    override val ipv6=factory.supported(true)
    private class Operation(val continuation:CancellableContinuation<ByteArray>) {
        val terminal=AtomicBoolean()
        private var cancelled=false
        private var socket:EchoSocket?=null
        private var job:Job?=null
        @Synchronized fun attach(value:EchoSocket):Boolean {if(cancelled){value.close();return false};socket=value;return true}
        @Synchronized fun attach(value:Job){job=value;if(cancelled)value.cancel()}
        @Synchronized fun cancel(){cancelled=true;runCatching{socket?.close()};job?.cancel()}
        @Synchronized fun release(){runCatching{socket?.close()};socket=null}
    }
    private val lock=Any();private var closed=false;private val operations=LinkedHashSet<Operation>()
    val pendingOperations:Int get()=synchronized(lock){operations.size}
    override suspend fun query(network:Network?,target:InetAddress,wire:ByteArray):ByteArray = suspendCancellableCoroutine { continuation ->
        if(!EchoProtocol.validRequest(target,wire)){continuation.resumeWithException(ProxyException(ProxyError.AccessDenied,"echo-type-denied"));return@suspendCancellableCoroutine}
        if(!admission.tryAcquire()){continuation.resumeWithException(ProxyException(ProxyError.TooManyConnections,"echo-capacity"));return@suspendCancellableCoroutine}
        val op=Operation(continuation)
        val accepted=synchronized(lock){if(closed)false else operations.add(op)}
        if(!accepted){admission.release();continuation.cancel(CancellationException("Echo stopped"));return@suspendCancellableCoroutine}
        continuation.invokeOnCancellation{if(op.terminal.compareAndSet(false,true))op.cancel()}
        val request=EchoProtocol.Request(target,wire)
        val job=workers.launch(start=CoroutineStart.LAZY) {
            try {
                val answer=executing.withPermit {
                    currentCoroutineContext().ensureActive()
                    factory.open(target,network).use { socket ->
                        currentCoroutineContext().ensureActive();if(!op.attach(socket))throw CancellationException("Echo cancelled during setup")
                        while(true){currentCoroutineContext().ensureActive();when(socket.send(wire)){
                            DatagramSendResult.SENT->break
                            DatagramSendResult.TOO_LARGE->throw ProxyException(ProxyError.RemoteUnreachable,"echo-too-large")
                            DatagramSendResult.WOULD_BLOCK->delay(5)
                        }}
                        val buffer=ByteArray(EchoProtocol.MAX_MESSAGE+1)
                        var reply:ByteArray?=null
                        while(reply==null){
                            currentCoroutineContext().ensureActive()
                            for(attempt in 0 until 16){
                                val received=socket.receive(buffer)?:break
                                if(received.peer==target&&EchoProtocol.matches(request,buffer,socket.identifier,received.bytes))
                                    reply=EchoProtocol.restore(request,buffer.copyOf(received.bytes))
                                if(reply!=null)break
                            }
                            if(reply==null)delay(5)
                        }
                        reply!!
                    }
                }
                if(op.terminal.compareAndSet(false,true))continuation.resume(answer)
            }catch(cancel:CancellationException){if(op.terminal.compareAndSet(false,true))continuation.cancel(cancel)}
            catch(error:Exception){if(op.terminal.compareAndSet(false,true))continuation.resumeWithException(if(error is ProxyException)error else ProxyException(ProxyError.RemoteUnreachable,"echo-io"))}
        }
        op.attach(job)
        job.invokeOnCompletion {
            op.release();synchronized(lock){operations.remove(op)};admission.release()
            if(op.terminal.compareAndSet(false,true))continuation.cancel(CancellationException("Echo work cancelled"))
        }
        job.start()
    }
    override fun close(){val victims=synchronized(lock){if(closed)return;closed=true;operations.toList()};victims.forEach{it.continuation.cancel(CancellationException("Echo stopped"))}}
    private companion object {
        val admission=java.util.concurrent.Semaphore(16)
        val executing=kotlinx.coroutines.sync.Semaphore(4)
        val executor=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(32),
            { task->Thread(task,"hxmy-echo").apply{isDaemon=true} }).apply{allowCoreThreadTimeOut(true)}
        val workers=CoroutineScope(SupervisorJob()+executor.asCoroutineDispatcher())
    }
}
