package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `ACCESS_LOCAL_NETWORK`（Android 17 / targetSdk 37 起强制）的硬门管理。
 *
 * 该权限在运行设备为 **Android 17（SDK 37）及以上**时才强制；更低版本上本地网络访问为
 * opt-in/开放，等价于已授权。所有判断按 [Build.VERSION.SDK_INT] 守卫，使 App 在 10–16 上也正确。
 */
class LocalNetworkPermissionManager(private val context: Context) {

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val ANDROID_17 = 37
    }

    /** 当前运行设备是否会强制本地网络权限。 */
    fun isEnforcedOnThisDevice(): Boolean = Build.VERSION.SDK_INT >= ANDROID_17

    /** 是否允许本地网络访问（含未强制场景=true）。 */
    fun isGranted(): Boolean {
        if (!isEnforcedOnThisDevice()) return true
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /** 是否应进入硬门拦截（强制且未授权）。 */
    fun isBlocking(): Boolean = isEnforcedOnThisDevice() && !isGranted()
}
