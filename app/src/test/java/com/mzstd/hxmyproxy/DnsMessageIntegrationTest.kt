package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
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

class DnsMessageIntegrationTest {
    private val loopback=InetAddress.getByName("127.0.0.1")
    private val basic="Proxy-Authorization: Basic "+Base64.getEncoder().encodeToString("u:p".toByteArray())+"\r\n"
    private data class Response(val code:Int,val headers:String,val bytes:ByteArray)
    private fun response(s:Socket):Response {
        val h=buildString {while(!endsWith("\r\n\r\n")){val b=s.getInputStream().read();check(b>=0){"EOF before headers"};append(b.toChar());check(length<32768)}}
        val size=Regex("(?i)content-length: ([0-9]+)").find(h)!!.groupValues[1].toInt()
        val body=ByteArray(size);DataInputStream(s.getInputStream()).readFully(body)
        return Response(h.substringAfter(' ').substringBefore(' ').toInt(),h,body)
    }
    private inner class Fixture(capacity:Int=16,val work:suspend (ByteArray)->ByteArray={DnsTestWire.answer(it)}):AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val session=ConnectUdpSession(capacity,enableSocks=true)
        val rules=RuleEngine();val registry=ConnectionRegistry(64,64);val calls=AtomicInteger();val active=AtomicInteger();val cancelled=AtomicInteger();val legacyDns=AtomicInteger()
        val backend=DnsMessageQuery { _,query -> calls.incrementAndGet();active.incrementAndGet()
            try{work(query)}catch(c:CancellationException){cancelled.incrementAndGet();throw c}finally{active.decrementAndGet()} }
        val connector=OutboundConnector(object:EgressGuard{override fun isAllowed(addr:InetAddress)=true},
            systemDns=SystemDnsLookup{_,_->legacyDns.incrementAndGet();listOf(loopback)}).apply{backupDnsEnabled=false}
        val auth=SingleCredentialAuthenticator("u","p",true)
        val socks=Socks5ProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),{auth},{ConnectionLimits()},Dispatchers.IO,
            ruleEngine=rules,udpSession=session,dnsMessages=backend)
        val http=HttpProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),{auth},{ConnectionLimits()},Dispatchers.IO,
            ruleEngine=rules,udpSession=session,socksCapabilities={socks.capabilities()},dnsMessages=backend)
        val ports:List<Int>
        init{socks.start(scope,0,loopback);http.start(scope,0,loopback);ports=listOf(ProxyTestKit.awaitPort(http),ProxyTestKit.awaitPort(socks))}
        fun send(port:Int,wire:ByteArray=DnsTestWire.query(),path:String="/dns-query",authenticate:Boolean=true,extra:String=""):Socket {
            val s=Socket(loopback,port).apply{soTimeout=5000}
            try{s.getOutputStream().write(("POST $path HTTP/1.1\r\nHost: phone\r\n"+(if(authenticate)basic else "")+
                "Content-Type: application/dns-message\r\nContent-Length: ${wire.size}\r\n$extra\r\n").toByteArray()+wire);return s}
            catch(t:Throwable){s.close();throw t}
        }
        fun get(port:Int,path:String):Response=Socket(loopback,port).use{s->s.soTimeout=4000;s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: phone\r\n$basic\r\n".toByteArray());response(s)}
        override fun close(){session.close();http.stop();socks.stop();scope.cancel();connector.cancelPendingDns();backend.close();
            assertTrue(ProxyTestKit.await(5000){registry.activeGlobal==0&&session.activeLeases==0&&active.get()==0})}
    }

    @Test(timeout=15000) fun bothPortsExposeAuthenticatedRawDnsAndAccurateCapabilitiesWithoutAHandshake() {
        Fixture().use{f->for((index,port) in f.ports.withIndex()) {
            f.send(port,authenticate=false).use{assertEquals(407,response(it).code)}
            val cap=f.get(port,"/__hxmyproxy/capabilities");assertEquals(200,cap.code)
            val json=String(cap.bytes);assertTrue(json.contains("\"dns_query\":{\"path\":\"/dns-query\""));assertTrue(json.contains("\"connect_udp_h1\":${index==0}"))
            val wire=DnsTestWire.query(type=65,id=0x1200+index)
            f.send(port,wire).use{val answer=response(it);assertEquals(200,answer.code);assertTrue(answer.headers.contains("application/dns-message"));assertArrayEquals(DnsTestWire.answer(wire),answer.bytes)}
            val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
            assertArrayEquals(DnsTestWire.answer(wire),f.get(port,"/dns-query?dns=$encoded").bytes)
        };assertEquals(4,f.calls.get());assertEquals(0,f.legacyDns.get())}
    }

    @Test(timeout=15000) fun blockedQuestionIsAValidRefusedReplyAndInvalidInputsNeverReachTheResolver() {
        Fixture().use{f->f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply{add("blocked.test")}))
            for(port in f.ports){
                val q=DnsTestWire.query("blocked.test",16);f.send(port,q).use{val r=response(it);assertEquals(200,r.code);assertEquals(5,DnsMessage.response(r.bytes,DnsMessage.query(q)).flags and 15)}
                f.send(port,q.copyOf().apply{this[5]=2}).use{assertEquals(400,response(it).code)}
                f.send(port,extra="X-HxmyProxy-Session: stale\r\n").use{assertEquals(409,response(it).code)}
            };assertEquals(0,f.calls.get())
        }
    }

    @Test(timeout=15000) fun udpUsesLegalTcWhileTcpAndDefaultRetainTheFullDnsAnswer() {
        Fixture(work={DnsTestWire.answer(it,ByteArray(4000){1})}).use{f->for(port in f.ports){
            val q=DnsTestWire.query(type=16,udpSize=512)
            f.send(port,q,path="/dns-query?transport=udp").use{val r=response(it);assertEquals(200,r.code);assertTrue(r.bytes.size<=512);assertTrue(DnsMessage.response(r.bytes,DnsMessage.query(q)).flags and 0x200!=0)}
            f.send(port,q,path="/dns-query?transport=tcp").use{val r=response(it);assertEquals(200,r.code);assertArrayEquals(DnsTestWire.answer(q,ByteArray(4000){1}),r.bytes)}
        }}
    }

    @Test(timeout=15000) fun socksControlDoesNotBecomeAnHttpProxyAndStandardSocksStillWorks() {
        Fixture().use{f->assertEquals(404,f.get(f.ports[1],"http://must-not-dial.example/").code);assertEquals(0,f.legacyDns.get())
            ProxyTestKit.startEcho().use{echo->Socket(loopback,f.ports[1]).use{s->s.soTimeout=4000
                s.getOutputStream().write(byteArrayOf(5,1,2));assertArrayEquals(byteArrayOf(5,2),ByteArray(2).also{DataInputStream(s.getInputStream()).readFully(it)})
                s.getOutputStream().write(byteArrayOf(1,1,'u'.code.toByte(),1,'p'.code.toByte()));assertArrayEquals(byteArrayOf(1,0),ByteArray(2).also{DataInputStream(s.getInputStream()).readFully(it)})
                s.getOutputStream().write(byteArrayOf(5,1,0,1,127,0,0,1,(echo.localPort ushr 8).toByte(),echo.localPort.toByte()))
                val reply=ByteArray(10);DataInputStream(s.getInputStream()).readFully(reply);assertEquals(0,reply[1].toInt())
                s.getOutputStream().write(byteArrayOf(9));assertEquals(9,s.getInputStream().read())
            }}
        }
    }

    @Test(timeout=15000) fun quotaNetworkEpochAndRulesCancelPendingDnsWithoutLateReplies() {
        Fixture(capacity=1,work={awaitCancellation()}).use{f->
            f.send(f.ports[0]).use{first->assertTrue(ProxyTestKit.await{f.active.get()==1})
                f.send(f.ports[1]).use{assertEquals(429,response(it).code)}
                f.session.changePolicy("credentials-changed"){f.auth.updateCredentials("u","new")}
                assertEquals(-1,first.getInputStream().read());assertTrue(ProxyTestKit.await{f.active.get()==0&&f.session.activeLeases==0})
            }
            f.auth.updateCredentials("u","p")
            f.send(f.ports[1]).use{s->assertTrue(ProxyTestKit.await{f.active.get()==1})
                f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply{add("qa.test")}));assertEquals(-1,s.getInputStream().read())
            }
            assertTrue(ProxyTestKit.await{f.cancelled.get()==2&&f.active.get()==0})
        }
    }

    @Test(timeout=10000) fun queryDeadlineCancelsTheBackendAndDirectWithoutPhysicalNetworkCannotFallBack() {
        Fixture(work={awaitCancellation()}).use{f->f.send(f.ports[0]).use{assertEquals(504,response(it).code)};assertEquals(1,f.cancelled.get())}
        Fixture().use{f->f.rules.update(RuleEngine.Snapshot(userDirect=RuleMatcher().apply{add("qa.test")}))
            f.send(f.ports[0]).use{assertEquals(503,response(it).code)};assertEquals(0,f.calls.get())
        }
    }
}
