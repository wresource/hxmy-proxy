package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.security.*
import com.mzstd.hxmyproxy.core.security.Authenticator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.Base64

/** Real HTTP and same-port SOCKS control, with no DNS/outbound business access. */
class ClientRouteCapabilityTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private class Reply(val status:Int,val body:String)
    private inner class Fixture(val rules:RuleEngine?=RuleEngine(), val auth:Authenticator=NoAuthAuthenticator):AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val registry=ConnectionRegistry()
        val session=ConnectUdpSession()
        val connector=OutboundConnector(object:EgressGuard { override fun isAllowed(addr:InetAddress)=false },
            systemDns=SystemDnsLookup { _,_->error("Capabilities must never perform DNS") })
        val http=HttpProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),
            {auth},{ConnectionLimits()},Dispatchers.IO,ruleEngine=rules,udpSession=session,proxyLocalTargets={false})
        val socks=Socks5ProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),
            {auth},{ConnectionLimits()},Dispatchers.IO,ruleEngine=rules,udpSession=session,proxyLocalTargets={false})
        val ports:List<Int>
        init { http.start(scope,0,loopback);socks.start(scope,0,loopback);ports=listOf(ProxyTestKit.awaitPort(http),ProxyTestKit.awaitPort(socks)) }
        fun query(port:Int=ports.first(),headers:String=""):Reply=ProxyTestKit.client(port).use {
            val head=ProxyTestKit.sendAndReadHead(it,"GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: phone\r\n$headers\r\n")
            Reply(head.status,String(ProxyTestKit.readBody(it.getInputStream(),head)))
        }
        override fun close(){session.close();http.stop();socks.stop();connector.cancelPendingDns();scope.cancel()
            assertTrue(ProxyTestKit.await { registry.activeGlobal==0 })}
    }
    private fun route(body:String):String = Regex("\"client_route\":(\\{[^}]+})").find(body)?.groupValues?.get(1)
        ?: throw AssertionError("Missing client_route: $body")
    private fun revision(route:String):Long=Regex("\"revision\":(\\d+)").find(route)!!.groupValues[1].toLong()
    private fun mode(route:String):String=Regex("\"mode\":\"([^\"]+)\"").find(route)!!.groupValues[1]

    @Test(timeout=15000) fun bothPortsExposeOnlyAppliedSourceScopedIntentAndKeepOldPolicy() {
        Fixture().use { f ->
            f.rules!!.updateDirectClients(setOf("192.0.2.25"))
            for(port in f.ports){val r=f.query(port,"X-Forwarded-For: 192.0.2.25\r\nForwarded: for=192.0.2.25\r\n")
                assertEquals(200,r.status);val intent=route(r.body)
                assertTrue(intent.contains("\"version\":1"));assertTrue(intent.contains("\"scope\":\"source-ip\""))
                assertTrue(intent.contains("\"source_ip\":\"127.0.0.1\""));assertEquals("RULES",mode(intent))
                assertFalse("Must not expose other clients",r.body.contains("192.0.2.25"))
                assertTrue(r.body.contains("\"client_policy\":{\"version\":1,\"revision\":0,\"proxy_local_targets\":false}"))
                assertTrue(r.body.contains("\"session_id\":\"${f.session.id}\""))
            }
            f.rules.updateDirectClients(setOf("127.0.0.1"))
            for(port in f.ports)assertEquals("DIRECT",mode(route(f.query(port).body)))
            assertEquals(RuleSrc.CLIENT_DIRECT,f.rules.decideDetailed("blocked.example",loopback).src)
        }
    }
    @Test(timeout=15000) fun modeAbaAdvancesRevisionButDomainRulesAndNoOpUpdatesDoNot() {
        Fixture().use { f ->
            val first=route(f.query().body);assertEquals(0L,revision(first))
            f.rules!!.updateDirectClients(setOf("127.0.0.1"));val direct=route(f.query().body)
            assertEquals("DIRECT",mode(direct));assertTrue(revision(direct)>revision(first))
            f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply{add("example.com")}))
            f.rules.updateDirectClients(setOf("127.0.0.1"));assertEquals(direct,route(f.query().body))
            f.rules.updateDirectClients(emptySet());val rules=route(f.query().body)
            assertEquals("RULES",mode(rules));assertTrue(revision(rules)>revision(direct))
            f.rules.updateDirectClients(setOf("127.0.0.1"));val again=route(f.query().body)
            assertEquals("DIRECT",mode(again));assertTrue(revision(again)>revision(rules))
        }
    }
    @Test(timeout=15000) fun authenticationPrecedesClientIntentOnBothPorts() {
        Fixture(auth=SingleCredentialAuthenticator("user","secret",enabled=true)).use { f ->
            f.rules!!.updateDirectClients(setOf("127.0.0.1"))
            for(port in f.ports){val denied=f.query(port);assertEquals(407,denied.status);assertFalse(denied.body.contains("client_route"))
                val allowed=f.query(port,"Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("user:secret".toByteArray())}\r\n")
                assertEquals(200,allowed.status);assertEquals("DIRECT",mode(route(allowed.body)))
            }
        }
    }
    @Test(timeout=15000) fun missingRuntimeEngineDoesNotInventAnAuthoritativeMode() {
        Fixture(rules=null).use { f ->for(port in f.ports){val r=f.query(port);assertEquals(200,r.status);assertFalse(r.body.contains("client_route"))} }
    }
}
