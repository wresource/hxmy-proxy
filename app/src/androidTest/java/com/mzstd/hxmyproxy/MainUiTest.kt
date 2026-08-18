package com.mzstd.hxmyproxy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
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

    /**
     * 等首屏真正画出来再断言。
     *
     * 首屏内容取决于 onboarding 标志，它要从 DataStore 经 Flow 回到 composition；在这之前
     * `when(showOnboarding)` 的 null 分支**什么都不画**，导航栏并不存在。不等就断言，
     * 拿到的是「还没画」的那一帧 —— 报错是 "nav_run is not displayed"，
     * 与「导航栏真的坏了」完全同形，会把人往错的方向带。
     */
    @Before
    fun ensureMainUi() {
        try {
            // 不假设初始状态：首屏可能是引导页（首次启动，或 DataStore 被同轮其他仪器测试改写过——
            // 实测就撞上过），也可能直接是主界面。两者都等，谁先出现认谁。
            // 在这之前 `when(showOnboarding)` 的 null 分支什么都不画，此时断言只会拿到空树，
            // 报错文本与「导航栏真的坏了」完全同形。
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithTag("nav_run").fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()
            }
            if (rule.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("onboarding_skip").performClick()
                rule.waitUntil(timeoutMillis = 15_000) {
                    rule.onAllNodesWithTag("nav_run").fetchSemanticsNodes().isNotEmpty()
                }
            }
        } catch (e: Throwable) {
            // 超时时把语义树打进 logcat：区分「画的是引导页」「画的是空」「画了但没 testTag」，
            // 这三种在报错文本上完全同形。
            rule.onRoot(useUnmergedTree = true).printToLog("UITREE_NO_NAV")
            throw e
        }
    }

    @Test
    fun bottomNavTabsShown() {
        rule.onNodeWithTag("nav_run").assertIsDisplayed()
        rule.onNodeWithTag("nav_rules").assertIsDisplayed()
        rule.onNodeWithTag("nav_settings").assertIsDisplayed()
    }

    @Test
    fun navigationSwitchesScreens() {
        // 进设置 → 出现语言选项 "中文"（仅设置页，且两种语言下文案都是 "中文"）
        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithText("中文").assertIsDisplayed()
        // 回主页 → "中文" 不再存在
        rule.onNodeWithTag("nav_run").performClick()
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
