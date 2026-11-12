package com.mzstd.hxmyproxy

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.testsupport.VerificationEntryPoint
import com.mzstd.hxmyproxy.ui.locale.localizedFor
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/** Observe actual Android notification posts; no mock NotificationManager or production-device use. */
class NotificationUpdateDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val entry by lazy {
        EntryPointAccessors.fromApplication(context.applicationContext, VerificationEntryPoint::class.java)
    }
    private val repository get() = entry.repository()
    private val settings get() = entry.settings()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun await(label: String, timeout: Long = 12_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        assertTrue(label, predicate())
    }

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }

    private fun serviceExists() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == ProxyForegroundService::class.java.name }

    private fun current() = notifications.activeNotifications.singleOrNull { it.id == 1001 }

    private fun stop() {
        ProxyForegroundService.stop(context)
        await("service and notification stopped") { !repository.isRunning() && !serviceExists() && current() == null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun changeState(block: (ShareState) -> ShareState) {
        // Input at the real service's existing StateFlow boundary, not a second notification implementation.
        val state = repository.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            .get(repository) as MutableStateFlow<ShareState>
        instrumentation.runOnMainSync { state.update(block) }
    }

    private fun fixture(test: () -> Unit) {
        check(Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("ownedNotificationPolicy") == "true")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 37) shell("pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        val original = runBlocking { settings.settings.first() }
        runBlocking { settings.setOnboardingCompleted(true) }
        ActivityScenario.launch(MainActivity::class.java).use {
            stop()
            val port = ServerSocket(0).use { server -> server.localPort }
            try {
                runBlocking { settings.update {
                    ProxySettings(httpPort = port, socksEnabled = false, pacEnabled = false,
                        selectedInterfaceIds = setOf("eth0/10.0.2.15"), backupDnsEnabled = false)
                } }
                ProxyForegroundService.start(context)
                await("real foreground service running and notification ready") {
                    repository.state.value.let { state -> state.running && state.diagnostics.httpPortUp } &&
                        current()?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)
                            ?.contains(port.toString()) == true
                }
                Thread.sleep(200)
                test()
            } finally {
                stop()
                runBlocking { settings.update { original } }
            }
        }
    }

    @Test fun invisibleStateChangesDoNotRepostTheSameNotification() = fixture {
        val baseline = requireNotNull(current())
        val text = baseline.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val posts = mutableListOf(baseline.postTime)
        repeat(25) { index ->
            changeState { it.copy(totalBytes = 10_000L + index, signalDbm = -30 - index) }
            Thread.sleep(60)
            val posted = requireNotNull(current())
            assertEquals("only invisible fields changed", text,
                posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            posts += posted.postTime
        }
        File(context.filesDir, "notification-invisible-posts.txt").writeText(posts.joinToString("\n"))
        assertEquals("unrelated state must not repost the same notification: $posts", 1, posts.distinct().size)
    }

    @Test fun stoppingWhileARefreshIsPendingCannotResurrectTheNotification() = fixture {
        repeat(8) { index ->
            changeState { it.copy(activeConnections = 100 + index) }
            Thread.sleep(20)
        }
        stop()
        Thread.sleep(1_300)
        assertNull("late refresh must not recreate a stopped foreground notification", current())
        assertFalse(repository.isRunning())
        assertFalse(serviceExists())
    }

    @Test fun languageChangeReplacesQueuedOldLanguageContentAndStopAction() = fixture {
        fun stopLabel() = current()?.notification?.actions?.singleOrNull()?.title?.toString()
        val englishStop = context.localizedFor(AppLanguage.ENGLISH).getString(R.string.notif_stop)
        val chineseStop = context.localizedFor(AppLanguage.CHINESE).getString(R.string.notif_stop)
        assertNotEquals(englishStop, chineseStop)
        runBlocking { settings.update { it.copy(language = AppLanguage.ENGLISH) } }
        await("English notification action") { stopLabel() == englishStop }
        repeat(5) { index -> changeState { it.copy(activeConnections = 50 + index) }; Thread.sleep(20) }
        runBlocking { settings.update { it.copy(language = AppLanguage.CHINESE) } }
        await("Chinese notification action") { stopLabel() == chineseStop }
        Thread.sleep(1_300)
        assertEquals("old pending content must not restore the previous language", chineseStop, stopLabel())
        assertTrue(repository.isRunning())
    }
}
