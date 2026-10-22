package com.mzstd.hxmyproxy

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.RuleRepository
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.service.ServiceState
import com.mzstd.hxmyproxy.testsupport.VerificationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real DataStore -> foreground service -> serial worker -> listeners. Existing owned emulator only. */
@RunWith(AndroidJUnit4::class)
class RuleApplicationLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val entry by lazy { EntryPointAccessors.fromApplication(context.applicationContext, VerificationEntryPoint::class.java) }
    private val repository get() = entry.repository()
    private val settings get() = entry.settings()
    private fun await(label: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        assertTrue("$label: ${repository.state.value.ruleApplication}", predicate())
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() }
    }
    private fun foreground() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == ProxyForegroundService::class.java.name }
    private fun stop() {
        ProxyForegroundService.stop(context)
        await("service stopped") { !repository.isRunning() && !foreground() }
    }
    private class Faults(context: Context) : ContextWrapper(context) {
        @Volatile var failing = false
        val failures = AtomicInteger()
        val block = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun getAssets(): AssetManager {
            if (block.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "controlled asset barrier timed out" }
            }
            if (failing) { failures.incrementAndGet(); error("QA rule assets unavailable") }
            return super.getAssets()
        }
    }
    private inner class Fixture(val faults: Faults) {
        val port = ServerSocket(0).use { it.localPort }
        val base = ProxySettings(httpPort = port, socksEnabled = false, pacEnabled = false,
            selectedInterfaceIds = setOf("eth0/10.0.2.15"), backupDnsEnabled = false)
        val protected = base.copy(enabledRuleGroups = setOf("ads-oisd-small"),
            userDirectRules = listOf(RuleEntry("example.com")))
        val changed = protected.copy(userDirectRules = protected.userDirectRules + RuleEntry("example.org"))
        fun save(value: ProxySettings) = runBlocking { settings.update { value } }
        fun start(value: ProxySettings) {
            save(value); ProxyForegroundService.start(context)
            await("rules applied and listening") {
                repository.state.value.let { it.running && it.diagnostics.httpPortUp && it.ruleApplication.phase == RuleApplyPhase.APPLIED }
            }
        }
        fun adStatus(): String = Socket("10.0.2.15", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET http://pagead2.googlesyndication.com/ HTTP/1.1\r\nHost: pagead2.googlesyndication.com\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        fun assertClosed() {
            val connection = runCatching { Socket("10.0.2.15", port) }
            connection.getOrNull()?.close()
            assertTrue("failed protection must close the proxy port", connection.isFailure)
        }
    }
    private fun fixture(test: Fixture.() -> Unit) {
        check(Build.HARDWARE == "ranchu") { "Owned emulator only" }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 37) shell("pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        val original = runBlocking { settings.settings.first() }
        runBlocking { settings.setOnboardingCompleted(true) }
        ActivityScenario.launch(MainActivity::class.java).use {
            stop()
            val field = repository.javaClass.getDeclaredField("ruleRepository").apply { isAccessible = true }
            val rules = field.get(repository) as RuleRepository
            val contextField = rules.javaClass.getDeclaredField("context").apply { isAccessible = true }
            val originalContext = contextField.get(rules) as Context
            val faults = Faults(originalContext)
            contextField.set(rules, faults)
            try { Fixture(faults).test() }
            finally {
                faults.release.countDown(); faults.failing = false
                stop()
                contextField.set(rules, originalContext)
                runBlocking { settings.update { original } }
            }
        }
    }

    @Test fun emptySnapshotFailedProtectionStopsServiceAndRequiresExplicitRestart() = fixture {
        start(base)
        assertEquals(0, repository.state.value.ruleApplication.counts.total)
        faults.failing = true; save(protected)
        await("failed empty snapshot closed service") {
            repository.state.value.ruleApplication.stoppedForFailure && !foreground()
        }
        assertFalse(repository.isRunning()); assertFalse(ServiceState.wasSharing(context)); assertClosed()
        assertEquals(RuleApplyPhase.FAILED, repository.state.value.ruleApplication.phase)
        val count = faults.failures.get()
        faults.failing = false
        repository.retryRules() // Running-only retry must not silently restart a stopped service.
        Thread.sleep(1_300)
        assertFalse(repository.isRunning()); assertFalse(foreground()); assertEquals(count, faults.failures.get())
        ProxyForegroundService.start(context)
        // Applying rules precedes the asynchronous listener bind. Assert the real port is
        // ready before issuing the request, rather than racing engine-ready against bind.
        await("explicit restart applies saved rules and binds the port") {
            repository.state.value.let { it.running && it.diagnostics.httpPortUp && it.ruleApplication.phase == RuleApplyPhase.APPLIED }
        }
        assertTrue(adStatus().contains(" 403 "))
        assertEquals(1, repository.state.value.ruleApplication.counts.userDirect)
    }

    @Test fun goodSnapshotSurvivesBoundedRetriesAndManualRetryRecovers() = fixture {
        start(protected)
        val applied = repository.state.value.ruleApplication.appliedVersion
        faults.failing = true; save(changed)
        await("automatic retry budget exhausted") {
            repository.state.value.ruleApplication.let { it.phase == RuleApplyPhase.FAILED && it.attempt == 2 && !it.retryScheduled }
        }
        assertTrue(repository.isRunning()); assertTrue(foreground())
        assertEquals(applied, repository.state.value.ruleApplication.appliedVersion)
        assertEquals(1, repository.state.value.ruleApplication.counts.userDirect)
        assertTrue(adStatus().contains(" 403 "))
        assertEquals(6, faults.failures.get()) // OISD + allowlist, three attempts.
        faults.failing = false; repository.retryRules()
        await("manual retry applies latest rules") {
            repository.state.value.ruleApplication.let { it.phase == RuleApplyPhase.APPLIED && it.counts.userDirect == 2 }
        }
        assertTrue(repository.state.value.ruleApplication.appliedVersion > applied)
        assertTrue(adStatus().contains(" 403 "))
    }

    @Test fun initialFailureClosesListenersAndPreservesErrorThroughServiceDestruction() = fixture {
        save(protected.copy(userDirectRules = listOf(RuleEntry("cold-${System.nanoTime()}.test"))))
        faults.failing = true; ProxyForegroundService.start(context)
        await("initial failure service ended") { repository.state.value.startupFailed && !foreground() }
        assertFalse(ServiceState.wasSharing(context)); assertClosed()
        assertEquals(RuleApplyPhase.FAILED, repository.state.value.ruleApplication.phase)
        assertTrue(repository.state.value.ruleApplication.stoppedForFailure)
    }

    @Test fun proxyOnlyOldSnapshotCannotKeepSharingWhenNewProtectionFails() = fixture {
        start(base.copy(hostOverrides = mapOf("proxy-only.test" to RuleAction.PROXY)))
        assertEquals(1, repository.state.value.ruleApplication.counts.total)
        assertEquals(0, repository.state.value.ruleApplication.counts.protection)
        faults.failing = true; save(protected)
        await("proxy-only old configuration must stop") {
            repository.state.value.ruleApplication.stoppedForFailure && !foreground()
        }
        assertClosed(); assertFalse(ServiceState.wasSharing(context))
    }

    @Test fun userStopCancelsPendingAutomaticRetry() = fixture {
        start(protected)
        faults.failing = true; save(changed)
        await("retry queued") { repository.state.value.ruleApplication.retryScheduled }
        stop(); val reads = faults.failures.get()
        faults.failing = false; Thread.sleep(1_400)
        assertFalse(foreground()); assertFalse(repository.isRunning()); assertFalse(ServiceState.wasSharing(context))
        assertEquals(reads, faults.failures.get())
        assertEquals(RuleApplyPhase.SAVED, repository.state.value.ruleApplication.phase)
    }

    @Test fun nonUserServiceDestructionKeepsTheExistingRestartIntent() = fixture {
        start(protected)
        faults.failing = true; save(changed)
        await("old protection remains after update failure") { repository.state.value.ruleApplication.retryScheduled }
        assertTrue(ServiceState.wasSharing(context))
        // Exercise onDestroy without the explicit user STOP action. Do not misclassify this
        // as the new rule-safety stop, which would erase the previous restart contract.
        context.stopService(android.content.Intent(context, ProxyForegroundService::class.java))
        await("external destruction completed") { !foreground() && !repository.isRunning() }
        assertTrue(ServiceState.wasSharing(context))
        assertFalse(repository.state.value.ruleApplication.stoppedForFailure)
    }

    @Test fun newSettingsSupersedeBlockedOldBuildBeforePublish() = fixture {
        start(base)
        val version = repository.state.value.ruleApplication.appliedVersion
        faults.block.set(true); save(protected)
        assertTrue(faults.entered.await(5, TimeUnit.SECONDS))
        val oldRequest = repository.state.value.ruleApplication.requestId
        save(base.copy(userDirectRules = listOf(RuleEntry("new.test"))))
        await("latest request accepted") { repository.state.value.ruleApplication.requestId > oldRequest }
        faults.release.countDown()
        await("new snapshot published") { repository.state.value.ruleApplication.phase == RuleApplyPhase.APPLIED }
        assertEquals(version + 1, repository.state.value.ruleApplication.appliedVersion)
        assertEquals(0, repository.state.value.ruleApplication.counts.reject)
        assertEquals(1, repository.state.value.ruleApplication.counts.userDirect)
    }

    @Test fun stoppedBlockedBuildCannotOverwriteNewSharingSession() = fixture {
        start(base)
        val version = repository.state.value.ruleApplication.appliedVersion
        faults.block.set(true); save(protected)
        assertTrue(faults.entered.await(5, TimeUnit.SECONDS))
        stop()
        save(base.copy(userDirectRules = listOf(RuleEntry("new-session.test"))))
        ProxyForegroundService.start(context)
        await("new session preparing") { repository.state.value.starting }
        faults.release.countDown()
        await("new session applied and listening") {
            repository.state.value.let { it.running && it.diagnostics.httpPortUp && it.ruleApplication.phase == RuleApplyPhase.APPLIED }
        }
        assertEquals(version + 1, repository.state.value.ruleApplication.appliedVersion)
        assertEquals(0, repository.state.value.ruleApplication.counts.reject)
        assertEquals(1, repository.state.value.ruleApplication.counts.userDirect)
        assertTrue(ServiceState.wasSharing(context))
    }
}
