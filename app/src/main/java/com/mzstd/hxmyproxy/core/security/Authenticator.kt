package com.mzstd.hxmyproxy.core.security

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Reject malformed wire bytes rather than silently authenticating them as U+FFFD. */
internal fun decodeCredentialUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) { null }

/**
 * 认证（**可选**，D5）。用于 SOCKS5 RFC1929 用户名/密码与 HTTP Basic。
 * [enabled] 为 false 时所有请求放行（默认）。
 */
interface Authenticator {
    val enabled: Boolean
    fun verify(username: String, password: String): Boolean
}

/** 默认：不启用认证。 */
object NoAuthAuthenticator : Authenticator {
    override val enabled = false
    override fun verify(username: String, password: String) = true
}

/**
 * 单凭据认证（启用时）。凭据由设置层提供（真实存储用 EncryptedSharedPreferences/Keystore，见 Step 4）。
 */
class SingleCredentialAuthenticator(
    username: String = "",
    password: String = "",
    @Volatile override var enabled: Boolean = false,
) : Authenticator {
    private data class Credentials(val username: String, val password: String)
    @Volatile private var credentials = Credentials(username,password)
    var username: String
        get() = credentials.username
        set(value) = synchronized(this) { credentials = credentials.copy(username=value) }
    var password: String
        get() = credentials.password
        set(value) = synchronized(this) { credentials = credentials.copy(password=value) }
    /** The store publishes a pair. Never expose new username + old password between writes. */
    @Synchronized fun updateCredentials(username: String, password: String) {
        credentials = Credentials(username,password)
    }
    /**
     * 凭据未配置（空密码）时一律拒绝（fail-closed）：避免"开了认证但没设密码"时空凭据被放行。
     *
     * 比较用 [MessageDigest.isEqual] 而非 `==`：这是全项目唯一的真安全边界，而 String 的 `==`
     * 一遇到不同字符就返回，比较耗时随"猜对了多少个前缀字符"变化，理论上可被逐字符爆破。
     * 局域网内噪声大、可利用性低，但这是密码比较的标准做法，成本也只是一行。
     *
     * 用户名与密码**都算完再合并**（`and` 而非 `&&`）：短路的话「用户名错」会比「密码错」
     * 提前返回，时间差能区分这两种失败——等于告诉攻击者用户名猜对了没有。
     * 而开头那个空密码判断与外部输入无关，不构成侧信道，保留短路。
     */
    override fun verify(username: String, password: String): Boolean {
        val expected = credentials
        if (expected.password.isEmpty()) return false
        val userOk = MessageDigest.isEqual(username.toByteArray(), expected.username.toByteArray())
        val passOk = MessageDigest.isEqual(password.toByteArray(), expected.password.toByteArray())
        return userOk and passOk
    }
}
