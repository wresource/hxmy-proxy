package com.mzstd.hxmyproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试（模拟器）：验证 DataStore 持久化与 [ProxySettings] 映射往返（含枚举 / 上限 / 集合）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryInstrumentedTest {

    @Test
    fun persistsAndMapsSettingsRoundTrip() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(ctx)
        val original = repo.settings.first()
        try {
            repo.update {
                it.copy(
                    httpPort = 9099,
                    socksPort = 1099,
                    language = AppLanguage.CHINESE,
                    preset = PerformancePreset.HIGH_THROUGHPUT,
                    limits = PerformancePreset.HIGH_THROUGHPUT.toLimits(),
                    authEnabled = true,
                    vpnDownStrategy = VpnDownStrategy.WARN,
                    blockPrivateLanEgress = true,
                    selectedInterfaceIds = setOf("wlan0/192.168.1.5", "ap0/192.168.43.1"),
                )
            }

            val s = repo.settings.first()
            assertEquals(9099, s.httpPort)
            assertEquals(1099, s.socksPort)
            assertEquals(AppLanguage.CHINESE, s.language)
            assertEquals(PerformancePreset.HIGH_THROUGHPUT, s.preset)
            assertEquals(512, s.limits.maxGlobalConnections)
            assertEquals(VpnDownStrategy.WARN, s.vpnDownStrategy)
            assertTrue(s.authEnabled)
            assertTrue(s.blockPrivateLanEgress)
            assertTrue(s.selectedInterfaceIds.contains("wlan0/192.168.1.5"))
            assertEquals(2, s.selectedInterfaceIds.size)
        } finally {
            repo.update { original } // 还原默认，避免污染 App 状态
        }
    }

    @Test
    fun userRuleSetsRoundTrip() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(ctx)
        val original = repo.settings.first()
        try {
            repo.update {
                it.copy(
                    userRuleSets = listOf(
                        UserRuleSet("a", "我的直连", RuleAction.DIRECT, listOf("music.163.com", "example.com")),
                        UserRuleSet("b", "我的拦截", RuleAction.REJECT, listOf("ads.test"), enabled = false),
                    ),
                )
            }
            val s = repo.settings.first()
            assertEquals(2, s.userRuleSets.size)
            val direct = s.userRuleSets.first { it.id == "a" }
            assertEquals("我的直连", direct.name)
            assertEquals(RuleAction.DIRECT, direct.action)
            assertEquals(listOf("music.163.com", "example.com"), direct.domains)
            val reject = s.userRuleSets.first { it.id == "b" }
            assertEquals(RuleAction.REJECT, reject.action)
            assertEquals(false, reject.enabled)
        } finally {
            repo.update { original }
        }
    }
}
