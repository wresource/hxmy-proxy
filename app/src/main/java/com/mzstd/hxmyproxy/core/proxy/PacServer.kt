package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.security.AccessController
import kotlinx.coroutines.CoroutineDispatcher
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder

/**
 * 极简 HTTP 服务，承载三条路由：
 * - `GET /proxy.pac` — PAC（MIME `application/x-ns-proxy-autoconfig`），内容由 [pacProvider] 动态生成。
 * - `GET /`、`/setup` — 扫码配置落地页（按 User-Agent 分发各端引导，见 [SetupPageGenerator]）。
 * - `GET /hxmy.mobileconfig?ssid=…` — Apple 配置描述文件（为指定 Wi-Fi 设自动代理）。
 *
 * 落地页/描述文件里引用的回链「基址」直接取**本次连接的本机地址**（扫码设备正是连到它），
 * 故多接口下也总是给出可达地址；无需额外注入 base provider。
 */
class PacServer(
    ioDispatcher: CoroutineDispatcher,
    accessController: AccessController,
    registry: ConnectionRegistry,
    private val pacProvider: () -> String,
) : TcpProxyServerBase(ProxyProtocol.PAC, ioDispatcher, accessController, registry) {

    override suspend fun handle(client: Socket, tracker: TrafficAccounting.ConnTracker?) {
        client.soTimeout = ProxyTuning.HANDSHAKE_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readAsciiLine(input) ?: return
        // 读请求头：丢弃其余，仅留 User-Agent（落地页按平台分发用）。
        var userAgent = ""
        while (true) {
            val l = readAsciiLine(input) ?: break
            if (l.isEmpty()) break
            if (l.length >= 11 && l.regionMatches(0, "User-Agent:", 0, 11, ignoreCase = true)) {
                userAgent = l.substring(11).trim()
            }
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val rawTarget = parts.getOrNull(1) ?: "/"
        val path = rawTarget.substringBefore('?')
        val query = rawTarget.substringAfter('?', "")

        if (!method.equals("GET", ignoreCase = true)) {
            writeResponse(output, 405, "Method Not Allowed", null, null); return
        }

        // 本机为这次连接对外的地址（扫码设备连的就是它）→ 拼回链基址。
        val local = client.localSocketAddress as? InetSocketAddress
        val base = local?.address?.hostAddress?.let { "http://$it:${local.port}" }

        when (path) {
            "/proxy.pac" -> {
                val body = pacProvider().toByteArray(Charsets.UTF_8)
                // 给 PAC 明确缓存语义：iOS 对无 Cache-Control 的 PAC 可能在每个网络事件反复 fetch，
                // 放大「某次 fetch 撞上不可达就超时」的概率；max-age 让其缓存一小段，降低撞窗口频率。
                writeResponse(
                    output, 200, "OK", "application/x-ns-proxy-autoconfig", body,
                    cacheControl = "max-age=300",
                )
            }
            "/", "/setup" -> {
                if (base == null) { writeNoBase(output); return }
                val html = SetupPageGenerator.html(base, userAgent).toByteArray(Charsets.UTF_8)
                writeResponse(output, 200, "OK", "text/html; charset=utf-8", html)
            }
            "/hxmy.mobileconfig" -> {
                if (base == null) { writeNoBase(output); return }
                val ssid = parseSsid(query)
                val cfg = SetupPageGenerator.mobileconfig(base, ssid).toByteArray(Charsets.UTF_8)
                writeResponse(
                    output, 200, "OK", "application/x-apple-aspen-config", cfg,
                    disposition = "attachment; filename=\"hxmy.mobileconfig\"",
                )
            }
            else -> writeResponse(output, 404, "Not Found", null, null)
        }
    }

    /** 从查询串解析 `ssid`（URL 解码）；缺省空串。 */
    private fun parseSsid(query: String): String {
        if (query.isEmpty()) return ""
        for (kv in query.split('&')) {
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i) == "ssid") {
                return try { URLDecoder.decode(kv.substring(i + 1), "UTF-8") } catch (e: Exception) { "" }
            }
        }
        return ""
    }

    private fun writeNoBase(output: OutputStream) {
        val msg = "请先在 hxmy proxy 里选择接口并启动共享。".toByteArray(Charsets.UTF_8)
        writeResponse(output, 503, "Service Unavailable", "text/plain; charset=utf-8", msg)
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String?,
        body: ByteArray?,
        disposition: String? = null,
        cacheControl: String? = null,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (disposition != null) sb.append("Content-Disposition: ").append(disposition).append("\r\n")
        if (cacheControl != null) sb.append("Cache-Control: ").append(cacheControl).append("\r\n")
        sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) output.write(body)
        output.flush()
    }
}
