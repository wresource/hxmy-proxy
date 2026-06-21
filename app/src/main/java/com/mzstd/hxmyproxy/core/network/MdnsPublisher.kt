package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * 通过 NSD 发布代理服务（mDNS / `hxmyproxy.local`，D1 便利层）。
 *
 * - 发布 `_http._tcp`（HTTP/CONNECT）、`_socks._tcp`（SOCKS5）、PAC 服务。
 * - `onServiceRegistered` 回读系统可能改写过的实际 serviceName（冲突会被改名，grounded-ref §9）。
 * - 生命周期由前台服务驱动（serve 时 [publish]、stop 时 [unpublishAll]）。
 * - 需 `ACCESS_LOCAL_NETWORK`（targetSdk 37）+ 多播；发布失败不致命。
 */
class MdnsPublisher(context: Context) {

    data class ServiceSpec(val name: String, val type: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    @Volatile var lastRegisteredName: String? = null
        private set

    @Synchronized
    fun publish(specs: List<ServiceSpec>) {
        unpublishAll()
        val manager = nsd ?: return
        for (spec in specs) {
            val info = NsdServiceInfo().apply {
                serviceName = spec.name
                serviceType = spec.type
                port = spec.port
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    lastRegisteredName = serviceInfo.serviceName   // 可能被系统改名
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            runCatching {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(listener)
            }
        }
    }

    @Synchronized
    fun unpublishAll() {
        val manager = nsd ?: return
        for (l in listeners) runCatching { manager.unregisterService(l) }
        listeners.clear()
    }
}
