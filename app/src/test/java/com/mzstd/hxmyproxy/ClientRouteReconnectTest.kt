package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException

class ClientRouteReconnectTest {
    @Test(timeout=15000) fun routingChangeReleasesOnlyTheSelectedClientAndPreservesOtherLiveTraffic() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val registry=ConnectionRegistry(16,16)
        val rules=RuleEngine()
        val connector=testConnector(object:EgressGuard { override fun isAllowed(addr:InetAddress)=true })
        val servers=listOf("127.0.0.1","::1").map { address ->
            HttpProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),
                {NoAuthAuthenticator},{ConnectionLimits()},Dispatchers.IO,ruleEngine=rules).also {
                it.start(scope,0,InetAddress.getByName(address))
            }
        }
        try {
            ProxyTestKit.startEcho().use { peer ->
                fun open(index:Int):Socket {
                    val client=Socket(if(index==0)"127.0.0.1" else "::1",ProxyTestKit.awaitPort(servers[index])).apply{soTimeout=3000}
                    client.getOutputStream().write("CONNECT 127.0.0.1:${peer.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${peer.localPort}\r\n\r\n".toByteArray())
                    assertEquals(200,ProxyTestKit.readHead(client.getInputStream()).status)
                    return client
                }
                fun echo(client:Socket,value:Int) { client.getOutputStream().write(value);assertEquals(value,client.getInputStream().read()) }
                open(0).use { selected -> open(1).use { other ->
                    echo(selected,11);echo(other,22)
                    val changed=rules.updateDirectClients(setOf("127.0.0.1"))
                    servers.forEach { it.disconnectClients(changed) }
                    val closed=try{selected.getInputStream().read()==-1}catch(_:SocketException){true}
                    assertTrue(closed)
                    assertTrue(ProxyTestKit.await(3000){registry.activeGlobal==1})
                    echo(other,23);echo(other,24)
                    val restoredMode=rules.updateDirectClients(emptySet())
                    servers.forEach { it.disconnectClients(restoredMode) }
                    open(0).use { restored -> echo(restored,33) }
                    echo(other,25)
                } }
            }
        } finally {
            servers.forEach { it.stop() };scope.cancel();connector.cancelPendingDns()
            assertTrue(ProxyTestKit.await(3000){registry.activeGlobal==0})
        }
    }
}
