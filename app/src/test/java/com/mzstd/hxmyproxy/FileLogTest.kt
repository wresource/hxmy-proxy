package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [FileLog] 的三条硬承诺，错了都表现为「用户导出日志给我们、里面恰恰没有要找的东西」：
 *
 * 1. **滚动不丢最新**：磁盘占用必须封顶（main + 1 备份 ≈ 2×maxBytes），
 *    但淘汰的只能是最旧的；若把最新的滚掉，日志就永远停在事发之前。
 * 2. **key 环独立**：关键事件（监听起落、准入拒绝、崩溃栈、上次退出原因）在主环之外
 *    再存一份小环。主环实测能被 DNS/连接失败风暴刷到 3000 条/天，一夜就把崩溃栈冲没了；
 *    key 环若被同一批噪音污染或被主环的滚动带走，双环设计就等于不存在。
 * 3. **缓冲必须能被强制落盘**：写入路径是常驻 BufferedWriter + 每 20 条 flush 一次
 *    （热路径上不能每条 open/close）。代价是**最近的行默认还在内存里**——
 *    导出/崩溃/停止时若不强制 flush，导出文件的结尾就会「话说到一半断了」，
 *    而这与「进程被杀」在文件里完全同形（见 ExitReason 的类注释）。
 *
 * 另加总开关 [FileLog.enabled]：关掉后一条都不能落盘（含关键事件），且重新打开后要能继续写。
 */
class FileLogTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("filelog-test").toFile()
        // 全局 @Volatile 总开关：本类会把它关掉，务必在每个用例开始时复位，
        // 否则一旦某次断言提前失败，后面所有用例都会被静默连坐（全都写不进盘）。
        FileLog.enabled = true
    }

    @After fun tearDown() {
        FileLog.clear()
        FileLog.enabled = true
        dir.deleteRecursively()
    }

    @Test fun writesAndSnapshotsLines() {
        FileLog.init(dir)
        FileLog.i("t", "hello info")
        FileLog.w("t", "a warning")
        FileLog.e("t", "boom", RuntimeException("kaboom"))
        val snap = FileLog.snapshot()
        assertTrue(snap.contains("hello info"))
        assertTrue(snap.contains("I/t: hello info"))
        assertTrue(snap.contains("W/t: a warning"))
        assertTrue(snap.contains("E/t: boom"))
        assertTrue("应包含异常栈", snap.contains("kaboom"))
    }

    @Test fun rotatesCapsSizeAndKeepsRecent() {
        FileLog.init(dir, maxBytes = 1024) // 小阈值便于触发滚动
        repeat(200) { FileLog.i("t", "line-$it padding-padding-padding-padding") }
        // 触发滚动后应存在备份文件
        assertTrue("应生成备份 app.log.1", File(dir, "app.log.1").exists())
        val snap = FileLog.snapshot()
        // 最近的行应保留；滚动把总量限制在 ~2×maxBytes 量级（main + 1 备份），而非无限增长
        assertTrue("应保留最近的行", snap.contains("line-199"))
        assertTrue("总量应受限(滚动生效)", snap.length <= 1024 * 4)
    }

    @Test fun clearRemovesFiles() {
        FileLog.init(dir)
        FileLog.i("t", "x")
        FileLog.clear()
        assertEquals("", FileLog.snapshot())
        assertFalse(File(dir, "app.log").exists())
    }

    @Test fun appendBeforeInitIsNoOpNotCrash() {
        // 反初始化（指向新空目录前不写）——未 init 时 append 应安全无操作
        val fresh = Files.createTempDirectory("filelog-fresh").toFile()
        FileLog.init(fresh)
        FileLog.clear()
        // 即使内容为空也不应抛异常
        assertEquals("", FileLog.snapshot())
        fresh.deleteRecursively()
    }

    // ---------- 滚动：淘汰的必须是最旧的，且只留一层备份 ----------

    @Test fun `滚动淘汰最旧的行，保留最新的，且只留一层备份`() {
        FileLog.init(dir, maxBytes = 1024)
        repeat(200) { FileLog.i("t", "line-$it padding-padding-padding-padding") }
        val snap = FileLog.snapshot()
        // 上限 1KB、写了约 12KB ⇒ 开头那批必须已被淘汰。
        // 这一条与「保留最新」是一体两面：只断言「最新还在」的话，一个「压根没滚动、
        // 文件无限增长」的实现同样能过。
        assertFalse("最旧的行应已被淘汰", snap.contains("line-0 padding"))
        assertTrue("最新的行必须在", snap.contains("line-199"))
        // 只有 main + 1 备份，磁盘占用才封得住 ≈2×maxBytes；多出一层就是承诺失效。
        assertFalse("不应产生第二层备份", File(dir, "app.log.2").exists())
    }

    @Test fun `导出顺序是时间正序——备份段在前、当前段在后`() {
        FileLog.init(dir, maxBytes = 1024)
        repeat(200) { FileLog.i("t", "line-$it padding-padding-padding-padding") }
        val snap = FileLog.snapshot()
        // snapshot 拼接 backup + main。若顺序反了，导出日志会在滚动边界处出现一段时间倒流，
        // 读的人会据此推出完全错误的因果（「先崩溃后启动」）。
        val older = snap.indexOf("line-190 ")
        val newer = snap.indexOf("line-199 ")
        assertTrue("两行都应在窗口内(older=$older newer=$newer)", older >= 0 && newer >= 0)
        assertTrue("旧行应排在新行之前", older < newer)
    }

    // ---------- key 环：独立、不被主环噪音冲掉 ----------

    @Test fun `key 环不被主环的高频噪音冲掉`() {
        FileLog.init(dir, maxBytes = 1024) // 主环极小，模拟「被风暴刷爆」
        FileLog.append("I", "SVC", "listener bound :8080", null, key = true)
        // 主环滚动很多轮，那条关键事件必然被挤出主环
        repeat(300) { FileLog.i("DNS", "noise-$it padding-padding-padding-padding") }
        val snap = FileLog.snapshot()
        val head = "===== KEY EVENTS (never rotated away) ====="
        val body = "===== FULL LOG ====="
        assertTrue("应带分节头", snap.contains(head))
        val keySection = snap.substring(snap.indexOf(head), snap.indexOf(body))
        val mainSection = snap.substring(snap.indexOf(body))
        assertFalse("主环里它应已被噪音冲掉(前提成立才说明本用例有效)", mainSection.contains("listener bound"))
        assertTrue("key 环必须仍留着它", keySection.contains("listener bound :8080"))
        assertFalse("高频噪音不该进 key 环", keySection.contains("noise-"))
    }

    @Test fun `key 段排在正文之前——导出给人看时第一眼就是关键事件`() {
        FileLog.init(dir)
        FileLog.i("DNS", "ordinary line")
        Ev.k(LogCat.PROC, "proc.lastExit", "reason" to "SIGNALED(4)")
        val snap = FileLog.snapshot()
        assertTrue(snap.indexOf("KEY EVENTS") < snap.indexOf("FULL LOG"))
        assertTrue("正文仍要有全量", snap.substringAfter("FULL LOG").contains("ordinary line"))
    }

    @Test fun `没有关键事件时不加分节头，避免空段噪音`() {
        FileLog.init(dir)
        FileLog.i("DNS", "just an ordinary line")
        val snap = FileLog.snapshot()
        assertFalse(snap.contains("KEY EVENTS"))
        assertFalse(snap.contains("FULL LOG"))
        assertTrue(snap.contains("just an ordinary line"))
    }

    // ---------- 缓冲与强制落盘 ----------

    @Test fun `未满一个 flush 批次时行仍在缓冲区，flush 后才真正落盘`() {
        FileLog.init(dir)
        repeat(5) { FileLog.i("t", "buffered-$it") } // < FLUSH_EVERY(20)
        val beforeFlush = File(dir, "app.log").let { if (it.exists()) it.readText() else "" }
        assertFalse("这些行本就该还在内存里(否则热路径退化成每条写盘)", beforeFlush.contains("buffered-4"))
        FileLog.flush()
        assertTrue("flush 后必须能从磁盘读到", File(dir, "app.log").readText().contains("buffered-4"))
    }

    @Test fun `snapshot 自带强制 flush——导出不会缺最近几行`() {
        FileLog.init(dir)
        repeat(3) { FileLog.i("t", "unflushed-$it") }
        // 导出路径若忘了 flush，用户拿到的日志结尾就是「话说到一半断了」，
        // 而这与「进程被 SIGKILL」在文件里完全同形，排障会走进死胡同。
        assertTrue(FileLog.snapshot().contains("unflushed-2"))
    }

    @Test fun `重新 init 到新目录时旧缓冲要落盘，新行只写新目录`() {
        val dirB = Files.createTempDirectory("filelog-b").toFile()
        try {
            FileLog.init(dir)
            FileLog.i("t", "in-A") // 只有 1 条，仍在缓冲里
            FileLog.init(dirB)     // 切目录（如首次拿到 filesDir 后重定向）
            FileLog.i("t", "in-B")
            FileLog.flush()
            // init 必须 close 旧 sink，否则缓冲里的 in-A 随对象一起丢掉，日志出现无声空洞。
            assertTrue("切目录时旧缓冲应被 flush", File(dir, "app.log").readText().contains("in-A"))
            assertTrue(File(dirB, "app.log").readText().contains("in-B"))
            assertFalse("新行不该再写回旧目录", File(dir, "app.log").readText().contains("in-B"))
        } finally {
            dirB.deleteRecursively()
        }
    }

    // ---------- 总开关 ----------

    @Test fun `总开关关掉后一条都不落盘，连关键事件也不例外`() {
        FileLog.init(dir)
        FileLog.enabled = false
        FileLog.i("t", "should-not-appear")
        FileLog.e("t", "boom", RuntimeException("nope"))
        Ev.k(LogCat.PROC, "proc.start") // 关键事件也必须被总开关拦住（用户关的是「记不记日志」）
        assertEquals("", FileLog.snapshot())
        assertFalse("连文件都不该被创建", File(dir, "app.log").exists())
        assertFalse(File(dir, "key.log").exists())
    }

    @Test fun `重新打开总开关后能继续写——关一次不等于永久失效`() {
        FileLog.init(dir)
        FileLog.enabled = false
        FileLog.i("t", "while-off")
        FileLog.enabled = true
        FileLog.i("t", "after-on")
        val snap = FileLog.snapshot()
        assertFalse(snap.contains("while-off"))
        assertTrue(snap.contains("after-on"))
    }

    @Test fun `clear 之后仍能继续写入——清空日志不该让记录永久停摆`() {
        // 用户在设置里点「清空日志」是常规操作；若 clear 关掉 writer 后不再自愈，
        // 之后所有日志静默丢失，且要等到下次导出才会发现。
        FileLog.init(dir)
        FileLog.i("t", "before-clear")
        FileLog.clear()
        FileLog.i("t", "after-clear")
        val snap = FileLog.snapshot()
        assertFalse(snap.contains("before-clear"))
        assertTrue("clear 后应能重新建文件继续写", snap.contains("after-clear"))
    }

    @Test fun `clear 同时清掉 key 环，不留上一次会话的关键事件`() {
        FileLog.init(dir)
        Ev.k(LogCat.SVC, "svc.bound", "port" to 8080)
        FileLog.clear()
        assertEquals("", FileLog.snapshot())
        assertFalse(File(dir, "key.log").exists())
        assertFalse(File(dir, "key.log.1").exists())
    }
}
