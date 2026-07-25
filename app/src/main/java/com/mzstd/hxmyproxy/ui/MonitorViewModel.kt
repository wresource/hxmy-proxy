package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.model.DEFAULT_MONITORED_SERVICES
import com.mzstd.hxmyproxy.core.model.LatencyResult
import com.mzstd.hxmyproxy.core.network.LatencyProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 监控页延迟状态。防崩：**按需测**（开页 + 手动刷新），不持续轮询。
 */
@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val underlyingNetworkProvider: com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider,
) : ViewModel() {

    private val _latency = MutableStateFlow(DEFAULT_MONITORED_SERVICES.map { LatencyResult(it, null) })
    val latency: StateFlow<List<LatencyResult>> = _latency.asStateFlow()

    private val _measuring = MutableStateFlow(false)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    init {
        refreshLatency()
    }

    /**
     * 各服务**并发测、每个测完立即更新自己那一格**（渐进点亮，不等最慢的一次性刷新）。
     *
     * 旧实现用 `awaitAll` 等全部完成才 `_latency.value = results`：弱网下只要有一个境外服务连不通
     * （等满 [LatencyProbe.TIMEOUT_MS] 1s + DNS 解析），整张延迟卡就一直转圈到最慢那个才出——
     * 表现为「点进去半天全卡住、再一次性刷出」。改为逐格 [MutableStateFlow.update]：快的几十 ms 先亮、
     * 慢的各自等自己的超时、互不阻塞，首屏即时有反馈。全程 IO 线程，绝不碰主线程。
     */
    fun refreshLatency() {
        if (_measuring.value) return
        viewModelScope.launch {
            _measuring.value = true
            try {
                // 绑到代理**实际使用的出口**：用户手选 WIFI/CELLULAR/ETHERNET 后，
                // 不绑网的探针量的不是代理真实走的那条路（AUTO 时为 null＝跟随系统默认，行为同旧版）。
                val egress = runCatching { underlyingNetworkProvider.egressNetwork() }.getOrNull()
                coroutineScope {
                    DEFAULT_MONITORED_SERVICES.forEach { svc ->
                        launch(Dispatchers.IO) {
                            val ms = LatencyProbe.measureMillis(svc.host, svc.port, network = egress)
                            _latency.update { list ->
                                list.map { if (it.service == svc) LatencyResult(svc, ms) else it }
                            }
                        }
                    }
                }
            } finally {
                _measuring.value = false
            }
        }
    }
}
