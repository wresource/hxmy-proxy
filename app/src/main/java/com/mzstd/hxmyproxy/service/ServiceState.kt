package com.mzstd.hxmyproxy.service

import android.content.Context

/**
 * 服务自身的轻量运行状态（**独立于用户设置**）。
 *
 * **为何不放进 DataStore 的 ProxySettings**：`SettingsRepository.update` 是**全量 read-modify-write**
 * （一次写会重新序列化 30+ 个 key，含两坨规则 JSON），且会让 settings flow emit 一帧，连带触发
 * `applyTunables` + 规则表重建（65 组 5000+ 域名）+ `refresh()`。「服务是否在共享」是**每次启停都会写**的
 * 状态，走设置流纯属浪费，还把「某个 key 解析失败 → 默认值被写回固化」的风险从「用户改设置时」提前到
 * 「每次开共享」。改用独立 SharedPreferences：同步读写、不触发任何 flow。
 *
 * 注：此改动是卫生改进，**不是**已证实的某个故障的根因修复——`serverKey`（ProxyServerRepository）只由
 * http/socks/pac 的 enabled+port 组成，写这个标记不可能触发监听热重启。
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
