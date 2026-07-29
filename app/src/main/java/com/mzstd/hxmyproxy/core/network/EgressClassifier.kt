package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mzstd.hxmyproxy.core.stats.EgressKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把一条上游连接实际绑定的 [Network] 归类成 [EgressKind]，供历史流量统计分出口累加。
 *
 * `network == null` 表示**没绑定**、走系统默认路由 —— 对本进程而言，有 VPN 时默认路由就是那条 VPN，
 * 所以查 `activeNetwork` 即可，不必额外问 VpnStateDetector。
 *
 * **VPN 必须先判**：系统 VPN 的 capabilities 通常同时带 `TRANSPORT_VPN` 和底层物理 transport
 * （隧道跑在 Wi-Fi 上就带 `TRANSPORT_WIFI`），先判物理会把隧道流量记成「Wi-Fi 直连」——而这两者
 * 正是这张统计表最要区分的东西。
 *
 * 默认路由的分类结果缓存 [CACHE_MS]：首屏几十个域名同时建连会连着问几十次 binder，而默认网络
 * 在一秒内不会变。绑定了具体 network 的不缓存（句柄本身就精确，且量少）。
 */
@Singleton
class EgressClassifier @Inject constructor(@ApplicationContext context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var cachedDefault: EgressKind = EgressKind.OTHER
    @Volatile private var cachedAt = 0L

    fun classify(network: Network?): EgressKind {
        if (network != null) return of(cm?.getNetworkCapabilities(network))
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cachedDefault
        val kind = of(runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull())
        cachedDefault = kind
        cachedAt = now
        return kind
    }

    private fun of(caps: NetworkCapabilities?): EgressKind = when {
        caps == null -> EgressKind.OTHER
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> EgressKind.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> EgressKind.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> EgressKind.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> EgressKind.ETHERNET
        else -> EgressKind.OTHER
    }

    private companion object {
        const val CACHE_MS = 1_000L
    }
}
