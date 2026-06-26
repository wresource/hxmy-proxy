package com.mzstd.hxmyproxy.core.rules

/**
 * 用户自建命名规则集（在「规则集管理」界面创建/编辑）。
 * 一组域名（支持泛域名/后缀）+ 一个动作（DIRECT 出口分流 / REJECT 拦截），整集一个开关。
 * 优先级高于内置集（防误杀）;PROXY 是默认动作、无意义,故只允许 DIRECT/REJECT。
 */
data class UserRuleSet(
    val id: String,
    val name: String,
    val action: RuleAction,
    val domains: List<String> = emptyList(),
    val enabled: Boolean = true,
)
