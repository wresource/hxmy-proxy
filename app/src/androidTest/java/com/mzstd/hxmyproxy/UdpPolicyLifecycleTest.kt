package com.mzstd.hxmyproxy

import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.RuleApplyPhase
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.testsupport.VerificationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.net.*
import java.nio.ByteBuffer
import java.util.Base64

/** Real persisted settings/credentials -> real foreground service and real bound UDP sockets.
 * Explicitly owned emulator/QA VPN/loopback physical peer; never an Internet probe. */
class UdpPolicyLifecycleTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun shell(command: String)=instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() }
    }
    private fun await(label: String, test: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(!test()&&SystemClock.elapsedRealtime()<end)Thread.sleep(20)
        assertTrue(label,test())
    }
    private fun headers(s: Socket): String=buildString {
        while(!endsWith("\r\n\r\n")){val b=s.getInputStream().read();if(b<0)throw EOFException("Carrier closed before HTTP response");append(b.toChar());check(length<32768)}
    }
    private fun sendH1(s: Socket,b: ByteArray) {
        val f=UdpCapsuleEncoder.datagram(b).bytes;s.getOutputStream().write(ByteArray(f.remaining()).also { f.get(it) })
    }
    private fun receiveH1(s: Socket): ByteArray {
        val input=DataInputStream(s.getInputStream())
        fun number(): Long {val first=input.readUnsignedByte();var n=(first and 63).toLong();repeat((1 shl (first ushr 6))-1){n=(n shl 8) or input.readUnsignedByte().toLong()};return n}
        assertEquals(0L,number());val length=number();assertEquals(0L,number())
        return ByteArray((length-1).toInt()).also { input.readFully(it) }
    }

    @Test fun realAuthenticationEgressAndNetworkChangesRevokeOldUdpAndRecover() {
        check(Build.HARDWARE=="ranchu" && InstrumentationRegistry.getArguments().getString("controlledUdpFixture")=="true")
        val physicalPort=InstrumentationRegistry.getArguments().getString("physicalUdpPort")!!.toInt()
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        if(Build.VERSION.SDK_INT>=37)shell("pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        val entry=EntryPointAccessors.fromApplication(context.applicationContext,VerificationEntryPoint::class.java)
        val repo=entry.repository();val settings=entry.settings();val credentials=entry.credentials()
        val original=runBlocking { settings.settings.first() };val oldCredential=runBlocking { credentials.credentials.first() }
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val checks=JSONArray();val result=JSONObject().put("versionCode",context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
        val held=mutableListOf<Socket>()
        // Observe the actual app generation, not a fixed sleep or retrying an assertion.
        // Android may send default/VPN/physical callbacks for one network transition.
        fun epoch(): Long = (repo.javaClass.getDeclaredField("udpSession").apply{isAccessible=true}.get(repo) as ConnectUdpSession).currentEpoch
        fun awaitNetworkQuiescence() {
            var previous=epoch();var since=SystemClock.elapsedRealtime()
            await("observed network generation settled") {
                val now=epoch()
                if(now!=previous){previous=now;since=SystemClock.elapsedRealtime()}
                SystemClock.elapsedRealtime()-since>=250
            }
        }
        fun checkDone(name: String){checks.put(name)}
        fun stop() {ProxyForegroundService.stop(context);await("FGS stopped"){!repo.isRunning()&&context.getSystemService(ActivityManager::class.java).getRunningServices(100).none{it.service.className==ProxyForegroundService::class.java.name}}}
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                stop()
                val physical=cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false }
                val address=cm.getLinkProperties(physical)!!.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().first()
                val iface=cm.getLinkProperties(physical)!!.interfaceName+"/"+address.hostAddress
                val httpPort=ServerSocket(0,1,address).use { it.localPort }
                val socksPort=ServerSocket(httpPort,1,address).use { ServerSocket(0,1,address).use { it.localPort } }
                var config=ProxySettings(httpPort=httpPort,socksEnabled=true,socksPort=socksPort,pacEnabled=false,selectedInterfaceIds=setOf(iface),
                    egressChoice=EgressNetworkChoice.VPN,egressFallback=EgressFallback.STRICT,backupDnsEnabled=false,enabledRuleGroups=emptySet(),authEnabled=true)
                fun save(value: ProxySettings) {config=value;runBlocking { settings.update { value } }}
                runBlocking { credentials.update("qa-user","first") };save(config)
                ProxyForegroundService.start(context);await("actual listeners"){repo.state.value.let{it.running&&it.diagnostics.httpPortUp&&it.diagnostics.socksPortUp}}
                fun http(path: String,password: String?): Pair<Socket,String> {
                    val s=Socket(address,httpPort).apply { soTimeout=4000 };held.add(s)
                    val auth=password?.let{"Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("qa-user:$it".toByteArray())}\r\n"}.orEmpty()
                    val upgrade=if(path.startsWith("/.well-known/"))"Connection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n" else ""
                    s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: phone\r\n$auth$upgrade\r\n".toByteArray())
                    return s to headers(s)
                }
                val target="/.well-known/masque/udp/10.0.2.2/$physicalPort/"
                fun h1(password: String?="first", expected: Int=101): Socket {
                    val (s,h)=http(target,password);assertTrue("Expected $expected, got $h",h.startsWith("HTTP/1.1 $expected "));return s
                }
                fun native(password: String?, expectedMethod: Int=if(password==null)0 else 2, expectedAuth: Int=0): Pair<Socket,InetSocketAddress?> {
                    val s=Socket(address,socksPort).apply { soTimeout=4000 };held.add(s);val input=DataInputStream(s.getInputStream())
                    s.getOutputStream().write(byteArrayOf(5,1,(if(password==null)0 else 2).toByte()))
                    assertEquals(5,input.readUnsignedByte());assertEquals(expectedMethod,input.readUnsignedByte())
                    if(expectedMethod==255)return s to null
                    if(password!=null){val u="qa-user".toByteArray();val p=password.toByteArray();s.getOutputStream().write(byteArrayOf(1,u.size.toByte())+u+byteArrayOf(p.size.toByte())+p)
                        assertEquals(1,input.readUnsignedByte());assertEquals(expectedAuth,input.readUnsignedByte());if(expectedAuth!=0)return s to null}
                    s.getOutputStream().write(byteArrayOf(5,3,0,1,0,0,0,0,0,0));assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
                    val size=when(input.readUnsignedByte()){1->4;4->16;else->error("invalid BND address")}
                    return s to InetSocketAddress(InetAddress.getByAddress(ByteArray(size).also { input.readFully(it) }),input.readUnsignedShort())
                }
                fun roundtrip(s: Socket,prefix: String) {sendH1(s,byteArrayOf(1,2,3));assertArrayEquals(prefix.toByteArray()+byteArrayOf(1,2,3),receiveH1(s))}
                fun nativeRoundtrip(relay: InetSocketAddress,prefix: String) {
                    DatagramSocket(null).use { s -> physical.bindSocket(s);s.bind(InetSocketAddress(address,0));s.soTimeout=4000
                        val payload=byteArrayOf(7,8);val wire=SocksUdpCodec.encode(InetSocketAddress("10.0.2.2",physicalPort),payload)
                        s.send(DatagramPacket(wire,wire.size,relay));val packet=DatagramPacket(ByteArray(65536),65536);s.receive(packet)
                        val answer=SocksUdpCodec.decode(ByteBuffer.wrap(packet.data,0,packet.length))!!
                        assertArrayEquals(prefix.toByteArray()+payload,answer.payload)
                    }
                }
                h1(null,407).close();native(null,255).first.close();native("wrong",expectedAuth=1).first.close()
                val (cap,capHead)=http("/__hxmyproxy/capabilities","first");assertTrue(capHead.contains(" 200 "))
                val length=Regex("(?i)content-length: ([0-9]+)").find(capHead)!!.groupValues[1].toInt()
                val body=ByteArray(length);DataInputStream(cap.getInputStream()).readFully(body);val advertised=JSONObject(String(body)).getJSONObject("socks5")
                assertEquals(socksPort,advertised.getInt("port"));assertTrue(advertised.getBoolean("bind"));assertTrue(advertised.getBoolean("udp_associate"));cap.close()
                checkDone("wrong-and-missing-auth-denied-and-actual-capabilities")
                val first=h1();roundtrip(first,"VPN4|");val (control,relay)=native("first");nativeRoundtrip(relay!!,"VPN4|")
                runBlocking { credentials.update("qa-user","second") }
                assertEquals(-1,first.getInputStream().read());assertEquals(-1,control.getInputStream().read());h1("first",407).close();native("first",expectedAuth=1).first.close()
                var current=h1("second");roundtrip(current,"VPN4|");checkDone("credential-rotation-revokes-H1-and-native-old-auth")
                save(config.copy(authEnabled=false));assertEquals(-1,current.getInputStream().read());current=h1(null);roundtrip(current,"VPN4|");native(null).first.close()
                checkDone("auth-disable-revokes-old-generation-and-keeps-no-auth-client")
                save(config.copy(egressChoice=EgressNetworkChoice.CELLULAR));assertEquals(-1,current.getInputStream().read());current=h1(null);roundtrip(current,"PHYS|")
                checkDone("explicit-physical-egress-revokes-VPN-carrier")
                save(config.copy(egressChoice=EgressNetworkChoice.WIFI,egressFallback=EgressFallback.STRICT));assertEquals(-1,current.getInputStream().read());h1(null,502).close()
                val gate=native(null).first
                save(config.copy(egressFallback=EgressFallback.DEGRADE));assertEquals(-1,gate.getInputStream().read());current=h1(null);roundtrip(current,"PHYS|")
                checkDone("missing-pinned-network-STRICT-refuses-DEGRADE-uses-physical")
                save(config.copy(egressChoice=EgressNetworkChoice.VPN,egressFallback=EgressFallback.STRICT,directEgressChoice=DirectEgressChoice.CELLULAR,userDirectRules=listOf(RuleEntry("10.0.2.2"))))
                assertEquals(-1,current.getInputStream().read());await("DIRECT rule applied"){repo.state.value.ruleApplication.let{it.phase==RuleApplyPhase.APPLIED&&it.counts.protection>0}}
                current=h1(null);roundtrip(current,"PHYS|")
                save(config.copy(directEgressChoice=DirectEgressChoice.WIFI));assertEquals(-1,current.getInputStream().read());h1(null,503).close()
                checkDone("DIRECT-pinned-physical-and-missing-DIRECT-never-falls-to-VPN")
                save(config.copy(userDirectRules=emptyList(),directEgressChoice=DirectEgressChoice.CELLULAR))
                await("DIRECT rule removed"){repo.state.value.ruleApplication.let{it.phase==RuleApplyPhase.APPLIED&&it.counts.protection==0}}
                current=h1(null);roundtrip(current,"VPN4|")
                val oldVpn=cm.allNetworks.first{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}.networkHandle
                shell("am start -f 0x10008000 -n com.mzstd.hxmyproxy.udpvpnfixture/.LaunchActivity --ez stop true")
                await("QA VPN lost"){entry.networks().egressNetwork()==null&&cm.allNetworks.none{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}}
                assertEquals(-1,current.getInputStream().read())
                val transitionEpoch=epoch()
                try {
                    val (socket,head)=http(target,null)
                    socket.use {
                        if(head.startsWith("HTTP/1.1 409 ")) {
                            // The epoch may change after authentication but before lease acquisition.
                            // That existing guard deliberately rejects admission before any socket is opened.
                            assertNotEquals("409 requires an observed network generation change",transitionEpoch,epoch())
                            val length=Regex("(?i)content-length: ([0-9]+)").find(head)!!.groupValues[1].toInt()
                            val body=ByteArray(length);DataInputStream(socket.getInputStream()).readFully(body)
                            assertEquals("UDP authorization or route changed; reconnect",String(body,Charsets.UTF_8))
                            checkDone("pre-admission-network-revocation-is-explicit-conflict")
                        } else assertTrue("Stable unavailable route must be 502: $head",head.startsWith("HTTP/1.1 502 "))
                    }
                } catch(closed: EOFException) {
                    assertNotEquals("EOF is allowed only when a real network event revoked that request",transitionEpoch,epoch())
                    checkDone("in-flight-network-revocation-is-not-a-success-or-fallback")
                }
                awaitNetworkQuiescence();h1(null,502).close()
                shell("am start -f 0x10008000 -n com.mzstd.hxmyproxy.udpvpnfixture/.LaunchActivity --es targetApp ${context.packageName}")
                await("new QA VPN"){entry.networks().egressNetwork()?.networkHandle?.let{it!=oldVpn}==true&&cm.allNetworks.any{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true&&it.networkHandle!=oldVpn}}
                awaitNetworkQuiescence()
                current=h1(null);roundtrip(current,"VPN4|");current.close()
                checkDone("VPN-loss-closes-old-socket-STRICT-refuses-new-network-recovers")
                result.put("passed",true)
            } catch(failure: Throwable) {result.put("passed",false).put("failure",failure.toString());throw failure}
            finally {
                held.forEach{runCatching{it.close()}}
                try {stop()} finally {
                    runBlocking {credentials.update(oldCredential.username,oldCredential.password);settings.update{original}}
                    java.io.File(context.filesDir,"udp-policy-result.json").writeText(result.put("checks",checks).toString(2))
                }
            }
        }
    }
}
