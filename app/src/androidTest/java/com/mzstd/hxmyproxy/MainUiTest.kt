package com.mzstd.hxmyproxy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI 仪器测试（真 MainActivity + 真 Hilt 图）。
 * 导航用稳定的 testTag（nav_<route>，与语言/字形渲染无关），
 * 断言用本地化文案，重点验证导航 + 中英文运行时切换（之前修过的崩溃点）。
 */
@RunWith(AndroidJUnit4::class)
class MainUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavTabsShown() {
        rule.onNodeWithTag("nav_dashboard").assertIsDisplayed()
        rule.onNodeWithTag("nav_interfaces").assertIsDisplayed()
        rule.onNodeWithTag("nav_diagnostics").assertIsDisplayed()
        rule.onNodeWithTag("nav_settings").assertIsDisplayed()
    }

    @Test
    fun navigationSwitchesScreens() {
        // 进设置 → 出现语言选项 "中文"（仅设置页，且两种语言下文案都是 "中文"）
        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithText("中文").assertIsDisplayed()
        // 回主页 → "中文" 不再存在
        rule.onNodeWithTag("nav_dashboard").performClick()
        rule.onNodeWithText("中文").assertDoesNotExist()
    }

    @Test
    fun languageSwitchEnZhBothWays() {
        rule.onNodeWithTag("nav_settings").performClick()
        rule.waitForIdle()
        // 切中文 → 标题变中文 "语言"（"语言" 仅中文 UI 存在）
        rule.onNodeWithText("中文").performClick()
        pollUntil("语言")
        rule.onNodeWithText("语言").assertExists()
        // 切回英文 → 标题变英文 "Language"（"Language" 仅英文 UI 存在）
        rule.onNodeWithText("English").performClick()
        pollUntil("Language")
        rule.onNodeWithText("Language").assertExists()
    }

    /**
     * 语言切换是异步链路（点击→ViewModel→DataStore 写入→Flow→uiState→重组），
     * 不在 Compose idle 跟踪内。手动轮询（waitForIdle + sleep）等本地化文案出现；
     * 超时则 dump 语义树到 logcat 便于诊断。
     */
    private fun pollUntil(text: String, attempts: Int = 40) {
        repeat(attempts) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(250)
        }
        rule.onRoot().printToLog("UITREE_TIMEOUT_$text")
    }
}
