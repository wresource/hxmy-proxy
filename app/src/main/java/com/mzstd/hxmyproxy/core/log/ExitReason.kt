package com.mzstd.hxmyproxy.core.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * 上次进程是**怎么没的** —— 以及没的时候在干什么。
 *
 * 【为什么需要它】
 * 进程被系统杀掉时，我们自己的日志恰恰在最关键的位置最不可靠：[FileLog] 用常驻 BufferedWriter，
 * SIGKILL 一到，缓冲区里还没落盘的那几行直接蒸发。于是导出日志的结尾永远是「话说到一半断了」，
 * 而「被杀了」与「活着但卡住了」在文件里**完全同形**——这正是「客户端连不上」查了三轮仍未结案的
 * 岔路口之一。
 *
 * 系统侧的 [ApplicationExitInfo] 不受这个限制：它是内核/AMS 记的账，进程死得再突然也拿得到。
 *
 * 【必须同时记 lmkReported 的原因】
 * 官方明确：**不是所有设备都上报 [ApplicationExitInfo.REASON_LOW_MEMORY]**。不支持的设备上，
 * 内存压力杀进程会报成 [ApplicationExitInfo.REASON_SIGNALED] + `status=SIGKILL(9)`。
 * 因此单看 `reason=SIGNALED` 无法区分「真被信号杀」与「被 LMK 杀但本机不这么报」——
 * 不把 `ActivityManager.isLowMemoryKillReportSupported()` 一并落下来，就是给未来的自己埋一个
 * 看起来有结论、实际会误导的字段（同类教训见 absence-is-not-evidence）。
 *
 * 【不做归因翻译】
 * reason/status/importance/pss 一律**原样落盘**（附常量名便于人读，但数值保留）。不写
 * 「reason==3 就是内存不足」这种断言：猜错一次，后面所有推理都建在错的前提上。
 */
object ExitReason {

    private const val PREFS = "exit_reason"
    private const val KEY_LAST_SEEN = "lastSeenTs"

    /** 一次最多回溯几条历史退出记录（系统本身用环形缓冲，深度无文档承诺）。 */
    private const val MAX_RECORDS = 8

    /** 系统对状态摘要的硬限制（官方 javadoc：Maximum length is 128 bytes）。 */
    private const val SUMMARY_MAX_BYTES = 128

    /**
     * 进程启动时调用一次：把「上次（们）是怎么没的」落进 key.log。
     *
     * **「没有新记录」本身就是结论**：系统保证每次进程死亡都记一笔，所以查不到新记录 =
     * 上次是正常退出或进程压根没被杀过 —— 这与日志的静默不同，后者永远有歧义。因此这一支
     * 也显式落盘（`none=true`），而不是悄悄返回。
     */
    fun reportLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // 查不到 ≠ 没发生。API 29 上明确写出来，免得日后把空白当成「没被杀过」。
            Ev.k(LogCat.PROC, "proc.lastExit", "unavailable" to "api${Build.VERSION.SDK_INT}")
            return
        }
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            // packageName=null → 本 UID 全部；pid=0 / maxNum=MAX_RECORDS；返回按时间**从新到旧**。
            val all = am.getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
            val lmk = runCatching { ActivityManager.isLowMemoryKillReportSupported() }.getOrNull()
            if (all.isEmpty()) {
                Ev.k(LogCat.PROC, "proc.lastExit", "none" to true, "lmkReported" to lmk)
                return
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
            val fresh = all.filter { it.timestamp > lastSeen }
            if (fresh.isEmpty()) {
                // 有历史但没有新的：同样是「本次启动之前没死过」的证据。
                Ev.k(LogCat.PROC, "proc.lastExit", "none" to true, "seen" to all.size, "lmkReported" to lmk)
            } else {
                // 按时间正序落盘（最老的先），读日志时与时间线一致。
                fresh.sortedBy { it.timestamp }.forEach { info -> logOne(info, lmk) }
            }
            prefs.edit().putLong(KEY_LAST_SEEN, all.first().timestamp).apply()
        }.onFailure {
            Ev.w(LogCat.PROC, "proc.lastExit.error", "err" to it.toString())
        }
    }

    private fun logOne(info: ApplicationExitInfo, lmk: Boolean?) {
        Ev.k(
            LogCat.PROC, "proc.lastExit",
            "reason" to "${reasonName(info.reason)}(${info.reason})",
            // SIGNALED 时 status 就是信号号（9=SIGKILL）；CRASH 时是退出码。
            "status" to info.status,
            "imp" to "${importanceName(info.importance)}(${info.importance})",
            "pssKb" to info.pss,
            "rssKb" to info.rss,
            "at" to info.timestamp,
            // 见类注释：没有它，reason=SIGNALED 无法解释。
            "lmkReported" to lmk,
            "desc" to info.description,
            // 死亡时刻附近的自定义状态摘要（见 updateStateSummary）。
            "state" to info.processStateSummary?.toString(Charsets.US_ASCII),
        )
    }

    /**
     * 把「此刻在干什么」交给系统保管：进程被杀后，下一个进程能从
     * [ApplicationExitInfo.getProcessStateSummary] 读回来。
     *
     * 精度取决于调用频率——调用方按 60s 心跳节拍更新，所以拿到的是**死前最多一个心跳周期内**的
     * 快照，不是断气瞬间。别把它当精确时刻用。
     *
     * **绝不放 PII**：只有开关态与计数，没有客户端 IP、没有域名（官方 javadoc 亦明确要求）。
     * 系统会对本 API 限流，过度调用抛 RuntimeException —— 心跳节拍远低于阈值，另有 runCatching 兜底。
     */
    fun updateStateSummary(
        context: Context,
        sharing: Boolean,
        clients: Int,
        conns: Int,
        acceptTotal: Long,
        uptimeSec: Long,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val s = "sh=${if (sharing) 1 else 0} cl=$clients cn=$conns ac=$acceptTotal up=$uptimeSec"
            val bytes = s.toByteArray(Charsets.US_ASCII)
            am.setProcessStateSummary(
                if (bytes.size <= SUMMARY_MAX_BYTES) bytes else bytes.copyOf(SUMMARY_MAX_BYTES),
            )
        }
    }

    /** 常量名仅为便于人读，数值一并保留（见类注释「不做归因翻译」）。 */
    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PKG_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PKG_UPDATED"
        else -> "R$r"
    }

    /** 前台被杀与缓存态被杀严重程度完全不同，值得一眼看出。 */
    private fun importanceName(i: Int): String = when (i) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FGS"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> "I$i"
    }
}
