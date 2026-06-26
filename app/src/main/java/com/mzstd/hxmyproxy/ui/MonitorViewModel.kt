package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * 监控页延迟状态。防崩：**按需测**（开页 + 手动刷新），不持续轮询；并发跑 IO、一次性批量更新。
 */
@HiltViewModel
class MonitorViewModel @Inject constructor() : ViewModel() {

    private val _latency = MutableStateFlow(DEFAULT_MONITORED_SERVICES.map { LatencyResult(it, null) })
    val latency: StateFlow<List<LatencyResult>> = _latency.asStateFlow()

    private val _measuring = MutableStateFlow(false)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    init {
        refreshLatency()
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
}
