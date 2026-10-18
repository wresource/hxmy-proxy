package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.run.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ConnectionPresentationTest {
    private fun iface(ip: String) = ShareInterface("wlan0/$ip", "wlan0", InterfaceType.WIFI, InetAddress.getByName(ip),
        if (':' in ip) 64 else 24, false, true, InterfaceStatus.UP)
    private fun state(vararg addresses: String): MainUiState {
        val interfaces = addresses.map(::iface)
        return MainUiState(share = ShareState(running = true, admissionEmpty = false, interfaces = interfaces,
            recommendedEntries = interfaces.flatMap { i -> listOf(ProxyEntry(i.address.hostAddress!!, 8080, ProxyProtocol.HTTP, i.id), ProxyEntry(i.address.hostAddress!!, 1080, ProxyProtocol.SOCKS5, i.id), ProxyEntry(i.address.hostAddress!!, 8899, ProxyProtocol.PAC, i.id)) },
            diagnostics = DiagnosticsSummary(httpPortUp = true, socksPortUp = true, pacPortUp = true, httpBoundPort = 8080, socksBoundPort = 1080, pacBoundPort = 8899)),
            settings = ProxySettings(selectedInterfaceIds = interfaces.map { it.id }.toSet()))
    }
    @Test fun hiddenIpv6NeverRemovesTheOnlyAvailableFamilyOrChangesAdmission() {
        val mixed = state("192.0.2.1", "2001:db8::1")
        assertEquals(listOf("192.0.2.1"), connectionHosts(mixed).map { it.host })
        assertEquals(2, mixed.settings.selectedInterfaceIds.size)
        assertTrue(connectionHosts(state("2001:db8::1")).single().host.contains(':'))
    }
    @Test fun removingChosenInterfaceCannotAdvertiseItsOldAddress() {
        val before = state("192.0.2.1", "192.0.2.2")
        val after = before.copy(settings = before.settings.copy(selectedInterfaceIds = setOf("wlan0/192.0.2.2")))
        assertEquals("192.0.2.2", selectedConnectionHost(after, "192.0.2.1")?.host)
        assertNull(selectedConnectionHost(after.copy(settings = after.settings.copy(selectedInterfaceIds = emptySet())), "192.0.2.1"))
    }
    @Test fun listenersAreIndependentAndSavedPortsAreNotClaimedToBeListening() {
        val normal = state("192.0.2.1")
        val conflict = normal.copy(share = normal.share.copy(portBindErrors = setOf(ProxyProtocol.HTTP)))
        assertEquals(ListenerPresentation.PORT_BUSY, listenerPresentation(conflict, ProxyProtocol.HTTP, connectionHosts(conflict).first()))
        assertEquals(ListenerPresentation.LISTENING, listenerPresentation(conflict, ProxyProtocol.SOCKS5, connectionHosts(conflict).first()))
        assertEquals(ListenerPresentation.STOPPED, listenerPresentation(normal.copy(share = normal.share.copy(running = false)), ProxyProtocol.HTTP, connectionHosts(normal).first()))
        assertEquals(ListenerPresentation.UNAVAILABLE, listenerPresentation(normal.copy(share = normal.share.copy(diagnostics = DiagnosticsSummary())), ProxyProtocol.HTTP, connectionHosts(normal).first()))
        assertEquals(ListenerPresentation.NO_ADDRESS, listenerPresentation(normal, ProxyProtocol.HTTP, null))
    }
    @Test fun qrUsesChosenIpCurrentPacPortAndBracketsIpv6() {
        val pending = state("192.0.2.1", "2001:db8::1").let { it.copy(settings = it.settings.copy(showIpv6 = true, pacPort = 9002)) }
        assertNull(connectionSetupUrl(pending))
        val ui = pending.copy(share = pending.share.copy(diagnostics = pending.share.diagnostics.copy(pacBoundPort = 9002),
            recommendedEntries = pending.share.recommendedEntries.map { if (it.protocol == ProxyProtocol.PAC) it.copy(port = 9002) else it }))
        val v6 = connectionHosts(ui).last().host
        assertEquals("http://[$v6]:9002/", connectionSetupUrl(ui, v6))
        assertEquals("http://192.0.2.1:9002/", connectionSetupUrl(ui, "192.0.2.1"))
        assertNull(connectionSetupUrl(ui.copy(settings = ui.settings.copy(pacEnabled = false))))
        assertNull(connectionSetupUrl(ui.copy(share = ui.share.copy(portBindErrors = setOf(ProxyProtocol.PAC)))))
    }
    @Test fun disabledProtocolKeepsItsConfiguredPortWithoutAnActiveCopyTarget() {
        val ui = state("192.0.2.1").let { it.copy(settings = it.settings.copy(httpEnabled = false, httpPort = 9080)) }
        assertEquals(9080, connectionEntry(ui, connectionHosts(ui).single(), ProxyProtocol.HTTP).port)
        assertEquals(ListenerPresentation.OFF, listenerPresentation(ui, ProxyProtocol.HTTP, connectionHosts(ui).first()))
    }
    @Test fun exportedIpv6EndpointDoesNotLeakAnAndroidOnlyZoneName() {
        val entry = ProxyEntry("2001:db8::1%wlan0", 8899, ProxyProtocol.PAC, "wifi")
        assertEquals("http://[2001:db8::1]:8899/proxy.pac", entry.copyValue)
        assertEquals("[2001:db8::1]:8080", ProxyEntry("[2001:db8::1]", 8080, ProxyProtocol.HTTP, "wifi").ipEndpoint)
    }
    @Test fun disappearedSelectionKeepsRuntimeFallbackButExplicitEmptySelectionDeniesAll() {
        val original = state("192.0.2.1", "192.0.2.2")
        val moved = original.copy(settings = original.settings.copy(selectedInterfaceIds = setOf("old-wifi")))
        assertEquals(2, connectionHosts(moved).size)
        assertTrue(connectionHosts(moved.copy(settings = moved.settings.copy(selectedInterfaceIds = emptySet()))).isEmpty())
    }
    @Test fun onlySelectedIpv6IsNotHiddenByAnUnselectedIpv4Interface() {
        val original = state("192.0.2.1", "2001:db8::1")
        val onlyV6 = original.copy(settings = original.settings.copy(selectedInterfaceIds = setOf("wlan0/2001:db8::1")))
        assertTrue(connectionHosts(onlyV6).single().host.contains(':'))
    }
    @Test fun desiredPortIsNotCopyableUntilActualListenerAndAdvertisedEndpointMatch() {
        val original = state("192.0.2.1")
        val pending = original.copy(settings = original.settings.copy(httpPort = 9080))
        assertEquals(ListenerPresentation.UPDATING, listenerPresentation(pending, ProxyProtocol.HTTP, connectionHosts(pending).first()))
        val ready = pending.copy(share = pending.share.copy(diagnostics = pending.share.diagnostics.copy(httpBoundPort = 9080),
            recommendedEntries = pending.share.recommendedEntries.map { if (it.protocol == ProxyProtocol.HTTP) it.copy(port = 9080) else it }))
        assertEquals(ListenerPresentation.LISTENING, listenerPresentation(ready, ProxyProtocol.HTTP, connectionHosts(ready).first()))
    }
}
