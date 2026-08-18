package com.mzstd.hxmyproxy.ui

import com.mzstd.hxmyproxy.R

/**
 * 顶层导航目的地（底栏/侧栏 + 设置页「导航栏」自定义共用的单一来源）。
 *
 * 2026-08 工作台重构：5 tab 压成 3 tab（运行 / 规则 / 设置），tab 内用分段控件横切。
 * 旧的 dashboard / protection / monitor 路由仍在 NavHost 注册（详情页形态，防
 * restoreState / 深层链接落空），但不再是顶层目的地。
 *
 * [fixed]=true 的项（运行/设置）**永远不可隐藏**——这是防崩与可用性的双保险：
 * 无论持久化数据多脏，过滤后可见集至少含这两项，底栏不会空；设置页入口永在，隐藏可恢复。
 */
enum class NavTab(val route: String, val label: Int, val icon: Int, val fixed: Boolean) {
    RUN("run", R.string.nav_run, R.drawable.ic_nav_monitor, true),
    RULES("rules", R.string.nav_rules, R.drawable.ic_nav_rules, false),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_nav_settings, true),
    ;

    companion object {
        /**
         * 由持久化的隐藏集合算出可见 tab（保持枚举声明顺序，不重排——遵守 M3「目的地位置固定」）。
         * 脏数据安全：未知 route（含 5-tab 时代存下的 dashboard/protection/monitor）无效果；
         * fixed 项即使被写进 hidden 也强制可见。
         */
        fun visible(hidden: Set<String>): List<NavTab> =
            entries.filter { it.fixed || it.route !in hidden }
    }
}
