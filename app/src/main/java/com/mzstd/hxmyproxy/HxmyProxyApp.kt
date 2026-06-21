package com.mzstd.hxmyproxy

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 应用入口。注解触发 Hilt 组件树生成，承载全局单例（代理引擎/Repository 等）。
 */
@HiltAndroidApp
class HxmyProxyApp : Application()
