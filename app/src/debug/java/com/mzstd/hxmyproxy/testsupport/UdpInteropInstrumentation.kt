package com.mzstd.hxmyproxy.testsupport

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.mzstd.hxmyproxy.MainActivity
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.network.InterfaceScanner
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Explicitly invoked only on an owned emulator. Real application settings/FGS/repository/ports.
 * Release does not include this instrumentation or its raw loopback ingress adapter. */
class UdpInteropInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val versionCode = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).longVersionCode
        val result = JSONObject().put("versionCode", versionCode).put("sdk", Build.VERSION.SDK_INT)
        val checks = JSONArray()
        var failure: Throwable? = null
        try {
            check(Build.HARDWARE == "ranchu") { "Owned read-only emulator only" }
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val entry = EntryPointAccessors.fromApplication(targetContext.applicationContext, VerificationEntryPoint::class.java)
            val repository = entry.repository();val settings = entry.settings()
            val original = runBlocking { settings.settings.first() }
            val credentials = entry.credentials()
            val originalCredentials = runBlocking { credentials.credentials.first() }
            val servingAuth = arguments.getString("auth") == "true"
            val authHeader = if (servingAuth) "Proxy-Authorization: Basic " +
                java.util.Base64.getEncoder().encodeToString("qa-proxy:qa-proxy-secret".toByteArray()) + "\r\n" else ""
            fun await(label: String, timeout: Long = 20000, predicate: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + timeout
                while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
                check(predicate()) { "$label: ${repository.state.value}" }
            }
            fun stop() {
                ProxyForegroundService.stop(targetContext)
                await("proxy stopped") { !repository.isRunning() && targetContext.getSystemService(ActivityManager::class.java).getRunningServices(100)
                    .none { it.service.className == ProxyForegroundService::class.java.name } }
            }
            stop()
            val physicalAddresses = targetContext.getSystemService(ConnectivityManager::class.java).let { cm ->
                cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
                    .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }.map { it.address }.toSet()
            }
            val iface = InterfaceScanner().scan(emptySet()).first { it.address is Inet4Address && it.address in physicalAddresses }
            val port = ServerSocket(0, 1, iface.address).use { it.localPort }
            val nativeMode = arguments.getString("mode") == "native-self"
            val exposeSocks = nativeMode || arguments.getString("socks") == "true"
            val socksPort = ServerSocket(port, 1, iface.address).use { ServerSocket(0, 1, iface.address).use { s -> s.localPort } }
            val testEgress = EgressNetworkChoice.valueOf(arguments.getString("egress", "VPN")!!)
            val hostDirect = arguments.getString("hostDirect") == "true"
            val base = ProxySettings(httpEnabled = true, httpPort = port, socksEnabled = exposeSocks, socksPort = socksPort, pacEnabled = false,
                selectedInterfaceIds = setOf(iface.id), egressChoice = testEgress,
                egressFallback = EgressFallback.STRICT, backupDnsEnabled = false, enabledRuleGroups = emptySet(), authEnabled = servingAuth,
                directEgressChoice = if(hostDirect)DirectEgressChoice.CELLULAR else DirectEgressChoice.AUTO,
                userDirectRules = if(hostDirect)listOf(RuleEntry("10.0.2.2")) else emptyList())
            fun save(value: ProxySettings) = runBlocking { settings.update { value } }
            fun start(expectHttp: Boolean = true) {
                ProxyForegroundService.start(targetContext)
                await("real service listener ready") { repository.state.value.let { it.running && !it.starting &&
                    (if (expectHttp) it.diagnostics.httpPortUp else it.diagnostics.socksPortUp) } }
            }
            fun client() = Socket(iface.address, port).apply { soTimeout = 4000; tcpNoDelay = true }
            fun capability(): String = client().use { s ->
                s.getOutputStream().write("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: phone\r\n$authHeader\r\n".toByteArray())
                val headers = readHeaders(s);check(headers.startsWith("HTTP/1.1 200 ")) { headers }
                val length = Regex("(?i)content-length: ([0-9]+)").find(headers)!!.groupValues[1].toInt()
                val body = ByteArray(length);DataInputStream(s.getInputStream()).readFully(body)
                JSONObject(String(body)).getString("session_id")
            }
            fun upgrade(host: String, token: String? = null): Pair<Socket, String> {
                val s = client()
                val escaped = URLEncoder.encode(host, "UTF-8").replace("+", "%20")
                val text = "GET /.well-known/masque/udp/$escaped/53000/ HTTP/1.1\r\nHost: phone\r\nConnection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n" +
                    (token?.let { "X-HxmyProxy-Session: $it\r\n" } ?: "") + "\r\n"
                try { s.getOutputStream().write(text.toByteArray());return s to readHeaders(s) }
                catch (e: Throwable) { s.close();throw e }
            }
            try {
                if (servingAuth) runBlocking { credentials.update("qa-proxy","qa-proxy-secret") }
                save(base);start()
                if (exposeSocks) await("native SOCKS listener ready") { repository.state.value.diagnostics.socksPortUp }
                val session = capability();result.put("initialSession", session).put("interface", iface.id).put("phonePort", port)
                if (arguments.getString("mode") == "serve") {
                    val stopFile = File(targetContext.filesDir, "udp-interop-stop").apply { delete() }
                    RawIngress(iface.address, port).use { ingress ->
                        File(targetContext.filesDir, "udp-interop-ready.json").writeText(JSONObject()
                            .put("bridgePort", ingress.port).put("phoneHost", iface.address.hostAddress).put("phonePort", port)
                            .put("session", session).put("versionCode", versionCode).put("socksPort", if (exposeSocks) socksPort else JSONObject.NULL)
                            .put("authEnabled",servingAuth)
                            .put("hostDirectRule",hostDirect).put("directEgress",base.directEgressChoice.name)
                            .put("selectedDnsServers",JSONArray(targetContext.getSystemService(ConnectivityManager::class.java).let { cm ->
                                (entry.networks().egressNetwork()?:cm.activeNetwork)?.let{cm.getLinkProperties(it)?.dnsServers?.map{a->a.hostAddress}}.orEmpty()
                            }))
                            .put("egress", testEgress.name).put("defaultNetworkIsVpn", targetContext.getSystemService(ConnectivityManager::class.java).let { cm ->
                                cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                            }).toString(2))
                        val seconds = arguments.getString("seconds", "300")!!.toLong().coerceIn(10, 1800)
                        val end = SystemClock.elapsedRealtime() + seconds * 1000
                        while (!stopFile.exists() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
                        result.put("stoppedByOwner", stopFile.exists())
                    }
                } else if (nativeMode) {
                    await("native SOCKS listener") { repository.state.value.diagnostics.socksPortUp }
                    val cm = targetContext.getSystemService(ConnectivityManager::class.java)
                    val physical = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                        cm.getLinkProperties(it)?.linkAddresses?.any { a -> a.address == iface.address } == true }
                    fun association(udpPort: Int): Pair<Socket, InetSocketAddress> {
                        val control = Socket(iface.address, socksPort).apply { soTimeout = 5000 }
                        try {
                            val input = DataInputStream(control.getInputStream()); val output = control.getOutputStream()
                            output.write(byteArrayOf(5,1,0)); check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
                            output.write(byteArrayOf(5,3,0,1,0,0,0,0,(udpPort ushr 8).toByte(),udpPort.toByte()))
                            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0 && input.readUnsignedByte() == 0)
                            val size = when(input.readUnsignedByte()) { 1 -> 4; 4 -> 16; else -> error("bad bound family") }
                            val address = InetAddress.getByAddress(ByteArray(size).also { input.readFully(it) })
                            return control to InetSocketAddress(address,input.readUnsignedShort())
                        } catch(error: Throwable) { control.close(); throw error }
                    }
                    fun udp() = DatagramSocket(null).apply { physical.bindSocket(this); bind(InetSocketAddress(iface.address,0)); soTimeout=4000 }
                    fun exchange(client: DatagramSocket, relay: InetSocketAddress, host: String, payload: ByteArray,
                        targetPort:Int=53000,expectedPrefix:String=if(':' in host)"VPN6|" else "VPN4|") {
                        val wire = SocksUdpCodec.encode(InetSocketAddress(InetAddress.getByName(host),targetPort),payload)
                        client.send(DatagramPacket(wire,wire.size,relay))
                        val packet = DatagramPacket(ByteArray(65535),65535); client.receive(packet)
                        val reply = checkNotNull(SocksUdpCodec.decode(ByteBuffer.wrap(packet.data,0,packet.length)))
                        check(InetAddress.getByName(reply.host)==InetAddress.getByName(host) && reply.port==targetPort)
                        check(reply.payload.contentEquals(expectedPrefix.toByteArray()+payload))
                    }
                    udp().use { client ->
                        val (control,relay)=association(client.localPort)
                        control.use {
                            for(host in listOf("198.18.0.7","2001:db8:77::7")) for(size in listOf(0,1,1200)) {
                                exchange(client,relay,host,ByteArray(size){(it%251).toByte()})
                                checks.put(JSONObject().put("case","native-socks-roundtrip").put("host",host).put("bytes",size).put("passed",true))
                            }
                            if(arguments.getString("verifySourceIdentity")=="true") {
                                val since=SystemClock.elapsedRealtime()
                                for(host in listOf("198.18.0.7","198.18.0.8","2001:db8:77::7","2001:db8:77::8"))
                                    exchange(client,relay,host,byteArrayOf(73))
                                var rows=emptyList<JSONObject>()
                                await("fixture has recorded all four observed datagrams") {
                                    val descriptor=uiAutomation.executeShellCommand("run-as com.mzstd.hxmyproxy.udpvpnfixture cat files/status.json")
                                    val tuples=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use{JSONObject(it.readText()).getJSONArray("udpTuples")}
                                    rows=(0 until tuples.length()).map{tuples.getJSONObject(it)}.filter{it.getLong("rt")>=since&&it.getInt("targetPort")==53000}
                                    rows.size>=4
                                }
                                for(v6 in listOf(false,true)) {
                                    val family=rows.filter{(':' in it.getString("target"))==v6}
                                    check(family.map{it.getString("target")}.toSet().size==2){"Missing observed peer tuples: $family"}
                                    check(family.map{it.getInt("sourcePort")}.toSet().size==1){"Source mapping changed across peers: $family"}
                                }
                                checks.put(JSONObject().put("case","native-selected-Network-and-family-source-port-continuity").put("tuples",JSONArray(rows)).put("passed",true))
                                arguments.getString("physicalUdpPort")?.let{physicalPort->
                                    check(hostDirect){"Physical control requires explicit host DIRECT fixture rule"}
                                    exchange(client,relay,"10.0.2.2",byteArrayOf(74),physicalPort.toInt(),"PHYS|")
                                    exchange(client,relay,"198.18.0.8",byteArrayOf(75))
                                    checks.put(JSONObject().put("case","native-same-association-DIRECT-physical-versus-PROXY-VPN-isolation").put("passed",true))
                                }
                            }
                            save(base.copy(userRejectRules=listOf(RuleEntry("198.18.0.7"))))
                            await("native rule change") {
                                runCatching { upgrade("198.18.0.7").let { (s,h) -> s.close(); h.startsWith("HTTP/1.1 403 ") } }.getOrDefault(false)
                            }
                            val denied=SocksUdpCodec.encode(InetSocketAddress("198.18.0.7",53000),byteArrayOf(99))
                            client.send(DatagramPacket(denied,denied.size,relay))
                            exchange(client,relay,"2001:db8:77::7",byteArrayOf(44))
                            if(arguments.getString("verifySourceIdentity")=="true")exchange(client,relay,"198.18.0.8",byteArrayOf(45))
                            checks.put(JSONObject().put("case","native-socks-rule-other-target-survives").put("passed",true))
                            stop(); check(control.getInputStream().read()==-1)
                            check(runCatching { Socket(iface.address,socksPort).close() }.isFailure)
                            checks.put(JSONObject().put("case","native-socks-OFF-closes-control-and-listener").put("passed",true))
                        }
                    }
                    save(base);start(); await("native SOCKS restarted") { repository.state.value.diagnostics.socksPortUp }
                    udp().use { client -> association(client.localPort).let { (s,relay) -> s.use { exchange(client,relay,"198.18.0.7",byteArrayOf(88)) } } }
                    checks.put(JSONObject().put("case","native-socks-new-association-after-ON").put("passed",true))
                    stop();save(base.copy(httpEnabled=false));start(expectHttp=false)
                    udp().use { client -> association(client.localPort).let { (s,relay) -> s.use { exchange(client,relay,"198.18.0.7",byteArrayOf(89)) } } }
                    checks.put(JSONObject().put("case","native-socks-independent-of-http-listener").put("passed",true))
                } else {
                    for (host in listOf("198.18.0.7", "2001:db8:77::7")) {
                        val (s, headers) = upgrade(host)
                        s.use {
                            check(headers.startsWith("HTTP/1.1 101 ")) { headers }
                            for (size in listOf(0, 1, 1200)) {
                                val payload = ByteArray(size) { (it % 251).toByte() }
                                send(s, payload)
                                val prefix = (if (':' in host) "VPN6|" else "VPN4|").toByteArray()
                                check(receive(s).contentEquals(prefix + payload)) { "wire mismatch $host/$size" }
                                checks.put(JSONObject().put("case", "udp-roundtrip").put("host", host).put("bytes", size).put("passed", true))
                            }
                            // Large datagram must be discarded, not fragmented or corrupt the next small one.
                            send(s, ByteArray(2000));send(s, byteArrayOf(42))
                            val prefix = (if (':' in host) "VPN6|" else "VPN4|").toByteArray()
                            check(receive(s).contentEquals(prefix + byteArrayOf(42))) { "oversize not dropped: $host" }
                        }
                    }
                    val cm = targetContext.getSystemService(ConnectivityManager::class.java)
                    val vpn = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                    for (host in listOf("198.18.0.7", "2001:db8:77::7")) {
                        AndroidDatagramEndpointFactory.open(InetAddress.getByName(host), 53000, vpn).use { endpoint ->
                            check(endpoint.send(ByteArray(2000)) == DatagramSendResult.TOO_LARGE) { "no-fragment enforcement failed: $host" }
                            checks.put(JSONObject().put("case", "native-EMSGSIZE").put("host", host).put("passed", true))
                        }
                    }
                    repeat(4) { AndroidDatagramEndpointFactory.open(InetAddress.getByName("198.18.0.7"),53000,vpn).close() }
                    val before = File("/proc/self/fd").list()!!.size
                    repeat(25) { AndroidDatagramEndpointFactory.open(InetAddress.getByName("198.18.0.7"),53000,vpn).close() }
                    val after = File("/proc/self/fd").list()!!.size
                    check(after <= before + 4) { "UDP descriptor leak: $before->$after" }
                    checks.put(JSONObject().put("case", "descriptor-recovery").put("before", before).put("after", after).put("passed", true))
                    val (blocked, hb) = upgrade("198.18.0.7", session)
                    val (healthy, hh) = upgrade("198.18.0.8", session)
                    check(hb.startsWith("HTTP/1.1 101 ") && hh.startsWith("HTTP/1.1 101 "))
                    blocked.use { healthy.use {
                        save(base.copy(userRejectRules = listOf(RuleEntry("198.18.0.7"))))
                        await("updated reject actually active") {
                            runCatching { upgrade("198.18.0.7").let { (s,h) -> s.close();h.startsWith("HTTP/1.1 403 ") } }.getOrDefault(false)
                        }
                        check(blocked.getInputStream().read() == -1)
                        send(healthy, byteArrayOf(77));check(receive(healthy).contentEquals("VPN4|".toByteArray() + byteArrayOf(77)))
                        checks.put(JSONObject().put("case", "production-rule-revocation").put("passed", true))
                        stop();check(healthy.getInputStream().read() == -1)
                        check(runCatching { client().close() }.isFailure) { "listener survived phone OFF" }
                        checks.put(JSONObject().put("case", "phone-OFF-closes-listener-and-flow").put("passed", true))
                    } }
                    save(base);start()
                    val fresh = capability();check(fresh != session)
                    upgrade("198.18.0.8", session).let { (s,h) -> s.use { check(h.startsWith("HTTP/1.1 409 ")) { h } } }
                    upgrade("198.18.0.8").let { (s,h) -> s.use {
                        check(h.startsWith("HTTP/1.1 101 "));send(s, byteArrayOf(88));check(receive(s).contentEquals("VPN4|".toByteArray() + byteArrayOf(88)))
                    } }
                    checks.put(JSONObject().put("case", "new-session-stale-extension-and-generic-client").put("passed", true))
                }
            } finally {
                try { stop() } finally {
                    com.mzstd.hxmyproxy.core.log.FileLog.flush()
                    runBlocking { credentials.update(originalCredentials.username,originalCredentials.password) }
                    save(original)
                }
            }
        } catch (error: Throwable) {
            failure = error
            result.put("error", error.toString()).put("stack", error.stackTraceToString())
        }
        result.put("passed", failure == null).put("checks", checks)
        File(targetContext.filesDir, "udp-interop-result.json").writeText(result.toString(2))
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", result.toString()) })
    }

    private fun readHeaders(s: Socket): String = buildString {
        val input = s.getInputStream()
        while (!endsWith("\r\n\r\n")) { val b = input.read();check(b >= 0);append(b.toChar());check(length < 32768) }
    }
    private fun send(s: Socket, payload: ByteArray) {
        val wire = UdpCapsuleEncoder.datagram(payload).bytes
        s.getOutputStream().write(ByteArray(wire.remaining()).also { wire.get(it) })
    }
    private fun receive(s: Socket): ByteArray {
        val input = s.getInputStream()
        fun number(): Long { val first = input.read();check(first >= 0);var n = (first and 63).toLong();repeat((1 shl (first ushr 6)) - 1) { val b=input.read();check(b>=0);n=(n shl 8) or b.toLong() };return n }
        check(number() == 0L);val size = number();check(number() == 0L)
        return ByteArray((size - 1).toInt()).also { DataInputStream(input).readFully(it) }
    }

    private class RawIngress(private val target: InetAddress, private val targetPort: Int) : Closeable {
        private val server = ServerSocketChannel.open().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)) }
        val port = (server.localAddress as InetSocketAddress).port
        private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val reactor = NioRelayReactor(1).apply { start() }
        private val channels = ConcurrentHashMap.newKeySet<SocketChannel>()
        private val worker = thread(isDaemon=true,name="qa-udp-ingress") {
            try { while(server.isOpen) {
                val client=server.accept();channels.add(client)
                val upstream=SocketChannel.open(InetSocketAddress(target,targetPort));channels.add(upstream)
                client.configureBlocking(false);upstream.configureBlocking(false)
                scope.launch { try { reactor.relay(client,upstream,8192,120000) { _,_-> } }
                    finally { channels.remove(client);channels.remove(upstream);runCatching{client.close()};runCatching{upstream.close()} } }
            } } catch (_: IOException) { }
        }
        override fun close() { server.close();channels.forEach { runCatching{it.close()} };scope.cancel();reactor.stop();worker.join(2000) }
    }
}
