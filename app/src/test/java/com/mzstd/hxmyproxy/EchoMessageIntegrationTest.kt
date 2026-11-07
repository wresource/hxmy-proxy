package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class EchoMessageIntegrationTest {
    private val loopback=InetAddress.getByName("127.0.0.1")
    private val basic="Proxy-Authorization: Basic "+Base64.getEncoder().encodeToString("u:p".toByteArray())+"\r\n"
    private data class Response(val code:Int,val body:ByteArray)
    private fun response(s:Socket):Response {
        val h=buildString{while(!endsWith("\r\n\r\n")){val b=s.getInputStream().read();check(b>=0);append(b.toChar());check(length<32768)}}
        val n=Regex("(?i)content-length: ([0-9]+)").find(h)!!.groupValues[1].toInt()
        return Response(h.substringAfter(' ').substringBefore(' ').toInt(),ByteArray(n).also{DataInputStream(s.getInputStream()).readFully(it)})
    }
    private inner class Fixture(capacity:Int=16,val work:suspend(InetAddress,ByteArray)->ByteArray={ip,q->
        EchoProtocol.restore(EchoProtocol.Request(ip,q),EchoTestWire.nativeReply(q,ip !is Inet4Address))
    }):AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val session=ConnectUdpSession(capacity,enableSocks=true)
        val rules=RuleEngine();val registry=ConnectionRegistry(64,64);val calls=AtomicInteger();val active=AtomicInteger();val cancelled=AtomicInteger()
        val backend=EchoMessageQuery{_,ip,q->calls.incrementAndGet();active.incrementAndGet();try{work(ip,q)}catch(c:CancellationException){cancelled.incrementAndGet();throw c}finally{active.decrementAndGet()}}
        val connector=OutboundConnector(object:EgressGuard{override fun isAllowed(addr:InetAddress)=true})
        val auth=SingleCredentialAuthenticator("u","p",true)
        val socks=Socks5ProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),{auth},{ConnectionLimits()},Dispatchers.IO,
            ruleEngine=rules,udpSession=session,echoMessages=backend)
        val http=HttpProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),{auth},{ConnectionLimits()},Dispatchers.IO,
            ruleEngine=rules,udpSession=session,socksCapabilities={socks.capabilities()},echoMessages=backend)
        val ports:List<Int>
        init{socks.start(scope,0,loopback);http.start(scope,0,loopback);ports=listOf(ProxyTestKit.awaitPort(http),ProxyTestKit.awaitPort(socks))}
        fun send(port:Int,wire:ByteArray=EchoTestWire.request(),target:String="198.18.0.7",auth:Boolean=true,extra:String="",chunked:Boolean=false):Socket {
            val s=Socket(loopback,port).apply{soTimeout=5000}
            val framing=if(chunked)"Transfer-Encoding: chunked\r\n" else "Content-Length: ${wire.size}\r\n"
            s.getOutputStream().write(("POST /icmp-echo?target=$target HTTP/1.1\r\nHost: phone\r\n"+(if(auth)basic else "")+
                "Content-Type: application/octet-stream\r\n$framing$extra\r\n").toByteArray())
            if(chunked)s.getOutputStream().write("${wire.size.toString(16)}\r\n".toByteArray())
            s.getOutputStream().write(wire)
            if(chunked)s.getOutputStream().write("\r\n0\r\n\r\n".toByteArray())
            return s
        }
        fun capability(port:Int)=Socket(loopback,port).use{s->s.soTimeout=5000;s.getOutputStream().write("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: phone\r\n$basic\r\n".toByteArray());response(s)}
        override fun close(){session.close();http.stop();socks.stop();scope.cancel();backend.close();connector.cancelPendingDns()
            assertTrue(ProxyTestKit.await(5000){active.get()==0&&session.activeLeases==0&&registry.activeGlobal==0})}
    }

    @Test(timeout=15000) fun clientAllDirectAffectsBothEchoPortsAndRestoresOriginalRouting() {
        Fixture().use { f ->
            f.rules.updateDirectClients(setOf("127.0.0.1"))
            for (port in f.ports) {
                f.send(port, auth=false).use { assertEquals(407, response(it).code) }
                f.send(port).use { assertEquals(503, response(it).code) }
            }
            assertEquals(0, f.calls.get())
            f.rules.updateDirectClients(emptySet())
            for (port in f.ports) f.send(port).use { assertEquals(200, response(it).code) }
            assertEquals(2, f.calls.get())
        }
    }

    @Test(timeout=15000) fun bothPortsCarryOnlyEchoWithOriginalIdentityAndAccurateCapabilities() {
        Fixture().use{f->for((index,port) in f.ports.withIndex()){
            f.send(port,auth=false).use{assertEquals(407,response(it).code)}
            val cap=f.capability(port);assertEquals(200,cap.code);val json=String(cap.body)
            assertTrue(json.contains("\"icmp_echo\":{\"path\":\"/icmp-echo\""));assertTrue(json.contains("\"connect_udp_no_fragment\":${index==0}"))
            for(v6 in listOf(false,true))for(size in listOf(8,9,1208,65507)){
                val ip=InetAddress.getByName(if(v6)"2001:db8:77::7" else "198.18.0.7");val q=EchoTestWire.request(v6,size)
                f.send(port,q,ip.hostAddress!!,chunked=size==9).use{val r=response(it);assertEquals(200,r.code)
                    assertTrue(EchoProtocol.matches(EchoProtocol.Request(ip,q),r.body,0x1234))
                    if(!v6)assertEquals(0,EchoProtocol.checksum(r.body))else assertEquals(0,EchoProtocol.word(r.body,2))}
            }
        };assertEquals(16,f.calls.get())}
    }

    @Test(timeout=15000) fun rejectionInvalidTargetsAndStaleSessionsNeverSendEcho() {
        Fixture().use{f->f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply{add("198.18.0.7")}))
            for(port in f.ports){
                f.send(port).use{assertEquals(403,response(it).code)}
                f.send(port,extra="X-HxmyProxy-Session: stale\r\n").use{assertEquals(409,response(it).code)}
                f.send(port,target="not-an-ip.test").use{assertEquals(400,response(it).code)}
                f.send(port,wire=EchoTestWire.request().apply{this[0]=3}).use{assertEquals(400,response(it).code)}
                f.send(port,target="127.0.0.1").use{assertEquals(403,response(it).code)}
            };assertEquals(0,f.calls.get())
        }
    }

    @Test(timeout=15000) fun quotaCredentialRuleAndOffRevokeWithoutLatePublication() {
        Fixture(capacity=1,work={_,_->awaitCancellation()}).use{f->
            f.send(f.ports[0]).use{first->assertTrue(ProxyTestKit.await{f.active.get()==1})
                f.send(f.ports[1]).use{assertEquals(429,response(it).code)}
                f.session.changePolicy("credentials-changed"){f.auth.updateCredentials("u","new")};assertEquals(-1,first.getInputStream().read())}
            assertTrue(ProxyTestKit.await{f.active.get()==0});f.auth.updateCredentials("u","p")
            f.send(f.ports[1]).use{s->assertTrue(ProxyTestKit.await{f.active.get()==1});f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply{add("198.18.0.7")}));assertEquals(-1,s.getInputStream().read())}
            assertTrue(ProxyTestKit.await{f.active.get()==0});f.rules.update(RuleEngine.Snapshot())
            f.send(f.ports[0]).use{s->assertTrue(ProxyTestKit.await{f.active.get()==1});f.session.close();assertEquals(-1,s.getInputStream().read())}
            assertTrue(ProxyTestKit.await{f.cancelled.get()==3&&f.active.get()==0})
        }
    }

    @Test(timeout=10000) fun silenceIsOneAttemptAndDirectWithoutPhysicalNetworkCannotFallBack() {
        Fixture(work={_,_->awaitCancellation()}).use{f->f.send(f.ports[0]).use{assertEquals(504,response(it).code)};assertEquals(1,f.calls.get());assertEquals(1,f.cancelled.get())}
        Fixture().use{f->f.rules.update(RuleEngine.Snapshot(userDirect=RuleMatcher().apply{add("198.18.0.7")}))
            f.send(f.ports[1]).use{assertEquals(503,response(it).code)};assertEquals(0,f.calls.get())}
        Fixture(work={_,_->ByteArray(0)}).use{f->f.send(f.ports[0]).use{assertEquals(502,response(it).code)}}
    }
}
