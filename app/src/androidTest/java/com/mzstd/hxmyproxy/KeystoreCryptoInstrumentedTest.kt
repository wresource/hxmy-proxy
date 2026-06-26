package com.mzstd.hxmyproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.security.KeystoreCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试（模拟器）：真 AndroidKeyStore AES-GCM 加解密往返、密文不等于明文、损坏数据安全返回 null。
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoInstrumentedTest {

    private val crypto = KeystoreCrypto()

    @Test
    fun encryptDecryptRoundTrip() {
        val secret = "s3cr3t-密码-🔐"
        val cipher = crypto.encrypt(secret)
        assertNotEquals("密文不应等于明文", secret, cipher)
        assertEquals(secret, crypto.decrypt(cipher))
    }

    @Test
    fun encryptUsesFreshIvEachTime() {
        // GCM 每次随机 IV → 同一明文两次密文应不同（防止重放/模式分析）。
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
    }

    @Test
    fun decryptGarbageReturnsNull() {
        assertNull(crypto.decrypt("not-valid-base64-$$"))
        assertNull(crypto.decrypt(""))
    }
}
