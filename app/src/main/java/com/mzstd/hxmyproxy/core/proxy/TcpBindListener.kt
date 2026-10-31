package com.mzstd.hxmyproxy.core.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Nonblocking listener; accepted sockets retain the listener's explicitly selected Network. */
interface TcpBindListener : Closeable {
    val bound: InetSocketAddress
    val networkHandle: Long?
    fun acceptOrNull(): Socket?
}

fun interface TcpBindListenerFactory {
    fun open(expectedPeer: InetAddress, network: Network?): TcpBindListener
}

/** Public APIs only. A local listener cannot create an ISP/VPN public port mapping. */
class AndroidTcpBindListenerFactory(context: Context) : TcpBindListenerFactory {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    override fun open(expectedPeer: InetAddress, network: Network?): TcpBindListener {
        val selected = network ?: connectivity.activeNetwork ?: throw IOException("BIND network unavailable")
        val v4 = expectedPeer is Inet4Address
        val local = connectivity.getLinkProperties(selected)?.linkAddresses?.map { it.address }
            ?.firstOrNull { (it is Inet4Address) == v4 && !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: throw IOException("BIND network has no usable address in the peer family")
        // Both creation flags are public in API29. fcntlInt became public only in API30.
        val fd = try { Os.socket(if (v4) OsConstants.AF_INET else OsConstants.AF_INET6,
            OsConstants.SOCK_STREAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC, 0) }
        catch (error: ErrnoException) { throw IOException("BIND socket creation failed",error) }
        try {
            selected.bindSocket(fd)
            if (!v4) Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
            Os.bind(fd, local, 0)
            Os.listen(fd, 8)
            val address = Os.getsockname(fd) as InetSocketAddress
            return object : TcpBindListener {
                override val bound = address
                override val networkHandle = selected.networkHandle
                private var closed = false
                @Synchronized override fun acceptOrNull(): Socket? {
                    if (closed) throw SocketException("BIND listener closed")
                    val accepted = try { Os.accept(fd, InetSocketAddress(0)) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) return null
                        throw IOException("BIND accept failed (errno=${error.errno})", error)
                    }
                    var descriptor: ParcelFileDescriptor? = null
                    try {
                        // accept() does not inherit O_NONBLOCK. API29 ParcelFileDescriptor.dup
                        // uses F_DUPFD_CLOEXEC internally, then the raw accepted fd is closed.
                        // The adapter owns only the duplicate; no private fd access is needed.
                        val owned = ParcelFileDescriptor.dup(accepted).also { descriptor = it }
                        Os.close(accepted)
                        return AcceptedFdSocket(owned)
                    } catch (error: Throwable) {
                        runCatching { descriptor?.close() };runCatching { Os.close(accepted) }
                        throw IOException("BIND accepted socket setup failed",error)
                    }
                }
                @Synchronized override fun close() {
                    if (closed) return
                    closed = true
                    Os.close(fd)
                }
            }
        } catch (error: Throwable) {
            runCatching { Os.close(fd) }
            throw IOException("BIND listener setup failed", error)
        }
    }
}

/** Adapter for the existing bounded blocking relay. No hidden SocketImpl or fd reflection.
 * Input/output do not share a blocking Java lock; close shuts down first to wake both pumps. */
internal class AcceptedFdSocket(private val descriptor: ParcelFileDescriptor) : Socket() {
    private val fd: FileDescriptor = descriptor.fileDescriptor
    private val local = Os.getsockname(fd) as InetSocketAddress
    private val peer = Os.getpeername(fd) as InetSocketAddress
    private val closed = AtomicBoolean()
    private val inputEnded = AtomicBoolean()
    private val outputEnded = AtomicBoolean()
    @Volatile private var timeout = 0
    private fun checkOpen() { if (closed.get()) throw SocketException("BIND socket closed") }
    private fun ioError(error: ErrnoException): IOException =
        if (error.errno == OsConstants.EAGAIN) SocketTimeoutException("BIND socket read timeout").apply { initCause(error) }
        else SocketException("BIND socket I/O failed (errno=${error.errno})").apply { initCause(error) }

    private val input = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b,0,1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            checkOpen()
            if (inputEnded.get()) return -1
            return try { Os.read(fd,bytes,offset,length).let { if (it == 0) -1 else it } }
            catch (error: ErrnoException) { throw ioError(error) }
        }
        override fun close() = this@AcceptedFdSocket.close()
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()),0,1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            var at = offset
            while (at < offset + length) {
                checkOpen()
                if (outputEnded.get()) throw SocketException("BIND output shut down")
                val n = try { Os.write(fd,bytes,at,offset+length-at) }
                catch (error: ErrnoException) { throw ioError(error) }
                if (n <= 0) throw IOException("BIND socket write made no progress")
                at += n
            }
        }
        override fun close() = this@AcceptedFdSocket.close()
    }

    override fun getInputStream(): InputStream { checkOpen(); return input }
    override fun getOutputStream(): OutputStream { checkOpen(); return output }
    override fun getInetAddress(): InetAddress = peer.address
    override fun getLocalAddress(): InetAddress = local.address
    override fun getPort(): Int = peer.port
    override fun getLocalPort(): Int = local.port
    override fun getRemoteSocketAddress() = peer
    override fun getLocalSocketAddress() = local
    override fun isConnected() = true
    override fun isBound() = true
    override fun isClosed() = closed.get()
    override fun isInputShutdown() = inputEnded.get()
    override fun isOutputShutdown() = outputEnded.get()
    override fun getSoTimeout() = timeout
    override fun setSoTimeout(value: Int) {
        require(value >= 0); checkOpen()
        try { Os.setsockoptTimeval(fd,OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,StructTimeval.fromMillis(value.toLong())) }
        catch (error: ErrnoException) { throw ioError(error) }
        timeout = value
    }
    override fun shutdownInput() { checkOpen(); if (inputEnded.compareAndSet(false,true)) Os.shutdown(fd,OsConstants.SHUT_RD) }
    override fun shutdownOutput() { checkOpen(); if (outputEnded.compareAndSet(false,true)) Os.shutdown(fd,OsConstants.SHUT_WR) }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        runCatching { Os.shutdown(fd,OsConstants.SHUT_RDWR) }
        descriptor.close()
    }
}
