package com.mzstd.hxmyproxy

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.mzstd.hxmyproxy.core.log.*
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.channels.SocketChannel

class ReviewResourcesRegressionTest {
    @Test(timeout=5000) fun timeoutDuringAcquisitionClosesDiscardedSocket() = runBlocking {
        val socket=Socket()
        val result=acquireUpstream(30) {
            withContext(NonCancellable) { delay(80) }
            socket
        }
        assertNull(result)
        assertTrue(socket.isClosed)
    }

    private suspend fun replyFailure(protocol: String, nio: Boolean, configureFailure: Boolean=false) {
        ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { listener ->
            SocketChannel.open(InetSocketAddress(InetAddress.getLoopbackAddress(),listener.localPort)).use { upstream ->
                listener.accept().use { peer ->
                    val connector=mockk<OutboundConnector>()
                    coEvery { connector.connect(any<String>(),any(),any(),any(),any()) } returns upstream.socket()
                    coEvery { connector.connectChannel(any<String>(),any(),any(),any(),any()) } returns upstream
                    val host="upstream.example"
                    val input=if(protocol=="HTTP") "CONNECT $host:443 HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray()
                        else byteArrayOf(5,1,0,5,1,0,3,host.length.toByte())+host.toByteArray()+byteArrayOf(1,(-69).toByte())
                    val sent=ByteArrayOutputStream()
                    val output=object: OutputStream() {
                        override fun write(b:Int) {
                            if(!configureFailure && (protocol=="HTTP" || sent.size()>=2)) throw IOException("controlled client reset during success reply")
                            sent.write(b)
                        }
                    }
                    val socket=mockk<Socket>(relaxed=true) {
                        every { getInputStream() } returns ByteArrayInputStream(input)
                        every { getOutputStream() } returns output
                        every { port } returns 12345
                    }
                    val client=mockk<SocketChannel>(relaxed=true) { every { socket() } returns socket }
                    if(configureFailure) every { client.configureBlocking(false) } throws java.nio.channels.ClosedChannelException()
                    val relay=mockk<RelayEngine>(relaxed=true)
                    val reactor=mockk<NioRelayReactor>(relaxed=true)
                    val server=if(protocol=="HTTP") HttpProxyServer(Dispatchers.IO,AllowAllAccessController,ConnectionRegistry(),connector,relay,{NoAuthAuthenticator},{ConnectionLimits()},Dispatchers.IO,nioReactor=reactor,useNioRelay=nio)
                        else Socks5ProxyServer(Dispatchers.IO,AllowAllAccessController,ConnectionRegistry(),connector,relay,{NoAuthAuthenticator},{ConnectionLimits()},Dispatchers.IO,nioReactor=reactor,useNioRelay=nio)
                    val error=runCatching { invokeSuspend(server,"handle",client,null) }.exceptionOrNull()
                    assertTrue(error is IOException)
                    assertFalse("Failed handoff must close upstream",upstream.isOpen)
                    assertTrue(upstream.socket().isClosed)
                    peer.soTimeout=60
                    assertEquals(-1,peer.getInputStream().read())
                    upstream.close()
                    assertEquals(-1,peer.getInputStream().read())
                }
            }
        }
    }
    @Test(timeout=10000) fun handoff_httpNioReplyCloses() = runBlocking { replyFailure("HTTP",true) }
    @Test(timeout=10000) fun handoff_httpBlockingReplyCloses() = runBlocking { replyFailure("HTTP",false) }
    @Test(timeout=10000) fun handoff_socksNioReplyCloses() = runBlocking { replyFailure("SOCKS",true) }
    @Test(timeout=10000) fun handoff_socksBlockingReplyCloses() = runBlocking { replyFailure("SOCKS",false) }
    @Test(timeout=10000) fun handoff_httpNioConfigureCloses() = runBlocking { replyFailure("HTTP",true,true) }
    @Test(timeout=10000) fun handoff_socksNioConfigureCloses() = runBlocking { replyFailure("SOCKS",true,true) }

