package com.mzstd.hxmyproxy

import android.app.Application
import com.mzstd.hxmyproxy.core.log.FileLog
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
        FileLog.init(File(filesDir, "logs"))
        // 只记录错误/崩溃，不记常规信息日志（保持日志精简、便于分析）
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            FileLog.e("crash", "Uncaught in thread ${thread.name}", ex)
            previous?.uncaughtException(thread, ex)
        }
    }
}
