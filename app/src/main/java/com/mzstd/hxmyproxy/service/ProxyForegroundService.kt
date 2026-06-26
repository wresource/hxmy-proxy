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
            shutdown()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID,
            buildNotification(localized().getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!started) {
            started = true
            scope.launch { repository.start(scope) }
            scope.launch { repository.state.collect { lastState = it; pushNotification() } }
            scope.launch { settingsRepository.settings.collect { language = it.language; pushNotification() } }
        }
        return START_STICKY
    }

    private fun shutdown() {
        repository.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
        val entry = state.recommendedEntries.firstOrNull { it.protocol == ProxyProtocol.SOCKS5 }
            ?: state.recommendedEntries.firstOrNull()
        return when {
            !state.running -> loc.getString(R.string.notif_stopped)
            entry == null -> loc.getString(R.string.notif_running_no_entry)
            else -> loc.getString(
                R.string.notif_running,
                entry.ipEndpoint,
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
            // 品牌珊瑚色 accent：Android 12+ 状态栏单色图标被 tint 成此色；通知抽屉里小图标/标题也着色。
            .setColor(0xFFFF7A59.toInt())
            // 前台服务通知：整条通知背景染品牌珊瑚色（系统自动处理文字对比度），品牌感最强。
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
