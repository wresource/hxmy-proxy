package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * 系统健康探针：通知开关与电池优化白名单——共享「间断」的两大常见根源，供诊断展示与引导修复。
 */
class PermissionProbe(private val context: Context) {

    /** 通知是否可用（含用户手动关掉 app 通知的情况，比只查 POST_NOTIFICATIONS 权限更准）。 */
    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 是否已在电池优化白名单（「无限制」）。false = 系统可能在后台冻结 app 造成共享间断。 */
    fun batteryUnrestricted(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
