package com.mzstd.hxmyproxy.core.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [Ev] 的**日志行契约**：`evt=<事件码> k=v k=v ...`，加上级别/分类/key 环的归属。
 *
 * 【为什么值得测】
 * 这批语义的错法全是「日志照样出、只是骗人」——没有任何异常、没有任何红字：
 * - **null 字段若被打成 `k=null`**：心跳里 stallIn/stallOut/maxStall/relayN 在无样本时本该整组消失
 *   （见 ProxyServerRepository 心跳 `"stallIn" to stall?.let{...}`、ProxyServer 的
 *   `"emfile" to (if (emfile) true else null)`）。一旦变成 `stallIn=null`，
 *   就等于每分钟给日志灌 4 个假字段，排障时会把「没有样本」误读成「测到了 0%」。
 * - **含空格的值不加引号**：`name=My Phone` 会让所有按空格切 k=v 的 awk/grep 管线
 *   在这一行之后整行错位，且只在设备名带空格时发生。
 * - **delta 在相等时也产出**：`只记变化` 退化成 `记快照`，出口选择这类低频配置态
 *   会被每次 applyTunables 刷屏，真正的那次切换反而淹没（这正是当初引入 delta 的原因）。
 * - **tag 位不是 LogCat 名**：导出日志的 `grep " /ADMIT: "` 分子系统检索直接失效。
 * - **k/kw/e 没进 key.log**：崩溃栈与准入拒绝会被 DNS 风暴冲出滚动窗口，
 *   用户导出的日志里「关键事件」段空空如也——这是双环设计的全部意义。
 *
 * 【做法】[FileLog] 与 Android 解耦（只碰 java.io），所以直接 init 到临时目录、
 * 用 [FileLog.snapshot] 把真实落盘结果读回来断言，不 mock 任何东西。
 */
class EvTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ev-test").toFile()
        // enabled 是全局 @Volatile 总开关；别的用例若把它关掉又没恢复，这里会被静默连坐。
        FileLog.enabled = true
        FileLog.init(dir)
    }

    @After
    fun tearDown() {
        FileLog.clear()
        FileLog.enabled = true
        dir.deleteRecursively()
    }

    // ---------- k=v 格式化 ----------

    @Test
    fun `值为 null 的字段整个消失，而不是打成字面量 null`() {
        // 心跳的真实形状：无 relay 样本时 stall 三兄弟一起为 null，应当整组不出现。
        Ev.i(
            LogCat.PERF, "perf.heartbeat",
            "conns" to 3,
            "stallIn" to null,
            "stallOut" to null,
            "maxStall" to null,
            "relayN" to 7,
        )
        assertEquals("evt=perf.heartbeat conns=3 relayN=7", singleMsg("perf.heartbeat"))
    }

    @Test
    fun `空串是有值，不能与缺失混为一谈`() {
        // 防「顺手把 v != null 改成 v.isNullOrBlank()」：空串代表「取到了但内容为空」
        // （如 err 为空的异常、无描述的退出原因），与「压根没这个维度」是两回事。
        Ev.i(LogCat.CFG, "cfg.apply", "note" to "", "n" to 1)
        assertEquals("evt=cfg.apply note= n=1", singleMsg("cfg.apply"))
    }

    @Test
    fun `含空格的值加引号，保证按空格切分键值对不错位`() {
        Ev.i(LogCat.CFG, "cfg.apply", "name" to "My Phone", "n" to 1)
        assertEquals("""evt=cfg.apply name="My Phone" n=1""", singleMsg("cfg.apply"))
    }

    @Test
    fun `不含空格的值不加引号——引号会污染 grep 出来的值`() {
        Ev.i(LogCat.DNS, "dns.query", "host" to "example.com", "ms" to 12L, "ok" to true)
        assertEquals("evt=dns.query host=example.com ms=12 ok=true", singleMsg("dns.query"))
    }

    @Test
    fun `没有任何 kv 时只剩事件码，不留多余空格`() {
        // 行尾多一个空格会让 `grep -c "evt=xxx$"` 这类精确统计全部落空。
        Ev.i(LogCat.PROC, "test.bare")
        assertEquals("evt=test.bare", singleMsg("test.bare"))
    }

    // ---------- 级别与分类（日志行的 tag 位） ----------

    @Test
    fun `分类占 tag 位，级别在前——导出日志可按子系统 grep`() {
        // 契约来自 LogCat 的类注释：`grep " /ADMIT: "` 必须能捞到准入子系统的全部行。
        Ev.i(LogCat.ADMIT, "admit.set")
        Ev.w(LogCat.EGRESS, "egress.fallback")
        val text = FileLog.snapshot()
        assertTrue("I 级 + ADMIT 分类", text.contains(" I/ADMIT: evt=admit.set"))
        assertTrue("W 级 + EGRESS 分类", text.contains(" W/EGRESS: evt=egress.fallback"))
    }

    // ---------- key 环归属 ----------

    @Test
    fun `k 与 kw 进 key 环，i 与 w 不进`() {
        Ev.k(LogCat.SVC, "svc.bound", "port" to 8080)
        Ev.kw(LogCat.NET, "net.lost")
        Ev.i(LogCat.DNS, "dns.query", "host" to "a.com")
        Ev.w(LogCat.DNS, "dns.slow")
        val key = keySection()
        val main = mainSection()
        assertTrue("关键事件应在 key 环", key.contains("evt=svc.bound"))
        assertTrue("降级事件应在 key 环", key.contains("evt=net.lost"))
        assertFalse("高频 I 不该挤进 key 环", key.contains("evt=dns.query"))
        assertFalse("高频 W 不该挤进 key 环", key.contains("evt=dns.slow"))
        // 双写而非移写：主环里 4 条都要在，否则时间线断裂
        listOf("svc.bound", "net.lost", "dns.query", "dns.slow").forEach {
            assertTrue("主环应保留 $it", main.contains("evt=$it"))
        }
    }

    @Test
    fun `e 级默认进 key 环并带完整异常栈`() {
        // 崩溃/异常是最不能被冲掉的一类证据，Ev.e 不给调用方选择的余地。
        Ev.e(LogCat.RELAY, "relay.error", RuntimeException("kaboom-xyz"), "conn" to 42)
        val key = keySection()
        assertTrue(key.contains("E/RELAY: evt=relay.error conn=42"))
        assertTrue("异常栈应随行落盘", key.contains("kaboom-xyz"))
    }

    // ---------- delta：只记变化 ----------

    @Test
    fun `delta 相等返回 null——调用方据此跳过落盘`() {
        assertNull(Ev.delta("egress", "AUTO", "AUTO"))
        assertNull("两边都没值也算没变化", Ev.delta("egress", null, null))
    }

    @Test
    fun `delta 不等产出 旧到新 的箭头形式`() {
        assertEquals("egress" to "AUTO->WIFI", Ev.delta("egress", "AUTO", "WIFI"))
    }

    @Test
    fun `首次生效 null 到有值算变化，且旧值以 null 字面量落盘`() {
        // 这一条是「出口选择」的基线行：lastEgressChoice 初始为 null，首次 applyTunables
        // 必须落一条 `egress=null->WIFI`。若把 null 旧值当成「没变化」跳过，日志里就永远
        // 看不到当前出口是什么，只能靠反推代码分支去猜（7-30 mac 客户端排查绕远路的原因）。
        val d = Ev.delta("egress", null, "WIFI")
        assertEquals("egress" to "null->WIFI", d)
        Ev.k(LogCat.EGRESS, "egress.choice", d!!)
        // 注意与「null 字段省略」的边界：被省略的是 *值为 null* 的字段，
        // 而这里的值是字符串 "null->WIFI"，必须原样落盘。
        assertEquals("evt=egress.choice egress=null->WIFI", singleMsg("egress.choice"))
    }

    @Test
    fun `delta 按 equals 判定，不看是不是同一个对象`() {
        // 出口选择等类型是 data class / enum；用 === 判定会让每次重建对象都报一次假变化。
        assertNull(Ev.delta("x", listOf(1, 2), listOf(1, 2)))
        assertEquals("x" to "[1, 2]->[1, 3]", Ev.delta("x", listOf(1, 2), listOf(1, 3)))
    }

    // ---------- throttled：窗口 + 补报被抑制条数 ----------

    @Test
    fun `节流窗口内只落一条，窗口结束补报被抑制的条数`() {
        val dedup = "test-throttle-window"
        repeat(3) {
            Ev.throttled(LogCat.DNS, "dns.fail", dedup, 60_000L, kv = arrayOf("host" to "a.com"))
        }
        val first = evtLines("dns.fail")
        assertEquals("窗口内 3 次只应落 1 条", 1, first.size)
        assertFalse("第一条没有被抑制者，不该凭空出现 suppressed", first[0].contains("suppressed"))

        // windowMs=0 等价于「窗口已过」：补报期间被吞掉的 2 条。
        // 没有这个数，读日志的人会以为只发生过一次——这正是引入 suppressed 的原因。
        Ev.throttled(LogCat.DNS, "dns.fail", dedup, 0L, kv = arrayOf("host" to "a.com"))
        val after = evtLines("dns.fail")
        assertEquals(2, after.size)
        assertTrue("应补报 suppressed=2，实际: ${after[1]}", after[1].contains("suppressed=2"))
    }

    @Test
    fun `补报后计数清零，不会把旧账重复算进下一轮`() {
        val dedup = "test-throttle-reset"
        Ev.throttled(LogCat.CONN, "conn.reject", dedup, 60_000L)
        Ev.throttled(LogCat.CONN, "conn.reject", dedup, 60_000L) // 被抑制 1 条
        Ev.throttled(LogCat.CONN, "conn.reject", dedup, 0L)      // 补报 suppressed=1
        Ev.throttled(LogCat.CONN, "conn.reject", dedup, 0L)      // 无新抑制 ⇒ 不该再带 suppressed
        val lines = evtLines("conn.reject")
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("suppressed=1"))
        assertFalse("计数应已被 remove 清零，实际: ${lines[2]}", lines[2].contains("suppressed"))
    }

    /**
     * **键表满了不得整表清空** —— 那会让 `suppressed=N` 这个补偿机制失效。
     *
     * 原实现是 `throttleAt.clear(); suppressed.clear()`。三个后果:
     *  ① 已累计但尚未补报的抑制条数**永久丢失**（`suppressed=N` 的全部意义就是
     *     「日志只有一行，但实际发生了 N 次」，清掉它等于让那 N 次凭空消失）;
     *  ② 清空后所有键重新开始，下一条统统落盘 → 日志突发，
     *     而突发恰好在键最多的时候（故障期，域名基数最大）;
     *  ③ 512 对本项目真的紧张：一个活跃域名占五六个槽，几十个域名就到顶。
     *
     * 这条测试锁的是**信息不凭空消失**：被淘汰键的抑制条数必须转入可查询的累计值。
     * 0814 那份日志里 `suppressed` 总共只有 124，此前被读成「节流很少触发」——
     * 有了 `throttleStats()` 才能判断那个数是不是已经被清掉过。
     */
    @Test
    fun `键表满时按LRU淘汰且抑制条数不得凭空消失`() {
        val (keys0, _, dropped0) = Ev.throttleStats()
        // 先造一个有抑制计数的键，且让它成为最久未用的那批之一
        val victim = "test-evict-victim"
        Ev.throttled(LogCat.CONN, "test.evict", victim, 600_000L)
        Ev.throttled(LogCat.CONN, "test.evict", victim, 600_000L)   // 抑制 +1，尚未补报
        // 灌满键表触发淘汰（上限 512）
        repeat(700) { Ev.throttled(LogCat.CONN, "test.evict.filler", "test-filler-$it", 600_000L) }
        val (keys1, evictions, dropped1) = Ev.throttleStats()
        assertTrue("应发生过淘汰，实际 evictions=$evictions", evictions > 0)
        assertTrue("键表不得被清空到 0（那是整表 clear 的特征），实际 keys=$keys1", keys1 > 100)
        assertTrue(
            "被淘汰键的抑制条数必须转入累计值，否则信息凭空消失：dropped $dropped0 -> $dropped1",
            dropped1 > dropped0,
        )
    }

    @Test
    fun `不同 dedup 键各走各的窗口，互不遮蔽`() {
        // dedup 常含来源 IP：两台客户端同时出问题时，绝不能因为「同一事件码」只报一台。
        // 事件码用 test.* 前缀：本类按事件码计数，撞上生产事件码会被别的用例的后台线程干扰。
        Ev.throttled(LogCat.ADMIT, "test.notAdmitted", "admit:10.0.0.2", 60_000L, kv = arrayOf("remote" to "10.0.0.2"))
        Ev.throttled(LogCat.ADMIT, "test.notAdmitted", "admit:10.0.0.3", 60_000L, kv = arrayOf("remote" to "10.0.0.3"))
        val lines = evtLines("test.notAdmitted")
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("remote=10.0.0.2") })
        assertTrue(lines.any { it.contains("remote=10.0.0.3") })
    }

    @Test
    fun `节流行的级别可指定，key 参数决定是否进 key 环`() {
        // accept.error / reject.limit 这类都是 key=true 的节流事件：既要限流，又不能被冲掉。
        Ev.throttled(LogCat.CONN, "accept.error", "test-throttle-key", 60_000L, key = true, level = "E", kv = arrayOf("emfile" to true))
        Ev.throttled(LogCat.DNS, "dns.noisy", "test-throttle-nokey", 60_000L)
        val key = keySection()
        assertTrue("key=true 的节流事件应进 key 环", key.contains("E/CONN: evt=accept.error emfile=true"))
        assertFalse("key=false 的节流事件不该进 key 环", key.contains("evt=dns.noisy"))
        assertTrue("默认级别是 W", mainSection().contains("W/DNS: evt=dns.noisy"))
    }

    @Test
    fun `节流键表有界——被灌爆后清表，宁可丢节流状态也不无限增长`() {
        // dedup 里带来源 IP/域名，端口扫描器或 DNS 风暴可以造出无穷多个键。
        // 上限的可观察后果：清表后老键的节流状态一并丢失，会重新落一条（而不是继续被抑制）。
        val victim = "test-throttle-victim"
        Ev.throttled(LogCat.DNS, "dns.victim", victim, 600_000L)
        Ev.throttled(LogCat.DNS, "dns.victim", victim, 600_000L)
        assertEquals("同窗口内应被抑制", 1, evtLines("dns.victim").size)

        repeat(600) { Ev.throttled(LogCat.DNS, "dns.noise", "flood:10.0.$it.1", 600_000L) }

        Ev.throttled(LogCat.DNS, "dns.victim", victim, 600_000L)
        assertEquals("键表被清后老键应重新落盘（证明表有上限）", 2, evtLines("dns.victim").size)
    }

    // ---------- 辅助 ----------

    /** snapshot 的 key 段（无关键事件时为空串）。 */
    private fun keySection(): String {
        val s = FileLog.snapshot()
        if (!s.startsWith(KEY_HEAD)) return ""
        return s.substring(KEY_HEAD.length, s.indexOf(MAIN_HEAD))
    }

    /** snapshot 的主环段。 */
    private fun mainSection(): String {
        val s = FileLog.snapshot()
        if (!s.startsWith(KEY_HEAD)) return s
        return s.substring(s.indexOf(MAIN_HEAD) + MAIN_HEAD.length)
    }

    /** 主环里属于某事件码的行（按写入顺序），只取 `evt=` 之后的消息体。 */
    private fun evtLines(evt: String): List<String> =
        mainSection().lineSequence()
            .map { it.substringAfter(": ", "") }
            .filter { it == "evt=$evt" || it.startsWith("evt=$evt ") }
            .toList()

    /**
     * 断言某事件码「只落了一条」并返回其完整消息体（去掉时间戳与 `L/TAG: ` 前缀），
     * 用于逐字断言 k=v 串。按事件码取而不是「取全文件唯一一行」：别的测试类可能留下
     * 仍在收尾的后台线程往同一个 FileLog 里写行，那不该让格式断言变成随机失败。
     */
    private fun singleMsg(evt: String): String {
        val msgs = evtLines(evt)
        assertEquals("事件 $evt 只应落一条: $msgs", 1, msgs.size)
        return msgs[0]
    }

    private companion object {
        const val KEY_HEAD = "===== KEY EVENTS (never rotated away) =====\n"
        const val MAIN_HEAD = "\n===== FULL LOG =====\n"
    }
}
