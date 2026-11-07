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
import com.mzstd.hxmyproxy.core.rules.ClientAddress
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.testsupport.VerificationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.*
import java.util.Base64

/** Applied repository state, real authenticated HTTP/SOCKS control, owned emulator only. */
class ClientRouteCapabilityDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val ctx=ins.targetContext
    private fun shell(s:String)=ins.uiAutomation.executeShellCommand(s).use{ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
    private fun await(label:String,condition:()->Boolean){val until=SystemClock.elapsedRealtime()+15000;while(!condition()&&SystemClock.elapsedRealtime()<until)Thread.sleep(20);assertTrue(label,condition())}

    @Test fun appliedModeIsAuthenticatedSourceScopedAndSurvivesListenerRestart() {
        check(Build.HARDWARE=="ranchu"&&InstrumentationRegistry.getArguments().getString("ownedClientRouteFixture")=="true")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        if(Build.VERSION.SDK_INT>=37)shell("pm grant ${ctx.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        val entry=EntryPointAccessors.fromApplication(ctx.applicationContext,VerificationEntryPoint::class.java)
        val repo=entry.repository();val settings=entry.settings();val credentials=entry.credentials()
        val before=runBlocking{settings.settings.first()};val authBefore=runBlocking{credentials.credentials.first()}
        val report=JSONObject().put("versionCode",ctx.packageManager.getPackageInfo(ctx.packageName,0).longVersionCode)
        val frames=JSONArray()
        fun stop(){ProxyForegroundService.stop(ctx);await("sharing stopped"){!repo.isRunning()&&ctx.getSystemService(ActivityManager::class.java).getRunningServices(100).none{it.service.className==ProxyForegroundService::class.java.name}}}
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                stop()
                val cm=ctx.getSystemService(ConnectivityManager::class.java)
                val network=cm.allNetworks.first{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false}
                val link=cm.getLinkProperties(network)!!
                val source=link.linkAddresses.map{it.address}.filterIsInstance<Inet4Address>().first()
                val sourceKey=ClientAddress.key(source)
                val http=ServerSocket(0,1,source).use{it.localPort}
                val socks=ServerSocket(http,1,source).use{ServerSocket(0,1,source).use{it.localPort}}
                val config=ProxySettings(httpPort=http,socksEnabled=true,socksPort=socks,pacEnabled=false,authEnabled=true,
                    selectedInterfaceIds=setOf(link.interfaceName+"/"+source.hostAddress),enabledRuleGroups=emptySet())
                runBlocking{credentials.update("route-qa","owned");settings.update{config}}
                fun start(){ProxyForegroundService.start(ctx);await("listeners ready"){repo.state.value.let{it.running&&it.diagnostics.httpPortUp&&it.diagnostics.socksPortUp}}}
                start()
                fun query(port:Int,phase:String,authenticated:Boolean=true):JSONObject {
                    Socket(source,port).use{s->
                        s.soTimeout=5000
                        val auth=if(authenticated)"Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("route-qa:owned".toByteArray())}\r\n" else ""
                        s.getOutputStream().write(("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: phone\r\n"+auth+"X-Forwarded-For: 192.0.2.25\r\n\r\n").toByteArray())
                        val input=s.getInputStream();val head=buildString{while(!endsWith("\r\n\r\n")){val c=input.read();check(c>=0);append(c.toChar());check(length<16384)}}
                        val status=head.substringAfter(' ').substringBefore(' ').toInt()
                        val n=Regex("(?i)content-length: ([0-9]+)").find(head)!!.groupValues[1].toInt()
                        val bytes=ByteArray(n);DataInputStream(input).readFully(bytes);val body=String(bytes)
                        frames.put(JSONObject().put("phase",phase).put("port",port).put("status",status).put("body",if(status==200)JSONObject(body)else body))
                        if(!authenticated){assertEquals(407,status);assertFalse(body.contains("client_route"));return JSONObject()}
                        assertEquals(200,status);assertTrue(head.contains("Cache-Control: no-store"));assertFalse(body.contains("192.0.2.25"))
                        return JSONObject(body)
                    }
                }
                fun mode(mode:String,phase:String):List<JSONObject> = listOf(http,socks).map{port->
                    val json=query(port,phase);val intent=json.getJSONObject("client_route")
                    assertEquals(1,intent.getInt("version"));assertEquals("source-ip",intent.getString("scope"))
                    assertEquals(sourceKey,intent.getString("source_ip"));assertEquals(mode,intent.getString("mode"))
                    assertEquals(false,json.getJSONObject("client_policy").getBoolean("proxy_local_targets"));json
                }
                query(http,"unauth-http",false);query(socks,"unauth-socks",false)
                val initial=mode("RULES","initial").first()
                runBlocking{settings.update{it.copy(directClientIps=setOf(sourceKey))}}
                await("DIRECT applied"){sourceKey in repo.state.value.directClientIps}
                val direct=mode("DIRECT","direct").first()
                assertTrue(direct.getJSONObject("client_route").getLong("revision")>initial.getJSONObject("client_route").getLong("revision"))
                runBlocking{settings.update{it.copy(directClientIps=setOf(sourceKey,"192.0.2.25"))}}
                await("other client applied"){repo.state.value.directClientIps.size==2}
                val other=mode("DIRECT","other-client").first()
                assertTrue(other.getJSONObject("client_route").getLong("revision")>direct.getJSONObject("client_route").getLong("revision"))
                assertEquals(initial.getString("session_id"),other.getString("session_id"))
                runBlocking{settings.update{it.copy(directClientIps=emptySet())}}
                await("RULES applied"){repo.state.value.directClientIps.isEmpty()};val rules=mode("RULES","rules").first()
                assertTrue(rules.getJSONObject("client_route").getLong("revision")>other.getJSONObject("client_route").getLong("revision"))
                runBlocking{settings.update{it.copy(directClientIps=setOf(sourceKey))}}
                await("DIRECT restored"){sourceKey in repo.state.value.directClientIps};val again=mode("DIRECT","direct-again").first()
                assertTrue(again.getJSONObject("client_route").getLong("revision")>rules.getJSONObject("client_route").getLong("revision"))
                stop();start();await("persisted mode applied"){sourceKey in repo.state.value.directClientIps}
                val restarted=mode("DIRECT","listener-restarted").first()
                assertNotEquals(again.getString("session_id"),restarted.getString("session_id"))
                report.put("passed",true).put("source",sourceKey)
            } catch(e:Throwable){report.put("passed",false).put("error",e.toString());throw e}
            finally {
                try{stop()}finally{runBlocking{credentials.update(authBefore.username,authBefore.password);settings.update{before}}}
                File(ctx.filesDir,"client-route-capability.json").writeText(report.put("frames",frames).toString(2))
            }
        }
    }
}
