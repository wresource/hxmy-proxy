package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.mzstd.hxmyproxy.core.log.FileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通过 NSD 发布代理服务（mDNS / `hxmyproxy.local`，D1 便利层）。
 *
 * - 发布 `_http._tcp`（HTTP/CONNECT）、`_socks._tcp`（SOCKS5）、PAC 服务。
 * - `onServiceRegistered` 回读系统可能改写过的实际 serviceName（冲突会被改名，grounded-ref §9），
 *   并更新 [registeredName]——注册是**异步**的（系统要先 Probing ~1s），故用 StateFlow 让上层在
 *   注册真正完成后刷新诊断状态，否则诊断会停在 publish 那一刻的「未发布」假象。
 * - **幂等**：相同 specs 不反复重发（避免频繁 refresh 触发 NSD Add/Remove 抖动）。
 * - **[republish]**：WiFi 切换/IP 变化时强制重注册——NsdManager 不会自动跟随网络变化在新 IP 上通告
 *   （Android 官方把重注册责任留给开发者），否则 `hxmyproxy.local` 切网后可能仍指向旧 IP。
 * - 前台期间持 [MulticastLock]，保证 mDNS 多播不被省电策略掐断（`CHANGE_WIFI_MULTICAST_STATE`）。
 * - 需 `ACCESS_LOCAL_NETWORK`（targetSdk 37）+ 多播。
 */
class MdnsPublisher(context: Context) {

    data class ServiceSpec(val name: String, val type: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    private var lastSpecs: List<ServiceSpec>? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 已注册的服务名（系统可能改名）；null = 未注册/失败。**异步注册完成后才置位** → 上层据此刷新诊断。 */
    private val _registeredName = MutableStateFlow<String?>(null)
    val registeredName: StateFlow<String?> = _registeredName.asStateFlow()

    /** 兼容旧读法（同 [registeredName] 当前值）。 */
    val lastRegisteredName: String? get() = _registeredName.value

    @Synchronized
    fun publish(specs: List<ServiceSpec>) {
        // 幂等：相同 specs 且已在注册 → 不重发，避免频繁 refresh 引起 NSD Add/Remove 抖动。
        if (specs == lastSpecs && listeners.isNotEmpty()) return
        acquireMulticastLock()
        unregisterListeners()
        lastSpecs = specs
        val manager = nsd ?: return
        for (spec in specs) {
            val info = NsdServiceInfo().apply {
                serviceName = spec.name
                serviceType = spec.type
                port = spec.port
            }
            val listener = registrationListener()
            runCatching {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(listener)
            }
        }
    }

    /**
     * 强制重发（绕过幂等）：WiFi 切换 / IP 变化后，specs（端口/协议）不变但必须重新注册，才能在新接口上
     * 以新 IP 重新通告 A 记录——NsdManager 不会自动跟随网络变化（Android 官方把重注册责任留给开发者）。
     * 不释放 MulticastLock（仍在前台共享中）。
     */
    @Synchronized
    fun republish() {
        val specs = lastSpecs ?: return        // 没发布过则无需重发
        unregisterListeners()                  // 清 lastSpecs=null
        publish(specs)                         // lastSpecs 已 null → 不早返回 → 在新 IP 上重新注册
    }

    @Synchronized
    fun unpublishAll() {
        unregisterListeners()
        releaseMulticastLock()
    }

    /** 仅注销服务（不动 MulticastLock）；[republish] 的中间态用。 */
    private fun unregisterListeners() {
        val manager = nsd ?: return
        for (l in listeners) runCatching { manager.unregisterService(l) }
        listeners.clear()
        lastSpecs = null
        _registeredName.value = null
    }

    private fun registrationListener() = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // 异步注册完成（Probing 通过）→ 置位实际名，触发上层刷新 mdnsPublished。
            _registeredName.value = serviceInfo.serviceName
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // 不再静默：记录 errorCode 便于定位（如 0=INTERNAL、3=ALREADY_ACTIVE、4=MAX_LIMIT）。
            FileLog.w(TAG, "mDNS 注册失败 ${serviceInfo.serviceName} (${serviceInfo.serviceType}): errorCode=$errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    /** 前台共享期间持多播锁，避免 mDNS 通告/查询被 WiFi 省电策略掐断。非引用计数，acquire 幂等。 */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            wifi?.createMulticastLock("hxmy-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    private companion object {
        const val TAG = "hxmyproxy"
    }
}
