package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleDecision

/** 规则判定日志的节流窗口：同一 host 每这么久最多落一条。 */
private const val DECIDE_LOG_INTERVAL_MS = 30_000L

/**
 * 落盘一次规则判定 —— **只记非 PROXY 的判定**（DIRECT/REJECT 是少数，也正是排障焦点；
 * PROXY 是兜底默认，逐条记录会把日志淹掉），并按 host 节流。
 *
 * 记的是 `act` + `src`：只知道结果（如 DIRECT）不足以排障 —— 用户移除自己的规则后仍是 DIRECT，
 * 究竟是内置组还在管、还是父域规则盖住了子域，只有 `src` 能回答。
 */
internal fun logDecision(proto: String, host: String, d: RuleDecision) {
    if (d.action == RuleAction.PROXY) return
    Ev.throttled(
        LogCat.RULE, "decide", "rule:$host", DECIDE_LOG_INTERVAL_MS,
        level = "I",
        kv = arrayOf("proto" to proto, "host" to host, "act" to d.action, "src" to d.src),
    )
}
