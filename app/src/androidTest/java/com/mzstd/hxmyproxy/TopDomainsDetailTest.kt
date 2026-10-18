package com.mzstd.hxmyproxy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.ui.monitor.TopDomainsDetailContent
import java.net.InetAddress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real accounting snapshots and the production LazyColumn, including sorting/recomposition. */
@RunWith(AndroidJUnit4::class)
class TopDomainsDetailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameHostAndOverflowBucketsAcrossProtocolsRemainVisibleAfterUpdates() {
        val accounting = TrafficAccounting(maxDomains = 2)
        val client = InetAddress.getByName("192.0.2.10")
        val http = accounting.openConnection(client, ProxyProtocol.HTTP).apply {
            bindHost("chatgpt.com"); add(100, 200)
        }
        accounting.openConnection(client, ProxyProtocol.SOCKS5).apply {
            bindHost("chatgpt.com"); add(200, 400)
        }
        // 达到域名上限后，“其他”同样按协议分行，不能只修正常 host。
        accounting.openConnection(client, ProxyProtocol.HTTP).apply { bindHost("a.example") }
        accounting.openConnection(client, ProxyProtocol.SOCKS5).apply { bindHost("b.example") }
        val domains = mutableStateOf(accounting.snapshot().topDomains)
        lateinit var trafficLabel: String
        lateinit var recentLabel: String
        compose.setContent {
            val context = LocalContext.current
            trafficLabel = context.getString(R.string.domains_sort_traffic)
            recentLabel = context.getString(R.string.domains_sort_recent)
            MaterialTheme {
                TopDomainsDetailContent(domains.value, onBack = {}, actionForHost = { null }, onEdit = {})
            }
        }
        compose.onNodeWithTag("domain_search").performTextReplacement("chatgpt.com")
        compose.onAllNodes(hasText("chatgpt.com") and !hasTestTag("domain_search")).assertCountEquals(2)
        compose.onNodeWithText(recentLabel).performClick()
        compose.onNodeWithText(trafficLabel).performClick()
        compose.runOnIdle {
            http.add(3000, 5000) // 改变排序后再发布快照，复用同一批 item identity。
            domains.value = accounting.snapshot().topDomains.reversed()
        }
        compose.onAllNodes(hasText("chatgpt.com") and !hasTestTag("domain_search")).assertCountEquals(2)
        compose.onNodeWithText(trafficLabel).performClick()
        compose.onNodeWithText(recentLabel).performClick()
        compose.onAllNodes(hasText("chatgpt.com") and !hasTestTag("domain_search")).assertCountEquals(2)
        compose.onNodeWithTag("domain_search").performTextReplacement("")
        for (protocol in listOf("HTTP", "SOCKS5")) {
            val tag = "domain_$protocol:${TrafficAccounting.OTHERS}"
            compose.onNodeWithTag("all_domain_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithTag("all_domain_list").performScrollToIndex(0)
        compose.onNodeWithTag("domain_search").performTextReplacement("chatgpt.com")
        compose.runOnIdle { domains.value = emptyList() }
        compose.onAllNodes(hasText("chatgpt.com") and !hasTestTag("domain_search")).assertCountEquals(0)
        compose.runOnIdle { domains.value = accounting.snapshot().topDomains }
        compose.onAllNodes(hasText("chatgpt.com") and !hasTestTag("domain_search")).assertCountEquals(2)
    }
}
