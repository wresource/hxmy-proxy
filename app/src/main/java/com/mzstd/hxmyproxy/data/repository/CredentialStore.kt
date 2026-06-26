package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mzstd.hxmyproxy.core.security.Crypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// 凭据独立于 hxmy_settings：密码以密文持久化，绝不进明文设置快照（D5/D-E）。
private val Context.credentialDataStore by preferencesDataStore(name = "hxmy_credentials")

/**
 * 代理认证凭据存储：用户名明文（非敏感），密码经 [Crypto]（Keystore AES-GCM）加密后存 DataStore。
 * 读出时在内存解密喂给认证器；解密失败（如密钥被系统清除）按空密码处理。
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: Crypto,
) {
    data class Credentials(val username: String = "", val password: String = "")

    val credentials: Flow<Credentials> = context.credentialDataStore.data.map { prefs ->
        val username = prefs[USERNAME] ?: ""
        val password = prefs[PASSWORD_ENC]?.let { crypto.decrypt(it) } ?: ""
        Credentials(username, password)
    }

    /** 更新凭据：空密码清除已存密文（便于"留空=清空"），非空则加密存储。 */
    suspend fun update(username: String, password: String) {
        context.credentialDataStore.edit { prefs ->
            prefs[USERNAME] = username
            if (password.isEmpty()) prefs.remove(PASSWORD_ENC) else prefs[PASSWORD_ENC] = crypto.encrypt(password)
        }
    }

    private companion object {
        val USERNAME = stringPreferencesKey("auth_username")
        val PASSWORD_ENC = stringPreferencesKey("auth_password_enc")
    }
}
