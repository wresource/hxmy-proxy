package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mzstd.hxmyproxy.core.model.VpnState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 观察「默认网络」的变化（含 VPN 接入/断开）。
 * 暴露 [vpnState]（当前出口 VPN 状态）与 [networkChanges]（任意变化的去抖信号，用于触发接口重扫）。
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _vpnState = MutableStateFlow(VpnState())
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _networkChanges = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val networkChanges: SharedFlow<Unit> = _networkChanges.asSharedFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkChanges.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            _vpnState.value = VpnState()
            _networkChanges.tryEmit(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _vpnState.value = VpnStateDetector.from(caps)
            _networkChanges.tryEmit(Unit)
        }
    }

    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }
}
