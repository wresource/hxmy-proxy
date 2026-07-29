package com.mzstd.hxmyproxy.core.stats

import android.content.Context
import com.mzstd.hxmyproxy.core.log.FileLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoField
import java.util.concurrent.atomic.LongAdder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨会话的历史流量统计：按「时段 × 出口 × 上下行」聚合并落盘。
 *
 * 与首页那个 `Total` 的关系：那是**本次共享**的，停止即归零（会话计量边界见
 * `ProxyServerRepository.resetSessionCounters`）；这里是**跨会话累计**，不受启停影响。
 * 两个数字口径不同、都要能自圆其说，所以 UI 上明确标注周期起点与会话次数。
 *
 * 三层桶：
 * - [hours] 小时桶，只留 [HOURS_KEEP_DAYS] 天——「今日」那 24 根柱子需要它，再久没人看；
 * - [days] 天桶，留 [DAYS_KEEP] 天，周/月/年全部由它累加；
 * - [total] 永久累计器，**即使天桶被裁剪也不丢**（「总计」这个数字的权威来源）。
 *
 * 线程模型：[record] 是搬字节热路径，只对 [LongAdder] 累加（无锁）；[tick] 由 1s ticker 单线程调用，
 * 把增量收进桶里。热路径与桶之间用**差分**而非 `sumThenReset`——后者在并发 add 下会丢字节
 * （JDK 实现是「读 cell → 写 0」两步，中间的 add 直接蒸发），统计表里表现为「总量对不上」这种
 * 查无可查的偏差。
 *
 * 隐私：只记「哪张网、多少字节」，不含域名/IP/时刻明细。文件落在 `noBackupFilesDir`，与日志同口径
 * ——不进云备份（见 privacy-hardening 决策）。
 */
