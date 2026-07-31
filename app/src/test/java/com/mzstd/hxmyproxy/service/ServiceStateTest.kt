package com.mzstd.hxmyproxy.service

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ServiceState] ——「上次是否在共享中」这一个布尔值的读写语义。
 *
 * **为什么这一个布尔值值得单独测**：它是 [RestartReceiver] 开机 / app 更新后是否自动恢复共享的**唯一**
 * 依据，而它的两种错法都是静默的、方向相反的用户可感知故障：
 *  - 写没提交（`putBoolean` 后忘了 `apply`/`commit`）、或读的键与写的键不一致 ⇒ 标记永远读出 false ⇒
 *    装完新版 / 重启后共享**不会**回来，用户以为「开了却上不了网」（浏览器还会把这个代理标记成
 *    bad proxy 退避 5 分钟，见 RestartReceiver 注释）；
 *  - 缺省值写成 true、或停止时抹键而非写 false ⇒ 用户**主动停过**的意愿被无视，开机后共享被擅自开启。
 * 两者在 UI 上都毫无异样，只有用户某天发现行为不对。
 *
 * **做法**：Context / SharedPreferences 用 mockk 替身（本类除 `getSharedPreferences` 外不碰任何 android API，
 * 所以替身足以覆盖全部真实逻辑；JVM 里 android.* 静默返回默认值，真跑 SharedPreferences 是假测试）。
 * 注意几处刻意的 stub：relaxed mock 的 `getBoolean` 本来就返回 false，直接断言 false 是**空过**的，
 * 故用 `answers { secondArg() }` 让替身回显调用方传入的缺省值，断言才真正落在「缺省值是不是 false」上。
 */
class ServiceStateTest {

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        // edit().putBoolean(...).apply() 是链式调用：putBoolean 必须返回同一个 editor，
        // 否则 apply() 落在另一个替身上，验证会看不见。
        every { editor.putBoolean(any(), any()) } returns editor
        prefs = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        ctx = mockk(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
    }

    /** 只 put 不 apply ⇒ 进程被杀后标记丢失 ⇒ 开机/更新后不再自动恢复共享，且无任何报错。 */
    @Test
    fun `开启共享的标记必须真正提交，只写不提交等于没写`() {
        ServiceState.setWasSharing(ctx, true)
        verify(exactly = 1) { editor.putBoolean(any(), true) }
        verify(exactly = 1) { editor.apply() }
    }

    /**
     * 用户主动停止时必须写入 false，而不是 `remove` 掉键——remove 与「从没写过」不可区分是没关系的，
     * 真正的坑是「既不写 false 也不 remove」：上次的 true 残留下来，下次开机就会擅自把共享打开。
     */
    @Test
    fun `主动停止共享写入的是 false，而不是把旧的 true 留在原地`() {
        ServiceState.setWasSharing(ctx, false)
        verify(exactly = 1) { editor.putBoolean(any(), false) }
        verify(exactly = 0) { editor.putBoolean(any(), true) }
        verify(exactly = 1) { editor.apply() }
    }

    /**
     * 全新安装 / 清除数据后没有任何记录，此时缺省必须是 false：
     * 缺省写成 true 就意味着「从没开过共享的用户，开机即被自动开启共享」——这是最不能接受的一种错。
     */
    @Test
    fun `读不到记录时缺省为 false，绝不擅自开启共享`() {
        // 让替身回显调用方传入的缺省值（见类注释：直接返回 false 的话本用例是空过的）。
        every { prefs.getBoolean(any(), any()) } answers { secondArg<Boolean>() }
        assertFalse(ServiceState.wasSharing(ctx))
    }

    /**
     * 读与写必须落在同一个 pref 文件、同一个键上，且都用 MODE_PRIVATE。
     * 任一侧被改名（重构时最容易发生）都会让标记「写进去了却读不出来」，症状与「压根没写」完全一致：
     * 更新完 app 共享不会自动回来，日志里也不会有任何异常。顺带验证取值不被取反。
     */
    @Test
    fun `读与写落在同一个 pref 文件与同一个键上，取值不被取反`() {
        val names = mutableListOf<String>()
        val modes = mutableListOf<Int>()
        every { ctx.getSharedPreferences(capture(names), capture(modes)) } returns prefs
        val writeKey = slot<String>()
        every { editor.putBoolean(capture(writeKey), any()) } returns editor
        val readKey = slot<String>()
        every { prefs.getBoolean(capture(readKey), any()) } returns true

        ServiceState.setWasSharing(ctx, true)
        assertTrue("存的是 true 就必须读出 true", ServiceState.wasSharing(ctx))

        assertEquals("应各访问一次 pref 文件", 2, names.size)
        assertEquals("读写用了不同的 SharedPreferences 文件", names[0], names[1])
        assertEquals("读写用了不同的键", writeKey.captured, readKey.captured)
        modes.forEach { assertEquals("必须 MODE_PRIVATE", Context.MODE_PRIVATE, it) }
    }
}
