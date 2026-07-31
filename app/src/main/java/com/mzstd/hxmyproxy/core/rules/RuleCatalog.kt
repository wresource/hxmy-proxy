package com.mzstd.hxmyproxy.core.rules

import com.mzstd.hxmyproxy.R

/** 内置规则组的判定动作。 */
enum class RuleGroupKind { DIRECT, PROXY, REJECT }

/** 内置规则组分类（管理页分节展示；60+ 组平铺不可用）。 */
enum class RuleCategory(val titleRes: Int) {
    SOCIAL(R.string.cat_social),
    VIDEO(R.string.cat_video),
    MUSIC(R.string.cat_music),
    SHOPPING(R.string.cat_shopping),
    PAY(R.string.cat_pay),
    BANK(R.string.cat_bank),
    BROKER(R.string.cat_broker),
    TRAVEL(R.string.cat_travel),
    TOOLS(R.string.cat_tools),
    GAME(R.string.cat_game),
    ADS(R.string.rules_module_ads),
}

/**
 * 一个内置规则组的元数据。对应的清单已预处理为「纯域名后缀、每行一个」放在 assets。
 *
 * @param id           持久化标识（存入 [com.mzstd.hxmyproxy.core.model.ProxySettings.enabledRuleGroups]）。
 * @param kind         命中该组域名时的判定。
 * @param assetPath    assets 下的清单路径（`.gz` 自动解压）。
 * @param titleRes     UI 名称。
 * @param sourceRes    来源 / License 说明。
 * @param defaultEnabled 是否默认开（全部默认关，由用户主动开）。
 * @param editable     ≤100 行的小集允许多行文本编辑（存覆盖版）；大表 false，只读 + 导出。
 * @param category     分类（管理页分节）。
 */
data class RuleGroup(
    val id: String,
    val kind: RuleGroupKind,
    val assetPath: String,
    val titleRes: Int,
    val sourceRes: Int,
    val defaultEnabled: Boolean = false,
    val editable: Boolean = false,
    val category: RuleCategory = RuleCategory.TOOLS,
)

/**
 * 内置规则组目录（65 个 App/服务 DIRECT 组 + 广告 REJECT 组）。
 * 数量由 RuleCatalogTest 与 assets/rules/app-*.txt 双向校验，不要手工同步这个数字。
 *
 * 数据来源：blackmatrix7/ios_rule_script(GPL-2) + v2fly/domain-list-community(MIT)
 * 双基线合并，再经多源调研补全验证（官网/开放平台/whois/TLS 证书交叉确认，剔除
 * 第三方共享 CDN 宽泛域与归属不明域，防误杀）。DIRECT 语义：命中域名绕过共享 VPN
 * 走手机真实网络（流量仍经本代理转发，监控标「直连」）。
 */
object RuleCatalog {
    val ADS_OISD = RuleGroup(
        id = "ads-oisd-small",
        kind = RuleGroupKind.REJECT,
        assetPath = "rules/ads-oisd-small.txt",
        titleRes = R.string.rule_ads_oisd,
        sourceRes = R.string.rule_ads_oisd_src,
        defaultEnabled = false,
        category = RuleCategory.ADS,
    )

    /** 广告拦截组（每表一个开关）。 */
    val adGroups: List<RuleGroup> = listOf(ADS_OISD)

    // App / 服务 DIRECT 组构造：id=app-{key}、asset=rules/app-{key}.txt。
    private fun g(key: String, title: Int, cat: RuleCategory, editable: Boolean = false) =
        RuleGroup("app-$key", RuleGroupKind.DIRECT, "rules/app-$key.txt", title, R.string.rule_app_src, false, editable, cat)

