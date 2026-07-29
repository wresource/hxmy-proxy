package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.stats.EgressKind
import com.mzstd.hxmyproxy.core.stats.StatsPeriod
import com.mzstd.hxmyproxy.core.stats.TrafficHistoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 历史流量统计的落盘与聚合。时钟与时区都注入固定值——「今日/本周」这类判定全依赖本地时区，
 * 用真实时钟测会在跨零点那一刻随机翻车。
 */
class TrafficHistoryStoreTest {

    private lateinit var file: File
    private var now = at(2026, 7, 29, 10)   // 周三 10:00 UTC

    private fun store() = TrafficHistoryStore(file, clock = { now }, zoneProvider = { ZONE })

    @Before
    fun setUp() {
        file = File.createTempFile("traffic-history", ".json").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    @Test
    fun `分出口分上下行累加`() {
        val s = store()
        s.record(EgressKind.VPN, 100, 900)
        s.record(EgressKind.CELLULAR, 10, 20)
        s.tick()

        val today = s.query(StatsPeriod.TODAY)
        assertEquals(1030L, today.bytes)
        assertEquals(110L, today.up)
        assertEquals(920L, today.down)
        // 占比降序：VPN 1000 在前
        assertEquals(EgressKind.VPN, today.slices[0].kind)
        assertEquals(1000L, today.slices[0].bytes)
        assertEquals(EgressKind.CELLULAR, today.slices[1].kind)
        assertEquals(30L, today.slices[1].bytes)
        // 没有流量的出口不占一行（UI 上不该出现一串 0 B）
        assertEquals(2, today.slices.size)
    }

    @Test
    fun `按小时分桶`() {
        val s = store()
        s.record(EgressKind.WIFI, 0, 100)
        s.tick()
        now = at(2026, 7, 29, 14)
        s.record(EgressKind.WIFI, 0, 300)
        s.tick()

        val buckets = s.query(StatsPeriod.TODAY).buckets
        assertEquals(24, buckets.size)
        assertEquals(100L, buckets[10].bytes)
        assertEquals(300L, buckets[14].bytes)
        assertEquals(0L, buckets[11].bytes)
        assertEquals(400L, s.query(StatsPeriod.TODAY).bytes)
    }

    @Test
    fun `跨天后今日归零而总计保留`() {
        val s = store()
        s.record(EgressKind.VPN, 0, 500)
        s.tick()
        now = at(2026, 7, 30, 9)
        s.tick()

        assertEquals(0L, s.query(StatsPeriod.TODAY).bytes)
        assertEquals(500L, s.query(StatsPeriod.ALL).bytes)
        // 昨天仍在本周里
        assertEquals(500L, s.query(StatsPeriod.WEEK).bytes)
    }

    @Test
    fun `本周是自然周而非滚动七天`() {
        val s = store()
        // 上周日（7-26 是周日？7-29 是周三，往前 4 天＝7-25 周六，属于上一周）
        now = at(2026, 7, 25, 12)
        s.record(EgressKind.VPN, 0, 700)
        s.tick()
        now = at(2026, 7, 29, 12)
        s.record(EgressKind.VPN, 0, 300)
        s.tick()

        val week = s.query(StatsPeriod.WEEK)
        // 只有本周一起的那 300，上一周的 700 不算进来（滚动 7 天会把它算进来）
        assertEquals(300L, week.bytes)
        assertEquals(7, week.buckets.size)
        val monday = LocalDate.of(2026, 7, 27).toEpochDay()
        assertEquals(monday, week.buckets.first().key)
        // 两天都在本月里
        assertEquals(1000L, s.query(StatsPeriod.MONTH).bytes)
    }

    @Test
    fun `两次 tick 之间的多笔记账不丢字节`() {
        val s = store()
        repeat(1000) { s.record(EgressKind.VPN, 1, 2) }
        s.tick()
        repeat(500) { s.record(EgressKind.VPN, 1, 2) }
        s.tick()
        assertEquals(1500L * 3, s.query(StatsPeriod.TODAY).bytes)
    }

    @Test
    fun `query 会带上尚未 tick 的增量`() {
        val s = store()
        s.record(EgressKind.WIFI, 5, 5)
        // 不调 tick，直接查
        assertEquals(10L, s.query(StatsPeriod.TODAY).bytes)
    }

    @Test
    fun `落盘后重载数据一致`() {
        val a = store()
        a.noteSessionStart()
        a.record(EgressKind.VPN, 100, 200)
        a.record(EgressKind.ETHERNET, 1, 2)
        a.tick()
        a.flush()
        assertTrue(file.exists())

        val b = store()
        val today = b.query(StatsPeriod.TODAY)
        assertEquals(303L, today.bytes)
        assertEquals(1, today.sessions)
        assertEquals(300L, today.slices.first { it.kind == EgressKind.VPN }.bytes)
        assertEquals(3L, today.slices.first { it.kind == EgressKind.ETHERNET }.bytes)
        // 小时桶也要活过重载（今日那 24 根柱子靠它）
        assertEquals(303L, b.query(StatsPeriod.TODAY).buckets[10].bytes)
    }

    @Test
    fun `会话次数按天计并计入总计`() {
        val s = store()
        s.noteSessionStart()
        s.noteSessionStart()
        now = at(2026, 7, 30, 9)
        s.noteSessionStart()

        assertEquals(1, s.query(StatsPeriod.TODAY).sessions)
        assertEquals(3, s.query(StatsPeriod.WEEK).sessions)
        assertEquals(3, s.query(StatsPeriod.ALL).sessions)
    }

    @Test
    fun `本月蜂窝用量单独可查`() {
        val s = store()
        s.record(EgressKind.CELLULAR, 0, 1024)
        s.record(EgressKind.VPN, 0, 9999)
        s.tick()
        assertEquals(1024L, s.query(StatsPeriod.TODAY).cellularThisMonth)
        // 上个月的蜂窝不算进本月
        now = at(2026, 8, 1, 0)
        assertEquals(0L, s.query(StatsPeriod.TODAY).cellularThisMonth)
    }

    @Test
    fun `无任何记录时 firstDay 为空`() {
        val s = store()
        assertNull(s.query(StatsPeriod.ALL).firstDayEpoch)
        assertEquals(false, s.summary().hasAnyData)
        // 第一笔流量之后才有起始日
        s.record(EgressKind.VPN, 1, 1)
        s.tick()
        assertNotNull(s.query(StatsPeriod.ALL).firstDayEpoch)
        assertTrue(s.summary().hasAnyData)
    }

    @Test
    fun `入口摘要给出今日与近七日`() {
        val s = store()
        now = at(2026, 7, 28, 10)
        s.record(EgressKind.VPN, 0, 400)
        s.tick()
        now = at(2026, 7, 29, 10)
        s.record(EgressKind.VPN, 0, 100)
        s.tick()

        val sum = s.summary()
        assertEquals(100L, sum.todayBytes)
        assertEquals(400L, sum.yesterdayBytes)
        assertEquals(7, sum.last7Days.size)
        assertEquals(100L, sum.last7Days.last())
        assertEquals(400L, sum.last7Days[5])
    }

    @Test
    fun `手动清空后一切归零且文件删除`() {
        val s = store()
        s.noteSessionStart()
        s.record(EgressKind.VPN, 100, 200)
        s.tick()
        s.flush()
        assertTrue(file.exists())

        s.clear()

        assertEquals(0L, s.query(StatsPeriod.ALL).bytes)
        assertEquals(0, s.query(StatsPeriod.ALL).sessions)
        assertNull(s.query(StatsPeriod.ALL).firstDayEpoch)
        assertEquals(0L, s.query(StatsPeriod.TODAY).bytes)
        assertEquals(false, file.exists())
        // 重开进程也不该把清掉的数据读回来
        assertEquals(0L, store().query(StatsPeriod.ALL).bytes)
    }

    @Test
    fun `清空后旧字节不会被下一次 tick 加回来`() {
        // 这是 clear 最容易写错的地方：热路径累加器只增不减，清空时若不把差分基线推到当前值，
        // 下一个 tick 就会把「清空之前已经搬过的字节」当成新增量——正在共享时点清空，数字自己长回去。
        val s = store()
        s.record(EgressKind.VPN, 0, 1000)
        s.tick()
        s.clear()
        s.tick()
        assertEquals(0L, s.query(StatsPeriod.TODAY).bytes)

        // 清空之后**新**产生的流量要照常记
        s.record(EgressKind.VPN, 0, 7)
        s.tick()
        assertEquals(7L, s.query(StatsPeriod.TODAY).bytes)
    }

    @Test
    fun `清空发生在 tick 之前也不漏不重`() {
        val s = store()
        s.record(EgressKind.WIFI, 0, 500)   // 还没 tick 进桶
        s.clear()
        s.tick()
        assertEquals(0L, s.query(StatsPeriod.TODAY).bytes)
        s.record(EgressKind.WIFI, 0, 3)
        assertEquals(3L, s.query(StatsPeriod.TODAY).bytes)
    }

    @Test
    fun `天桶裁剪后总计仍然准确`() {
        val s = store()
        // 每天记 10 字节，连着 800 天——超过 730 天的保留窗口
        repeat(800) { d ->
            now = at(2026, 1, 1, 12) + d * DAY_MS
            s.record(EgressKind.VPN, 0, 10)
            s.tick()
        }
        // 明细被裁到窗口内，但永久累计器不受影响
        assertEquals(8000L, s.query(StatsPeriod.ALL).bytes)
        assertTrue("裁剪后 firstDay 应往前推", s.query(StatsPeriod.ALL).firstDayEpoch!! > LocalDate.of(2026, 1, 1).toEpochDay())
    }

    private companion object {
        val ZONE: ZoneId = ZoneOffset.UTC
        const val DAY_MS = 86_400_000L

        fun at(y: Int, m: Int, d: Int, h: Int): Long =
            ZonedDateTime.of(y, m, d, h, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
