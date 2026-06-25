package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.security.AccessController
import com.mzstd.hxmyproxy.core.security.Authenticator
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.Socket
import java.util.Base64

/**
 * HTTP 代理：HTTPS `CONNECT` 隧道 + 普通 HTTP 正向转发。不做 MITM/解密。
 *
 * - **CONNECT host:port**（端口必填、无默认）→ 连上游 → `200 Connection established` → 盲转。
 * - **普通 HTTP**：解析 absolute-form 请求行 → 改写为 origin-form、保留 Host、剥离 hop-by-hop/Proxy-* 头 →
 *   按 Content-Length/chunked 正确定界转发，**支持 HTTP/1.1 keep-alive**（一个客户端连接承载多请求）。
 *   浏览器加载网页会复用同一连接发大量小图/资源请求——keep-alive 是它们能正常加载的前提。
 * - 可选 HTTP Basic（`Proxy-Authorization`），每个请求校验。
 */
class HttpProxyServer(
    acceptDispatcher: CoroutineDispatcher,
    accessController: AccessController,
    registry: ConnectionRegistry,
    private val connector: OutboundConnector,
    private val relay: RelayEngine,
    private val authProvider: () -> Authenticator,
    private val limitsProvider: () -> ConnectionLimits,
    /** relay 搬字节的受限派发器（与 acceptDispatcher 建连派发器分离）。 */
    private val relayDispatcher: CoroutineDispatcher,
    accounting: TrafficAccounting? = null,
    /** 流量计量回调（up, down 字节增量）；普通 HTTP 转发不走 RelayEngine，故经此计量。 */
    private val onTraffic: (Long, Long) -> Unit = { _, _ -> },
    /** 规则引擎（可空，默认 null=不判定）；判为 REJECT 的域名直接拒绝连接。 */
    private val ruleEngine: RuleEngine? = null,
) : TcpProxyServerBase(ProxyProtocol.HTTP, acceptDispatcher, accessController, registry, accounting) {

    override suspend fun handle(client: Socket, tracker: TrafficAccounting.ConnTracker?) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val auth = authProvider()
        var first = true
        // keep-alive 循环：同一客户端连接可承载多个请求（浏览器加载图片/资源的核心模式）
        while (true) {
            client.soTimeout = if (first) ProxyTuning.HANDSHAKE_TIMEOUT_MS else ProxyTuning.KEEPALIVE_IDLE_MS
            val requestLine = try {
                readAsciiLine(input)
            } catch (e: SocketTimeoutException) {
                return // 首请求慢速攻击 / keep-alive 空闲超时 → 关闭连接
            } ?: return
            if (requestLine.isEmpty()) { if (first) writeStatus(output, 400, "Bad Request"); return }
            first = false

            val parts = requestLine.split(' ')
            if (parts.size < 3) { writeStatus(output, 400, "Bad Request"); return }
            val method = parts[0]
            val target = parts[1]
            val version = parts[2]
            client.soTimeout = ProxyTuning.HANDSHAKE_TIMEOUT_MS
            val headers = readHeaders(input)

            if (auth.enabled && !checkBasicAuth(headers, auth)) { writeProxyAuthRequired(output); return }

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(client, output, target, tracker) // 盲隧道，终结本连接
                return
            }
            val keepAlive = forwardPlainHttp(client, input, output, method, target, version, headers, tracker)
            if (!keepAlive) return
        }
    }

    private suspend fun handleConnect(client: Socket, output: OutputStream, target: String, tracker: TrafficAccounting.ConnTracker?) {
        val hp = HttpParsing.parseHostPort(target) ?: run { writeStatus(output, 400, "Bad Request"); return }
        Log.i("hxmyproxy", "CONNECT -> ${hp.first}:${hp.second}")
        tracker?.bindHost(hp.first)
        val action = ruleEngine?.decide(hp.first)
        if (action == RuleAction.REJECT) {
            Log.i("hxmyproxy", "REJECT CONNECT ${hp.first}")
            writeStatus(output, 403, "Blocked"); return
        }
        val upstream = try {
            connector.connect(hp.first, hp.second, bypassVpn = action == RuleAction.DIRECT)
        } catch (e: ProxyException) {
            writeStatus(output, e.error.httpStatus, "Bad Gateway"); return
        }
        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        client.soTimeout = 0
        val limits = limitsProvider()
        val onBytes: (Long, Long) -> Unit = if (tracker != null) tracker::add else onTraffic
        relay.relay(client, upstream, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000, relayDispatcher, onBytes)
    }

    /**
     * 转发一个普通 HTTP 请求/响应；每请求新建上游连接（对上游强制 close 以获干净定界），
     * 但**保持客户端连接**以复用。返回是否可继续在该客户端连接上读下一个请求（keep-alive）。
     */
    private suspend fun forwardPlainHttp(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        method: String,
        target: String,
        version: String,
        headers: List<Pair<String, String>>,
        tracker: TrafficAccounting.ConnTracker?,
    ): Boolean {
        val uri = try { URI(target) } catch (e: Exception) { null }
        val host = uri?.host
        if (host == null) { writeStatus(output, 400, "Bad Request"); return false }
        val port = if (uri.port == -1) 80 else uri.port
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }

        tracker?.bindHost(host)
        val action = ruleEngine?.decide(host)
        if (action == RuleAction.REJECT) {
            Log.i("hxmyproxy", "REJECT HTTP $host")
            writeStatus(output, 403, "Blocked"); return false
        }
        Log.i("hxmyproxy", "HTTP $method -> $host:$port")
        val upstream = try {
            connector.connect(host, port, bypassVpn = action == RuleAction.DIRECT)
        } catch (e: ProxyException) {
            writeStatus(output, e.error.httpStatus, "Bad Gateway"); return false
        }
        try {
            val limits = limitsProvider()
            val buf = ByteArray(limits.relayBufferBytes.coerceAtLeast(8 * 1024))
            val idle = (limits.idleTimeoutSeconds * 1000).coerceAtLeast(1000)
            upstream.soTimeout = idle
            val upOut = upstream.getOutputStream()
            val upIn = upstream.getInputStream()

            // 1) 请求行 + 头 → 上游（origin-form + 保留 Host + 剥 hop-by-hop/Proxy-* + 对上游强制 close）
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            var hasHost = false
            for ((name, value) in headers) {
                val lower = name.lowercase()
                if (lower in HttpParsing.HOP_BY_HOP) continue
                if (lower == "host") hasHost = true
                sb.append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHost) sb.append("Host: ").append(if (port == 80) host else "$host:$port").append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); upOut.flush()

            // 2) 请求体（client → upstream），按客户端头定界
            val (reqFraming, reqLen) = HttpForwarding.requestFraming(headers)
            client.soTimeout = idle
            HttpForwarding.copyBody(input, upOut, reqFraming, reqLen, buf) { tracker?.add(it, 0) ?: onTraffic(it, 0) }
            upOut.flush()

            // 3) 上游响应行 + 头
            val statusLine = readAsciiLine(upIn) ?: run { writeStatus(output, 502, "Bad Gateway"); return false }
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val respHeaders = readHeaders(upIn)
            val (respFraming, respLen) = HttpForwarding.responseFraming(status, method, respHeaders)

            // 4) keep-alive 决策：客户端想保持 且 响应定界确定（非读到关闭才结束）
            val keepAlive = HttpForwarding.clientKeepAlive(version, headers) &&
                respFraming != HttpForwarding.Framing.UNTIL_CLOSE

            // 5) 响应行 + 头 → client（剥 hop-by-hop，设我们自己的 Connection；保留 Content-Length/Transfer-Encoding）
            val rb = StringBuilder()
            rb.append(statusLine).append("\r\n")
            for ((name, value) in respHeaders) {
                if (name.lowercase() in HttpParsing.HOP_BY_HOP) continue
                rb.append(name).append(": ").append(value).append("\r\n")
            }
            rb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
            output.write(rb.toString().toByteArray(Charsets.ISO_8859_1)); output.flush()

            // 6) 响应体（upstream → client）
            HttpForwarding.copyBody(upIn, output, respFraming, respLen, buf) { tracker?.add(0, it) ?: onTraffic(0, it) }
            output.flush()

            return keepAlive
        } catch (e: Throwable) {
            Log.w("hxmyproxy", "HTTP forward error $host:$port: ${e.message}")
            FileLog.w("hxmyproxy", "HTTP forward error $host:$port", e)
            return false
        } finally {
            upstream.closeQuietly()
        }
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) out.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return out
    }

    private fun checkBasicAuth(headers: List<Pair<String, String>>, auth: Authenticator): Boolean {
        val v = headers.firstOrNull { it.first.equals("Proxy-Authorization", true) }?.second ?: return false
        if (!v.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.getDecoder().decode(v.substring(6).trim()), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val idx = decoded.indexOf(':')
        if (idx < 0) return false
        return auth.verify(decoded.substring(0, idx), decoded.substring(idx + 1))
    }

    private fun writeStatus(output: OutputStream, code: Int, reason: String) {
        output.write(
            "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)
        )
        output.flush()
    }

    private fun writeProxyAuthRequired(output: OutputStream) {
        output.write(
            ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                "Proxy-Authenticate: Basic realm=\"hxmy proxy\"\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1)
        )
        output.flush()
    }
}
