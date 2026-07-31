package com.mzstd.hxmyproxy.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [RestartReceiver] 的**准入闸门**：什么情况下才允许自动把共享拉回来。
 *
 * 这个类只有 4 行判断，但两个方向的错都是用户直接受害的：
 *  - 闸门太松（漏判 action、漏判 [ServiceState.wasSharing]）⇒ 用户明明主动停过共享，开机后手机
 *    又自己变成代理网关；任何一条注册到本 receiver 的广播都能把服务拉起来。
 *  - 闸门太紧（少注册一个 action、或误判成 false）⇒ 装完新版共享不会自动回来。这一条尤其阴：
 *    `START_STICKY` 只覆盖「被系统杀」，**装新版后没有任何机制把服务拉回来**，而这段空窗里
 *    Chrome 会把该代理标记成 bad proxy 并在浏览器进程内退避 5 分钟，用户看到的是
 *    「明明重新开了代理还是上不了网」——app 侧根本没法自证清白。
 *
 * **做法**：Context 用 mockk 替身，断言落在「有没有调用 startForegroundService」上；
 * 走不通的正向路径不靠日志判断（[com.mzstd.hxmyproxy.core.log.FileLog] 未 init 时是静默 no-op）。
 * 另有一条 manifest 契约用例：代码分支再对，manifest 不给这个 action 注册也一样是死代码，
 * 而这种断裂在 JVM 里无法通过跑代码发现，只能拿 manifest 原文对。
 */
class RestartReceiverTest {

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var receiver: RestartReceiver

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
        receiver = RestartReceiver()
    }

    private fun intentOf(action: String?): Intent =
        mockk<Intent>().also { every { it.action } returns action }

    /**
     * 非开机 / 非更新的广播必须在读任何状态之前就被挡掉。
     * 断言「ctx 完全没被碰过」而不是「没启动服务」：闸门一旦被删，第一件事就是拿 ctx 去读
     * SharedPreferences，这里就会红——比只看启动与否更早、更准。
     */
    @Test
    fun `与开机和更新无关的广播一律无视，连状态都不去读`() {
        receiver.onReceive(ctx, intentOf("android.intent.action.SCREEN_ON"))
        receiver.onReceive(ctx, intentOf(""))
        receiver.onReceive(ctx, intentOf(null))
        verify { ctx wasNot Called }
    }

    /**
     * 上次不在共享中（用户主动停过，或从没开过）⇒ 开机后绝不自动开启。
     * 同时验证「确实读过状态」：否则万一 action 闸门被改坏成全拒，本用例会假通过。
     */
    @Test
    fun `上次未在共享中时，开机广播不会拉起服务`() {
        every { prefs.getBoolean(any(), any()) } returns false
        receiver.onReceive(ctx, intentOf(Intent.ACTION_BOOT_COMPLETED))
        verify(exactly = 1) { ctx.getSharedPreferences(any(), any()) }
        verify(exactly = 0) { ctx.startForegroundService(any()) }
        verify(exactly = 0) { ctx.startService(any()) }
    }

    /**
     * 上次在共享中 ⇒ 开机后自动恢复，且必须走 `startForegroundService`：
     * 用 `startService` 在后台启动前台服务会被系统直接拒（IllegalStateException），
     * 而这里的异常被 runCatching 吞掉，表现就是「什么都没发生」。
     */
    @Test
    fun `上次在共享中时，开机广播自动拉起前台服务`() {
        every { prefs.getBoolean(any(), any()) } returns true
        receiver.onReceive(ctx, intentOf(Intent.ACTION_BOOT_COMPLETED))
        verify(exactly = 1) { ctx.startForegroundService(any()) }
        verify(exactly = 0) { ctx.startService(any()) }
    }

    /** app 更新（MY_PACKAGE_REPLACED）与开机同权——这正是 START_STICKY 覆盖不到的那段空窗。 */
    @Test
    fun `app 更新广播与开机同样触发自动恢复`() {
        every { prefs.getBoolean(any(), any()) } returns true
        receiver.onReceive(ctx, intentOf(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(exactly = 1) { ctx.startForegroundService(any()) }
    }

    /**
     * 代码与 manifest 的契约：代码接受的两个 action 必须都在 intent-filter 里注册，
     * receiver 必须 `exported="true"`（系统广播来自外部进程，false 就永远收不到），
     * 且必须声明 RECEIVE_BOOT_COMPLETED。任何一处缺失都让上面几条分支变成死代码——
     * 跑代码测不出来，只能拿 manifest 原文对。action 常量直接取自 [Intent]，
     * 于是这条断言是「代码 ↔ manifest」的对账，而不是抄一遍字面量。
     */
    @Test
    fun `manifest 为这两个 action 注册了本 receiver 且可被系统唤起`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val block = Regex("<receiver[^>]*RestartReceiver[\\s\\S]*?</receiver>").find(manifest)?.value
        requireNotNull(block) { "AndroidManifest 里没有 RestartReceiver 的注册块" }
        assertTrue("intent-filter 缺 ${Intent.ACTION_BOOT_COMPLETED}", block.contains(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(
            "intent-filter 缺 ${Intent.ACTION_MY_PACKAGE_REPLACED}",
            block.contains(Intent.ACTION_MY_PACKAGE_REPLACED),
        )
        assertTrue("receiver 必须 exported=true，否则收不到系统广播", block.contains("android:exported=\"true\""))
        assertTrue(
            "缺 RECEIVE_BOOT_COMPLETED 权限，BOOT_COMPLETED 不会送达",
            manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"),
        )
    }
}
