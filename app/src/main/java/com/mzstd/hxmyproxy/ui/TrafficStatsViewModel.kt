package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.stats.PeriodStats
import com.mzstd.hxmyproxy.core.stats.StatsPeriod
import com.mzstd.hxmyproxy.core.stats.TrafficHistoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 流量统计概览页状态：周期选择 + 该周期的统计结果。
 *
 * 共享运行时数字一直在涨，所以低频轮询（[REFRESH_MS]）而不是只在开页时读一次——否则用户盯着
 * 页面看会以为统计坏了。查询本身是内存里的 HashMap 遍历（最多 31 个桶），代价可以忽略；
 * 全程 IO 线程，落盘由 store 自己按 30s 节流。
 */
@HiltViewModel
class TrafficStatsViewModel @Inject constructor(
    private val store: TrafficHistoryStore,
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.TODAY)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _stats = MutableStateFlow<PeriodStats?>(null)
    val stats: StateFlow<PeriodStats?> = _stats.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                _stats.value = store.query(_period.value)
                delay(REFRESH_MS)
            }
        }
    }

    /** 切周期：立即查一次，不等下一个轮询周期（否则点了要愣两秒才变）。 */
    fun select(p: StatsPeriod) {
        if (_period.value == p) return
        _period.value = p
        viewModelScope.launch(Dispatchers.IO) { _stats.value = store.query(p) }
    }

    /** 用户手动清空全部历史（不可撤销，UI 侧已经过确认弹窗）。清完立刻重查，页面当场回到空态。 */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clear()
            _stats.value = store.query(_period.value)
        }
    }

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
