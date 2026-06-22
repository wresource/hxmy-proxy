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
        super.onCreate()
        FileLog.init(File(filesDir, "logs"))
        FileLog.i("app", "app start")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            FileLog.e("crash", "Uncaught in thread ${thread.name}", ex)
            previous?.uncaughtException(thread, ex)
        }
    }
}
