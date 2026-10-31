package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.security.SingleCredentialAuthenticator
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AtomicCredentialUpdateTest {
    @Test(timeout=10000) fun aStorePairUpdateNeverAuthenticatesMixedCredentials() {
        val auth=SingleCredentialAuthenticator("a","first",true)
        val running=AtomicBoolean(true)
        val writer=thread { while(running.get()) { auth.updateCredentials("b","second");auth.updateCredentials("a","first") } }
        try { repeat(20000) {
            assertFalse(auth.verify("a","second"));assertFalse(auth.verify("b","first"))
        } } finally { running.set(false);writer.join(2000);assertFalse(writer.isAlive) }
        auth.updateCredentials("fresh","secret");assertTrue(auth.verify("fresh","secret"))
        auth.updateCredentials("fresh","");assertFalse(auth.verify("fresh",""))
    }
}
