package com.mzstd.hxmyproxy.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ProxyForegroundService] 本体在 JVM 单测里**测不了**：它是 Hilt 注入的 Service，
 * 每条语义都长在生命周期回调（onCreate/onStartCommand/onDestroy）、NotificationManager、
 * PendingIntent、资源字符串上——这些在 JVM 里全是 stub，配合本项目的 `isReturnDefaultValues = true`
 * 会**静默返回默认值**，写出来的断言只能证明「没抛异常」，不能证明任何行为。这类测试比没有更坏。
 *
 * 但有一类错**能**在 JVM 里守住，而且是这个服务最致命的一类：**代码与 manifest 的声明断裂**。
 * Android 14+ 起，`startForeground(id, notif, type)` 传的类型必须在 manifest 的
 * `foregroundServiceType` 里声明过、且对应的 FOREGROUND_SERVICE_* 权限已申请，否则不是「降级」
 * 而是**直接抛异常**——用户点「开始共享」即崩溃闪退，且这种断裂改一行 manifest 就能造成，
 * 编译期毫无提示。故这里只做静态契约对账，不假装能测服务行为。
 */
class ProxyForegroundServiceTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    /** 抓 `<service ... ProxyForegroundService ...>` 这一个开标签（属性全在开标签里，自闭合与否都适用）。 */
    private val serviceTag: String =
        Regex("<service[^>]*ProxyForegroundService[^>]*>").find(manifest)?.value
            ?: error("AndroidManifest 里没有 ProxyForegroundService 的 <service> 声明")

    /**
     * 服务不能对外暴露：exported=true 时任何第三方 app 都能拉起它，
     * 等于别的应用可以悄悄把用户手机变成代理网关（本服务只由本进程与 RestartReceiver 启动）。
     */
    @Test
    fun `代理服务不对外暴露`() {
        assertTrue("service 必须 exported=false，实际: $serviceTag", serviceTag.contains("android:exported=\"false\""))
    }

    /**
     * manifest 声明的 FGS 类型、对应权限、以及代码里 startForeground 传入的类型三者必须一致。
     * 错开任何一处，Android 14+ 上开共享直接崩（不是静默降级），而且只有真机能复现——
     * 所以这条对账放在 JVM 单测里最划算。
     */
    @Test
    fun `前台服务类型 connectedDevice 在代码与 manifest 与权限三处一致`() {
        assertTrue(
            "manifest 未声明 foregroundServiceType=connectedDevice，实际: $serviceTag",
            serviceTag.contains("android:foregroundServiceType=\"connectedDevice\""),
        )
        assertTrue(
            "缺 FOREGROUND_SERVICE 权限",
            manifest.contains("android.permission.FOREGROUND_SERVICE\""),
        )
        assertTrue(
            "缺 FOREGROUND_SERVICE_CONNECTED_DEVICE 权限，Android 14+ 开共享会抛 SecurityException",
            manifest.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"),
        )
        // 代码侧：startForeground 传的必须还是 CONNECTED_DEVICE。JVM 里加载不了这个 Service 类
        // （Hilt 基类 + 生命周期），故只能对源文件文本——换成别的类型会立刻红。
        val src = File("src/main/java/com/mzstd/hxmyproxy/service/ProxyForegroundService.kt").readText()
        assertTrue(
            "startForeground 传的前台服务类型与 manifest 声明不一致",
            src.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"),
        )
    }
}
