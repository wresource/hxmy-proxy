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
-renamesourcefileattribute SourceFile
