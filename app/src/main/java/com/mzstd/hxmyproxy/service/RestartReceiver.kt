package com.mzstd.hxmyproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mzstd.hxmyproxy.core.log.FileLog

/**
 * 开机 / app 更新后**自动恢复共享**——仅当上次确实在共享中（[ServiceState.wasSharing]）才拉起服务，
 * 用户主动停过就不会擅自开启。
 *
 * **为何必需**：更新 app 会杀掉进程，而 `START_STICKY` 只覆盖「被系统杀」这一种情况——**装新版后没有任何
 * 机制把服务拉回来**，必须用户手动进 app 重开。这段空窗里客户端连不上监听端口，浏览器（Chrome）会把该
 * 代理标记为 bad proxy 并退避 5 分钟（其状态在浏览器进程内，app 无法清除），表现为「明明重新开了代理却
 * 还是上不了网」。自动恢复把空窗压到只剩系统安装耗时。
 *
 * **状态读取走 [ServiceState]（SharedPreferences）而非 DataStore 设置**：同步读、无需 Hilt 注入与 goAsync，
 * 且不会触碰 settings flow（详见 [ServiceState] 的注释——1.14.4 的回归正出在那里）。
 *
 * **官方依据**：`ACTION_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED` 均在「允许从后台启动前台服务」的
 * 豁免清单内；且 Android 15 对「BOOT_COMPLETED 启动 FGS」的类型限制清单为
 * dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone，**不含本服务的 connectedDevice**。
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ServiceState.wasSharing(context)) return
        runCatching {
            FileLog.w(TAG, "auto-restore sharing after $action")
            ProxyForegroundService.start(context)
        }.onFailure { FileLog.w(TAG, "auto-restore failed after $action", it) }
    }

    private companion object {
        const val TAG = "hxmyproxy"
    }
}
