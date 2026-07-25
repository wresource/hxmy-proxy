# hxmy proxy · R8 规则
# 多数库（Compose / Hilt / coroutines / DataStore）自带 consumer rules，这里只补必要项。

# 枚举通过 valueOf() 从 DataStore 恢复（AppLanguage / PerformancePreset / VpnDownStrategy 等）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# kotlinx-coroutines（额外保险）
-dontwarn kotlinx.coroutines.**

# 保留行号便于崩溃栈定位（可选）
-keepattributes SourceFile,LineNumberTable

# release 剥离 logcat 的 v/d/i：正式版不再向 logcat 输出目标域名与客户端 IP
# （HttpProxyServer/Socks5ProxyServer 的规则判定与 ProxyServer 的 accept 都在打这些）。
# 只剥 v/d/i，保留 w/e 便于真机 adb 排障。
# 注意：-assumenosideeffects **只删调用、不删实参求值**，字符串拼接照跑，
# 因此热路径（accept/relay 循环）里禁止写昂贵拼接；也绝不可对 class * 用通配。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-renamesourcefileattribute SourceFile
