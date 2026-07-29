package com.mzstd.hxmyproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mzstd.hxmyproxy.MainActivity
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.ui.locale.localizedFor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 代理前台服务（`foregroundServiceType=connectedDevice`）。承载引擎生命周期与常驻通知。
 * 通知文案随所选语言本地化（用 locale-wrapped Context 取字符串，D-D）。
 */
@AndroidEntryPoint
class ProxyForegroundService : Service() {

    @Inject lateinit var repository: ProxyServerRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    @Volatile private var language = AppLanguage.SYSTEM
    @Volatile private var lastState = ShareState()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Ev.k(LogCat.SVC, "fgs.stop", "by" to "user", "startId" to startId)
            shutdown(startId)
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID,
            buildNotification(localized().getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        // 收集器只随服务实例启动一次（重复 collect 会叠加通知推送）。
        if (!started) {
            started = true
            scope.launch { repository.state.collect { lastState = it; pushNotification() } }
            scope.launch { settingsRepository.settings.collect { language = it.language; pushNotification() } }
        }
        // 引擎是否启动**看引擎自己的真实状态**，不只看 started 标志：标志是服务级的，曾经出过残留
        // （stopSelf→onDestroy 窗口），一旦残留就会整段跳过 repository.start()，连带跳过其中的
        // 计量归零 —— 表现为「重开了共享，流量却把上一次的接着算」。start() 内有 `if (running) return`
        // 保幂等，真在跑时这里不会重复启动、更不会误清零。
        if (!repository.isRunning()) {
            // intent == null ⇒ 系统按 START_STICKY 重建服务，即**此前被系统杀过**——这是「服务意外停止」
            // 这类故障唯一的直接证据（此前整个文件零落盘，只能靠旁证猜）。
            Ev.k(LogCat.SVC, "fgs.start", "restart" to (intent == null), "startId" to startId, "flags" to flags)
            // 记「共享中」：开机 / app 更新后由 RestartReceiver 据此自动恢复。**同步写独立 SharedPreferences**，
            // 绝不走 settings flow —— 那会触发 applyTunables/规则重建/serverKey 热重启/refresh，与引擎启动并发。
            ServiceState.setWasSharing(this, true)
            scope.launch { repository.start(scope) }
        }
        return START_STICKY
    }

    private fun shutdown(startId: Int) {
        // 必须复位（曾漏）：stopSelf 到 onDestroy 之间存在窗口，期间用户再点「开始」会因 started
        // 仍为 true 而跳过 repository.start()——startForeground 已执行、通知在、UI 却停在未共享，
        // 正是「开关共享开关也没用」的一种真实成因。
        started = false
        // 用户**主动**停止 → 清标记，开机/更新后不再自动恢复（绝不擅自开启共享）。同步写，不留悬挂协程。
        ServiceState.setWasSharing(this, false)
        repository.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 带 startId：若停止请求之后已有新的 start 送达（更大的 startId），本次销毁请求自动作废，
        // 新会话不会被这个排队中的旧停止请求杀掉。无参 stopSelf() 等同 stopService，会无视后到的 start。
        stopSelf(startId)
    }

    override fun onDestroy() {
        started = false   // 防御：系统直接销毁（未走 shutdown）时同样复位
        Ev.k(LogCat.SVC, "fgs.destroy")
        FileLog.flush()   // 常驻 BufferedWriter：服务销毁前把缓冲落盘，否则末尾几行会丢
        repository.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun localized() = localizedFor(language)

    private fun pushNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(statusText()))
    }

    private fun statusText(): String {
        val loc = localized()
        val state = lastState
        // 通知栏优先展示 HTTP 入口（最通用、客户端配置最简单），其次 SOCKS5、PAC;都没有再取列表首个兜底。
        val entry = listOf(ProxyProtocol.HTTP, ProxyProtocol.SOCKS5, ProxyProtocol.PAC)
            .firstNotNullOfOrNull { p -> state.recommendedEntries.firstOrNull { it.protocol == p } }
            ?: state.recommendedEntries.firstOrNull()
        return when {
            !state.running -> loc.getString(R.string.notif_stopped)
            // 失联告警借用常驻通知（不新弹）：客户端曾在、现探测不可达且无真实入站——
            // 用户不用等自己发现断网,瞥一眼通知即知,点开 app 可手动刷新/看指引。
            state.unreachableClients.isNotEmpty() ->
                loc.getString(R.string.notif_client_unreachable, state.unreachableClients.joinToString(", "))
            entry == null -> loc.getString(R.string.notif_running_no_entry)
            else -> loc.getString(
                R.string.notif_running,
                // displayEndpoint:HTTP/SOCKS 仍是 host:port,PAC 给完整 http://ip:port/proxy.pac,通知里也不误导。
                entry.displayEndpoint,
                state.activeConnections,
                com.mzstd.hxmyproxy.ui.formatRate(state.downloadRateBps),
                com.mzstd.hxmyproxy.ui.formatRate(state.uploadRateBps),
            )
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, localized().getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val loc = localized()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // 只设小图标（品牌单色剪影，显示在通知左侧，与其它 App 一致）。
        // 不设大图标——否则满色图标跑到右侧，左侧反而只剩不显眼的剪影。
        // 平台约束：通知小图标必须单色，系统会着色，无法放满色 App 图标。
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hxmy)
            // 品牌蓝 accent：Android 12+ 状态栏单色图标被 tint 成此色；通知抽屉里小图标/标题也着色。
            .setColor(com.mzstd.hxmyproxy.ui.theme.BRAND_BLUE_ARGB)
            // 前台服务通知：整条通知背景染品牌蓝（系统自动处理文字对比度），品牌感最强。
            .setColorized(true)
            .setContentTitle(loc.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, loc.getString(R.string.notif_stop), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.mzstd.hxmyproxy.action.STOP"
        private const val CHANNEL_ID = "proxy_service"
        private const val NOTIF_ID = 1001

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, ProxyForegroundService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
