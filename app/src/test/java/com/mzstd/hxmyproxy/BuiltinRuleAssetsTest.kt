package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleSrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
     * 第二批救济(2026-07-28,556 条冲突分诊)的高置信条目：都有实测/反编译级证据，
     * 且拦掉会造成用户可见的功能损坏。同时锁住「复核时被推翻」的那批仍要拦——
     * 它们看着像内容(img/video/cdn 命名)实则服务广告投放，是本轮最容易判错的一类。
     */
    @Test
    fun secondBatchAllowlistRescuesAndStillBlocksLookalikes() {
        val engine = RuleEngine()
        engine.update(
            RuleEngine.Snapshot(
                adsAllow = matcherOf("ads-allowlist.txt"),
                reject = matcherOf("ads-oisd-small.txt"),
                direct = matcherOf(
                    "app-tencent.txt", "app-wechat.txt", "app-alibaba.txt",
                    "app-douyin.txt", "app-kuaishou.txt", "app-netease.txt", "app-jd.txt",
                ),
            ),
        )
        // 拦掉会坏功能的：长连接/起播/搜索/设备注册/直播拉流/验证码/商品图
        listOf(
            "accscdn.m.taobao.com",        // 淘系 ACCS 长连接(反编译硬证 SERVICE_HOST)
            "amdcopen.m.taobao.com",       // 淘系接入调度
            "vd6.l.qq.com",                // 腾讯视频起播
            "vi.l.qq.com",
            "wxsnsdy.tc.qq.com",           // 朋友圈视频正片(注意与 wxsnsad 广告域区分)
            "log.snssdk.com",              // 字节 device_register(实测返回 device_id/install_id)
            "search.ixigua.com",           // 西瓜搜索(实测 1.27MB 结果页)
            "ks-p2p.pull.yximgs.com",      // 快手直播拉流(父域 pull.yximgs.com 覆盖)
            "ye.dun.163yun.com",           // 网易易盾验证码
            "img2.360buyimg.com",          // 京东商品图分片
            "images.pinduoduo.com",        // 拼多多商品图
        ).forEach {
            assertNotEquals("$it 拦掉会坏用户功能，应被救济", RuleAction.REJECT, engine.decide(it))
        }
        // 复核时被推翻的「像内容实为广告」：必须仍然拦
        listOf(
            "wm.mipcdn.com",               // 实测 <title>百度网盟推广</title>
            "feed-image.baidu.com",        // 网盘开屏广告图
            "img1.126.net",                // 网易富媒体广告图床
            "adsmind.gdtimg.com",          // 广点通素材
            "ads-img-al.xhscdn.com",       // 小红书广告图
            "v1-ad.video.yximgs.com",      // 快手广告视频(证明 pull.yximgs.com 父域没扩散过界)
        ).forEach {
            assertEquals("$it 是广告投放，应保持拦截", RuleAction.REJECT, engine.decide(it))
        }
    }

    /** 规则文件的有效行（去注释与空行，小写、去尾点），与装载逻辑同一口径。 */
    private fun lines(name: String): List<String> =
        File(assetsDir, name).readLines()
            .map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() && it[0] != '#' }

    /** 全部 app-*.txt 的域名集合（内置直连组）。 */
    private fun appDomains(): Set<String> {
        val out = HashSet<String>()
        assetsDir.listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".txt") }
            ?.forEach { out.addAll(lines(it.name)) }
        return out
    }

    /** ads 表里「父域已在某个 app-* 直连组」的条目 —— 潜在误杀清单。 */
    private fun adsAppConflicts(): List<String> {
        val app = appDomains()
        return lines("ads-oisd-small.txt").filter { a ->
            val labels = a.split('.')
            // 从 i=1 起：只看**父域**落在直连组的情况（自身同名不算冲突，那是两表都收的同一条）
            (1 until labels.size).any { i -> labels.subList(i, labels.size).joinToString(".") in app }
        }
    }

    /**
     * 冲突可见性：统计的是**尚未被救济表覆盖**的潜在误杀数，不是冲突总数。
     *
     * 口径 2026-07-29 改过一次。旧口径统计 ads×app 交集总数（556，阈值 620），问题是**已经人工复核并
     * 补进 ads-allowlist.txt 的条目仍然占着额度** —— 救济得越多，离阈值反而越近，等于惩罚做正事。
     * 上游 oisd 更新带来的**新增**误杀因此被已有存量稀释，要涨到 620 才报警，晚了 60 多条。
     *
     * 新口径只数「冲突且没被救济」的（实测 498，阈值 560）：救济一条就少一条，剩下的才是真正待复核的。
     */
    @Test
    fun adsAppConflictsStayWithinKnownBaseline() {
        val conflicts = adsAppConflicts()
        // 用真实的救济表判定（与 RuleEngine 里 `adsAllow.matches(host)` 同一份代码路径）
        val allow = load("ads-allowlist.txt")
        val unrescued = conflicts.filter { !allow.matches(it) }

        // 2026-07-29 实测：冲突 556，其中 58 条已被救济表的 50 个条目覆盖，剩 498 待复核。
        assertTrue(
            "未救济的 ads×app 冲突 ${unrescued.size} 条（总冲突 ${conflicts.size}）超出基线 560，" +
                "请复核新增项是否为误杀并按需补进 ads-allowlist.txt",
            unrescued.size <= 560,
        )
        // 反向锁：救济表确实在生效。若哪次重构把它从引擎里摘掉，上面那条会因为「没救济任何东西」
        // 而数字暴涨——但那要涨到 560 才报警，这里直接钉住「至少救到 40 条」，摘掉即刻失败。
        assertTrue(
            "救济表只覆盖了 ${conflicts.size - unrescued.size} 条冲突，远低于已知的 58 条——" +
                "ads-allowlist.txt 是否被清空或未被装载？",
            conflicts.size - unrescued.size >= 40,
        )
    }

    /**
     * 救济表的**收录纪律**（表头写的三条标准里可机器验证的两条）：
     *
     * ① 每条的父域必须在某个 app-*.txt 直连组里 —— 救济表只用来修「我们自己认定该直连、却被
     *    公共黑名单拦掉」的域，不是随手放行的口子；
     * ② 每条都必须**确实救到**广告表里的某条 —— 上游把某条移除后，对应的救济就成了空转，
     *    留着会让人误以为「这个域名曾经被误杀过」。空转不影响功能，但会让表越来越难维护。
     *
     * 注意 ② 的方向：救济条目常写成**父域**去覆盖 ads 表里的一批子域
     * （`pull.yximgs.com` 覆盖 7 条 `*.pull.yximgs.com`，`vodplayer.wxamedia.com` 覆盖 3 个租户号子域），
     * 所以判据是「ads 表里有它或它的子域」，不是「它在 ads 表里」——反过来查会误判成一堆空转。
     */
    @Test
    fun adsAllowlistEntriesFollowInclusionCriteria() {
        val app = appDomains()
        val ads = lines("ads-oisd-small.txt")
        val allow = lines("ads-allowlist.txt")
        assertTrue("救济表不应为空", allow.isNotEmpty())

        val orphans = allow.filter { e ->
            val labels = e.split('.')
            labels.indices.none { i -> labels.subList(i, labels.size).joinToString(".") in app }
        }
        assertTrue("救济表条目的父域必须已在某个 app-* 直连组：$orphans", orphans.isEmpty())

        val noop = allow.filter { e -> ads.none { it == e || it.endsWith(".$e") } }
        assertTrue(
            "这些救济条目没救到广告表里的任何域名（上游可能已移除，建议清理）：$noop",
            noop.size <= 3,
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

    /**
     * **广告表的子域必须命中**（0806 观察分歧的静态锁）。
     *
     * 背景：上游中间层代理实测 `pagead2.googlesyndication.com` 被放行，而名单里
     * 有 `googlesyndication.com`、匹配器默认就是全层级（无前缀 = addSuffix），
     * 按语义子域本该命中。本测试把「名单 + 匹配语义」这一层钉死：
     * 只要它是绿的，就说明问题不在名单也不在匹配器，而在**运行时的表构建**——
     * 这正是排除法需要的那一半。
     *
     * 同时验证救济名单没有反向抵消（那 50 条全是国内厂商内容域名，不该含这些）。
     */
    @Test
    fun adsListMustMatchSubdomainsOfListedEntries() {
        val ads = load("ads-oisd-small.txt")
        val allow = load("ads-allowlist.txt")
        // 取名单里真实存在的父域，各造一个子域
        val cases = listOf(
            "googlesyndication.com" to "pagead2.googlesyndication.com",
            "g.doubleclick.net" to "googleads.g.doubleclick.net",
        )
        for ((parent, child) in cases) {
            assertTrue("$parent 应在广告名单内（前提失效则本测试无意义）", ads.matches(parent))
            assertTrue("$child 应被父域 $parent 以全层级语义命中", ads.matches(child))
            assertTrue("$child 不该被误杀救济名单放行", !allow.matches(child))
        }
    }
}
