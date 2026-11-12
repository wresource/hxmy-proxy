package com.mzstd.hxmyproxy.core.proxy

private val diagnosticToken = Regex("[a-z0-9][a-z0-9_.-]{0,63}")

/** Optional facts from a locally generated failure, never a policy or retry instruction. */
internal fun ProxyException.diagnosticHeaders(): Array<String> = buildList {
    add("X-Hxmyproxy-Error: ${error.code}")
    stage?.takeIf { it.length <= 64 && diagnosticToken.matches(it) }?.let {
        add("X-Hxmyproxy-Stage: $it")
    }
}.toTypedArray()
