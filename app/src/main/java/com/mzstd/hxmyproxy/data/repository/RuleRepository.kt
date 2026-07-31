package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import android.util.Log
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleGroupKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则装载：按设置（启用的内置组 + 用户白名单）从 assets 读清单，构建 [RuleEngine.Snapshot] 热替换进引擎。
 *
 * 读 assets + 建后缀树有 IO/CPU 开销（OISD ~6 万条），调用方应在 IO 线程调 [rebuild]。
 * 用户白名单（userDirectRules）走 DIRECT，优先级最高（防误杀，连广告表都覆盖）。
 */
@Singleton
class RuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleEngine: RuleEngine,
) {
    /**
     * 按设置重建规则引擎快照。
     *
     * **记忆化早退**：只有 [RuleInputs] 里那 9 个字段变了才真重建。此前任何一次设置写入
     * （改主题、切语言、隐藏一个 tab）都会走到这里全量重装 —— 实测启用广告表时
     * `rules.rebuilt ... ms=1124`，即改个主题要花 1.1 秒重读 1.16MB assets、重建 61063 条后缀树，
     * 而且发生在代理正在转发流量的时候。
     *
     * **[Synchronized] 不是可选的**：本方法阻塞且不可取消（`forEachLine` 读 1.16MB 全程无挂起点），
     * 所以 stop() 的 `sessionScope.cancel()` 停不掉正在跑的 rebuild。用户在这 1.1 秒内停止再开启共享，
     * 两个 rebuild 就会并发；交错成 `T1.update(X) → T2.update(Y) → T1.lastBuilt=X` 之后，
     * 引擎里装的是 Y 而 memo 说装的是 X —— 此后任何 X 请求都被跳过，**规则静默停在旧版本**。
     * 加锁同时也修掉了 [assetFailures] 原有的竞态。
     *
     * memo 记的是「引擎里现在装的是哪一份」而非「谁请求过什么」，所以跨 stop/start 不会失配
     * （RuleEngine 与本类都是 @Singleton，快照跨会话存活）；顺带白赚一条：规则没改时重开共享
     * 不再花那 1.1 秒。
     */
    @Synchronized
    fun rebuild(settings: ProxySettings) {
        val key = RuleInputs.of(settings)
        if (key == lastBuilt) {
            // 跳过也要留痕，而且**必须带各表规模**：rules.rebuilt 是全仓唯一记录规则表条数的地方
            // （见下方 rules.rebuilt 的注释：6.6 万条变成 3 条与「规则没生效」在 UI 上完全同形）。
            // 只写一句 why=unchanged 的话，一次规则未变的长会话里就再也没有规模证据了。
            val s = ruleEngine.snapshot
            Ev.i(
                LogCat.RULE, "rules.skipped", "why" to "unchanged",
                "reject" to s.reject.size, "direct" to s.direct.size,
                "userDirect" to s.userDirect.size, "userReject" to s.userReject.size,
                "adsAllow" to s.adsAllow.size,
            )
            return
        }
        val t0 = System.currentTimeMillis()
        assetFailures = 0
        val reject = RuleMatcher()
        val direct = RuleMatcher()
        val proxy = RuleMatcher()
        for (id in settings.enabledRuleGroups) {
            val group = RuleCatalog.byId(id) ?: continue
            val into = when (group.kind) {
                RuleGroupKind.REJECT -> reject
                // 内置组默认直连；被用户移到规则页「拦截行」的组进 reject 表（两行式）。
                RuleGroupKind.DIRECT -> if (id in settings.rejectedGroups) reject else direct
                RuleGroupKind.PROXY -> proxy
            }
            val override = settings.ruleSetOverrides[id]
            if (override != null) override.forEach { into.add(it) }
            else loadAsset(group.assetPath, into)
        }
        // 广告表误杀救济表：**始终装载、不受任何开关控制**——它是对上游公共黑名单的修正，
        // 只在广告表启用时才有作用（广告表没启用时 reject 为空，救济表自然无影响）。
        val adsAllow = RuleMatcher()
        loadAsset(ADS_ALLOWLIST_ASSET, adsAllow)
        val userDirect = RuleMatcher()
        val userReject = RuleMatcher()
        // 第一模块快速白名单 → 直连（受整体开关控制；关掉则整组临时失效）
        if (settings.userDirectEnabled) settings.userDirectRules.filter { it.enabled }.forEach { userDirect.add(it.value) }
        // 快速拦截名单 → userReject（受整体开关控制；关掉则整组临时失效，对称白名单）。**停用条目不装载=不参与判定**。
        if (settings.userRejectEnabled) settings.userRejectRules.filter { it.enabled }.forEach { userReject.add(it.value) }
        // 用户自建命名集（按动作进 direct/reject;优先级高于内置）
        settings.userRuleSets.filter { it.enabled }.forEach { set ->
            val into = if (set.action == RuleAction.REJECT) userReject else userDirect
            set.domains.forEach { into.add(it) }
        }
        // per-host 三态覆盖（最高优先级：误拦救济/手动指定）
        val ovrDirect = RuleMatcher()
        val ovrReject = RuleMatcher()
        val ovrProxy = RuleMatcher()
        settings.hostOverrides.forEach { (host, action) ->
            when (action) {
                RuleAction.DIRECT -> ovrDirect.add(host)
                RuleAction.REJECT -> ovrReject.add(host)
                RuleAction.PROXY -> ovrProxy.add(host)
            }
        }
        // 平手裁决用:两张用户表各自最近一条的添加时间(停用条目不算,它们不参与判定)。
        val newestAllow = settings.userDirectRules.filter { it.enabled }.maxOfOrNull { it.addedAt } ?: 0L
        val newestBlock = settings.userRejectRules.filter { it.enabled }.maxOfOrNull { it.addedAt } ?: 0L
        ruleEngine.update(
            RuleEngine.Snapshot(
                ovrDirect = ovrDirect, ovrReject = ovrReject, ovrProxy = ovrProxy,
                userDirect = userDirect, userReject = userReject,
                userDirectNewer = newestAllow >= newestBlock,
                adsAllow = adsAllow,
                reject = reject, direct = direct, proxy = proxy,
            ),
        )
        // 规则表规模落盘：防护突然失灵时，「表里到底有多少条」是第一个要看的数字——
        // 6.6 万条变成 3 条与「规则没生效」在 UI 上完全同形，只有这里能区分。
        // 同时带 assetFail：任一内置组装载失败都会让它静默变空（见 loadAsset）。
        Ev.i(
            LogCat.RULE, "rules.rebuilt",
            "reject" to reject.size, "direct" to direct.size, "proxy" to proxy.size,
            "userDirect" to userDirect.size, "userReject" to userReject.size,
            "adsAllow" to adsAllow.size, "overrides" to settings.hostOverrides.size,
            "assetFail" to assetFailures.takeIf { it > 0 },
            "ms" to (System.currentTimeMillis() - t0),
        )
        // 装载失败过就**不**记忆化：留住「下次设置变更时顺带重试」的机会。
        // 否则一次 assets 读失败（见 loadAsset 的注释：这是静默失败最危险的一处）
        // 会被永久固化成空表，而 UI 上开关仍显示「已启用」。
        lastBuilt = if (assetFailures == 0) key else null
    }

    /** 本轮 [rebuild] 中装载失败的 assets 数量（喂给 rules.rebuilt 的 assetFail 字段）。 */
    private var assetFailures = 0

    /** 引擎里当前装的是哪一份规则输入；null=未知/需重建。仅在 [rebuild] 的锁内读写。 */
    private var lastBuilt: RuleInputs? = null

    /**
     * [rebuild] 真正读到的字段，一个不多一个不少。
     *
     * 多一个：无关变更也会触发重建，白白付掉那 1.1 秒。
     * 少一个：改了规则却不重建 —— **静默失效**，UI 上开关一切正常，用户只会发现「我明明改了却没生效」。
     * 所以这份清单必须对着 [rebuild] 正文逐行核，不能凭印象。
     *
     * 两张用户表存**整条 [RuleEntry]** 而不是 List<String>：`addedAt` 参与 userDirectNewer 的
     * 平手裁决、`enabled` 决定是否装载，只比对 value 会漏掉这两类变更。
     */
    private data class RuleInputs(
        val groups: Set<String>,
        val rejectedGroups: Set<String>,
        val overrides: Map<String, List<String>>,
        val hostOverrides: Map<String, RuleAction>,
        val userDirectEnabled: Boolean,
        val userRejectEnabled: Boolean,
        val userDirect: List<RuleEntry>,
        val userReject: List<RuleEntry>,
        val sets: List<UserRuleSet>,
    ) {
        companion object {
            fun of(s: ProxySettings) = RuleInputs(
                groups = s.enabledRuleGroups,
                rejectedGroups = s.rejectedGroups,
                overrides = s.ruleSetOverrides,
                hostOverrides = s.hostOverrides,
                userDirectEnabled = s.userDirectEnabled,
                userRejectEnabled = s.userRejectEnabled,
                userDirect = s.userDirectRules,
                userReject = s.userRejectRules,
                sets = s.userRuleSets,
            )
        }
    }

    private fun loadAsset(path: String, into: RuleMatcher) {
        try {
            context.assets.open(path).use { raw ->
                val stream = if (path.endsWith(".gz")) GZIPInputStream(raw) else raw
                stream.bufferedReader().forEachLine { line ->
                    val d = line.trim()
                    if (d.isNotEmpty() && d[0] != '#') into.add(d)
                }
            }
        } catch (e: Exception) {
            // **静默失败最危险的一处**：某个规则集变成空表，广告照进、直连组失效，而 UI 上
            // 开关仍显示「已启用」。此前只有 Log.w（release 下 logcat 还被剥离），导出日志里
            // 一个字都没有。进 key.log。
            assetFailures++
            Ev.kw(LogCat.RULE, "rules.assetFailed", "path" to path, "err" to e.toString())
        }
    }

    /** 读 assets 清单供 UI 预览：返回总条数 + 前 [limit] 条（大表如 OISD 不全量进 UI）。 */
    fun groupPreview(assetPath: String, limit: Int = 30): GroupPreview {
        var total = 0
        val sample = ArrayList<String>(limit)
        try {
            context.assets.open(assetPath).use { raw ->
                val stream = if (assetPath.endsWith(".gz")) GZIPInputStream(raw) else raw
                stream.bufferedReader().forEachLine { line ->
                    val d = line.trim()
                    if (d.isNotEmpty() && d[0] != '#') {
                        total++
                        if (sample.size < limit) sample.add(d)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("hxmyproxy", "preview $assetPath failed: ${e.message}")
        }
        return GroupPreview(total, sample)
    }

    data class GroupPreview(val total: Int, val sample: List<String>)

    companion object {
        /** 广告表误杀救济表资产路径（见该文件头部的收录标准）。 */
        const val ADS_ALLOWLIST_ASSET = "rules/ads-allowlist.txt"
    }
}
