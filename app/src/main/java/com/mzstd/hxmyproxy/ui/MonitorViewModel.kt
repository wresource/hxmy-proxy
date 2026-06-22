package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.model.DEFAULT_MONITORED_SERVICES
import com.mzstd.hxmyproxy.core.model.LatencyResult
import com.mzstd.hxmyproxy.core.network.LatencyProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 监控页状态：服务延迟 + 错误日志（行）。
 *
 * 防崩设计：延迟**按需测**（开页 + 手动刷新），不做持续轮询；测量并发跑在 IO 上、一次性批量更新
 * （避免逐项刷新刷爆重组）；日志读取也在 IO 线程。UI 列表用 LazyColumn 仅渲染可见项。
 */
@HiltViewModel
class MonitorViewModel @Inject constructor() : ViewModel() {

    private val _latency = MutableStateFlow(DEFAULT_MONITORED_SERVICES.map { LatencyResult(it, null) })
    val latency: StateFlow<List<LatencyResult>> = _latency.asStateFlow()

    private val _measuring = MutableStateFlow(false)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        refreshLatency()
        reloadLogs()
    }

    fun refreshLatency() {
        if (_measuring.value) return
        viewModelScope.launch {
            _measuring.value = true
            try {
                val results = DEFAULT_MONITORED_SERVICES.map { svc ->
                    async(Dispatchers.IO) { LatencyResult(svc, LatencyProbe.measureMillis(svc.host, svc.port)) }
                }.awaitAll()
                _latency.value = results
            } finally {
                _measuring.value = false
            }
        }
    }

    fun reloadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val text = FileLog.snapshot()
            _logs.value = text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(300).asReversed()
        }
    }

    fun clearLogs() {
        FileLog.clear()
        reloadLogs()
    }
}
