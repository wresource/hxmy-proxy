package com.mzstd.hxmyproxy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.monitor.*
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RuntimeIssueUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dnsProblemsHaveLocalizedTextAndDoNotOfferSwitchingTheDirectRoute() {
        check(android.os.Build.HARDWARE == "ranchu")
        val language = mutableStateOf(AppLanguage.CHINESE)
        val code = mutableStateOf("dns-local-address")
        var settings = ""; var applied = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                ProvideAppLocale(language.value) { HxmyProxyTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
                    val host = "download.example"
                    val record = RequestActivityRecord("1:1", 1, "HTTP", host, 1, 2, "DIRECT", "USER_ALLOW",
                        RequestOutcome.FAILED, errorCode = code.value, dnsSource = "sys-egress")
                    val state = DomainDetailState(DomainActivity(host, ProxyProtocol.HTTP,
                        DomainTraffic(host, ProxyProtocol.HTTP, 0, 0), listOf(record)), RuleAction.DIRECT, "USER_ALLOW")
                    DomainDetailSheet(state, {}, {}, {}, { settings = it }, { applied = true })
                } } }
            }
        }
        for (lang in listOf(AppLanguage.CHINESE, AppLanguage.ENGLISH)) {
            for (error in listOf("dns-local-address", "dns-busy")) {
                compose.runOnIdle { language.value = lang; code.value = error }
                val text = when {
                    lang == AppLanguage.CHINESE && error == "dns-local-address" -> "域名被解析为内网地址，已拦截。请检查网络登录页面或 DNS 设置。"
                    lang == AppLanguage.CHINESE -> "DNS 暂时繁忙，请稍后重试。"
                    error == "dns-local-address" -> "This domain resolved to a private network address and was blocked. Check the network sign-in page or DNS settings."
                    else -> "DNS is temporarily busy. Try again shortly."
                }
                compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("domain_primary_action").assertIsDisplayed().performClick()
                compose.runOnIdle { assertEquals("dns", settings); assertFalse(applied) }
                compose.onAllNodesWithText(if (lang == AppLanguage.CHINESE) "改走代理" else "Use proxy").assertCountEquals(0)
                val ins = InstrumentationRegistry.getInstrumentation()
                ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(ins.targetContext.filesDir, "runtime-313-$lang-$error.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
    }
}
