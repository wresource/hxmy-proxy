package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.security.AccessController
import kotlinx.coroutines.CoroutineDispatcher
import java.io.OutputStream
import java.net.Socket

/**
 * 极简 HTTP 服务，提供 `GET /proxy.pac`（MIME `application/x-ns-proxy-autoconfig`）。
 * PAC 内容由 [pacProvider] 按当前可用入口动态生成（见 Step 3/4 的 GeneratePacUseCase）。
 */
class PacServer(
    ioDispatcher: CoroutineDispatcher,
    accessController: AccessController,
    registry: ConnectionRegistry,
    private val pacProvider: () -> String,
) : TcpProxyServerBase(ProxyProtocol.PAC, ioDispatcher, accessController, registry) {

    override suspend fun handle(client: Socket) {
        client.soTimeout = ProxyTuning.HANDSHAKE_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readAsciiLine(input) ?: return
        // 读完并丢弃请求头
        while (true) {
            val l = readAsciiLine(input) ?: break
            if (l.isEmpty()) break
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: "/"

        if (!method.equals("GET", ignoreCase = true)) {
            writeResponse(output, 405, "Method Not Allowed", null, null); return
        }
        if (path == "/proxy.pac" || path.startsWith("/proxy.pac?")) {
            val body = pacProvider().toByteArray(Charsets.UTF_8)
            writeResponse(output, 200, "OK", "application/x-ns-proxy-autoconfig", body)
        } else {
            writeResponse(output, 404, "Not Found", null, null)
        }
    }

    private fun writeResponse(output: OutputStream, code: Int, reason: String, contentType: String?, body: ByteArray?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) output.write(body)
        output.flush()
    }
}
