package com.mzstd.hxmyproxy.ui.run

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.effectiveShareInterfaces
import com.mzstd.hxmyproxy.core.model.visibleUnderIpv6Pref
import com.mzstd.hxmyproxy.ui.MainUiState

internal enum class ListenerPresentation { OFF, STOPPED, STARTING, NO_ADDRESS, PORT_BUSY, UPDATING, UNAVAILABLE, LISTENING }

internal data class ConnectionHost(val host: String, val interfaceId: String, val label: String)

/** Display preference must never alter admission. All-IPv6 fallback lives in visibleInterfaces. */
internal fun connectionHosts(ui: MainUiState): List<ConnectionHost> = visibleUnderIpv6Pref(
    effectiveShareInterfaces(ui.share.interfaces, ui.settings.selectedInterfaceIds), ui.settings.showIpv6,
    com.mzstd.hxmyproxy.core.model.ShareInterface::isIpv6)
    .mapNotNull { iface -> iface.address.hostAddress?.let { ConnectionHost(it.substringBefore('%'), iface.id, iface.name) } }
    .distinctBy { it.host }
    .sortedBy { ':' in it.host }

internal fun selectedConnectionHost(ui: MainUiState, selected: String?): ConnectionHost? {
    val hosts = connectionHosts(ui)
    return hosts.firstOrNull { it.host == selected } ?: hosts.firstOrNull()
}

internal fun connectionEntry(ui: MainUiState, host: ConnectionHost, protocol: ProxyProtocol): ProxyEntry =
    ProxyEntry(host.host, when (protocol) {
        ProxyProtocol.HTTP -> ui.settings.httpPort
        ProxyProtocol.SOCKS5 -> ui.settings.socksPort
        ProxyProtocol.PAC -> ui.settings.pacPort
    }, protocol, host.interfaceId)

internal fun listenerPresentation(ui: MainUiState, protocol: ProxyProtocol, host: ConnectionHost?): ListenerPresentation {
    val enabled = when (protocol) {
        ProxyProtocol.HTTP -> ui.settings.httpEnabled
        ProxyProtocol.SOCKS5 -> ui.settings.socksEnabled
        ProxyProtocol.PAC -> ui.settings.pacEnabled
    }
    if (!enabled) return ListenerPresentation.OFF
    if (ui.share.starting) return ListenerPresentation.STARTING
    if (!ui.share.running) return ListenerPresentation.STOPPED
    if (host == null || ui.share.admissionEmpty) return ListenerPresentation.NO_ADDRESS
    if (protocol in ui.share.portBindErrors) return ListenerPresentation.PORT_BUSY
    val port = when (protocol) {
        ProxyProtocol.HTTP -> ui.share.diagnostics.httpBoundPort
        ProxyProtocol.SOCKS5 -> ui.share.diagnostics.socksBoundPort
        ProxyProtocol.PAC -> ui.share.diagnostics.pacBoundPort
    }
    if (port == null) return ListenerPresentation.UNAVAILABLE
    val expected = connectionEntry(ui, host, protocol)
    if (port != expected.port || ui.share.recommendedEntries.none {
        it.host.substringBefore('%') == host.host && it.protocol == protocol && it.port == port
    }) return ListenerPresentation.UPDATING
    return ListenerPresentation.LISTENING
}

/** The real PAC server serves its setup page at /, not the prototype's demo /setup route. */
internal fun connectionSetupUrl(ui: MainUiState, selected: String? = null): String? {
    val host = selectedConnectionHost(ui, selected) ?: return null
    if (listenerPresentation(ui, ProxyProtocol.PAC, host) != ListenerPresentation.LISTENING) return null
    return "http://${connectionEntry(ui, host, ProxyProtocol.PAC).ipEndpoint}/"
}
