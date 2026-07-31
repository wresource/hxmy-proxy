package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import java.io.File

/**
 * 按系统 FD 预算反推安全的最大连接数。
 *
 * 用户把「最大连接数」拉满时，每连接约占 [PER_CONN] 个 FD（下行 client + 上行 upstream），
 * 逼近进程 rlimit 就会 EMFILE —— 表现是连接莫名其妙建不起来，且日志里只有一堆
 * "Too many open files"，跟配置值毫无字面关联。所以这里按 rlimit 反推一个上限来钳制。
 *
 * 从 ProxyServerRepository 抽出：纯计算 + 一次文件读，不参与任何会话时序。
 * [limitsFile] 可注入是为了单测能喂各种 rlimit 值 —— 原先它是 private 方法，
 * 「读不到 rlimit 时不钳制」这条兜底逻辑一行都没测过，而它错了会直接把用户的连接数配置废掉。
 */
class FdBudget(private val limitsFile: File = File("/proc/self/limits")) {

    /** 进程 FD 软上限。-1=未读取，0=读取失败（此时不钳制）。读一次后缓存（进程生命周期内不变）。 */
    @Volatile
    var rlimit: Int = -1
        private set

    /**
     * 安全的最大全局连接数。读不到 rlimit 时返回 [Int.MAX_VALUE]（退回不钳制，避免误限），
     * 否则按 (rlimit - [RESERVED]) / [PER_CONN] 计算，并至少保留配置区间的下界。
     */
    fun safeMaxGlobal(): Int {
        if (rlimit < 0) rlimit = readSoftLimit()
        if (rlimit <= 0) return Int.MAX_VALUE
        return ((rlimit - RESERVED) / PER_CONN)
            .coerceAtLeast(ConnectionLimits.RANGE_GLOBAL.first)
    }

    /** 读 /proc/self/limits 的 "Max open files" 软上限；失败返回 0。 */
    private fun readSoftLimit(): Int = runCatching {
        limitsFile.readLines()
            .firstOrNull { it.startsWith("Max open files") }
            ?.split(Regex("\\s+"))?.getOrNull(3)?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    companion object {
        /** 每连接约占的 FD 数（下行 client + 上行 upstream）。 */
        const val PER_CONN = 2
        /** 给 App 自身保留的 FD（DataStore/日志/线程 pipe/监听 socket 等）。 */
        const val RESERVED = 256
    }
}
