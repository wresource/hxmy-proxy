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
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 代理前台服务（`foregroundServiceType=connectedDevice`）。承载代理引擎生命周期与常驻通知。
 *
 * Service 自有 `CoroutineScope(SupervisorJob + Dispatchers.IO)`，于 [onDestroy] 取消。
 * （此自定义 Scope 写法见 version-md NEEDS VERIFICATION，对照 coroutines best-practices。）
 */
@AndroidEntryPoint
class ProxyForegroundService : Service() {

    @Inject lateinit var repository: ProxyServerRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

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
            NOTIF_ID, buildNotification("正在启动…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!started) {
            started = true
            scope.launch { repository.start(scope) }
            scope.launch { repository.state.collect { updateNotification(it) } }
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

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "代理服务", NotificationManager.IMPORTANCE_LOW).apply {
            description = "hxmy proxy 前台服务运行状态"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("hxmy proxy")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "停止共享", stopIntent)
            .build()
    }

    private fun updateNotification(state: ShareState) {
        val entry = state.recommendedEntries.firstOrNull { it.protocol == ProxyProtocol.SOCKS5 }
            ?: state.recommendedEntries.firstOrNull()
        val text = when {
            !state.running -> "未运行"
            entry == null -> "运行中（未选择入口）"
            else -> "推荐 ${entry.ipEndpoint} · 客户端 ${state.clients.size}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
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
