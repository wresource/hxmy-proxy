package com.mzstd.hxmyproxy

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.ui.components.TopDomainRuleDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewRuleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun builtinDirectOffersExplicitProxyAndOnlyCustomRulesOfferRestore() {
        val current=mutableStateOf(RuleAction.DIRECT)
        val custom=mutableStateOf(false)
        lateinit var proxyLabel:String
        lateinit var clearLabel:String
        compose.setContent {
            val context=LocalContext.current
            proxyLabel=context.getString(R.string.top_domain_proxy)
            clearLabel=context.getString(R.string.top_domain_clear)
            MaterialTheme {
                TopDomainRuleDialog(
                    host="example.com", current=current.value,
                    source=if(custom.value) RuleSrc.OVERRIDE else RuleSrc.BUILTIN_APP,
                    canClear=custom.value,
                    onDirect={ current.value=RuleAction.DIRECT; custom.value=true },
                    onReject={ current.value=RuleAction.REJECT; custom.value=true },
                    onProxy={ current.value=RuleAction.PROXY; custom.value=true },
                    onClear={ current.value=RuleAction.DIRECT; custom.value=false },
                    onDismiss={},
                )
            }
        }
        compose.onAllNodesWithText(clearLabel).assertCountEquals(0)
        compose.onNodeWithText(proxyLabel).performClick()
        compose.runOnIdle { assertEquals(RuleAction.PROXY,current.value) }
        compose.onAllNodesWithText(clearLabel).assertCountEquals(1)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir,"review-rule-dialog.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
        }
        compose.onNodeWithText(clearLabel).performClick()
        compose.runOnIdle { assertEquals(RuleAction.DIRECT,current.value) }
        compose.onAllNodesWithText(clearLabel).assertCountEquals(0)
    }

    @Test fun preservedIpv6CidrAndSingleScopeMatchOnActualAndroid() {
        val parsed=RuleInput.parseLines("*.example.com\n2001:db8::/32\nnot a domain.com")
        assertEquals(listOf(3),parsed.invalidLines)
        val matcher=RuleMatcher().apply { parsed.rules.forEach(::add) }
        assertTrue(matcher.matches("child.example.com"))
        assertFalse(matcher.matches("deep.child.example.com"))
        assertTrue(matcher.matches("2001:db8::99"))
        assertFalse(matcher.matches("2001:db9::99"))
    }
}
