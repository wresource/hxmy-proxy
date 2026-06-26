package com.mzstd.hxmyproxy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.network.MdnsPublisher
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 真机 mDNS / NSD 注册验证（API 37）。单台设备即可硬验证「注册成功」与「可被发现」：
 * 用真 [MdnsPublisher] 发布与 App 相同的服务规格 → 等系统 onServiceRegistered 回调 →
 * 再用 NsdManager 发现本机发布的服务（同机自发现，无需第二设备）。
 *
 * 注：真正的「跨设备发现」需真·同一局域网；两个标准 AVD 各自 NAT 隔离、multicast 不互通，
 * 故此处只验证注册 + 本机可发现性（这已能证明 NSD 发布链路工作）。
 */
@RunWith(AndroidJUnit4::class)
class MdnsRegistrationTest {

    private val tag = "hxmyMdnsTest"
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun grantLocalNetwork() {
        // API 37 起 NSD 注册/发现可能需要 ACCESS_LOCAL_NETWORK（运行时权限）。best-effort 授予；
        // 若为普通权限或环境不支持，runCatching 无害跳过。
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }.onFailure { Log.w(tag, "grant ACCESS_LOCAL_NETWORK skipped: ${it.message}") }
    }

    @Test fun publishRegistersServicesViaNsd() {
        val pub = MdnsPublisher(ctx)
        try {
            // 与 ProxyServerRepository 实际发布的规格一致
            pub.publish(
                listOf(
                    MdnsPublisher.ServiceSpec("hxmy proxy HTTP", "_http._tcp", 18080),
                    MdnsPublisher.ServiceSpec("hxmy proxy SOCKS5", "_socks._tcp", 11080),
                ),
            )
            val ok = waitFor(12_000) { pub.lastRegisteredName != null }
            Log.i(tag, "registered name (系统可能改名) = ${pub.lastRegisteredName}")
            assertTrue("NSD 应成功注册（onServiceRegistered 回调触发）", ok)
        } finally {
            pub.unpublishAll()
        }
    }

    @Test fun publishedHttpServiceIsDiscoverableOnDevice() {
        val nsd = ctx.getSystemService(NsdManager::class.java)
            ?: run { Log.w(tag, "no NsdManager"); return }
        val pub = MdnsPublisher(ctx)
        val found = CopyOnWriteArrayList<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { Log.i(tag, "discovery started $serviceType") }
            override fun onServiceFound(s: NsdServiceInfo) { found.add(s.serviceName); Log.i(tag, "found ${s.serviceName}") }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(tag, "discovery start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            pub.publish(listOf(MdnsPublisher.ServiceSpec("hxmy proxy HTTP", "_http._tcp", 18080)))
            assertTrue("注册回调应先触发", waitFor(12_000) { pub.lastRegisteredName != null })
            val name = pub.lastRegisteredName!!
            nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            val discovered = waitFor(20_000) { found.any { it == name || it.startsWith("hxmy proxy HTTP") } }
            Log.i(tag, "discovery target=$name found=${found.toList()} discovered=$discovered")
            assertTrue("应能发现本机发布的 _http._tcp 服务（found=${found.toList()}）", discovered)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            pub.unpublishAll()
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }
}
