package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import android.util.Log
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
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
        val reject = DomainSuffixSet()
        val direct = DomainSuffixSet()
        val proxy = DomainSuffixSet()
        for (id in settings.enabledRuleGroups) {
            val group = RuleCatalog.byId(id) ?: continue
            val into = when (group.kind) {
                RuleGroupKind.REJECT -> reject
                RuleGroupKind.DIRECT -> direct
                RuleGroupKind.PROXY -> proxy
            }
            loadAsset(group.assetPath, into)
        }
        val userDirect = DomainSuffixSet().apply {
            settings.userDirectRules.forEach { addSuffix(it) }
        }
        ruleEngine.update(
            RuleEngine.Snapshot(userDirect = userDirect, reject = reject, direct = direct, proxy = proxy),
        )
        Log.i("hxmyproxy", "rules rebuilt: reject=${reject.size} direct=${direct.size} proxy=${proxy.size} user=${userDirect.size}")
    }

    private fun loadAsset(path: String, into: DomainSuffixSet) {
        try {
            context.assets.open(path).use { raw ->
                val stream = if (path.endsWith(".gz")) GZIPInputStream(raw) else raw
                stream.bufferedReader().forEachLine { line ->
                    val d = line.trim()
                    if (d.isNotEmpty() && d[0] != '#') into.addSuffix(d)
                }
            }
        } catch (e: Exception) {
            Log.w("hxmyproxy", "load rule asset $path failed: ${e.message}")
        }
    }
}
