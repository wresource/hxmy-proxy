package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleSrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * 出厂内置规则数据的真实路由验证（纯 JVM，直接读 shipped assets 灌进真实
 * [DomainSuffixSet] / [RuleEngine]）——不依赖设备/模拟器，证明发布数据经真实
 * 引擎代码正确分流。锁屏时这就是比 UI 截图更硬的证据。
 */
class BuiltinRuleAssetsTest {

    private val assetsDir = File("src/main/assets/rules")

    private fun load(name: String): DomainSuffixSet {
        val set = DomainSuffixSet()
        File(assetsDir, name).forEachLine { line ->
            val d = line.trim()
            if (d.isNotEmpty() && d[0] != '#') set.addSuffix(d)
        }
        return set
    }

    /** 每个 app-*.txt 都存在、非空、且每行都是合法域名（addSuffix 接受）。 */
    @Test
    fun allAppAssetsPresentNonEmptyAndValid() {
        val files = assetsDir.listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".txt") }
        requireNotNull(files) { "assets/rules 目录不存在" }
        assertTrue("app-* 规则文件应 >= 60 个，实际 ${files.size}", files.size >= 60)
        var totalDomains = 0
        val domainRx = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$")
        for (f in files) {
            val lines = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }
            assertTrue("${f.name} 不应为空", lines.isNotEmpty())
            for (d in lines) {
                assertTrue("${f.name} 含非法域名: $d", domainRx.matches(d))
            }
            totalDomains += lines.size
        }
        assertTrue("内置域名总量应 >= 5000，实际 $totalDomains", totalDomains >= 5000)
    }

    /** 后缀匹配语义：列表里的域名自身及其任意子域都命中，无关域名不命中。 */
    @Test
    fun suffixMatchingRoutesKnownDomainsAndSubdomains() {
        val cases = mapOf(
            "app-tencent.txt" to listOf("qq.com", "mail.qq.com", "wx2.qq.com", "im.qq.com"),
            "app-icbc.txt" to listOf("icbc.com.cn", "mybank.icbc.com.cn"),
            "app-alipay.txt" to listOf("alipay.com", "mobilecodec.alipay.com"),
            "app-neteasemusic.txt" to listOf("music.163.com"),
            "app-mihoyo.txt" to listOf("mihoyo.com", "api-takumi.mihoyo.com"),
            "app-bilibili.txt" to listOf("bilibili.com"),
        )
        for ((file, hosts) in cases) {
            val set = load(file)
            for (h in hosts) {
                assertTrue("$file 应命中 $h（自身或子域）", set.matches(h))
            }
        }
    }

    /** 无关域名绝不被内置组误命中（防误杀回归）。 */
    @Test
    fun unrelatedDomainsDoNotMatch() {
        val tencent = load("app-tencent.txt")
        listOf("google.com", "example.com", "cloudflare.com", "github.com").forEach {
            assertFalse("tencent 组不应误命中 $it", tencent.matches(it))
        }
    }

    /** 端到端：把内置直连组灌进 RuleEngine，decide() 对命中域名返回 DIRECT、其余兜底 PROXY。 */
    @Test
    fun ruleEngineDecidesDirectForBuiltinDirectGroups() {
        val direct = RuleMatcher()
        // 模拟 RuleRepository：把多个已启用直连组合并进 direct 集
        listOf("app-tencent.txt", "app-icbc.txt", "app-mihoyo.txt").forEach { f ->
            File(assetsDir, f).forEachLine { line ->
                val d = line.trim(); if (d.isNotEmpty() && d[0] != '#') direct.add(d)
            }
        }
        val engine = RuleEngine()
        engine.update(RuleEngine.Snapshot(direct = direct))

        assertEquals(RuleAction.DIRECT, engine.decide("mail.qq.com"))
        assertEquals(RuleAction.DIRECT, engine.decide("mybank.icbc.com.cn"))
        assertEquals(RuleAction.DIRECT, engine.decide("api-takumi.mihoyo.com"))
        // 未启用的组 / 无关域名 → 兜底 PROXY
        assertEquals(RuleAction.PROXY, engine.decide("google.com"))
        assertEquals(RuleAction.PROXY, engine.decide("192.168.1.1"))
    }

    /**
     * 广告表误杀救济：微信内容域必须直连而非被拦。
     *
     * 防的是 2026-07-28 的真实故障——ads-oisd-small.txt 收录了 wxa/wximg/wxsmw/wxsnsdythumb
     * 四个 wxs.qq.com 子域(实测全部解析到腾讯云 CDN，是小程序资源与图片/视频)，而
     * BUILTIN_ADS 优先于 BUILTIN_APP，导致我们自己在 app-wechat.txt 里认定要直连的域族
     * 反被广告表拦掉，表现为「小程序打不开、图片转圈」且用户无从得知卡在哪个域名。
     */
    @Test
    fun adsAllowlistRescuesWechatContentDomains() {
        val engine = RuleEngine()
        engine.update(
            RuleEngine.Snapshot(
                adsAllow = matcherOf("ads-allowlist.txt"),
                reject = matcherOf("ads-oisd-small.txt"),
                direct = matcherOf("app-wechat.txt", "app-tencent.txt"),
            ),
        )
        // 被 oisd 误收、经救济表放行 → 落到微信/腾讯直连组
        listOf(
            "wxa.wxs.qq.com",            // 小程序资源
            "wximg.wxs.qq.com",          // 图片
            "wxsmw.wxs.qq.com",          // 视频点播
            "wxsnsdythumb.wxs.qq.com",   // 朋友圈缩略图
            "gvideo.qpic.cn",            // 短视频
            "1500020991.vodplayer.wxamedia.com", // 小程序视频播放器(父域救济覆盖子域)
        ).forEach {
            assertEquals("$it 应被救济为直连", RuleAction.DIRECT, engine.decide(it))
        }
        // 救济表**不得**放过真广告/遥测：这些同为 qq 域族但属广告投放或上报
        listOf(
            "wxsnsad.tc.qq.com",         // 朋友圈广告
            "qzs.gdtimg.com",            // 广点通素材
            "otheve.beacon.qq.com",      // 遥测
            "h.trace.qq.com",            // 追踪
        ).forEach {
            assertEquals("$it 应保持拦截", RuleAction.REJECT, engine.decide(it))
        }
    }

    /**
     * 冲突可见性：ads 表里「父域已在某个 app-* 直连组」的条目属于潜在误杀，
     * 必须保持在已知规模内。上游 oisd 更新后若新增大量此类条目，此测试会失败，
     * 迫使人工复核并按需补进 ads-allowlist.txt（而不是等用户报告功能坏掉）。
     */
    @Test
    fun adsAppConflictsStayWithinKnownBaseline() {
        val appDomains = HashSet<String>()
        assetsDir.listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".txt") }
            ?.forEach { f ->
                f.forEachLine { line ->
                    val d = line.trim().lowercase().trimEnd('.')
                    if (d.isNotEmpty() && d[0] != '#') appDomains.add(d)
                }
            }
        val conflicts = ArrayList<String>()
        File(assetsDir, "ads-oisd-small.txt").forEachLine { line ->
            val a = line.trim().lowercase().trimEnd('.')
            if (a.isEmpty() || a[0] == '#') return@forEachLine
            val labels = a.split('.')
            for (i in 1 until labels.size) {
                if (labels.subList(i, labels.size).joinToString(".") in appDomains) {
                    conflicts.add(a); break
                }
            }
        }
        // 2026-07-28 实测基线 556；留出余量，显著增长即需人工复核。
        assertTrue(
            "ads 与 app 直连组的冲突条目 ${conflicts.size} 超出基线，请复核新增项是否为误杀并按需补进 ads-allowlist.txt",
            conflicts.size <= 620,
        )
    }

    /**
     * 优先级矩阵：手动操作(防护页 OVERRIDE / 规则页 USER_*)必须压过一切内置表。
     *
     * 「手动操作是更昂贵的动作，理应最高优先级」是产品口径；这里用真实 shipped assets 把它钉死，
     * 免得日后新增表(如 1.19.2 的救济表)时不小心插错层。同时锁定两层手动之间的相对次序：
     * per-host 覆盖(防护页，精确到一个 host) > 规则页列表(可为泛域族)。
     */
    @Test
    fun manualRulesOutrankEveryBuiltinTable() {
        val ads = matcherOf("ads-oisd-small.txt")
        val allow = matcherOf("ads-allowlist.txt")
        val app = matcherOf("app-wechat.txt", "app-tencent.txt")

        // ① 规则页放行「父域」→ 覆盖广告表里的子域，且判定为 DIRECT(不是兜底 PROXY)
        RuleEngine().apply {
            update(RuleEngine.Snapshot(userDirect = matcherOf0("wxs.qq.com"), adsAllow = allow, reject = ads, direct = app))
            assertEquals(RuleAction.DIRECT, decide("wxa.wxs.qq.com"))
            assertEquals(RuleSrc.USER_ALLOW, decideDetailed("wxa.wxs.qq.com").src)
            // 放行只作用于该域族：不在 wxs.qq.com 下的遥测域不受影响，仍被广告表拦
            assertEquals(RuleAction.REJECT, decide("otheve.beacon.qq.com"))
            assertEquals(RuleSrc.BUILTIN_ADS, decideDetailed("otheve.beacon.qq.com").src)
        }
        // ② 规则页拦截 > 救济表(用户想拦被救济的域名，拦得住)
        RuleEngine().apply {
            update(RuleEngine.Snapshot(userReject = matcherOf0("wxa.wxs.qq.com"), adsAllow = allow, reject = ads, direct = app))
            assertEquals(RuleAction.REJECT, decide("wxa.wxs.qq.com"))
            assertEquals(RuleSrc.USER_BLOCK, decideDetailed("wxa.wxs.qq.com").src)
        }
        // ③ 防护页 per-host 覆盖 > 规则页列表(两层手动的相对次序)
        RuleEngine().apply {
            update(
                RuleEngine.Snapshot(
                    ovrReject = matcherOf0("wxa.wxs.qq.com"),
                    userDirect = matcherOf0("wxs.qq.com"),
                    adsAllow = allow, reject = ads, direct = app,
                ),
            )
            assertEquals(RuleAction.REJECT, decide("wxa.wxs.qq.com"))
            assertEquals(RuleSrc.OVERRIDE, decideDetailed("wxa.wxs.qq.com").src)
            // 反向：防护页放行 + 规则页拦截 → 防护页赢
            update(
                RuleEngine.Snapshot(
                    ovrDirect = matcherOf0("otheve.beacon.qq.com"),
                    userReject = matcherOf0("otheve.beacon.qq.com"),
                    adsAllow = allow, reject = ads, direct = app,
                ),
            )
            assertEquals(RuleAction.DIRECT, decide("otheve.beacon.qq.com"))
            assertEquals(RuleSrc.OVERRIDE, decideDetailed("otheve.beacon.qq.com").src)
        }
        // ④ 无任何手动规则：救济表放行的落到 App 直连组，未救济的真广告仍被拦
        RuleEngine().apply {
            update(RuleEngine.Snapshot(adsAllow = allow, reject = ads, direct = app))
            assertEquals(RuleSrc.BUILTIN_APP, decideDetailed("wxa.wxs.qq.com").src)
            assertEquals(RuleSrc.BUILTIN_ADS, decideDetailed("qzs.gdtimg.com").src)
        }
    }

    private fun matcherOf0(vararg domains: String): RuleMatcher {
        val m = RuleMatcher()
        domains.forEach { m.add(it) }
        return m
    }

    private fun matcherOf(vararg names: String): RuleMatcher {
        val m = RuleMatcher()
        names.forEach { n ->
            File(assetsDir, n).forEachLine { line ->
                val d = line.trim(); if (d.isNotEmpty() && d[0] != '#') m.add(d)
            }
        }
        return m
    }
}
