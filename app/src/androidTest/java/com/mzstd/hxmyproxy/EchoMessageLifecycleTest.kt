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
import com.mzstd.hxmyproxy.core.proxy.AndroidEchoMessageQuery
import com.mzstd.hxmyproxy.core.proxy.ConnectUdpSession
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
import java.io.*
import java.net.*
import java.util.Base64

/** Ordinary application UID, public ping datagram sockets and the real FGS/control routes. */
class EchoMessageLifecycleTest {
    private val ins=InstrumentationRegistry.getInstrumentation();private val ctx=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
    private fun await(label:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+15000;while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);assertTrue(label,condition())}
    private fun word(b:ByteArray,n:Int)=((b[n].toInt() and 255) shl 8) or (b[n+1].toInt() and 255)
    private fun checksum(b:ByteArray):Int {var s=0L;var at=0;while(at<b.size){s+=((b[at].toInt() and 255) shl 8) or (if(at+1<b.size)b[at+1].toInt() and 255 else 0);at+=2};while(s ushr 16!=0L)s=(s and 65535)+(s ushr 16);return s.inv().toInt() and 65535}
    private fun request(v6:Boolean=false,size:Int=64)=ByteArray(size){(it%251).toByte()}.apply{
        this[0]=(if(v6)128 else 8).toByte();this[1]=0;this[2]=0;this[3]=0;this[4]=0x12;this[5]=0x34;this[6]=0x43;this[7]=0x21
        if(!v6){val c=checksum(this);this[2]=(c ushr 8).toByte();this[3]=c.toByte()}
    }
    private fun response(s:Socket):Pair<Int,ByteArray>{val head=buildString{while(!endsWith("\r\n\r\n")){val b=s.getInputStream().read();if(b<0)throw EOFException();append(b.toChar());check(length<32768)}}
        val n=Regex("(?i)content-length: ([0-9]+)").find(head)!!.groupValues[1].toInt();return head.substringAfter(' ').substringBefore(' ').toInt() to ByteArray(n).also{DataInputStream(s.getInputStream()).readFully(it)}}

    @Test fun actualEchoIdentityNetworkRulesCredentialsAndOff() {
        check(Build.HARDWARE=="ranchu"&&InstrumentationRegistry.getArguments().getString("controlledDnsFixture")=="true")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS");if(Build.VERSION.SDK_INT>=37)shell("pm grant ${ctx.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        val entry=EntryPointAccessors.fromApplication(ctx.applicationContext,VerificationEntryPoint::class.java)
        val repo=entry.repository();val settings=entry.settings();val store=entry.credentials()
        val original=runBlocking{settings.settings.first()};val originalCredentials=runBlocking{store.credentials.first()}
        val held=mutableListOf<Socket>();val checks=JSONArray();val packets=JSONArray();val report=JSONObject().put("versionCode",ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        fun stop(){ProxyForegroundService.stop(ctx);await("sharing stopped"){!repo.isRunning()&&ctx.getSystemService(ActivityManager::class.java).getRunningServices(100).none{it.service.className==ProxyForegroundService::class.java.name}}}
        ActivityScenario.launch(MainActivity::class.java).use{
            try{
                stop();val cm=ctx.getSystemService(ConnectivityManager::class.java)
                val physical=cm.allNetworks.first{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false}
                val address=cm.getLinkProperties(physical)!!.linkAddresses.map{it.address}.filterIsInstance<Inet4Address>().first()
                val iface=cm.getLinkProperties(physical)!!.interfaceName+"/"+address.hostAddress
                val http=ServerSocket(0,1,address).use{it.localPort};val socks=ServerSocket(http,1,address).use{ServerSocket(0,1,address).use{it.localPort}}
                var config=ProxySettings(httpPort=http,socksEnabled=true,socksPort=socks,pacEnabled=false,selectedInterfaceIds=setOf(iface),egressChoice=EgressNetworkChoice.VPN,
                    egressFallback=EgressFallback.STRICT,backupDnsEnabled=false,enabledRuleGroups=emptySet(),authEnabled=true)
                fun save(s:ProxySettings){config=s;runBlocking{settings.update{s}}}
                runBlocking{store.update("echo-qa","first")};save(config);ProxyForegroundService.start(ctx)
                await("Echo proxy listeners"){repo.state.value.let{it.running&&it.diagnostics.httpPortUp&&it.diagnostics.socksPortUp}}
                await("controlled network"){entry.networks().egressNetwork()?.let{cm.getLinkProperties(it)?.dnsServers?.any{a->a.hostAddress=="198.18.0.53"}}==true}
                fun epoch()=(repo.javaClass.getDeclaredField("udpSession").apply{isAccessible=true}.get(repo) as ConnectUdpSession).currentEpoch
                var last=epoch();var since=SystemClock.elapsedRealtime();await("initial callbacks settled"){val now=epoch();if(now!=last){last=now;since=SystemClock.elapsedRealtime()};SystemClock.elapsedRealtime()-since>200}
                val backend=repo.javaClass.getDeclaredField("echoMessages").apply{isAccessible=true}.get(repo) as AndroidEchoMessageQuery
                report.put("ipv4",backend.ipv4).put("ipv6",backend.ipv6);assertTrue(backend.ipv4&&backend.ipv6)
                fun send(port:Int,ip:String="198.18.0.7",wire:ByteArray=request(),password:String?="first"):Socket {
                    val s=Socket(address,port).apply{soTimeout=5000};held.add(s)
                    val auth=password?.let{"Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("echo-qa:$it".toByteArray())}\r\n"}.orEmpty()
                    s.getOutputStream().write(("POST /icmp-echo?target=$ip HTTP/1.1\r\nHost: phone\r\n$auth"+"Content-Type: application/octet-stream\r\nContent-Length: ${wire.size}\r\n\r\n").toByteArray()+wire);return s
                }
                for(port in listOf(http,socks))for(v6 in listOf(false,true))for(size in listOf(8,9,64,1208)){
                    val q=request(v6,size);send(port,if(v6)"2001:db8:77::7" else "198.18.0.7",q).use{val(code,b)=response(it);assertEquals(200,code)
                        assertEquals(size,b.size);assertEquals(if(v6)129 else 0,b[0].toInt() and 255);assertEquals(0,b[1].toInt());assertEquals(0x1234,word(b,4));assertEquals(0x4321,word(b,6))
                        assertArrayEquals(q.copyOfRange(8,size),b.copyOfRange(8,size));if(v6)assertEquals(0,word(b,2))else assertEquals(0,checksum(b))
                        packets.put(JSONObject().put("port",port).put("ipv6",v6).put("size",size).put("id",word(b,4)).put("sequence",word(b,6)))}
                };checks.put("both-ports-v4-v6-four-sizes-original-ID-sequence-data-checksum")
                send(http,password=null).use{assertEquals(407,response(it).first)}
                send(socks,wire=request().apply{this[0]=3}).use{assertEquals(400,response(it).first)}
                send(http,ip="127.0.0.1").use{assertEquals(403,response(it).first)};checks.put("authentication-type-and-self-target-guard")
                val fdBefore=File("/proc/self/fd").list()!!.size
                send(http,ip="198.18.0.250").use{assertEquals(504,response(it).first)}
                await("timeout closes native operation"){backend.pendingOperations==0};checks.put("silent-echo-times-out-and-releases")
                val pending=send(http,ip="198.18.0.250");await("pending rule query"){backend.pendingOperations==1}
                save(config.copy(userRejectRules=listOf(RuleEntry("198.18.0.250"))))
                await("new reject applied"){repo.state.value.ruleApplication.let{it.phase==RuleApplyPhase.APPLIED&&it.counts.userReject==1}}
                assertEquals(-1,pending.getInputStream().read());await("rule closes socket"){backend.pendingOperations==0}
                send(http,ip="198.18.0.250").use{assertEquals(403,response(it).first)};checks.put("rule-revokes-native-echo")
                save(config.copy(userRejectRules=emptyList()));await("reject removed"){repo.state.value.ruleApplication.let{it.phase==RuleApplyPhase.APPLIED&&it.counts.userReject==0}}
                val rotating=send(socks,ip="198.18.0.250");await("pending credential query"){backend.pendingOperations==1}
                runBlocking{store.update("echo-qa","second")};assertEquals(-1,rotating.getInputStream().read());await("credentials close socket"){backend.pendingOperations==0}
                send(http).use{assertEquals(407,response(it).first)};send(http,password="second").use{assertEquals(200,response(it).first)};checks.put("credential-epoch-revokes-native-echo")
                val off=send(http,ip="198.18.0.250",password="second");await("pending stop query"){backend.pendingOperations==1}
                val t=SystemClock.elapsedRealtime();stop();assertEquals(-1,off.getInputStream().read());assertTrue(SystemClock.elapsedRealtime()-t<2500)
                await("OFF releases sockets"){backend.pendingOperations==0};checks.put("OFF-cancels-before-deadline")
                report.put("fdBefore",fdBefore).put("fdAfterStop",File("/proc/self/fd").list()!!.size).put("passed",true)
            }catch(error:Throwable){report.put("passed",false).put("failure",error.toString());throw error}
            finally{held.forEach{runCatching{it.close()}};try{stop()}finally{runBlocking{store.update(originalCredentials.username,originalCredentials.password);settings.update{original}}}
                File(ctx.filesDir,"echo-message-lifecycle.json").writeText(report.put("checks",checks).put("packets",packets).toString(2))}
        }
    }
}
