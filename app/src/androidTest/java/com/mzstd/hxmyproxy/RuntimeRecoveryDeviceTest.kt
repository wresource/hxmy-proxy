package com.mzstd.hxmyproxy

import android.net.*
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.network.AndroidSystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class RuntimeRecoveryDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private fun shell(s:String)=ins.uiAutomation.executeShellCommand(s).use{ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
    private fun network():Network {
        check(Build.HARDWARE=="ranchu"&&InstrumentationRegistry.getArguments().getString("ownedRecoveryFixture")=="true")
        val cm=ins.targetContext.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.single{cm.getLinkProperties(it)?.dnsServers?.any{a->a.hostAddress=="198.18.0.53"}==true}
    }
    private fun seen(host:String):Int {
        val rows=JSONObject(shell("run-as com.mzstd.hxmyproxy.udpvpnfixture cat files/status.json")).getJSONArray("dnsQueries")
        return (0 until rows.length()).count{rows.getJSONObject(it).getString("name")==host}
    }
    @Suppress("DEPRECATION")
    @Test fun productionFreshLookupBypassesButDoesNotWriteSystemCache() = runBlocking {
        val selected=network();val report=JSONArray()
        try {
            for(net in listOf(selected,null)) {
                val host="cacheprobe.${UUID.randomUUID()}.qa.test"
                val old=setOf(InetAddress.getByName("192.0.2.20"),InetAddress.getByName("2001:db8::20"))
                val good=setOf(InetAddress.getByName("192.0.2.10"),InetAddress.getByName("2001:db8::10"))
                val first=withTimeout(5000){AndroidSystemDnsLookup.resolve(net,host)}
                assertEquals(old,first.toSet());val before=seen(host);assertEquals(2,before)
                val regular=withTimeout(5000){AndroidSystemDnsLookup.resolve(net,host)}
                assertEquals(old,regular.toSet());assertEquals("ordinary retry did not ask the DNS server",before,seen(host))
                val fresh=withTimeout(5000){AndroidSystemDnsLookup.resolveFresh(net,host)}
                assertEquals(good,fresh.toSet());assertEquals(before+2,seen(host))
                val next=withTimeout(5000){AndroidSystemDnsLookup.resolve(net,host)}
                assertEquals("fresh recovery must not write Android's shared cache",old,next.toSet())
                assertEquals(before+2,seen(host))
                report.put(JSONObject().put("network",net?.networkHandle?:"default").put("afterFreshRegular",JSONArray(next.map{it.hostAddress})).put("first",JSONArray(first.map{it.hostAddress})).put("ordinary",JSONArray(regular.map{it.hostAddress})).put("fresh",JSONArray(fresh.map{it.hostAddress})).put("wireQueries",seen(host)))
            }
        }finally{
            File(ins.targetContext.filesDir,"recovery-native-cache.json").writeText(report.toString(2))
        }
    }
    private fun pair():Pair<SocketChannel,SocketChannel> = ServerSocketChannel.open().use {
        it.bind(InetSocketAddress("127.0.0.1",0));val p=SocketChannel.open(it.localAddress);p to it.accept()
    }
    private suspend fun oneWay(nio:Boolean):Int = coroutineScope {
        val (client,c)=pair();val (server,u)=pair();val count=AtomicInteger()
        val reactor=NioRelayReactor(1,10).apply{start()};client.socket().soTimeout=4000
        if(nio){c.configureBlocking(false);u.configureBlocking(false)}
        val relay=launch(Dispatchers.IO){if(nio)reactor.relay(c,u,1024,500,"native-stream.example",null){_,_->}
            else RelayEngine().relay(c.socket(),u.socket(),1024,500,Dispatchers.IO,host="native-stream.example")}
        val writer=launch(Dispatchers.IO){try{repeat(20){server.write(ByteBuffer.wrap(byteArrayOf(it.toByte())));delay(80)};server.shutdownOutput()}catch(_:Exception){}}
        try{withContext(Dispatchers.IO){while(client.socket().getInputStream().read()>=0)count.incrementAndGet()};count.get()}
        finally{listOf(client,c,server,u).forEach{runCatching{it.close()}};writer.cancelAndJoin();relay.cancelAndJoin();reactor.stop()}
    }
    @Test fun nativeBlockingAndNioBothPreserveOngoingOneWayTraffic()=runBlocking{
        network()
        val blocking=oneWay(false);val nio=oneWay(true)
        File(ins.targetContext.filesDir,"recovery-native-idle.json").writeText(JSONObject().put("blockingReceived",blocking).put("nioReceived",nio).put("expected",20).put("idleMs",500).put("packetIntervalMs",80).toString(2))
        assertEquals("active one-way transfer must survive",20,blocking)
        assertEquals(20,nio)
    }

    @Test fun ordinarySocketUpstreamAlsoKeepsUploadAndDownloadActive() = runBlocking {
        network()
        for (upload in listOf(false, true)) {
            val (peer, relayChannel) = pair()
            val (remote, upstream) = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
                Socket("127.0.0.1", it.localPort) to it.accept()
            }
            val source = if (upload) peer.socket() else remote
            val sink = if (upload) remote else peer.socket()
            sink.soTimeout = 5000
            val job = launch(Dispatchers.IO) { RelayEngine().relay(relayChannel.socket(), upstream, 4096, 500, Dispatchers.IO) }
            val writer = launch(Dispatchers.IO) { repeat(20) { source.getOutputStream().write(it); delay(80) }; source.shutdownOutput() }
            try {
                val bytes = ByteArray(20)
                withContext(Dispatchers.IO) { java.io.DataInputStream(sink.getInputStream()).readFully(bytes) }
                assertArrayEquals(ByteArray(20) { it.toByte() }, bytes)
                writer.join()
            } finally {
                listOf(peer.socket(), relayChannel.socket(), remote, upstream).forEach { runCatching { it.close() } }
                writer.cancelAndJoin(); job.cancelAndJoin()
            }
        }
    }
}
