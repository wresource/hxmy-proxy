package com.mzstd.hxmyproxy.core.log

import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/** 一次进程实例的身份；PID会复用，故同时保留随机session与启动时间。仅本地诊断。 */
object ProcessDiagnostics {
    data class Session(
        val pid: Int,
        val uid: Int,
        val processName: String,
        val startedAt: Long,
        val elapsedAtStart: Long,
        val versionCode: Long?,
        val id: String = UUID.randomUUID().toString(),
    ) {
        fun context() = "sid=$id pid=$pid uid=$uid process=$processName startedAt=$startedAt v=$versionCode"
    }

    @Volatile var current: Session? = null
        private set

    fun install(session: Session) {
        current = session
        Thread.setDefaultUncaughtExceptionHandler(handler(Thread.getDefaultUncaughtExceptionHandler(), session))
    }

    internal fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        session: Session,
        record: (String, Throwable, String) -> Unit = FileLog::crash,
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            record(thread.name, error, "${session.context()} fingerprint=${fingerprint(error)}")
        } catch (_: Throwable) {
            // 日志/哈希失败（包括内存不足）不能阻断系统原来的终止与上报。
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    /** 消息含域名等动态值，不参与指纹；类型/栈/原因链保留，完整异常仍另行落盘。 */
    internal fun fingerprint(error: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val parts = ArrayList<String>()
        var cause: Throwable? = error
        while (cause != null && seen.size < 16 && seen.add(cause)) {
            parts += cause.javaClass.name
            parts += cause.stackTrace.take(16).map { it.toString() }
            cause = cause.cause
        }
        return evidenceHash(parts)
    }
}

/** 长度前缀避免分隔符出现在字段中造成同一输入串；仅用于稳定关联，不是权限凭据。 */
internal fun evidenceHash(fields: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
