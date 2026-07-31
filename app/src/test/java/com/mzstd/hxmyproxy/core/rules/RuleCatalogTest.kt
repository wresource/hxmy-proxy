package com.mzstd.hxmyproxy.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [RuleCatalog]：界面开关 ↔ assets 清单 ↔ 持久化 id 的**唯一映射表**。
 *
 * 错了会表现成什么：开关在规则页亮着、用户以为分流已生效，底层却什么都没装载
 * （RuleRepository.loadAsset 打不开文件时只 `Log.w` 一行就过去了），于是本该直连的银行/支付域
 * 全走代理、本该拦的广告全放行 —— **没有任何报错，UI 上完全看不出来**。
 * 这类错误只会在「改了 assets 文件名」「加了新组忘了注册」「复制粘贴 id 写重」时引入，
 * 而这三件事恰恰是最容易顺手做的。
 *
 * 口径说明：
 * - 不断言任何 R 资源 id 的**具体值** —— JVM 单测里资源体系不可用，那种断言没有意义；
 *   这里只验证纯数据约束（id 唯一性、路径约定、文件存在、开关默认值）。
 * - assets 用相对路径读，与 BuiltinRuleAssetsTest 同一口径（工作目录 = app 模块目录）。
 * - `ads-allowlist.txt` 不是规则组（它是 RuleRepository.ADS_ALLOWLIST_ASSET 常量，随广告表一起装载），
 *   所以下面「文件与组一一对应」只针对 `app-*.txt`。
 */
class RuleCatalogTest {

    private val assetsDir = File("src/main/assets")

