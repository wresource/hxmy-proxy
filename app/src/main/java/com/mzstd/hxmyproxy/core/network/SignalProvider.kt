package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** 当前上行 Wi-Fi 信号。[level] 0..4；-1 表示无 Wi-Fi（蜂窝/以太/未连）。 */
data class SignalInfo(val level: Int = -1, val dbm: Int = 0)

/**
 * 读取设备当前活动网络的 Wi-Fi 信号强度（仅 ACCESS_WIFI_STATE，无需定位）。
 * 优先用 [NetworkCapabilities.getSignalStrength]（API 29+），不可用时退回 [WifiManager] RSSI。
 */
class SignalProvider(private val context: Context) {

    fun current(): SignalInfo = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            SignalInfo()
        } else {
            val capDbm = caps.signalStrength // 未知为 Int.MIN_VALUE
            val dbm = if (capDbm != Int.MIN_VALUE) {
                capDbm
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
                    ?: Int.MIN_VALUE
            }
            if (dbm == Int.MIN_VALUE || dbm == 0) SignalInfo() else SignalInfo(levelFor(dbm), dbm)
        }
    } catch (e: Throwable) {
        SignalInfo()
    }

    private fun levelFor(dbm: Int): Int = when {
        dbm >= -55 -> 4
        dbm >= -65 -> 3
        dbm >= -75 -> 2
        dbm >= -85 -> 1
        else -> 0
    }
}
