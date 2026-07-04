package com.mzstd.hxmyproxy.core.model

/**
 * 被拦截域名及其命中次数（广告/拒绝规则）。会话内累计，随 [com.mzstd.hxmyproxy.core.proxy.TrafficAccounting.reset] 清零。
 * 隐私上只含 host + 次数，不碰 path/query/内容。
 */
data class BlockedDomain(val host: String, val count: Long)
