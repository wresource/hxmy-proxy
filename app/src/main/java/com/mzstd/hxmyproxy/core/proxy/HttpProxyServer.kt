package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleSrc
import com.mzstd.hxmyproxy.core.security.AccessController
import com.mzstd.hxmyproxy.core.security.Authenticator
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.Socket
import java.nio.channels.SocketChannel
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
    /** 非阻塞 relay 反应堆；为 null 或 [useNioRelay]=false 时 CONNECT 走旧阻塞 [relay]。 */
    private val nioReactor: NioRelayReactor? = null,
    private val useNioRelay: Boolean = false,
) : TcpProxyServerBase(ProxyProtocol.HTTP, acceptDispatcher, accessController, registry, accounting) {

    override suspend fun handle(channel: SocketChannel, tracker: TrafficAccounting.ConnTracker?) {
        val client = channel.socket()   // 握手期阻塞流（channel 为 blocking 模式）
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
                handleConnect(channel, output, target, tracker) // 盲隧道，终结本连接
                return
            }
            val keepAlive = forwardPlainHttp(client, input, output, method, target, version, headers, tracker)
            if (!keepAlive) return
        }
    }

    private suspend fun handleConnect(channel: SocketChannel, output: OutputStream, target: String, tracker: TrafficAccounting.ConnTracker?) {
        val clientPort = channel.socket().port
        val hp = HttpParsing.parseHostPort(target) ?: run { writeStatus(output, 400, "Bad Request"); return }
        Log.i("hxmyproxy", "CONNECT -> ${hp.first}:${hp.second}")
        val trace = RequestTrace.open("HTTP", clientPort)
        val decision = ruleEngine?.decideDetailed(hp.first)?.also { logDecision("CONNECT", hp.first, it) }
        val action = decision?.action
        // **PROXY 也记**：logDecision 对 PROXY 直接 return，导致绝大多数流量走了哪条路完全不可见，
        // 排障时只能靠推测（此前已因此误判过一次方向）。
        trace.rule(hp.first, action ?: RuleAction.PROXY, decision?.src)
        Log.i("hxmyproxy", "RULE CONNECT ${hp.first} -> ${action ?: RuleAction.PROXY}")
        tracker?.bindHost(hp.first, direct = action == RuleAction.DIRECT)
        if (action == RuleAction.REJECT) {
            tracker?.recordBlocked(hp.first)
            writeStatus(
                output, 403, "Blocked", ruleHeader(hp.first, decision?.src),
                body = blockedBody(hp.first, decision?.src),
            )
            return
        }
        val bypass = action == RuleAction.DIRECT
        val limits = limitsProvider()
        val onBytes: (Long, Long) -> Unit = if (tracker != null) tracker::add else onTraffic
        // 实际出口由建连层回填（含降级后的真实网络），历史流量统计据此分类累加。
        val onEgress = tracker?.let { it::bindEgress }

        // 优先 NIO 非阻塞 relay（flag 开 + reactor 可用）。connectChannel 抛 IOException（反射 fd 不可用）→ 回退阻塞。
        if (useNioRelay && nioReactor != null) {
            val upstreamCh = try {
                // 建连阶段的硬上限（不含之后的 relay，隧道本身必须能长期存在）。
                // 超时折成 RemoteTimeout 抛出，复用下面既有的错误路径回 504——
                // 代理**先于客户端放弃**并给出状态码，好过让客户端干等到自己超时（见 CONNECT_PHASE_TIMEOUT_MS）。
                withTimeoutOrNull(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                    connector.connectChannel(hp.first, hp.second, bypassVpn = bypass, onEgress = onEgress, trace = trace)
                } ?: throw ProxyException(ProxyError.RemoteTimeout).also {
                    // 外层砍断会把内层原因吃掉（withTimeoutOrNull 返回 null，原始异常丢失），
                    // 所以这里显式记一笔「卡在建连阶段、耗尽了 CONNECT_PHASE_TIMEOUT_MS」。
                    trace.failed(hp.first, "connect-phase-timeout", ProxyError.RemoteTimeout)
                }
            } catch (e: ProxyException) {
                trace.failed(hp.first, "connect", e.error)
                writeStatus(
                    output, e.error.httpStatus, e.error.httpReason,
                    body = failureBody(hp.first, hp.second, e.error),
                )
                return
            } catch (e: IOException) {
                // 能力探测处已落一次结构化事件（nio.fdReflect.unavailable），此处不再重复。
                null
            }
            if (upstreamCh != null) {
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                channel.configureBlocking(false)            // 切非阻塞交给 reactor（握手已完成）
                upstreamCh.configureBlocking(false)
                nioReactor.relay(
                    channel, upstreamCh, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000,
                    host = hp.first, trace = trace, onTraffic = onBytes,
                )
                return
            }
        }

        // 阻塞 relay（flag 关 / NIO 反射回退）：channel 仍是 blocking，用 channel.socket() 走旧引擎。
        val upstream = try {
            withTimeoutOrNull(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                connector.connect(hp.first, hp.second, bypassVpn = bypass, onEgress = onEgress, trace = trace)
            } ?: throw ProxyException(ProxyError.RemoteTimeout).also {
                trace.failed(hp.first, "connect-phase-timeout", ProxyError.RemoteTimeout)
            }
        } catch (e: ProxyException) {
            trace.failed(hp.first, "connect", e.error)
            writeStatus(
                output, e.error.httpStatus, e.error.httpReason,
                body = failureBody(hp.first, hp.second, e.error),
            )
            return
        } catch (e: IOException) {
            // **必须回一个干净的状态码再关**。此前这里只接 ProxyException，
            // IOException（反射取 fd 失败、底层 socket 异常等）会一路冒泡到 accept 循环，
            // 那里只记日志然后 closeQuietly —— 客户端拿到的是**半截握手**而不是状态码。
            // 0806 实证：上游中间层代理侧记为 `proxy closed during CONNECT`，
            // 而我方日志里既无 504 也无任何失败记录（这条路径不写响应也不落盘）。
            // 对方只能把它归为传输层错误，无法计入「上游是否健康」的判定，
            // 信息量远低于一个明确的 502。
            trace.failed(hp.first, "connect-io", e.javaClass.simpleName)
            Ev.throttled(
                LogCat.EGRESS, "connect.io", "cio:${hp.first}", 30_000L, key = true,
                kv = arrayOf("host" to hp.first, "err" to e.javaClass.simpleName, "msg" to e.message),
            )
            writeStatus(
                output, 502, "Bad Gateway",
                body = "hxmy proxy: upstream connect failed\n" +
                    "target: ${hp.first}:${hp.second}\n" +
                    "cause: ${e.javaClass.simpleName} while opening upstream socket\n",
            )
            return
        }
        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        val client = channel.socket()
        client.soTimeout = 0
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
        val uriHost = uri?.host
        if (uriHost == null) { writeStatus(output, 400, "Bad Request"); return false }
        // IPv6 字面量：规则判定/DNS/统计要裸地址，Host 头重建要保留方括号（见 HttpParsing.bareHost）。
        val host = HttpParsing.bareHost(uriHost)
        val port = if (uri.port == -1) 80 else uri.port
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
        }

        val trace = RequestTrace.open("HTTP", client.port)
        val decision = ruleEngine?.decideDetailed(host)?.also { logDecision("HTTP", host, it) }
        val action = decision?.action
        trace.rule(host, action ?: RuleAction.PROXY, decision?.src)
        Log.i("hxmyproxy", "RULE HTTP $host -> ${action ?: RuleAction.PROXY}")
        tracker?.bindHost(host, direct = action == RuleAction.DIRECT)
        // HEAD 的响应不得带正文（RFC 9110 §9.3.2），否则客户端会把正文当成下一个响应的开头。
        val wantsBody = !method.equals("HEAD", ignoreCase = true)
        if (action == RuleAction.REJECT) {
            tracker?.recordBlocked(host)
            writeStatus(
                output, 403, "Blocked", ruleHeader(host, decision?.src),
                body = if (wantsBody) blockedBody(host, decision?.src) else null,
            )
            return false
        }
        Log.i("hxmyproxy", "HTTP $method -> $host:$port")
        val upstream = try {
            withTimeoutOrNull(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                connector.connect(
                    host, port,
                    bypassVpn = action == RuleAction.DIRECT,
                    onEgress = tracker?.let { it::bindEgress },
                    trace = trace,
                )
            } ?: throw ProxyException(ProxyError.RemoteTimeout)
        } catch (e: ProxyException) {
            writeStatus(
                output, e.error.httpStatus, e.error.httpReason,
                body = if (wantsBody) failureBody(host, port, e.error) else null,
            )
            return false
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
            // 用 uriHost（IPv6 仍带方括号）：`Host: 2001:db8::1:80` 是非法的，必须 `Host: [2001:db8::1]:80`。
            if (!hasHost) sb.append("Host: ").append(if (port == 80) uriHost else "$uriHost:$port").append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); upOut.flush()

            // 2) 请求体（client → upstream），按客户端头定界
            val (reqFraming, reqLen) = HttpForwarding.requestFraming(headers)
            client.soTimeout = idle
            HttpForwarding.copyBody(input, upOut, reqFraming, reqLen, buf) { tracker?.add(it, 0) ?: onTraffic(it, 0) }
            upOut.flush()

            // 3) 上游响应行 + 头
            val statusLine = readAsciiLine(upIn) ?: run {
                // 上游连上了却没吐出状态行（半死链路/被中途掐断）——这一类此前也是裸 502。
                writeStatus(
                    output, 502, "Bad Gateway",
                    body = if (wantsBody) {
                        "hxmy proxy: upstream closed before sending a response\n" +
                            "target: $host:$port\n"
                    } else null,
                )
                return false
            }
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

    /**
     * @param extraHeaders 追加的响应头（每项形如 `"X-Foo: bar"`，不含 CRLF）。
     * 值里出现 CR/LF 一律丢弃该头 —— 拒绝响应拆分（header injection）：
     * host 来自客户端请求，直接拼进响应头是典型的注入面。
     */
    private fun writeStatus(
        output: OutputStream,
        code: Int,
        reason: String,
        vararg extraHeaders: String,
        /**
         * 失败说明正文（具名传入；[extraHeaders] 是 vararg，位置参数会被它吞掉）。
         *
         * 为什么值得发：此前所有错误响应都是 `Content-Length: 0`，于是客户端只拿到一个裸状态码。
         * 0807 现场 claude cli 报的就是 **"no body"**，再由它自己的错误处理猜成认证问题、
         * 提示重新登录——而真实原因是出口不通、STRICT 拒绝降级。
         * 一个状态码撑不起归因，多发几十字节能让上游直接显示真相。
         *
         * **HEAD 请求必须传 null**（RFC 9110 §9.3.2：HEAD 响应不得有正文）。
         */
        body: String? = null,
    ) {
        val safe = extraHeaders.filter { it.isNotEmpty() && !it.contains('\r') && !it.contains('\n') }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            safe.forEach { append(it).append("\r\n") }
            if (bodyBytes != null) append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes?.size ?: 0).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (bodyBytes != null) output.write(bodyBytes)
        output.flush()
    }

    /**
     * 建连失败的说明正文。**只讲代理这一跳知道的事实**，不猜上游为什么不通。
     *
     * 之所以点明 "hxmy proxy"：链路上可能还有别的中间层（本机 shim 之类），
     * 客户端看到 502 时第一个问题是「谁返回的」。
     */
    private fun failureBody(host: String, port: Int, err: ProxyError): String =
        "hxmy proxy: upstream connect failed\n" +
            "target: $host:$port\n" +
            "cause: ${err.label} (${err.code})\n"

    /** 规则拦截的说明正文；与 `X-Proxy-Rule` 头同源，便于不看 header 的客户端也能读到。 */
    private fun blockedBody(host: String, src: RuleSrc?): String =
        "hxmy proxy: blocked by rule\n" +
            "target: $host\n" +
            "rule: ${src?.name?.lowercase() ?: "unknown"}\n"

    /**
     * 拦截响应上的规则说明头。
     *
     * 上游中间层代理反馈：排查「某站打不开」时，只有一个裸 403，无从判断是被哪条规则拦的，
     * 成本是「反复二分测试」。带上来源后可以一眼看出是内置广告表、用户规则还是 per-host 覆盖。
     *
     * 只在**拒绝**响应上出现 —— CONNECT 一旦回 200 就进入盲隧道，之后没有任何插入点；
     * 而拒绝恰恰是唯一需要解释的场景。
     */
    private fun ruleHeader(host: String, src: RuleSrc?): String =
        "X-Proxy-Rule: ${src?.name?.lowercase() ?: "unknown"}; host=$host"

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
