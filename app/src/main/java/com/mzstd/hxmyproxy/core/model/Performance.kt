package com.mzstd.hxmyproxy.core.model

/**
 * 连接/资源上限。全部可由用户调整，运行时生效。
 * 关键原则：连接数 ≠ 带宽（详见 version-md/v1-design.md §4）。
 */
data class ConnectionLimits(
    /** 同时存活的 TCP 连接总数。太小 → 新连接被拒、并行度被掐导致吞吐下降。
     *  注意：每连接通常对应「下行 client + 上行 upstream」约 2 个 FD，准入需与系统 FD 上限匹配（防 EMFILE）。 */
    val maxGlobalConnections: Int = 256,
    /** 单台客户端的连接数上限。设得过低会成隐形瓶颈：单浏览器对复杂页可开上百连接，会先于全局上限被拒。 */
    val maxPerClientConnections: Int = 256,
    /** relay 搬字节并行度 = **可同时全速搬字节的连接数**。引擎层按 2×N 开槽（每连接双向各占 1 槽）；握手/建连走独立 acceptDispatcher，不占此额度。视频多并行分段靠这个并发。 */
    val relayParallelism: Int = 32,
    /** 单连接 relay 缓冲（字节）。越大→单连接吞吐越高、syscall 越少（视频/大文件友好）；过大对吞吐收益递减且抬内存。 */
    val relayBufferBytes: Int = 64 * 1024,
    /** 无数据空闲多久断开（秒）。偏短可及时回收 relay 槽与 FD，避免空闲 keep-alive 隧道长期占用并行额度。 */
    val idleTimeoutSeconds: Int = 120,
    /** 域名流量统计最多跟踪多少个域名（Top-N + "(其他)" 兜底）；防统计内存无界。 */
    val maxTrackedDomains: Int = 256,
) {
    companion object {
        val RANGE_GLOBAL = 32..512
        val RANGE_PER_CLIENT = 16..512
        val RANGE_PARALLELISM = 4..64
        val RANGE_BUFFER_BYTES = (8 * 1024)..(128 * 1024)
        val RANGE_IDLE_SECONDS = 30..600
        val RANGE_TRACKED_DOMAINS = 64..2048
    }

    /** 钳制到合法范围，防越界设置。 */
    fun coerced(): ConnectionLimits = copy(
        maxGlobalConnections = maxGlobalConnections.coerceIn(RANGE_GLOBAL),
        maxPerClientConnections = maxPerClientConnections.coerceIn(RANGE_PER_CLIENT),
        relayParallelism = relayParallelism.coerceIn(RANGE_PARALLELISM),
        relayBufferBytes = relayBufferBytes.coerceIn(RANGE_BUFFER_BYTES),
        idleTimeoutSeconds = idleTimeoutSeconds.coerceIn(RANGE_IDLE_SECONDS),
        maxTrackedDomains = maxTrackedDomains.coerceIn(RANGE_TRACKED_DOMAINS),
    )
}

/**
 * 性能预设（一键档），可逐项覆盖（覆盖后 preset 记为 [CUSTOM]）。
 */
enum class PerformancePreset {
    BATTERY, BALANCED, HIGH_THROUGHPUT, CUSTOM;

    /** 预设对应的默认上限；CUSTOM 返回均衡档作为起点。 */
    fun toLimits(): ConnectionLimits = when (this) {
        BATTERY -> ConnectionLimits(64, 64, 16, 16 * 1024, 60)
        BALANCED -> ConnectionLimits(256, 256, 32, 64 * 1024, 120)
        HIGH_THROUGHPUT -> ConnectionLimits(512, 512, 64, 64 * 1024, 120)
        CUSTOM -> ConnectionLimits(256, 256, 32, 64 * 1024, 120)
    }

    companion object {
        /** 反查：若一组上限与某预设完全一致则返回该预设，否则 CUSTOM。 */
        fun match(limits: ConnectionLimits): PerformancePreset =
            entries.firstOrNull { it != CUSTOM && it.toLimits() == limits } ?: CUSTOM
    }
}
