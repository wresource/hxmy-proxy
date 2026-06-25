package com.mzstd.hxmyproxy.core.rules

import com.mzstd.hxmyproxy.R

/** 内置规则组的判定动作。 */
enum class RuleGroupKind { DIRECT, PROXY, REJECT }

/**
 * 一个内置规则组的元数据。对应的清单已预处理为「纯域名后缀、每行一个」、gzip 放在 assets。
 *
 * @param id           持久化标识（存入 [com.mzstd.hxmyproxy.core.model.ProxySettings.enabledRuleGroups]）。
 * @param kind         命中该组域名时的判定。
 * @param assetPath    assets 下的清单路径（`.gz` 自动解压）。
 * @param titleRes     UI 名称。
 * @param sourceRes    来源 / License 说明。
 * @param defaultEnabled 是否默认开（广告组默认关，由用户主动开）。
 */
data class RuleGroup(
    val id: String,
    val kind: RuleGroupKind,
    val assetPath: String,
    val titleRes: Int,
    val sourceRes: Int,
    val defaultEnabled: Boolean = false,
)

/** 内置规则组目录。 */
object RuleCatalog {
    val ADS_OISD = RuleGroup(
        id = "ads-oisd-small",
        kind = RuleGroupKind.REJECT,
        assetPath = "rules/ads-oisd-small.txt",
        titleRes = R.string.rule_ads_oisd,
        sourceRes = R.string.rule_ads_oisd_src,
        defaultEnabled = false,
    )

    /** 广告拦截组（每表一个开关）。 */
    val adGroups: List<RuleGroup> = listOf(ADS_OISD)

    val all: List<RuleGroup> = adGroups

    fun byId(id: String): RuleGroup? = all.firstOrNull { it.id == id }
}
