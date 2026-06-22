package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.security.AccessController
import com.mzstd.hxmyproxy.core.security.Authenticator
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.Socket
import java.util.Base64

/**
 * HTTP 代理：HTTPS `CONNECT` 隧道 + 普通 HTTP 正向转发。不做 MITM/解密。
 *
 * - **CONNECT host:port**（端口必填、无默认）→ 连上游 → `200 Connection established` → 盲转。
 * - **普通 HTTP**：解析 absolute-form 请求行 → 改写为 origin-form、保留 Host、剥离 hop-by-hop/Proxy-*
 *   头、强制 `Connection: close`（V1 单请求/连接）→ 转发请求与响应。
 * - 可选 HTTP Basic（`Proxy-Authorization`）。
 */
class HttpProxyServer(
    ioDispatcher: CoroutineDispatcher,
    accessController: AccessController,
    registry: ConnectionRegistry,
    private val connector: OutboundConnector,
    private val relay: RelayEngine,
    private val authProvider: () -> Authenticator,
    private val limitsProvider: () -> ConnectionLimits,
) : TcpProxyServerBase(ProxyProtocol.HTTP, ioDispatcher, accessController, registry) {

    override suspend fun handle(client: Socket) {
        client.soTimeout = ProxyTuning.HANDSHAKE_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) { writeStatus(output, 400, "Bad Request"); return }
        val method = parts[0]
        val target = parts[1]
        val headers = readHeaders(input)

        val auth = authProvider()
        if (auth.enabled && !checkBasicAuth(headers, auth)) { writeProxyAuthRequired(output); return }

        if (method.equals("CONNECT", ignoreCase = true)) {
            handleConnect(client, output, target)
        } else {
            handlePlainHttp(client, output, method, target, headers)
        }
    }

    private suspend fun handleConnect(client: Socket, output: OutputStream, target: String) {
        val hp = HttpParsing.parseHostPort(target) ?: run { writeStatus(output, 400, "Bad Request"); return }
        Log.i("hxmyproxy", "CONNECT -> ${hp.first}:${hp.second}")
        val upstream = try {
            connector.connect(hp.first, hp.second)
        } catch (e: ProxyException) {
            writeStatus(output, e.error.httpStatus, "Bad Gateway"); return
        }
        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        client.soTimeout = 0
        val limits = limitsProvider()
        relay.relay(client, upstream, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000)
    }

    private suspend fun handlePlainHttp(
        client: Socket,
        output: OutputStream,
        method: String,
        target: String,
        headers: List<Pair<String, String>>,
    ) {
        val uri = try { URI(target) } catch (e: Exception) { null }
        val host = uri?.host
        if (host == null) { writeStatus(output, 400, "Bad Request"); return }
        val port = if (uri.port == -1) 80 else uri.port
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }

        Log.i("hxmyproxy", "HTTP $method -> $host:$port")
        val upstream = try {
            connector.connect(host, port)
        } catch (e: ProxyException) {
            writeStatus(output, e.error.httpStatus, "Bad Gateway"); return
        }

        // 改写请求头发往上游（origin-form + 保留 Host + 剥离 hop-by-hop/Proxy-* + 强制 close）
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        var hasHost = false
        for ((name, value) in headers) {
            val lower = name.lowercase()
            if (lower in HttpParsing.HOP_BY_HOP) continue
            if (lower == "host") hasHost = true
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        if (!hasHost) {
            val hostHeader = if (port == 80) host else "$host:$port"
            sb.append("Host: ").append(hostHeader).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")

        val upOut = upstream.getOutputStream()
        upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        upOut.flush()

        client.soTimeout = 0
        val limits = limitsProvider()
        // relay 转发剩余请求体(client->upstream) 与响应(upstream->client)
        relay.relay(client, upstream, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000)
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