    @Test(timeout=10000) fun handoff_blockingIoExceptionHasNoFailureEventExceptHttpConnectControl() = runBlocking {
        val results=mutableListOf<String>()
        for(protocol in listOf("HTTP-PLAIN","SOCKS","HTTP-CONNECT")) {
            val dir=File(System.getProperty("review.evidence", System.getProperty("java.io.tmpdir")),"io-log-$protocol").apply { deleteRecursively(); mkdirs() }
            FileLog.enabled=true; FileLog.init(dir)
            val host="upstream.example"
            val input=when(protocol) {
                "HTTP-PLAIN" -> "GET http://$host/ HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray()
                "HTTP-CONNECT" -> "CONNECT $host:443 HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray()
                else -> byteArrayOf(5,1,0,5,1,0,3,host.length.toByte())+host.toByteArray()+byteArrayOf(1,(-69).toByte())
            }
            val output=ByteArrayOutputStream()
            val sock=mockk<Socket>(relaxed=true) {
                every { getInputStream() } returns ByteArrayInputStream(input)
                every { getOutputStream() } returns output
                every { port } returns 12346
            }
            val channel=mockk<SocketChannel> { every { socket() } returns sock }
            val connector=mockk<OutboundConnector>()
            coEvery { connector.connect(any<String>(),any(),any(),any(),any()) } throws IOException("injected raw IOException")
            val relay=mockk<RelayEngine>(relaxed=true)
            val server=if(protocol=="SOCKS") Socks5ProxyServer(Dispatchers.IO,AllowAllAccessController,ConnectionRegistry(),connector,relay,{NoAuthAuthenticator},{ConnectionLimits()},Dispatchers.IO)
                else HttpProxyServer(Dispatchers.IO,AllowAllAccessController,ConnectionRegistry(),connector,relay,{NoAuthAuthenticator},{ConnectionLimits()},Dispatchers.IO)
            val error=runCatching { invokeSuspend(server,"handle",channel,null) }.exceptionOrNull()
            coVerify(exactly=1) { connector.connect(any<String>(),any(),any(),any(),any()) }
            FileLog.flush()
            val log=File(dir,"app.log").takeIf { it.exists() }?.readText().orEmpty()
            val recorded=log.contains("evt=req.failed")
            assertTrue(recorded)
            assertNull(error)
            results.add("$protocol rawIOExceptionEscapes=${error is IOException} req.failed=$recorded")
        }
    }

    @Test fun criticalLogWritesImmediatelyAndCrashStillFlushes() {
        val dir=File(System.getProperty("review.evidence", System.getProperty("java.io.tmpdir")),"buffer-log").apply { deleteRecursively(); mkdirs() }
        FileLog.enabled=true; FileLog.init(dir)
        Ev.k(LogCat.PROC,"review-key-event","controlled" to true)
        repeat(20) { FileLog.i("repro","ordinary event $it") }
        val main=File(dir,"app.log"); val key=File(dir,"key.log")
        assertTrue(main.length()>0); assertTrue(key.length()>0)
        val before=key.length()
        FileLog.flush(); assertTrue(key.readText().contains("review-key-event"))
        val after=key.length()
        FileLog.crash("controlled-test",IllegalStateException("controlled crash record"))
        assertTrue(key.readText().contains("controlled crash record"))
    }

    @Test fun exitCursorAdvancesOnlyAfterConfirmedWrite() {
        val unsafeField=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }
        val unsafe=unsafeField.get(null) as sun.misc.Unsafe
        val field=Build.VERSION::class.java.getDeclaredField("SDK_INT")
        val base=unsafe.staticFieldBase(field); val offset=unsafe.staticFieldOffset(field)
        val old=unsafe.getInt(base,offset)
        unsafe.putInt(base,offset,36)
        try {
            val dir=File(System.getProperty("review.evidence", System.getProperty("java.io.tmpdir")),"exit-cursor-log").apply { deleteRecursively(); mkdirs() }
            FileLog.enabled=true; FileLog.init(dir)
            var cursor=0L; var bytesAtApply=-1L
            val key=File(dir,"key.log")
            val edit=mockk<SharedPreferences.Editor>(relaxed=true)
            every { edit.putStringSet("seenRecordsV2",any()) } returns edit
            every { edit.putLong("lastSeenTs",any()) } answers { cursor=secondArg(); edit }
            every { edit.apply() } answers { bytesAtApply=key.length() }
            val prefs=mockk<SharedPreferences> {
                every { getLong("lastSeenTs",0L) } answers { cursor }
                every { getStringSet("seenRecordsV2",null) } returns null
                every { edit() } returns edit
            }
            val info=mockk<ApplicationExitInfo>(relaxed=true) {
                every { timestamp } returns 123456789L
                every { reason } returns ApplicationExitInfo.REASON_SIGNALED
                every { status } returns 9
                every { description } returns "controlled SIGKILL history"
                every { processStateSummary } returns null
            }
            val am=mockk<ActivityManager> { every { getHistoricalProcessExitReasons(null,0,8) } returns listOf(info) }
            val ctx=mockk<Context> {
                every { getSystemService(ActivityManager::class.java) } returns am
                every { getSharedPreferences("exit_reason",0) } returns prefs
            }
            ExitReason.reportLastExit(ctx)
            assertEquals(123456789L,cursor); assertTrue(bytesAtApply>0)
            FileLog.flush(); assertTrue(key.readText().contains("123456789"))
            val broken=File(dir,"broken").apply { mkdirs() }
            File(broken,"key.log").mkdirs()
            FileLog.init(broken)
            cursor=0L; bytesAtApply=-1L
            ExitReason.reportLastExit(ctx)
            assertEquals("failed key write must not advance cursor",0L,cursor)
            assertEquals(-1L,bytesAtApply)
        } finally { unsafe.putInt(base,offset,old) }
    }
}
