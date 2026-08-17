package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.DirectEgressChoice
import com.mzstd.hxmyproxy.core.model.PHYSICAL_EGRESS_ORDER_DEFAULT
import com.mzstd.hxmyproxy.core.model.normalizeEgressPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 物理出口优先级的**规范化**。
 *
 * 这一层存在的理由:顺序要持久化,而存下来的东西可能是旧版本写的、可能被导入的备份污染、
 * 也可能将来加了新的物理网类型。任何一种情况都必须吐出一个**完整且无重复**的全排列——
 * 少一项就意味着 `current()` 会漏掉一张实际可用的网,用户看到的是「明明连着却说没网」。
 */
class EgressPriorityTest {

    @Test fun `默认顺序与历史硬编码一致`() {
        // 升级上来的用户行为必须不变:以太网/USB → WiFi → 蜂窝。
        assertEquals(
            listOf(DirectEgressChoice.ETHERNET, DirectEgressChoice.WIFI, DirectEgressChoice.CELLULAR),
            PHYSICAL_EGRESS_ORDER_DEFAULT,
        )
    }

    @Test fun `空输入回落到默认顺序`() {
        // 首次启动、或存储里还没有这个键。
        assertEquals(PHYSICAL_EGRESS_ORDER_DEFAULT, normalizeEgressPriority(emptyList()))
    }

    @Test fun `缺失项补到末尾且保持默认相对顺序`() {
        // 旧版本只存了一项,或将来新增了物理网类型 —— 不能因此丢掉其余的网。
        assertEquals(
            listOf(DirectEgressChoice.CELLULAR, DirectEgressChoice.ETHERNET, DirectEgressChoice.WIFI),
            normalizeEgressPriority(listOf(DirectEgressChoice.CELLULAR)),
        )
    }

    @Test fun `重复项只保留第一次出现`() {
        assertEquals(
            listOf(DirectEgressChoice.WIFI, DirectEgressChoice.ETHERNET, DirectEgressChoice.CELLULAR),
            normalizeEgressPriority(
                listOf(DirectEgressChoice.WIFI, DirectEgressChoice.WIFI, DirectEgressChoice.ETHERNET),
            ),
        )
    }

    /** AUTO 不是一个「物理出口」,它是「按这张表挑」的意思——混进表里会自我指涉。 */
    @Test fun `AUTO 不得出现在优先级表里`() {
        val out = normalizeEgressPriority(
            listOf(DirectEgressChoice.AUTO, DirectEgressChoice.WIFI, DirectEgressChoice.AUTO),
        )
        assertEquals(false, out.contains(DirectEgressChoice.AUTO))
        assertEquals(3, out.size)
        assertEquals(DirectEgressChoice.WIFI, out.first())
    }

    /** 无论输入多脏,输出永远是这三项的一个全排列——`current()` 依赖这个不变量。 */
    @Test fun `任何输入都产出完整全排列`() {
        listOf(
            emptyList(),
            listOf(DirectEgressChoice.AUTO),
            listOf(DirectEgressChoice.CELLULAR, DirectEgressChoice.CELLULAR),
            PHYSICAL_EGRESS_ORDER_DEFAULT.reversed(),
        ).forEach { input ->
            val out = normalizeEgressPriority(input)
            assertEquals("输入 $input 产出了 $out", 3, out.size)
            assertEquals("输入 $input 产出了重复项", 3, out.toSet().size)
            assertEquals(
                "输入 $input 丢了某个出口", PHYSICAL_EGRESS_ORDER_DEFAULT.toSet(), out.toSet(),
            )
        }
    }
}