@Singleton
class TrafficHistoryStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, FILE_NAME))

    private val lock = Any()

    /** 热路径累加器（**只增不减**），槽位 = kind.ordinal * 2 + (0=上行, 1=下行)。 */
    private val pending = Array(SLOTS) { LongAdder() }

    /** 上次 [tick] 已收进桶的累计值，用于差分。 */
    private val drained = LongArray(SLOTS)

    private var loaded = false
    private val hours = HashMap<Long, LongArray>()      // key = epochDay * 24 + hourOfDay（本地时区）
    private val days = HashMap<Long, LongArray>()       // key = epochDay（本地时区）
    private val sessions = HashMap<Long, Int>()         // key = epochDay → 当天共享次数
    private val total = LongArray(SLOTS)
    private var totalSessions = 0
    private var firstDay = -1L

    private var dirty = false
    private var lastWriteAt = 0L

    // ——— 采集侧 ———

    /** 搬字节热路径：由 `TrafficAccounting.ConnTracker.add` 每块字节调用一次。 */
    fun record(kind: EgressKind, up: Long, down: Long) {
        val base = kind.ordinal * 2
        if (up > 0) pending[base].add(up)
        if (down > 0) pending[base + 1].add(down)
    }

    /** 1s ticker 调用：把热路径增量收进当前小时/天桶，按需裁剪与落盘。 */
    fun tick() = synchronized(lock) {
        ensureLoaded()
        val now = clock()
        drainLocked(now)
        maybeWrite(now, force = false)
    }

    /**
     * 一次「共享」开始（[ProxyServerRepository.resetSessionCounters] 那个唯一的语义边界）。
     * 立即落盘：进程随时可能被 LMK 杀，会话次数丢了没法补记。
     */
    fun noteSessionStart() = synchronized(lock) {
        ensureLoaded()
        val now = clock()
        val dk = dayKey(now)
        sessions[dk] = (sessions[dk] ?: 0) + 1
        totalSessions++
        if (firstDay < 0) firstDay = dk
        dirty = true
        trimLocked()
        maybeWrite(now, force = true)
    }

    /**
     * 清空全部历史（**只由用户手动触发**，不做任何自动清理——留多久是用户自己的判断）。
     *
     * 关键在最后那步对齐：热路径累加器 [pending] 是**只增不减**的，清空时必须把差分基线 [drained]
     * 推到它当前的值，否则下一次 [tick] 会把「清空之前已经搬过的字节」当成新增量原样加回来——
     * 正在共享时点清空，数字会立刻自己长回去。
     */
    fun clear() = synchronized(lock) {
        ensureLoaded()
        hours.clear()
        days.clear()
        sessions.clear()
        total.fill(0)
        totalSessions = 0
        firstDay = -1L
        for (i in 0 until SLOTS) drained[i] = pending[i].sum()
        dirty = false
        lastWriteAt = clock()
        runCatching { file.delete() }
        Unit
    }

    /** 共享停止 / 进程收尾：收干净并强制落盘。 */
    fun flush() = synchronized(lock) {
        if (!loaded) return@synchronized
        val now = clock()
        drainLocked(now)
        maybeWrite(now, force = true)
    }

    // ——— 查询侧 ———

    /** 概览页：某个周期的总量、出口分解与柱状序列。含尚未 tick 的增量（读之前先收一次）。 */
    fun query(period: StatsPeriod): PeriodStats = synchronized(lock) {
        ensureLoaded()
        val now = clock()
        drainLocked(now)
        val today = localDate(now)
        val agg = LongArray(SLOTS)
        val buckets = ArrayList<TrafficBucket>()
        var sessionCount = 0

        when (period) {
            StatsPeriod.TODAY -> {
                val dk = today.toEpochDay()
                days[dk]?.let { addInto(agg, it) }
                sessionCount = sessions[dk] ?: 0
                for (h in 0..23) buckets += TrafficBucket(h.toLong(), sumOf(hours[dk * 24 + h]))
            }
            StatsPeriod.WEEK -> {
                // 自然周（周一起），未来的日子留空柱——比滚动 7 天更贴「本周」的字面意思，
                // 也和「今日 00:00 起」同一套周期语义。
                val monday = today.with(ChronoField.DAY_OF_WEEK, 1)
                for (i in 0..6) {
                    val d = monday.plusDays(i.toLong()).toEpochDay()
                    accumulateDay(d, agg, buckets)
                    sessionCount += sessions[d] ?: 0
                }
            }
            StatsPeriod.MONTH -> {
                val first = today.withDayOfMonth(1)
                for (i in 0 until today.lengthOfMonth()) {
                    val d = first.plusDays(i.toLong()).toEpochDay()
                    accumulateDay(d, agg, buckets)
                    sessionCount += sessions[d] ?: 0
                }
            }
            StatsPeriod.YEAR -> {
                val perMonth = LongArray(12)
                for ((d, v) in days) {
                    val date = LocalDate.ofEpochDay(d)
                    if (date.year != today.year) continue
                    addInto(agg, v)
                    perMonth[date.monthValue - 1] += sumOf(v)
                }
                for ((d, n) in sessions) {
                    if (LocalDate.ofEpochDay(d).year == today.year) sessionCount += n
                }
                for (m in 1..12) buckets += TrafficBucket(m.toLong(), perMonth[m - 1])
            }
            StatsPeriod.ALL -> {
                // 总量取永久累计器（天桶可能已被裁剪）；柱子只能按尚存的天桶按月铺，最多 [ALL_MONTHS] 个月。
                addInto(agg, total)
                sessionCount = totalSessions
                val perMonth = HashMap<Long, Long>()
                for ((d, v) in days) {
                    val date = LocalDate.ofEpochDay(d)
                    val key = date.year * 12L + (date.monthValue - 1)
                    perMonth[key] = (perMonth[key] ?: 0) + sumOf(v)
                }
                val end = today.year * 12L + (today.monthValue - 1)
                val start = (perMonth.keys.minOrNull() ?: end).coerceAtLeast(end - (ALL_MONTHS - 1))
                for (k in start..end) buckets += TrafficBucket(k, perMonth[k] ?: 0)
            }
        }

        val slices = EgressKind.entries
            .map { EgressSlice(it, agg[it.ordinal * 2], agg[it.ordinal * 2 + 1]) }
            .filter { it.bytes > 0 }
            .sortedByDescending { it.bytes }
        var up = 0L
        var down = 0L
        for (k in EgressKind.entries) {
            up += agg[k.ordinal * 2]
            down += agg[k.ordinal * 2 + 1]
        }
        return@synchronized PeriodStats(
            period = period,
            up = up,
            down = down,
            slices = slices,
            buckets = buckets,
            sessions = sessionCount,
            cellularThisMonth = cellularThisMonthLocked(today),
            firstDayEpoch = firstDay.takeIf { it >= 0 },
        )
    }

    /** 监控页入口卡：今日总量 + 昨日总量（算涨跌）+ 近 7 日（含今日）滚动趋势条。 */
    fun summary(): TrafficSummary = synchronized(lock) {
        ensureLoaded()
        val now = clock()
        drainLocked(now)
        val today = localDate(now)
        val last7 = ArrayList<Long>(7)
        for (i in 6 downTo 0) last7 += sumOf(days[today.minusDays(i.toLong()).toEpochDay()])
        return@synchronized TrafficSummary(
            todayBytes = last7.last(),
            yesterdayBytes = sumOf(days[today.minusDays(1).toEpochDay()]),
            last7Days = last7,
            hasAnyData = firstDay >= 0,
        )
    }

    // ——— 内部 ———

    private fun accumulateDay(epochDay: Long, agg: LongArray, buckets: MutableList<TrafficBucket>) {
        val v = days[epochDay]
        if (v != null) addInto(agg, v)
        buckets += TrafficBucket(epochDay, sumOf(v))
    }

    private fun cellularThisMonthLocked(today: LocalDate): Long {
        val first = today.withDayOfMonth(1).toEpochDay()
        val last = today.toEpochDay()
        var sum = 0L
        for (d in first..last) {
            val v = days[d] ?: continue
            sum += v[EgressKind.CELLULAR.ordinal * 2] + v[EgressKind.CELLULAR.ordinal * 2 + 1]
        }
        return sum
    }

    /** 把热路径增量收进桶。必须在 [lock] 内调用。 */
    private fun drainLocked(now: Long) {
        var hourBucket: LongArray? = null
        var dayBucket: LongArray? = null
        var changed = false
        for (i in 0 until SLOTS) {
            val cur = pending[i].sum()
            val delta = cur - drained[i]
            if (delta <= 0) continue
            drained[i] = cur
            if (hourBucket == null) {
                hourBucket = hours.getOrPut(hourKey(now)) { LongArray(SLOTS) }
                dayBucket = days.getOrPut(dayKey(now)) { LongArray(SLOTS) }
            }
            hourBucket[i] += delta
            dayBucket!![i] += delta
            total[i] += delta
            changed = true
        }
        if (!changed) return
        if (firstDay < 0) firstDay = dayKey(now)
        dirty = true
        trimLocked()
    }

    private fun trimLocked() {
        if (hours.size > HOURS_KEEP) {
            hours.keys.sorted().take(hours.size - HOURS_KEEP).forEach { hours.remove(it) }
        }
        if (days.size > DAYS_KEEP) {
            val drop = days.keys.sorted().take(days.size - DAYS_KEEP)
            drop.forEach { days.remove(it); sessions.remove(it) }
            // firstDay 只用于「总计自 X 起」的文案，裁剪后跟着往前推，别再声称有那么早的明细。
            days.keys.minOrNull()?.let { if (it > firstDay) firstDay = it }
        }
        if (sessions.size > DAYS_KEEP) {
            sessions.keys.sorted().take(sessions.size - DAYS_KEEP).forEach { sessions.remove(it) }
        }
    }

    private fun maybeWrite(now: Long, force: Boolean) {
        if (!dirty) return
        if (!force && now - lastWriteAt < WRITE_INTERVAL_MS) return
        writeLocked()
        lastWriteAt = now
        dirty = false
    }

    /**
     * 行式文本，不用 JSON —— `org.json` 在 JVM 单测里是 android.jar 的 stub，配上
     * `isReturnDefaultValues=true` 会**静默返回 null 而不报错**，落盘失败在测试里完全看不出来
     * （这个格式就是被那次静默失败换掉的）。行式格式无依赖、可在纯 JVM 下端到端验证。
     *
     * ```
     * hxmy-traffic 1
     * f <firstDay>   n <totalSessions>   t <csv>
     * h <hourKey> <csv>   d <dayKey> <csv>   s <dayKey> <count>
     * ```
     * 未知前缀的行直接跳过，将来加字段不会让旧版本读崩。
     */
    private fun writeLocked() {
        runCatching {
            val sb = StringBuilder(64 + (hours.size + days.size) * 48)
            sb.append(MAGIC).append(' ').append(VERSION).append('\n')
            sb.append("f ").append(firstDay).append('\n')
            sb.append("n ").append(totalSessions).append('\n')
            sb.append("t ").append(encodeSlots(total)).append('\n')
            for ((k, v) in hours) sb.append("h ").append(k).append(' ').append(encodeSlots(v)).append('\n')
            for ((k, v) in days) sb.append("d ").append(k).append(' ').append(encodeSlots(v)).append('\n')
            for ((k, v) in sessions) sb.append("s ").append(k).append(' ').append(v).append('\n')
            file.parentFile?.mkdirs()
            // 原子替换：直接写目标文件会在进程被杀时留下半截内容，下次启动整表作废。
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { FileLog.w(TAG, "traffic history 落盘失败: ${it.message}") }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val lines = file.readLines()
            // 版本不匹配直接丢弃重来：统计数据不是关键资产，不值得为它写迁移代码。
            if (lines.firstOrNull() != "$MAGIC $VERSION") return
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val p = line.split(' ')
                when (p[0]) {
                    "f" -> firstDay = p.getOrNull(1)?.toLongOrNull() ?: -1L
                    "n" -> totalSessions = p.getOrNull(1)?.toIntOrNull() ?: 0
                    "t" -> decodeSlots(p.getOrNull(1), total)
                    "h" -> p.getOrNull(1)?.toLongOrNull()?.let { k ->
                        hours[k] = LongArray(SLOTS).also { decodeSlots(p.getOrNull(2), it) }
                    }
                    "d" -> p.getOrNull(1)?.toLongOrNull()?.let { k ->
                        days[k] = LongArray(SLOTS).also { decodeSlots(p.getOrNull(2), it) }
                    }
                    "s" -> p.getOrNull(1)?.toLongOrNull()?.let { k ->
                        sessions[k] = p.getOrNull(2)?.toIntOrNull() ?: 0
                    }
                }
            }
            trimLocked()
        }.onFailure {
            FileLog.w(TAG, "traffic history 读取失败，从空表重来: ${it.message}")
            hours.clear(); days.clear(); sessions.clear()
            total.fill(0); totalSessions = 0; firstDay = -1L
        }
    }

    /** 槽位数组编成逗号分隔串（比 JSONArray 省一半体积，桶多起来后文件小很多）。 */
    private fun encodeSlots(v: LongArray) = v.joinToString(",")

    private fun decodeSlots(s: String?, into: LongArray) {
        if (s.isNullOrEmpty()) return
        val parts = s.split(',')
        for (i in 0 until minOf(parts.size, into.size)) into[i] = parts[i].toLongOrNull() ?: 0L
    }

    private fun addInto(agg: LongArray, v: LongArray) {
        for (i in 0 until SLOTS) agg[i] += v[i]
    }

    private fun sumOf(v: LongArray?): Long {
        if (v == null) return 0
        var s = 0L
        for (x in v) s += x
        return s
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zoneProvider()).toLocalDate()
    private fun dayKey(ms: Long): Long = localDate(ms).toEpochDay()
    private fun hourKey(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(zoneProvider()).let { it.toLocalDate().toEpochDay() * 24 + it.hour }

    companion object {
        private const val TAG = "hxmyproxy"
        const val FILE_NAME = "traffic-history.txt"
        private const val MAGIC = "hxmy-traffic"
        private const val VERSION = 1
        private val SLOTS = EgressKind.entries.size * 2

        /** 小时桶保留天数：只服务「今日」那 24 根柱子，多留几天覆盖跨时区/夏令时的边界即可。 */
        private const val HOURS_KEEP_DAYS = 8
        private const val HOURS_KEEP = HOURS_KEEP_DAYS * 24
        /** 天桶保留天数（约两年）：周/月/年三档全靠它，两年足够画满「今年」并留出对比。 */
        private const val DAYS_KEEP = 730
        /** 「总计」的柱状最多铺多少个月。 */
        private const val ALL_MONTHS = 24
        /** 常规落盘间隔：搬字节期间没必要每秒写盘。会话开始/停止会强制写。 */
        private const val WRITE_INTERVAL_MS = 30_000L
    }
}

/** 概览页的时间维度。 */
enum class StatsPeriod { TODAY, WEEK, MONTH, YEAR, ALL }

/** 一个出口在某周期内的用量。 */
data class EgressSlice(val kind: EgressKind, val up: Long, val down: Long) {
    val bytes: Long get() = up + down
}

/** 柱状序列的一格。[key] 含义随周期：今日=小时(0-23)，周/月=epochDay，年=月份(1-12)，总计=year*12+month-1。 */
data class TrafficBucket(val key: Long, val bytes: Long)

/** 某周期的统计结果。 */
data class PeriodStats(
    val period: StatsPeriod,
    val up: Long,
    val down: Long,
    val slices: List<EgressSlice>,
    val buckets: List<TrafficBucket>,
    val sessions: Int,
    val cellularThisMonth: Long,
    val firstDayEpoch: Long?,
) {
    val bytes: Long get() = up + down
}

/** 监控页入口卡用的轻量摘要。 */
data class TrafficSummary(
    val todayBytes: Long = 0,
    val yesterdayBytes: Long = 0,
    val last7Days: List<Long> = emptyList(),
    val hasAnyData: Boolean = false,
)
