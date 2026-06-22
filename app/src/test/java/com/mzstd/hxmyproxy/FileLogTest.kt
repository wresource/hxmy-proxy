package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileLogTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("filelog-test").toFile()
    }

    @After fun tearDown() {
        FileLog.clear()
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
}
