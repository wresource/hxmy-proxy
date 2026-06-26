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
        // 抬高 kotlinx 的 IO 线程池上限（默认 64）：relay 池要 2×relayParallelism（HIGH 档 = 128）个阻塞搬字节线程，
        // 若不抬高，relay 占满 64 会把 acceptDispatcher 的握手线程饿死 → 回退 Stripe 队头阻塞修复。
        // 必须先于任何 Dispatchers.IO 使用（这里是进程最早期，代理引擎要到前台服务才启动）。线程按需创建、空闲回收。
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
