package com.mzstd.hxmyproxy.core.model

/**
 * 连接/资源上限。全部可由用户调整，运行时生效。
 * 关键原则：连接数 ≠ 带宽（详见 version-md/v1-design.md §4）。
 */
data class ConnectionLimits(
    /** 同时存活的 TCP 连接总数。太小 → 新连接被拒、并行度被掐导致吞吐下降。 */
    val maxGlobalConnections: Int = 256,
    /** 单台客户端的连接数上限。 */
    val maxPerClientConnections: Int = 128,
    /** relay 并行度，映射到 Dispatchers.IO.limitedParallelism(N)。同刻并行搬字节的连接数。 */
    val relayParallelism: Int = 32,
    /** 单连接 relay 缓冲（字节）。 */
    val relayBufferBytes: Int = 32 * 1024,
    /** 无数据空闲多久断开（秒）。 */
    val idleTimeoutSeconds: Int = 300,
) {
    companion object {
        val RANGE_GLOBAL = 32..1024
        val RANGE_PER_CLIENT = 16..512
        val RANGE_PARALLELISM = 4..64
        val RANGE_BUFFER_BYTES = (8 * 1024)..(256 * 1024)
        val RANGE_IDLE_SECONDS = 30..1800
    }

    /** 钳制到合法范围，防越界设置。 */
    fun coerced(): ConnectionLimits = copy(
        maxGlobalConnections = maxGlobalConnections.coerceIn(RANGE_GLOBAL),
        maxPerClientConnections = maxPerClientConnections.coerceIn(RANGE_PER_CLIENT),
        relayParallelism = relayParallelism.coerceIn(RANGE_PARALLELISM),
        relayBufferBytes = relayBufferBytes.coerceIn(RANGE_BUFFER_BYTES),
        idleTimeoutSeconds = idleTimeoutSeconds.coerceIn(RANGE_IDLE_SECONDS),
    )
}

/**
 * 性能预设（一键档），可逐项覆盖（覆盖后 preset 记为 [CUSTOM]）。
 */
enum class PerformancePreset {
    BATTERY, BALANCED, HIGH_THROUGHPUT, CUSTOM;

    /** 预设对应的默认上限；CUSTOM 返回均衡档作为起点。 */
    fun toLimits(): ConnectionLimits = when (this) {
        BATTERY -> ConnectionLimits(64, 32, 16, 16 * 1024, 300)
        BALANCED -> ConnectionLimits(256, 128, 32, 32 * 1024, 300)
        HIGH_THROUGHPUT -> ConnectionLimits(512, 256, 64, 64 * 1024, 300)
        CUSTOM -> ConnectionLimits(256, 128, 32, 32 * 1024, 300)
    }

    companion object {
        /** 反查：若一组上限与某预设完全一致则返回该预设，否则 CUSTOM。 */
        fun match(limits: ConnectionLimits): PerformancePreset =
            entries.firstOrNull { it != CUSTOM && it.toLimits() == limits } ?: CUSTOM
    }
}
