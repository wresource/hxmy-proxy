package com.mzstd.hxmyproxy.core.log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 首条启动日志前读取同一份DataStore开关；存储未就绪/损坏时不擅自开启记录。 */
internal fun initialLoggingPreference(enabled: Flow<Boolean>, timeoutMs: Long = 1000): Boolean =
    runCatching {
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { enabled.first() } ?: false }
    }.getOrDefault(false)
