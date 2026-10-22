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
import com.mzstd.hxmyproxy.core.rules.RuleInputs
import com.mzstd.hxmyproxy.core.rules.RuleBuildResult
import com.mzstd.hxmyproxy.core.rules.RuleCounts
import com.mzstd.hxmyproxy.core.rules.RuleLoadFailure
import kotlinx.coroutines.CancellationException
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
    /** Compatibility helper for isolated matching tests; the service consumes the typed result. */
    fun rebuild(settings: ProxySettings, canApply: () -> Boolean = { true }): Boolean =
        rebuildResult(settings, canApply) { action ->
            if (!canApply()) false else { action(); true }
        } is RuleBuildResult.Ready

    @Synchronized
    fun rebuildResult(
        settings: ProxySettings,
        canApply: () -> Boolean = { true },
        commit: ((() -> Unit) -> Boolean) = { action -> if (!canApply()) false else { action(); true } },
    ): RuleBuildResult {
        if (!canApply()) return RuleBuildResult.Superseded
        assetFailures = 0
        assetFailureDetails.clear()
        assetFailureCause = null
        return try { buildSnapshot(settings, canApply, commit) }
        catch (_: CancellationException) { RuleBuildResult.Superseded }
        catch (error: Exception) {
            if (!canApply()) RuleBuildResult.Superseded
            else failed(settings, RuleLoadFailure.STORAGE, error.toString(), error = error)
        }
    }

    private fun buildSnapshot(settings: ProxySettings, canApply: () -> Boolean,
        commit: ((() -> Unit) -> Boolean)): RuleBuildResult {
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
            return if (commit { }) ready(changed = false) else RuleBuildResult.Superseded
        }
        val t0 = System.currentTimeMillis()
        val probes = ArrayList<String>(PROBE_MAX)
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
            // 只对进 reject 表的内置组采样：自检要验的正是「该拦的到底拦不拦得住」。
            else loadAsset(group.assetPath, into, if (into === reject) probes else null)
        }
        // 广告表误杀救济表：**始终装载、不受任何开关控制**——它是对上游公共黑名单的修正，
        // 只在广告表启用时才有作用（广告表没启用时 reject 为空，救济表自然无影响）。
        val adsAllow = RuleMatcher()
        loadAsset(ADS_ALLOWLIST_ASSET, adsAllow)
        val userDirect = RuleMatcher()
        val userReject = RuleMatcher()
        // 第一模块快速白名单 → 直连（受整体开关控制；关掉则整组临时失效）
        if (settings.userDirectEnabled) settings.userDirectRules.forEach { if (it.enabled) userDirect.add(it.value) }
        // 快速拦截名单 → userReject（受整体开关控制；关掉则整组临时失效，对称白名单）。**停用条目不装载=不参与判定**。
        if (settings.userRejectEnabled) settings.userRejectRules.forEach { if (it.enabled) userReject.add(it.value) }
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
        val newestAllow = settings.userDirectRules.asSequence().filter { it.enabled }.maxOfOrNull { it.addedAt } ?: 0L
        val newestBlock = settings.userRejectRules.asSequence().filter { it.enabled }.maxOfOrNull { it.addedAt } ?: 0L
        // Building is blocking. Commit is fenced by the service's lifecycle/request gate.
        if (!canApply()) return RuleBuildResult.Superseded
        val snapshot = RuleEngine.Snapshot(
            ovrDirect = ovrDirect, ovrReject = ovrReject, ovrProxy = ovrProxy,
            userDirect = userDirect, userReject = userReject,
            userDirectNewer = newestAllow >= newestBlock,
            adsAllow = adsAllow, reject = reject, direct = direct, proxy = proxy,
        )
        val valid = assetFailures == 0 && selfCheck(reject, probes)
        if (!valid) return failed(settings,
            if (assetFailures > 0) RuleLoadFailure.ASSET_READ else RuleLoadFailure.VALIDATION,
            if (assetFailures > 0) assetFailureDetails.joinToString("; ").take(512) else "Built-in rule self-check failed",
            RuleCounts.of(snapshot), assetFailureCause)
        if (!commit {
            ruleEngine.update(snapshot)
            // Memo and snapshot change together; an obsolete request cannot change either.
            lastBuilt = key
        }) return RuleBuildResult.Superseded
        // 规则表规模落盘：防护突然失灵时，「表里到底有多少条」是第一个要看的数字——
        // 6.6 万条变成 3 条与「规则没生效」在 UI 上完全同形，只有这里能区分。
        // 同时带 assetFail：任一内置组装载失败都会让它静默变空（见 loadAsset）。
        Ev.i(
            LogCat.RULE, "rules.rebuilt",
            "reject" to reject.size, "direct" to direct.size, "proxy" to proxy.size,
            "userDirect" to userDirect.size, "userReject" to userReject.size,
            "adsAllow" to adsAllow.size, "overrides" to settings.hostOverrides.size,
            "assetFail" to assetFailures.takeIf { it > 0 },
            // 自检：抽样验证「刚装进去的条目能被匹配出来」，含子域（全层级语义）。
            // 条数正常但个别条目丢失时，只有这个字段会变红。
            "probe" to "ok:${probes.size}",
            "ms" to (System.currentTimeMillis() - t0),
        )
        return ready(changed = true)
    }

    private var assetFailures = 0
    private val assetFailureDetails = ArrayList<String>()
    private var assetFailureCause: Exception? = null
    private var lastBuilt: RuleInputs? = null

    private fun ready(changed: Boolean) = RuleBuildResult.Ready(ruleEngine.version.value,
        RuleCounts.of(ruleEngine.snapshot), changed)

    private fun failed(settings: ProxySettings, reason: RuleLoadFailure, detail: String,
        candidate: RuleCounts? = null, error: Throwable? = null): RuleBuildResult.Failed {
        lastBuilt = null // Retain the complete snapshot, but permit a real retry of these inputs.
        val old = ready(false)
        val needsProtection = try {
            settings.enabledRuleGroups.any { id ->
                RuleCatalog.byId(id)?.kind?.let { it != RuleGroupKind.PROXY } == true &&
                    settings.ruleSetOverrides[id]?.isEmpty() != true
            } || settings.userDirectEnabled && settings.userDirectRules.any { it.enabled } ||
                settings.userRejectEnabled && settings.userRejectRules.any { it.enabled } ||
                settings.userRuleSets.any { it.enabled && it.domains.isNotEmpty() } ||
                settings.hostOverrides.values.any { it != RuleAction.PROXY }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { true } // A storage error is not proof that protection is unnecessary.
        Ev.e(LogCat.RULE, "rules.rebuildFailed", error, "reason" to reason, "detail" to detail.take(512),
            "source" to if (reason == RuleLoadFailure.STORAGE) "rule-content" else "assets",
            "assetFail" to assetFailures, "appliedVersion" to old.version,
            "previousRules" to old.counts.total, "previousReject" to old.counts.reject,
            "previousUserDirect" to old.counts.userDirect,
            "candidateRules" to candidate?.total, "candidateReject" to candidate?.reject,
            "candidateUserDirect" to candidate?.userDirect, "requestedProtection" to needsProtection)
        return RuleBuildResult.Failed(reason, detail.take(512), old.version, old.counts, needsProtection)
    }

    private fun loadAsset(path: String, into: RuleMatcher, sampleInto: MutableList<String>? = null) {
        try {
            context.assets.open(path).use { raw ->
                val stream = if (path.endsWith(".gz")) GZIPInputStream(raw) else raw
                var n = 0
                stream.bufferedReader().forEachLine { line ->
                    val d = line.trim()
                    if (d.isNotEmpty() && d[0] != '#') {
                        into.add(d)
                        // 采样首条与每 20000 条一条，供构建后自检（见 selfCheck）。
                        // 只取纯域名（跳过 IP/CIDR 与带作用域前缀的写法），保证子域探测语义明确。
                        if (sampleInto != null && sampleInto.size < PROBE_MAX &&
                            (n == 0 || n % PROBE_STRIDE == 0) &&
                            d.all { it.isLetterOrDigit() || it == '.' || it == '-' } && d.contains('.')
                        ) {
                            sampleInto.add(d)
                        }
                        n++
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            // **静默失败最危险的一处**：某个规则集变成空表，广告照进、直连组失效，而 UI 上
            // 开关仍显示「已启用」。此前只有 Log.w（release 下 logcat 还被剥离），导出日志里
            // 一个字都没有。进 key.log。
            assetFailures++
            if (assetFailureCause == null) assetFailureCause = e
            assetFailureDetails.add("$path: ${e.message ?: e.javaClass.simpleName}")
            Ev.kw(LogCat.RULE, "rules.assetFailed", "path" to path, "err" to e.toString())
        }
    }

    /**
     * 构建后自检：**刚装进去的条目，能不能被匹配出来**。
     *
     * 为什么条数不够用：`rules.rebuilt` 只记各表规模，能发现「6.6 万条变成 3 条」这种整体失效，
     * 但**发现不了个别条目丢失**——而 0806 的分歧正是这一类：
     * `googleads.g.doubleclick.net` 照常被拦，`pagead2.googlesyndication.com` 却放行了，
     * 两者都在同一张表里，条数完全正常。
     *
     * 探针取自本次真实装载的内容（首条 + 每 [PROBE_STRIDE] 条采样一个），所以不依赖
     * 任何硬编码域名，名单更新后依然有效。每个探针验证两件事：
     *   1. **自身**能命中 —— 条目确实进了表
     *   2. **子域**能命中 —— 全层级语义生效（内置表 6 万条全是这一档）
     *
     * 失败即落 key.log：这是「防护看着开着、实际不拦」的唯一可见信号。
     */
    private fun selfCheck(reject: RuleMatcher, probes: List<String>): Boolean {
        if (probes.isEmpty()) return true
        val selfMiss = probes.filterNot { reject.matches(it) }
        val subMiss = probes.filterNot { reject.matches("probe0.$it") }
        if (selfMiss.isEmpty() && subMiss.isEmpty()) return true
        Ev.kw(
            LogCat.RULE, "rules.selfcheck.fail",
            "probes" to probes.size,
            "selfMiss" to selfMiss.size, "subMiss" to subMiss.size,
            "sample" to (selfMiss.firstOrNull() ?: subMiss.firstOrNull()),
        )
        return false
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

        /** 自检探针数量上限：够覆盖全表分布即可，匹配开销 O(标签数) 可忽略。 */
        private const val PROBE_MAX = 8

        /** 采样步长：6 万条表大约每 2 万条取一个，首条必取。 */
        private const val PROBE_STRIDE = 20_000
    }
}
