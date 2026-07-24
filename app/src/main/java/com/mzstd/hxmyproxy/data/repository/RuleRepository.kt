package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import android.util.Log
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
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
    fun rebuild(settings: ProxySettings) {
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
        ruleEngine.update(
            RuleEngine.Snapshot(
                ovrDirect = ovrDirect, ovrReject = ovrReject, ovrProxy = ovrProxy,
                userDirect = userDirect, userReject = userReject,
                reject = reject, direct = direct, proxy = proxy,
            ),
        )
        Log.i("hxmyproxy", "rules rebuilt: reject=${reject.size} direct=${direct.size} proxy=${proxy.size} userDirect=${userDirect.size} userReject=${userReject.size} overrides=${settings.hostOverrides.size}")
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
            Log.w("hxmyproxy", "load rule asset $path failed: ${e.message}")
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
}
