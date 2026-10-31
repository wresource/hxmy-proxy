package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.data.repository.LocalNetworkProxyPreference
import org.junit.Assert.*
import org.junit.Test

class LocalNetworkProxyPreferenceTest {
    @Test fun newInstallationAndLegacyDefaultAllowRemainOffUntilOptIn() {
        assertTrue(ProxySettings().blockPrivateLanEgress)
        for (legacy in listOf(null, false, true)) {
            assertTrue(LocalNetworkProxyPreference.isBlocked(null, legacy))
            assertTrue(LocalNetworkProxyPreference.isBlocked(false, legacy))
        }
    }

    @Test fun explicitChoiceSurvivesAndOlderDenialCannotBeOverriddenSilently() {
        assertFalse(LocalNetworkProxyPreference.isBlocked(true, false))
        assertFalse(LocalNetworkProxyPreference.isBlocked(true, null))
        assertTrue(LocalNetworkProxyPreference.isBlocked(true, true))
        for (blocked in listOf(true, false, true, false)) {
            assertEquals(blocked, LocalNetworkProxyPreference.isBlocked(!blocked, blocked))
        }
    }
}