    /** App / 服务规则集（每服务一个开关；按 [RuleCategory] 分节展示）。 */
    val appGroups: List<RuleGroup> = listOf(
        // —— 社交/通讯 ——
        g("tencent", R.string.rg_tencent, RuleCategory.SOCIAL),
        g("wechat", R.string.rule_app_wechat, RuleCategory.SOCIAL, editable = true),
        g("weibo", R.string.rg_weibo, RuleCategory.SOCIAL),
        g("zhihu", R.string.rg_zhihu, RuleCategory.SOCIAL, editable = true),
        g("douban", R.string.rg_douban, RuleCategory.SOCIAL, editable = true),
        g("xiaohongshu", R.string.rg_xiaohongshu, RuleCategory.SOCIAL, editable = true),
        g("dingtalk", R.string.rg_dingtalk, RuleCategory.SOCIAL, editable = true),
        g("feishu", R.string.rg_feishu, RuleCategory.SOCIAL, editable = true),
        // —— 视频 ——
        g("bilibili", R.string.rule_app_bilibili, RuleCategory.VIDEO),
        g("douyin", R.string.rg_douyin, RuleCategory.VIDEO, editable = true),
        g("kuaishou", R.string.rg_kuaishou, RuleCategory.VIDEO),
        g("iqiyi", R.string.rg_iqiyi, RuleCategory.VIDEO, editable = true),
        g("youku", R.string.rg_youku, RuleCategory.VIDEO, editable = true),
        g("mangotv", R.string.rg_mangotv, RuleCategory.VIDEO, editable = true),
        // —— 音乐/音频 ——
        g("neteasemusic", R.string.rule_app_netease, RuleCategory.MUSIC, editable = true),
        g("qqmusic", R.string.rg_qqmusic, RuleCategory.MUSIC, editable = true),
        g("kugou", R.string.rg_kugou, RuleCategory.MUSIC, editable = true),
        g("ximalaya", R.string.rg_ximalaya, RuleCategory.MUSIC, editable = true),
        // —— 购物/本地生活 ——
        g("alibaba", R.string.rg_alibaba, RuleCategory.SHOPPING),
        g("jd", R.string.rg_jd, RuleCategory.SHOPPING),
        g("pinduoduo", R.string.rg_pinduoduo, RuleCategory.SHOPPING, editable = true),
        g("meituan", R.string.rg_meituan, RuleCategory.SHOPPING, editable = true),
        g("eleme", R.string.rg_eleme, RuleCategory.SHOPPING, editable = true),
        // —— 支付 ——
        g("alipay", R.string.rg_alipay, RuleCategory.PAY, editable = true),
        g("unionpay", R.string.rg_unionpay, RuleCategory.PAY, editable = true),
        g("wise", R.string.rg_wise, RuleCategory.PAY, editable = true),
        // —— 银行 ——
        g("icbc", R.string.rg_icbc, RuleCategory.BANK, editable = true),
        g("ccb", R.string.rg_ccb, RuleCategory.BANK, editable = true),
        g("abc", R.string.rg_abc, RuleCategory.BANK, editable = true),
        g("boc", R.string.rg_boc, RuleCategory.BANK, editable = true),
        g("bocom", R.string.rg_bocom, RuleCategory.BANK, editable = true),
        g("cmb", R.string.rg_cmb, RuleCategory.BANK, editable = true),
        g("psbc", R.string.rg_psbc, RuleCategory.BANK, editable = true),
        g("ceb", R.string.rg_ceb, RuleCategory.BANK, editable = true),
        g("cgb", R.string.rg_cgb, RuleCategory.BANK, editable = true),
        g("pingan", R.string.rg_pingan, RuleCategory.BANK, editable = true),
        g("citicbank", R.string.rg_citicbank, RuleCategory.BANK, editable = true),
        g("cmbc", R.string.rg_cmbc, RuleCategory.BANK, editable = true),
        g("spdb", R.string.rg_spdb, RuleCategory.BANK, editable = true),
        g("cib", R.string.rg_cib, RuleCategory.BANK, editable = true),
        g("hxb", R.string.rg_hxb, RuleCategory.BANK, editable = true),
        // —— 券商/行情 ——
        g("eastmoney", R.string.rg_eastmoney, RuleCategory.BROKER, editable = true),
        g("xueqiu", R.string.rg_xueqiu, RuleCategory.BROKER, editable = true),
        g("tonghuashun", R.string.rg_tonghuashun, RuleCategory.BROKER, editable = true),
        g("huatai", R.string.rg_huatai, RuleCategory.BROKER, editable = true),
        g("guotaijunan", R.string.rg_guotaijunan, RuleCategory.BROKER, editable = true),
        g("citicsec", R.string.rg_citicsec, RuleCategory.BROKER, editable = true),
        g("yinhe", R.string.rg_yinhe, RuleCategory.BROKER, editable = true),
        g("gfsec", R.string.rg_gfsec, RuleCategory.BROKER, editable = true),
        g("cmsec", R.string.rg_cmsec, RuleCategory.BROKER, editable = true),
        g("futu", R.string.rg_futu, RuleCategory.BROKER, editable = true),
        g("tigerfintech", R.string.rg_tigerfintech, RuleCategory.BROKER, editable = true),
        // —— 出行 ——
        g("didi", R.string.rg_didi, RuleCategory.TRAVEL, editable = true),
        g("amap", R.string.rg_amap, RuleCategory.TRAVEL, editable = true),
        g("railway12306", R.string.rg_12306, RuleCategory.TRAVEL, editable = true),
        g("ctrip", R.string.rg_ctrip, RuleCategory.TRAVEL, editable = true),
        // —— 工具/大厂 ——
        g("baidu", R.string.rg_baidu, RuleCategory.TOOLS),
        g("xiaomi", R.string.rg_xiaomi, RuleCategory.TOOLS),
        g("netease", R.string.rg_netease, RuleCategory.TOOLS),
        g("wps", R.string.rg_wps, RuleCategory.TOOLS, editable = true),
        g("xunlei", R.string.rg_xunlei, RuleCategory.TOOLS, editable = true),
        g("evernote", R.string.rg_evernote, RuleCategory.TOOLS, editable = true),
        g("apple", R.string.rg_apple, RuleCategory.TOOLS, editable = true),
        // —— 游戏 ——
        g("mihoyo", R.string.rg_mihoyo, RuleCategory.GAME, editable = true),
        g("steam", R.string.rg_steam, RuleCategory.GAME, editable = true),
    )

    val all: List<RuleGroup> = adGroups + appGroups

    fun byId(id: String): RuleGroup? = all.firstOrNull { it.id == id }
}
