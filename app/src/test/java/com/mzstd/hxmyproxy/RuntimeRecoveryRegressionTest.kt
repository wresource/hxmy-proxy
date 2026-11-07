package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** Red/green regression from the runtime investigation. Only local sockets or explicit test doubles. */
class RuntimeRecoveryRegressionTest {
    private val allowed = object:EgressGuard { override fun isAllowed(addr:InetAddress)=true }
    private fun pair():Pair<SocketChannel,SocketChannel> = ServerSocketChannel.open().use {
        it.bind(InetSocketAddress("127.0.0.1",0)); val peer=SocketChannel.open(it.localAddress);peer to it.accept()
    }
    private suspend fun oneWay(nio:Boolean):Int = coroutineScope {
        val (client,c)=pair();val (server,u)=pair()
        val reactor=NioRelayReactor(1,10).apply{start()};val count=AtomicInteger()
        client.socket().soTimeout=3000
        if(nio){c.configureBlocking(false);u.configureBlocking(false)}
        val job=launch(Dispatchers.IO) {
            if(nio)reactor.relay(c,u,1024,250,"stream.example",null){_,_->}
            else RelayEngine().relay(c.socket(),u.socket(),1024,250,Dispatchers.IO,host="stream.example")
        }
        val writer=launch(Dispatchers.IO) {
            try { repeat(20){server.write(ByteBuffer.wrap(byteArrayOf(it.toByte())));delay(40)};server.shutdownOutput() }
            catch(_:Exception){}
        }
        try {
            withContext(Dispatchers.IO){while(client.socket().getInputStream().read()>=0)count.incrementAndGet()}
            count.get()
        } finally { listOf(client,c,server,u).forEach{runCatching{it.close()}};writer.cancelAndJoin();job.cancelAndJoin();reactor.stop() }
    }
    @Test(timeout=10000) fun bothRelaysKeepAnActiveOneWayStream() = runBlocking {
        val blocking=oneWay(false);val nio=oneWay(true)
        assertEquals("ongoing opposite-direction traffic is not idle",20,blocking)
        assertEquals("normal NIO must preserve all streaming bytes",20,nio)
    }

    private class Fixture(primaryBad:Boolean) {
        val calls=AtomicInteger();val bad=InetAddress.getByName("192.0.2.20");val good=InetAddress.getByName("192.0.2.10")
        var recovered=false
        var onBadDial: (() -> Unit)? = null
        val net=mockk<Network>{every{networkHandle}returns 9981L}
        val physical=mockk<Network>{every{networkHandle}returns 9982L}
        val factory=mockk<SocketFactory>()
        val provider=mockk<UnderlyingNetworkProvider>(relaxed=true){every{current()}returns physical;every{egressSelection()}returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.VPN,net)}
        init{
            every{net.socketFactory}returns factory
            every{factory.createSocket()}answers{object:Socket(){
                private var peer:InetAddress?=null
                override fun connect(endpoint:SocketAddress?,timeout:Int){val a=(endpoint as InetSocketAddress).address;if(a==bad){onBadDial?.invoke();throw SocketTimeoutException("controlled unreachable answer")};peer=a}
                override fun getInetAddress()=peer
            }}
        }
        val connector=OutboundConnector(object:EgressGuard{override fun isAllowed(addr:InetAddress)=true},underlyingNetworkProvider=provider,
            systemDns=SystemDnsLookup { n,_ ->
                if(n===net){val call=calls.incrementAndGet();if(call==1&&!primaryBad)emptyList() else listOf(if(recovered)good else bad)}
                else if(n===physical)listOf(bad) else emptyList()
            }).apply{backupDnsEnabled=false}
    }
    @Test(timeout=10000) fun failedPrimaryAnswerGetsOneFreshRecovery()=runBlocking{
        val f=Fixture(true)
        try{
            f.onBadDial={f.recovered=true}
            f.connector.connect("primary.example",443).use { assertEquals(f.good,it.inetAddress) }
            assertEquals(2,f.calls.get())
            f.connector.connect("primary.example",443).close();assertEquals(2,f.calls.get())
        }finally{f.connector.cancelPendingDns()}
    }
    @Test(timeout=10000) fun identicalRecheckRetainsRescueIdentityAndAllowsLaterRecovery()=runBlocking{
        val f=Fixture(false)
        try{
            assertTrue(runCatching{f.connector.connect("rescue.example",443)}.isFailure)
            assertEquals(2,f.calls.get())
            val cache=f.connector.javaClass.getDeclaredField("dnsCache").apply{isAccessible=true}.get(f.connector) as Map<*,*>
            val cached=cache.values.single()!!;val answer=cached.javaClass.getDeclaredField("answer").apply{isAccessible=true}.get(cached)
            val source=answer.javaClass.getDeclaredField("source").apply{isAccessible=true}.get(answer)
            assertEquals(DnsSource.RESCUE_PHYSICAL,source)
            f.recovered=true
            delay(1600) // existing negative-recheck cooldown remains in force
            f.connector.connect("rescue.example",443).use { assertEquals(f.good,it.inetAddress) }
            assertEquals(3,f.calls.get())
        }finally{f.connector.cancelPendingDns()}
    }
    @Test(timeout=10000) fun successfulBusinessDialSupersedesConcurrentProbeFailure()=runBlocking{
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val health=EgressHealth(probe={entered.complete(Unit);gate.await();false},nowMs={100_000L})
        val f=Fixture(true);f.recovered=true
        val connector=OutboundConnector(allowed,underlyingNetworkProvider=f.provider,egressHealth=health,systemDns=SystemDnsLookup{_,_->listOf(f.good)})
        val probe=async{health.confirmOrSideline(f.net)}
        try{
            entered.await();connector.connect("good.example",443).close()
            gate.complete(Unit);probe.await()
            assertFalse(health.isSidelined(f.net))
            connector.connect("good.example",443).close()
        }finally{gate.complete(Unit);probe.cancelAndJoin();connector.cancelPendingDns();f.connector.cancelPendingDns()}
    }
}
