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

/** Real platform rawQuery + controlled DNS Network + production HTTP/SOCKS control routes. */
class DnsMessageLifecycleTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val ctx=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
    private fun await(label:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+15000;while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);assertTrue(label,condition())}
    private fun wire(host:String="qa.test",type:Int=1,id:Int=1234,udp:Int?=1232):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.writeShort(id);out.writeShort(0x0110);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(if(udp==null)0 else 1)
        for(label in host.split('.')){val b=label.toByteArray();out.writeByte(b.size);out.write(b)};out.writeByte(0);out.writeShort(type);out.writeShort(1)
        if(udp!=null){out.writeByte(0);out.writeShort(41);out.writeShort(udp);out.writeInt(0x8000);out.writeShort(0)}
        return bytes.toByteArray()
    }
    private data class Response(val status:Int,val body:ByteArray)
    private fun response(s:Socket):Response {
        val head=buildString{while(!endsWith("\r\n\r\n")){val b=s.getInputStream().read();if(b<0)throw EOFException();append(b.toChar());check(length<32768)}}
        val n=Regex("(?i)content-length: ([0-9]+)").find(head)!!.groupValues[1].toInt();val body=ByteArray(n);DataInputStream(s.getInputStream()).readFully(body)
        return Response(head.substringAfter(' ').substringBefore(' ').toInt(),body)
    }
    private fun word(b:ByteArray,n:Int)=((b[n].toInt() and 255) shl 8) or (b[n+1].toInt() and 255)

    @Test fun actualDnsRecordsTruncationAndRevocationUseTheSelectedPhoneNetwork() {
        check(Build.HARDWARE=="ranchu"&&InstrumentationRegistry.getArguments().getString("controlledDnsFixture")=="true")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS");if(Build.VERSION.SDK_INT>=37)shell("pm grant ${ctx.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        val entry=EntryPointAccessors.fromApplication(ctx.applicationContext,VerificationEntryPoint::class.java)
        val repo=entry.repository();val settings=entry.settings();val store=entry.credentials()
        val original=runBlocking{settings.settings.first()};val originalCredentials=runBlocking{store.credentials.first()}
        val held=mutableListOf<Socket>();val checks=JSONArray();val packets=JSONArray();val report=JSONObject().put("versionCode",ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        fun stop(){ProxyForegroundService.stop(ctx);await("sharing stopped"){!repo.isRunning()&&ctx.getSystemService(ActivityManager::class.java).getRunningServices(100).none{it.service.className==ProxyForegroundService::class.java.name}}}
        ActivityScenario.launch(MainActivity::class.java).use {
            try{
                stop();val cm=ctx.getSystemService(ConnectivityManager::class.java)
                val physical=cm.allNetworks.first{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false}
                val address=cm.getLinkProperties(physical)!!.linkAddresses.map{it.address}.filterIsInstance<Inet4Address>().first()
                val iface=cm.getLinkProperties(physical)!!.interfaceName+"/"+address.hostAddress
                val http=ServerSocket(0,1,address).use{it.localPort};val socks=ServerSocket(http,1,address).use{ServerSocket(0,1,address).use{it.localPort}}
                var config=ProxySettings(httpPort=http,socksEnabled=true,socksPort=socks,pacEnabled=false,selectedInterfaceIds=setOf(iface),egressChoice=EgressNetworkChoice.VPN,
                    egressFallback=EgressFallback.STRICT,backupDnsEnabled=false,enabledRuleGroups=emptySet(),authEnabled=true)
                fun save(s:ProxySettings){config=s;runBlocking{settings.update{s}}}
                runBlocking{store.update("dns-qa","first")};save(config);ProxyForegroundService.start(ctx)
                await("DNS proxy listeners"){repo.state.value.let{it.running&&it.diagnostics.httpPortUp&&it.diagnostics.socksPortUp}}
                await("selected controlled DNS network"){entry.networks().egressNetwork()?.let{cm.getLinkProperties(it)?.dnsServers?.any{a->a.hostAddress=="198.18.0.53"}}==true}
                report.put("selectedDnsNetwork",entry.networks().egressNetwork()!!.networkHandle)
                fun epoch()=(repo.javaClass.getDeclaredField("udpSession").apply{isAccessible=true}.get(repo) as ConnectUdpSession).currentEpoch
                var last=epoch();var since=SystemClock.elapsedRealtime();await("initial callbacks settled"){val now=epoch();if(now!=last){last=now;since=SystemClock.elapsedRealtime()};SystemClock.elapsedRealtime()-since>200}
                fun send(port:Int,q:ByteArray,path:String="/dns-query",password:String?="first"):Socket {
                    val s=Socket(address,port).apply{soTimeout=5000};held.add(s)
                    val auth=password?.let{"Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("dns-qa:$it".toByteArray())}\r\n"}.orEmpty()
                    s.getOutputStream().write(("POST $path HTTP/1.1\r\nHost: phone\r\n$auth"+"Content-Type: application/dns-message\r\nContent-Length: ${q.size}\r\n\r\n").toByteArray()+q);return s
                }
                fun querySeen(name:String):Boolean=runCatching{val data=JSONObject(shell("run-as com.mzstd.hxmyproxy.udpvpnfixture cat files/status.json")).getJSONArray("dnsQueries");(0 until data.length()).any{data.getJSONObject(it).getString("name")==name}}.getOrDefault(false)
                for(port in listOf(http,socks))for(type in listOf(1,28,16,33,64,65)){
                    val q=wire(type=type,id=type+port);send(port,q).use{s->val r=response(s);assertEquals(200,r.status);assertEquals(word(q,0),word(r.body,0));assertEquals(1,word(r.body,6));assertEquals(1,word(r.body,10))
                        packets.put(JSONObject().put("port",port).put("type",type).put("query",Base64.getEncoder().encodeToString(q)).put("answer",Base64.getEncoder().encodeToString(r.body)))}
                };checks.put("both-ports-six-RR-types-EDNS-and-original-IDs")
                send(http,wire("missing.qa.test")).use{val r=response(it);assertEquals(200,r.status);assertEquals(3,word(r.body,2) and 15)}
                val large=wire("large.qa.test",16,udp=512)
                send(socks,large,"/dns-query?transport=udp").use{val r=response(it);assertEquals(200,r.status);assertTrue(r.body.size<=512);assertTrue(word(r.body,2) and 0x200!=0);assertEquals(0,word(r.body,6))}
                send(socks,large,"/dns-query?transport=tcp").use{val r=response(it);assertEquals(200,r.status);assertTrue(r.body.size>4000);assertEquals(1,word(r.body,6))}
                checks.put("negative-answer-and-legal-UDP-TC-versus-full-TCP")
                send(http,wire(),password=null).use{assertEquals(407,response(it).status)}
                send(socks,wire().apply{this[12]=0xc0.toByte();this[13]=12}).use{assertEquals(400,response(it).status)}
                val pending=send(http,wire("slow.qa.test"));await("controlled pending query"){querySeen("slow.qa.test")}
                save(config.copy(userRejectRules=listOf(RuleEntry("slow.qa.test"))))
                await("new reject applied"){repo.state.value.ruleApplication.let{it.phase==RuleApplyPhase.APPLIED&&it.counts.userReject==1}}
                assertEquals(-1,pending.getInputStream().read())
                send(http,wire("slow.qa.test")).use{val r=response(it);assertEquals(200,r.status);assertEquals(5,word(r.body,2) and 15)}
                checks.put("rule-revokes-native-query-and-returns-REFUSED")
                val rotating=send(socks,wire("slow-auth.qa.test"));await("pending credential query"){querySeen("slow-auth.qa.test")}
                runBlocking{store.update("dns-qa","second")};assertEquals(-1,rotating.getInputStream().read())
                send(http,wire()).use{assertEquals(407,response(it).status)}
                send(http,wire(),password="second").use{assertEquals(200,response(it).status)}
                checks.put("credential-epoch-revokes-native-query")
                val off=send(http,wire("silent.qa.test"),password="second");await("pending stop query"){querySeen("silent.qa.test")}
                val t=SystemClock.elapsedRealtime();stop();assertEquals(-1,off.getInputStream().read());assertTrue(SystemClock.elapsedRealtime()-t<2500)
                checks.put("OFF-cancels-before-DNS-deadline")
                report.put("passed",true)
            }catch(error:Throwable){report.put("passed",false).put("failure",error.toString());throw error}
            finally{
                held.forEach{runCatching{it.close()}};try{stop()}finally{runBlocking{store.update(originalCredentials.username,originalCredentials.password);settings.update{original}}}
                File(ctx.filesDir,"dns-message-lifecycle.json").writeText(report.put("checks",checks).put("packets",packets).toString(2))
            }
        }
    }
}
