package com.mzstd.hxmyproxy.service

import android.content.Context

/**
 * 服务自身的轻量运行状态（**独立于用户设置**）。
 *
 * **为何不放进 DataStore 的 ProxySettings**：`SettingsRepository.update` 会让 settings flow emit，
 * 而该 flow 的下游挂着 `applyTunables` / 规则表重建 / `serverKey` 变化即热重启监听 / `refresh()`（准入更新）。
 * 把「服务是否在共享」这种**每次启停都会写**的状态塞进设置流，等于每次开关共享都向代理引擎插一发状态变更，
 * 与引擎启动过程并发 —— 1.14.4 的局域网客户端连不上就出在这里。改用独立 SharedPreferences：同步读写、
 * 不触发任何 flow、不与引擎生命周期耦合。
 */
object ServiceState {
    private const val PREF = "service_state"
    private const val KEY_WAS_SHARING = "was_sharing"

    /** 记录共享是否处于开启中；开机 / app 更新后 [RestartReceiver] 据此决定是否自动恢复。 */
    fun setWasSharing(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_WAS_SHARING, on).apply()
    }

    fun wasSharing(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_WAS_SHARING, false)
}
