package com.mzstd.hxmyproxy.core.security

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
    @Volatile var username: String = "",
    @Volatile var password: String = "",
    @Volatile override var enabled: Boolean = false,
) : Authenticator {
    // 凭据未配置（空密码）时一律拒绝（fail-closed）：避免"开了认证但没设密码"时空凭据被放行。
    override fun verify(username: String, password: String): Boolean =
        this.password.isNotEmpty() && username == this.username && password == this.password
}