    /** 与装载逻辑同口径的有效行（去空行与 # 注释）。 */
    private fun effectiveLines(assetPath: String): List<String> =
        File(assetsDir, assetPath).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it[0] != '#' }

    /**
     * id 是**持久化键**（存进 ProxySettings.enabledRuleGroups）。重复的话 byId 只会返回第一个，
     * 两个组共用一个开关：用户开 A 却把 B 也打开了，或者关不掉其中一个。
     */
    @Test
    fun `组 id 全局唯一`() {
        val ids = RuleCatalog.all.map { it.id }
        val dup = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("重复的组 id：$dup", dup.isEmpty())
    }

    /** all 必须是两批之和，不能漏拼（漏了的组在管理页整个消失，但旧设置里仍存着它的 id）。 */
    @Test
    fun `all 恰好是广告组与 App 组之和`() {
        assertEquals(RuleCatalog.adGroups + RuleCatalog.appGroups, RuleCatalog.all)
        assertEquals(RuleCatalog.adGroups.size + RuleCatalog.appGroups.size, RuleCatalog.all.size)
    }

    /**
     * byId 是「持久化 id → 组」的反查，大小写敏感、未知 id 返回 null。
     * 返回 null 时 RuleRepository 会跳过该组；若这里对未知 id 兜底返回了某个组，
     * 旧版本残留的 id 会凭空点亮一个用户没开过的规则组。
     */
    @Test
    fun `byId 只认精确的已知 id`() {
        val wechat = RuleCatalog.byId("app-wechat")
        assertTrue("app-wechat 应存在", wechat != null)
        assertEquals("app-wechat", wechat?.id)
        assertNull(RuleCatalog.byId("APP-WECHAT"))       // 大小写敏感：持久化里存的是小写
        assertNull(RuleCatalog.byId("app-wechat "))      // 不做 trim：写入什么就查什么
        assertNull(RuleCatalog.byId("nonexistent"))
        assertNull(RuleCatalog.byId(""))
    }

    /** App 组的构造约定：id = `app-{key}`、assetPath = `rules/{id}.txt`、kind = DIRECT。 */
    @Test
    fun `App 组遵循 id 与清单路径的构造约定`() {
        RuleCatalog.appGroups.forEach { g ->
            assertTrue("${g.id} 应以 app- 开头", g.id.startsWith("app-"))
            assertEquals("${g.id} 的清单路径不符约定", "rules/${g.id}.txt", g.assetPath)
            assertEquals("${g.id} 应是直连组", RuleGroupKind.DIRECT, g.kind)
        }
    }

    /** 广告组是目前唯一的 REJECT 组，且归在 ADS 分类（管理页把它单独成节）。 */
    @Test
    fun `广告组是唯一的拦截组`() {
        assertEquals(listOf(RuleCatalog.ADS_OISD), RuleCatalog.adGroups)
        assertEquals(RuleGroupKind.REJECT, RuleCatalog.ADS_OISD.kind)
        assertEquals(RuleCategory.ADS, RuleCatalog.ADS_OISD.category)
        assertEquals(
            "REJECT 组只应有广告组",
            1, RuleCatalog.all.count { it.kind == RuleGroupKind.REJECT },
        )
    }

    /**
     * 每个组的清单文件必须真实存在且非空 —— 这是「开关亮着但表是空的」那类静默故障的唯一防线。
     * 文件缺失时 RuleRepository 只写一行 Log.w，规则页照样显示已启用。
     */
    @Test
    fun `每个组的清单文件都存在且非空`() {
        RuleCatalog.all.forEach { g ->
            val f = File(assetsDir, g.assetPath)
            assertTrue("${g.id} 的清单不存在：${g.assetPath}", f.isFile)
            assertTrue("${g.id} 的清单没有任何有效域名", effectiveLines(g.assetPath).isNotEmpty())
        }
    }

    /**
     * 双向对齐：assets 里的 `app-*.txt` 与 appGroups 一一对应。
     * - 有文件没组 ⇒ 白打包一份清单，用户在界面上根本找不到这个组；
     * - 有组没文件 ⇒ 开关能开，装载时静默失败（上一条已覆盖，这里补另一个方向）。
     */
    @Test
    fun `assets 里的 app 清单与组一一对应`() {
        val onDisk = File(assetsDir, "rules")
            .listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".txt") }
            ?.map { "rules/${it.name}" }?.toSortedSet()
        requireNotNull(onDisk) { "assets/rules 目录不存在（工作目录应为 app 模块目录）" }
        val inCatalog = RuleCatalog.appGroups.map { it.assetPath }.toSortedSet()
        assertEquals("未注册进目录的清单：${onDisk - inCatalog}；没有清单的组：${inCatalog - onDisk}",
            inCatalog, onDisk)
    }

    /**
     * 可编辑（多行文本框直接改）的组必须是小集。规格是「≤100 行」：
     * 把 2542 行的 tencent 组标成 editable，编辑页会塞进一个几万字符的 TextField —— 输入卡死，
     * 而且用户一旦保存就把整张表变成了「用户覆盖版」，之后再也吃不到内置更新。
     */
    @Test
    fun `可编辑组的清单都在小集规模内`() {
        RuleCatalog.all.filter { it.editable }.forEach { g ->
            val n = effectiveLines(g.assetPath).size
            assertTrue("${g.id} 标了 editable 却有 $n 行（上限 100）", n <= 100)
        }
        // 反向锁：大表绝不能被标成可编辑
        listOf("ads-oisd-small", "app-tencent", "app-kuaishou").forEach { id ->
            assertEquals("$id 不该可编辑", false, RuleCatalog.byId(id)?.editable)
        }
    }

    /**
     * 全部默认关闭 —— 产品口径「装上不改变用户网络行为，由用户主动开」。
     * 某天误设一个 defaultEnabled = true，新装用户的流量分流会在毫无提示下被改掉。
     */
    @Test
    fun `所有组默认关闭`() {
        val on = RuleCatalog.all.filter { it.defaultEnabled }.map { it.id }
        assertTrue("这些组默认开着：$on", on.isEmpty())
    }

    /** 每个分类至少有一个组：管理页按分类分节渲染，空分节会露出一个只有标题的空块。 */
    @Test
    fun `每个分类都至少有一个组`() {
        val used = RuleCatalog.all.map { it.category }.toSet()
        val empty = RuleCategory.values().filterNot { it in used }
        assertTrue("这些分类没有任何组，管理页会出现空分节：$empty", empty.isEmpty())
    }
}
