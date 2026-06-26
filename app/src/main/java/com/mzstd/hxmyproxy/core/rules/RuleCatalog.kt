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
    /** ≤100 行的小集允许多行文本编辑（存覆盖版）；大表（OISD/B站）false，只读 + 导出。 */
    val editable: Boolean = false,
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

    // App / 服务规则集（DIRECT：命中域名绕过共享 VPN 走手机真实网络；典型如网易云版权需国内真实 IP）。
    val APP_NETEASE = RuleGroup("app-neteasemusic", RuleGroupKind.DIRECT, "rules/app-neteasemusic.txt", R.string.rule_app_netease, R.string.rule_app_src, false, editable = true)
    val APP_BILIBILI = RuleGroup("app-bilibili", RuleGroupKind.DIRECT, "rules/app-bilibili.txt", R.string.rule_app_bilibili, R.string.rule_app_src, false)
    val APP_WECHAT = RuleGroup("app-wechat", RuleGroupKind.DIRECT, "rules/app-wechat.txt", R.string.rule_app_wechat, R.string.rule_app_src, false, editable = true)

    /** App / 服务规则集（每服务一个开关）。 */
    val appGroups: List<RuleGroup> = listOf(APP_NETEASE, APP_BILIBILI, APP_WECHAT)

    val all: List<RuleGroup> = adGroups + appGroups

    fun byId(id: String): RuleGroup? = all.firstOrNull { it.id == id }
}
