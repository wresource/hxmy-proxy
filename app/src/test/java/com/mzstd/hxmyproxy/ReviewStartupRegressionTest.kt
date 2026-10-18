package com.mzstd.hxmyproxy

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.*
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.security.*
import com.mzstd.hxmyproxy.data.repository.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReviewStartupRegressionTest {
    @Test(timeout=20000) fun initialListenerWaitsForCompleteRuleSnapshot() = runBlocking { startupScenario("success") }
    @Test(timeout=20000) fun stoppingDuringRuleLoadNeverOpensListenerLater() = runBlocking { startupScenario("stop") }
    @Test(timeout=20000) fun failedInitialRulesKeepListenerClosedAndShowFailure() = runBlocking { startupScenario("failure") }

    @Test(timeout=20000) fun dnsSettingsInvalidateAnswersWithoutResettingNetworkHealth() = runBlocking { startupScenario("config") }
    @Test(timeout=20000) fun manualResetPreservesReadyRulesAndResetsNetworkHealth() = runBlocking { startupScenario("reset") }

    private suspend fun CoroutineScope.startupScenario(mode: String) {
        val rootScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val engine=RuleEngine()
        val prefs=mockk<SharedPreferences>(relaxed=true) { every { getString(any(),any()) } returns null }
        val assets=mockk<AssetManager> {
            every { open(any()) } answers {
                entered.countDown()
                check(release.await(10,TimeUnit.SECONDS)) { "test asset gate timed out" }
                if(mode == "failure") throw java.io.IOException("controlled assets failure")
                File("src/main/assets",firstArg<String>()).inputStream()
            }
        }
        val ctx=mockk<Context>(relaxed=true) {
            every { getSharedPreferences(any(),any()) } returns prefs
            every { this@mockk.assets } returns assets
        }
        val port=ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { it.localPort }
        val settings=ProxySettings(httpPort=port,socksEnabled=false,pacEnabled=false,
            selectedInterfaceIds=setOf("repro/127.0.0.1"),userRejectRules=listOf(RuleEntry("blocked.example")))
        val settingsRepo=mockk<SettingsRepository>(relaxed=true) { every { this@mockk.settings } returns MutableStateFlow(settings) }
        val connectivity=mockk<ConnectivityObserver>(relaxed=true) {
            every { networkChanges } returns MutableSharedFlow()
            every { vpnState } returns MutableStateFlow(VpnState())
        }
        val scanner=mockk<InterfaceScanner> { every { scan(any()) } returns listOf(ShareInterface("repro/127.0.0.1","repro",InterfaceType.UNKNOWN,InetAddress.getLoopbackAddress(),32,false,true,InterfaceStatus.UP)) }
        val signals=mockk<SignalProvider> { every { current() } returns SignalInfo() }
        val underlying=mockk<UnderlyingNetworkProvider>(relaxed=true) { every { status() } returns EgressStatus() }
        val creds=mockk<CredentialStore> { every { credentials } returns flowOf(CredentialStore.Credentials()) }
        val repo=ProxyServerRepository(ctx,settingsRepo,connectivity,scanner,mockk(relaxed=true),mockk(relaxed=true),
            DefaultEgressGuard(),SingleCredentialAuthenticator(),SubnetAccessController(),signals,mockk(relaxed=true),creds,
            engine,RuleRepository(ctx,engine),underlying,mockk(relaxed=true),mockk(relaxed=true))
        val attempts=AtomicInteger()
        val connector=mockk<OutboundConnector>(relaxed=true)
        coEvery { connector.connectChannel(any<String>(),any(),any(),any(),any()) } answers {
            attempts.incrementAndGet(); throw ProxyException(ProxyError.DnsFailure,"controlled-upstream-sentinel")
        }
        // Replace only external dialing. Repository start, real listener, HTTP parsing,
        // admission, rule loading and rule decision are unmodified production objects.
        repo.javaClass.getDeclaredField("connector").apply { isAccessible=true }.set(repo,connector)
        fun request(): String = Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(),port),1000); s.soTimeout=3000
            s.getOutputStream().write("CONNECT blocked.example:443 HTTP/1.1\r\nHost: blocked.example\r\n\r\n".toByteArray())
            s.getOutputStream().flush(); s.getInputStream().bufferedReader().readLine()
        }
        if(mode == "config") {
            try {
                val apply = repo.javaClass.getDeclaredMethod("applyTunables", ProxySettings::class.java).apply { isAccessible=true }
                apply.invoke(repo, settings)
                apply.invoke(repo, settings.copy(themeMode=ThemeMode.DARK))
                verify(exactly=0) { connector.invalidateDnsPolicy() }
                val next=settings.copy(dohEgressChoice=DohEgressChoice.DEFAULT)
                apply.invoke(repo,next)
                val ordered=next.copy(egressPriority=next.egressPriority.reversed())
                apply.invoke(repo,ordered)
                apply.invoke(repo,ordered.copy(backupDnsEnabled=false))
                verify(exactly=3) { connector.invalidateDnsPolicy() }
                verify(exactly=0) { connector.clearDnsCache() }
            } finally { release.countDown(); repo.stop(); rootScope.cancel() }
            return
        }
        try {
            val starting = async(Dispatchers.Default) { repo.start(rootScope) }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(repo.state.value.starting)
            assertEquals(RuleAction.PROXY,engine.decide("blocked.example"))
            val before=withContext(Dispatchers.IO) { runCatching { request() }.exceptionOrNull() }
            assertTrue(before is java.io.IOException); assertEquals(0,attempts.get())
            if(mode == "stop") repo.stop()
            release.countDown()
            if(mode == "stop" || mode == "failure") {
                runCatching { starting.await() }
                assertFalse(repo.isRunning())
                assertFalse(repo.state.value.starting)
                assertEquals(mode == "failure",repo.state.value.startupFailed)
                assertEquals("stopped or failed build must not publish a snapshot",0,engine.version.value)
                assertTrue(withContext(Dispatchers.IO) { runCatching { request() }.exceptionOrNull() } is java.io.IOException)
                assertEquals(0,attempts.get())
                return
            }
            withTimeout(5000) { while(engine.version.value==0) delay(10) }
            starting.await()
            val after=withTimeout(3000) {
                var answer: String? = null
                while(answer==null) {
                    answer=withContext(Dispatchers.IO) { runCatching { request() }.getOrNull() }
                    if(answer==null) delay(10)
                }
                answer
            }
            assertTrue(after.contains("403")); assertEquals(0,attempts.get())
            if(mode == "reset") {
                repo.manualReset()
                withTimeout(5000) { while(repo.resetState.value==ManualResetPhase.RUNNING) delay(10) }
                verify(exactly=1) { connector.cancelPendingDns() }
                verify(exactly=1) { connector.clearDnsCache() }
                assertEquals(RuleAction.REJECT,engine.decide("blocked.example"))
                assertTrue(repo.isRunning())
            }
        } finally { release.countDown(); repo.stop(); rootScope.cancel() }
    }

    @Test(timeout=10000) fun initialNetworkGapWaitsForCallback() = runBlocking {
        mockkConstructor(NetworkRequest.Builder::class)
        val builder=mockk<NetworkRequest.Builder>()
        every { builder.addCapability(any()) } returns builder
        every { builder.removeCapability(any()) } returns builder
        every { builder.addTransportType(any()) } returns builder
        every { builder.build() } returns mockk<NetworkRequest>()
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } returns builder
        try {
            val callbacks=mutableListOf<ConnectivityManager.NetworkCallback>()
            val vpn=mockk<Network> {
                every { networkHandle } returns 505L
                every { socketFactory } returns javax.net.SocketFactory.getDefault()
            }
            val cm=mockk<ConnectivityManager>(relaxed=true) {
                every { allNetworks } returns arrayOf(vpn)
                every { activeNetwork } returns vpn
                every { registerNetworkCallback(any<NetworkRequest>(),any<ConnectivityManager.NetworkCallback>()) } answers { callbacks.add(secondArg()); Unit }
            }
            val ctx=mockk<Context>(relaxed=true) {
                every { getSystemService(ConnectivityManager::class.java) } returns cm
                every { getSystemService(android.telephony.TelephonyManager::class.java) } returns null
            }
            val p=UnderlyingNetworkProvider(ctx)
            p.setEgressChoice(EgressNetworkChoice.VPN); p.start()
            assertEquals(4,callbacks.size); assertNull(p.egressSelection().network)
            val guard=object: EgressGuard { override fun isAllowed(addr: InetAddress)=true }
            val connector=testConnector(guard,underlyingNetworkProvider=p,systemDns=SystemDnsLookup { _,_-> listOf(InetAddress.getLoopbackAddress()) })
            ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { listener ->
                val waiting=async { connector.connect("ready.example",listener.localPort) }
                delay(50)
                assertFalse("initialization should wait for callback",waiting.isCompleted)
                callbacks.last().onAvailable(vpn)
                assertSame(vpn,p.egressSelection().network)
                waiting.await().use { assertTrue(it.isConnected) }
            }
            p.stop()
        } finally { unmockkConstructor(NetworkRequest.Builder::class) }
    }
}
