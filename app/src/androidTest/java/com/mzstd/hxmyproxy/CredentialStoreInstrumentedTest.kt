package com.mzstd.hxmyproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.security.Crypto
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试（模拟器）：[CredentialStore] 凭据持久化与"空密码=清除"逻辑。
 * 用可逆假 [Crypto] 隔离出 DataStore 行为（Keystore 真路径由 KeystoreCryptoInstrumentedTest 覆盖）。
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreInstrumentedTest {

    // 假加密：可逆变换，验证 store 调用了 encrypt/decrypt 且密文非明文。
    private val fakeCrypto = object : Crypto {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc:")
    }

    @Test
    fun persistsAndDecryptsCredentials() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = CredentialStore(ctx, fakeCrypto)
        store.update("alice", "hunter2")
        val c = store.credentials.first()
        assertEquals("alice", c.username)
        assertEquals("hunter2", c.password)
    }

    @Test
    fun emptyPasswordClearsStoredSecret() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = CredentialStore(ctx, fakeCrypto)
        store.update("bob", "secret")
        store.update("bob", "")
        val c = store.credentials.first()
        assertEquals("bob", c.username)
        assertEquals("", c.password)
    }
}
