package com.mzstd.hxmyproxy

import android.app.Application
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/**
 * Hilt 应用入口。注解触发 Hilt 组件树生成，承载全局单例（代理引擎/Repository 等）。
 * 同时初始化持久化日志 [FileLog] 并安装全局未捕获异常处理器（崩溃落盘，便于导出分析）。
 */
@HiltAndroidApp
class HxmyProxyApp : Application() {
    override fun onCreate() {
        // 限定「直接派到 Dispatchers.IO」的阻塞任务上限（默认 64）：仅约束 OutboundConnector 的阻塞 connect /
        // Happy Eyeballs 扇出 / 出口探活等。accept 握手与 relay 搬字节已各自走独立的有界线程池
        // （见 ProxyServerRepository.startServers），不在此池内、也不受此值约束。
        // 关键：Dispatchers.IO.limitedParallelism(N) 是弹性视图、同样不受此值钳制（峰值会叠加到近无界），故本工程
        // 已弃用该视图、改用 newFixedThreadPool 硬限线程，杜绝用户「拉满」时线程爆炸 → native OOM 崩溃。
        // 必须先于任何 Dispatchers.IO 使用（进程最早期，代理引擎到前台服务才启动）。
        System.setProperty("kotlinx.coroutines.io.parallelism", "192")
        super.onCreate()
        // 日志放 noBackupFilesDir：该目录被 Android Auto Backup **明确排除**，绝不上云。
        // 此前放在 filesDir/logs（Auto Backup 默认包含范围），含用户访问域名的 app.log 会被备份到
        // Google 云 —— 与隐私政策「完全本地、不上云」冲突。同时清掉旧位置的残留，杜绝它继续被备份。
        FileLog.init(File(noBackupFilesDir, "logs"))
        runCatching { File(filesDir, "logs").deleteRecursively() }
        // 进程边界标记：此前只能靠「egress -> N (was null) 且无对应 lost」**推断** app 重启过，
        // 排障时无法区分「进程重启」与「网络句柄变化」，也无法按会话切分跨天的日志。
        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            Ev.k(
                LogCat.PROC, "session.start",
                "ver" to pi.versionName,
                "code" to pi.longVersionCode,
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "dev" to "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}",
                "pid" to android.os.Process.myPid(),
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            FileLog.e("crash", "Uncaught in thread ${thread.name}", ex)
            FileLog.flush()   // 常驻 BufferedWriter：崩溃前必须强制落盘，否则最关键的那几行会丢
            previous?.uncaughtException(thread, ex)
        }
    }
}
