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
import com.mzstd.hxmyproxy.core.security.decodeCredentialUtf8
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
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
    /** Owned by the sharing/listener generation. Null only for older/test constructions. */
    private val udpSession: ConnectUdpSession? = null,
    private val socksCapabilities: () -> Socks5Capabilities? = { null },
    private val dnsMessages:DnsMessageQuery? = null,
    private val echoMessages:EchoMessageQuery? = null,
    /** The applied phone policy, not a companion-owned preference. */
    private val proxyLocalTargets: (() -> Boolean)? = null,
) : TcpProxyServerBase(ProxyProtocol.HTTP, acceptDispatcher, accessController, registry, accounting) {

    override suspend fun handle(channel: SocketChannel, tracker: TrafficAccounting.ConnTracker?) = handleHttp(channel,tracker)

    /** Same-port DNS/capability control on SOCKS, never an alternate HTTP proxy entry. */
    internal suspend fun handleControl(channel:SocketChannel,tracker:TrafficAccounting.ConnTracker?,firstByte:Int) =
        handleHttp(channel,tracker,firstByte,true,"SOCKS5")

    private suspend fun handleHttp(channel: SocketChannel, tracker: TrafficAccounting.ConnTracker?,
                                  firstByte:Int?=null,controlOnly:Boolean=false,traceProtocol:String="HTTP") {
        val client = channel.socket()   // 握手期阻塞流（channel 为 blocking 模式）
        val input = if(firstByte==null)client.getInputStream() else SequenceInputStream(ByteArrayInputStream(byteArrayOf(firstByte.toByte())),client.getInputStream())
        val output = client.getOutputStream()
        val auth = authProvider()
        var first = true
        // keep-alive 循环：同一客户端连接可承载多个请求（浏览器加载图片/资源的核心模式）
        while (true) {
            client.soTimeout = if (first) ProxyTuning.HANDSHAKE_TIMEOUT_MS else ProxyTuning.KEEPALIVE_IDLE_MS
            val requestLine = try {
                readAsciiLine(input)
            } catch (_: HttpMetadataLimitException) {
                writeStatus(output,414,"URI Too Long"); return
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
            val headers = try { readHeaders(input) }
            catch (_: HttpMetadataLimitException) { writeStatus(output,431,"Request Header Fields Too Large"); return }

            val authorizationEpoch = udpSession?.currentEpoch
            if (auth.enabled && !checkBasicAuth(headers, auth)) { writeProxyAuthRequired(output); return }

            if ((target == ConnectUdpProtocol.CAPABILITIES_PATH || target.startsWith(ConnectUdpProtocol.UDP_PREFIX) || DnsHttpProtocol.recognizes(target) || EchoProtocol.recognizes(target)) &&
                authorizationEpoch != null && udpSession.currentEpoch != authorizationEpoch) {
                writeStatus(output, 409, "Conflict", body = "UDP authorization or route changed; reconnect")
                return
            }

            if (target == ConnectUdpProtocol.CAPABILITIES_PATH) {
                if (parts.size != 3 || !ConnectUdpProtocol.validControlGet(method, version, headers)) {
                    writeStatus(output, 400, "Bad Request"); return
                }
                val session = udpSession
                if (session == null) { writeStatus(output, 404, "Not Found"); return }
                if (session.isStopped) return
                if (!session.isActive) { writeStatus(output, 500, "Internal Server Error", body = "UDP relay unavailable"); return }
                val body = session.policySnapshot(authorizationEpoch) { epoch ->
                    ConnectUdpProtocol.capabilities(session.id, limitsProvider().idleTimeoutSeconds * 1000L,
                        socksCapabilities(),dnsMessages!=null,!controlOnly,echoMessages,
                        proxyLocalTargets?.invoke(),epoch,ruleEngine?.clientRouteFor(client.inetAddress))
                }
                if (body == null) { writeStatus(output,409,"Conflict",body="Policy changed; reconnect");return }
                writeStatus(output, 200, "OK", "Content-Type: application/json", "Cache-Control: no-store", body = body)
                return
            }
            if(DnsHttpProtocol.recognizes(target)) {
                if(parts.size!=3){writeStatus(output,400,"Bad Request");return}
                handleDns(channel,input,output,method,target,version,headers,tracker,authorizationEpoch,traceProtocol)
                return
            }
            if(EchoProtocol.recognizes(target)) {
                if(parts.size!=3){writeStatus(output,400,"Bad Request");return}
                handleEcho(channel,input,output,method,target,version,headers,tracker,authorizationEpoch,traceProtocol)
                return
            }
            if(controlOnly){writeStatus(output,404,"Not Found");return}
            if (target.startsWith(ConnectUdpProtocol.UDP_PREFIX)) {
                if (parts.size != 3) { writeStatus(output, 400, "Bad Request"); return }
                handleUdp(channel, output, method, target, version, headers, tracker, authorizationEpoch)
                return
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(channel, output, target, tracker) // 盲隧道，终结本连接
                return
            }
            val keepAlive = forwardPlainHttp(client, input, output, method, target, version, headers, tracker)
            if (!keepAlive) return
        }
    }

    private suspend fun handleEcho(client:SocketChannel,input:InputStream,output:OutputStream,method:String,target:String,version:String,
        headers:List<Pair<String,String>>,tracker:TrafficAccounting.ConnTracker?,epoch:Long?,protocol:String) {
        val backend=echoMessages;val session=udpSession
        if(backend==null||session==null){writeStatus(output,404,"Not Found");return}
        if(ConnectUdpProtocol.hasSessionHeader(headers)&&ConnectUdpProtocol.session(headers)!=session.id){writeStatus(output,409,"Conflict");return}
        val request=try{EchoProtocol.read(method,target,version,headers,input)}catch(error:ControlHttpBody.Error){writeStatus(output,error.status,error.reason);return}
        if(if(request.v6)!backend.ipv6 else !backend.ipv4){writeStatus(output,501,"Not Implemented");return}
        val host=request.target.hostAddress?:run{writeStatus(output,400,"Bad Request");return}
        val decision=ruleEngine?.decideDetailed(host, client.socket().inetAddress);val action=decision?.action?:RuleAction.PROXY
        val trace=RequestTrace.open(protocol,client.socket().port)
        return requestBoundary(trace, host, output, onFailure = { writable, error ->
            if (writable != null) {
                val failure = error as? ProxyException
                writeStatus(writable, failure?.error?.httpStatus ?: 500, failure?.error?.httpReason ?: "Internal Server Error",
                    *(failure?.diagnosticHeaders() ?: emptyArray()))
            }
            Unit
        }) { output ->
            trace.rule(host,action,decision?.src);tracker?.bindHost(host,action==RuleAction.DIRECT)
            if(action==RuleAction.REJECT){tracker?.recordBlocked(host);writeStatus(output,403,"Forbidden");trace.tunnelClosed(host,"echo-blocked",0,0);return}
            val owner=currentCoroutineContext()[Job]
            val lease=session.acquire(epoch){reason->owner?.cancel(CancellationException("Echo $reason"));client.closeQuietly()}
            if(lease==null){trace.tunnelClosed(host,"echo-not-admitted",0,0);if(session.isStopped||owner?.isCancelled==true)return
                writeStatus(output,if(epoch!=session.currentEpoch)409 else 429,if(epoch!=session.currentEpoch)"Conflict" else "Too Many Requests");return}
            val cancelReason=java.util.concurrent.atomic.AtomicReference<String?>();var why="echo-response";var up=0L;var down=0L
            lease.use {try{
                val answer=coroutineScope {
                    val queryOwner=currentCoroutineContext()[Job]
                    val watcher=ruleEngine?.let{rules->launch(Dispatchers.Default){rules.version.collect{
                        if(rules.decideDetailed(host, client.socket().inetAddress).action!=action){cancelReason.set("echo-rule-changed");queryOwner?.cancel(CancellationException("Echo rule changed"));client.closeQuietly()}
                    }}}
                    try{connector.queryEchoMessage(request.target,request.wire,action==RuleAction.DIRECT,backend,tracker?.let{it::bindEgress})}finally{watcher?.cancel()}
                }
                currentCoroutineContext().ensureActive()
                if(!lease.isCurrent||(ruleEngine?.decideDetailed(host, client.socket().inetAddress)?.action?:RuleAction.PROXY)!=action)throw CancellationException("Echo policy changed")
                if(!EchoProtocol.matches(request,answer.wire,request.id)||(!request.v6&&EchoProtocol.checksum(answer.wire)!=0)||
                    (request.v6&&(answer.wire[2]!=0.toByte()||answer.wire[3]!=0.toByte())))throw ProxyException(ProxyError.RemoteUnreachable,"echo-response-invalid")
                trace.connected(host,host,0,answer.networkHandle);trace.echoReply(host,request.v6,answer.wire.size,answer.networkHandle)
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nContent-Length: ${answer.wire.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                output.write(answer.wire);output.flush();up=request.wire.size.toLong();down=answer.wire.size.toLong();tracker?.add(up,down)
            }catch(cancel:CancellationException){why=lease.reason?:cancelReason.get()?:"echo-cancelled";throw cancel}
            catch(failure:ProxyException){currentCoroutineContext().ensureActive();if(!lease.isCurrent)throw CancellationException("Echo ${lease.reason}")
                why=failure.stage?:"echo-failed";trace.failed(host,why,failure.error)
                when{failure.error==ProxyError.TooManyConnections->writeStatus(output,429,"Too Many Requests",*failure.diagnosticHeaders())
                    failure.stage=="echo-too-large"->writeStatus(output,413,"Content Too Large",*failure.diagnosticHeaders())
                    failure.stage=="echo-unavailable"->writeStatus(output,501,"Not Implemented",*failure.diagnosticHeaders())
                    else->writeStatus(output,failure.error.httpStatus,failure.error.httpReason,*failure.diagnosticHeaders())}
            }catch(error:IOException){currentCoroutineContext().ensureActive();why="echo-io";trace.failed(host,why,error.javaClass.simpleName)}
            finally{trace.tunnelClosed(host,why,up,down)}}
        }
    }

    private suspend fun handleDns(client:SocketChannel,input:InputStream,output:OutputStream,method:String,target:String,
        version:String,headers:List<Pair<String,String>>,tracker:TrafficAccounting.ConnTracker?,epoch:Long?,protocol:String) {
        val backend=dnsMessages;val session=udpSession
        if(backend==null||session==null){writeStatus(output,404,"Not Found");return}
        if(ConnectUdpProtocol.hasSessionHeader(headers)&&ConnectUdpProtocol.session(headers)!=session.id){writeStatus(output,409,"Conflict");return}
        val request=try{DnsHttpProtocol.read(method,target,version,headers,input)}catch(e:ControlHttpBody.Error){writeStatus(output,e.status,e.reason);return}
        val question=try{DnsMessage.query(request.wire)}catch(_:DnsMessage.Malformed){writeStatus(output,400,"Bad Request");return}
        val decision=ruleEngine?.decideDetailed(question.ruleHost, client.socket().inetAddress);val action=decision?.action?:RuleAction.PROXY
        val trace=RequestTrace.open(protocol,client.socket().port)
        return requestBoundary(trace, question.ruleHost, output, onFailure = { writable, error ->
            if (writable != null) {
                val failure = error as? ProxyException
                writeStatus(writable, failure?.error?.httpStatus ?: 500, failure?.error?.httpReason ?: "Internal Server Error",
                    *(failure?.diagnosticHeaders() ?: emptyArray()))
            }
            Unit
        }) { output ->
            trace.rule(question.ruleHost,action,decision?.src)
            tracker?.bindHost(question.ruleHost,action==RuleAction.DIRECT)
            val owner=currentCoroutineContext()[Job]
            val lease=session.acquire(epoch){reason->owner?.cancel(CancellationException("DNS $reason"));client.closeQuietly()}
            if(lease==null){
                trace.tunnelClosed(question.ruleHost,"dns-not-admitted",0,0)
                if(session.isStopped||owner?.isCancelled==true)return
                writeStatus(output,if(epoch!=session.currentEpoch)409 else 429,if(epoch!=session.currentEpoch)"Conflict" else "Too Many Requests");return
            }
            var up=0L;var down=0L;var why="dns-response"
            val cancelReason=java.util.concurrent.atomic.AtomicReference<String?>()
            lease.use {
                try {
                    val result=if(action==RuleAction.REJECT){tracker?.recordBlocked(question.ruleHost);OutboundConnector.DnsMessageAnswer(DnsMessage.refused(question),null)}
                    else coroutineScope {
                        val queryOwner=currentCoroutineContext()[Job]
                        val watcher=ruleEngine?.let { rules -> launch(Dispatchers.Default) { rules.version.collect {
                            if(rules.decideDetailed(question.ruleHost, client.socket().inetAddress).action!=action){cancelReason.set("dns-rule-changed");queryOwner?.cancel(CancellationException("DNS rule changed"));client.closeQuietly()}
                        } } }
                        try{connector.queryDnsMessage(question.ruleHost,request.wire,action==RuleAction.DIRECT,backend,tracker?.let{it::bindEgress},trace)}
                        finally{watcher?.cancel()}
                    }
                    currentCoroutineContext().ensureActive()
                    if(!lease.isCurrent||(ruleEngine?.decideDetailed(question.ruleHost, client.socket().inetAddress)?.action?:RuleAction.PROXY)!=action)
                        throw CancellationException("DNS policy changed")
                    val answer=try{DnsMessage.forTransport(result.wire,question,request.udp)}catch(_:DnsMessage.Malformed){throw ProxyException(ProxyError.DnsFailure,"dns-response-invalid")}
                    val state=DnsMessage.response(answer,question)
                    val flags=state.flags;val rcode=((state.opt?.ttl?.ushr(24)?:0) shl 4) or (flags and 15)
                    trace.dnsMessageResult(question.ruleHost,question.type,rcode,result.networkHandle,(flags and 0x0200)!=0)
                    if(action!=RuleAction.REJECT) {
                        if(rcode==0)trace.connected(question.ruleHost,null,53,result.networkHandle)
                        else trace.failed(question.ruleHost,"dns-rcode-$rcode",ProxyError.DnsFailure)
                    }
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nCache-Control: no-store\r\nContent-Length: ${answer.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(answer);output.flush();up=request.wire.size.toLong();down=answer.size.toLong();tracker?.add(up,down)
                }catch(cancel:CancellationException){why=lease.reason?:cancelReason.get()?:"dns-cancelled";throw cancel}
                catch(failure:ProxyException){
                    currentCoroutineContext().ensureActive();if(!lease.isCurrent)throw CancellationException("DNS ${lease.reason}")
                    why=failure.stage?:"dns-failed";trace.failed(question.ruleHost,why,failure.error)
                    if(failure.error==ProxyError.TooManyConnections)writeStatus(output,429,"Too Many Requests",*failure.diagnosticHeaders())
                    else writeStatus(output,failure.error.httpStatus,failure.error.httpReason,*failure.diagnosticHeaders())
                }catch(error:IOException){currentCoroutineContext().ensureActive();why="dns-io";trace.failed(question.ruleHost,why,error.javaClass.simpleName)}
                finally{trace.tunnelClosed(question.ruleHost,why,up,down)}
            }
        }
    }

    private suspend fun handleUdp(
        client: SocketChannel, output: OutputStream, method: String, path: String, version: String,
        headers: List<Pair<String, String>>, tracker: TrafficAccounting.ConnTracker?,
        authorizationEpoch: Long?,
    ) {
        val target = ConnectUdpProtocol.target(path)
        if (target == null || !ConnectUdpProtocol.validUpgrade(method, version, headers)) {
            writeStatus(output, 400, "Bad Request"); return
        }
        val session = udpSession
        if (session == null) { writeStatus(output, 404, "Not Found"); return }
        if (session.isStopped) return
        // Optional discovery/affinity extension. Generic RFC clients need no private handshake;
        // an explicitly supplied stale/ambiguous value still fails before DNS/socket allocation.
        if (ConnectUdpProtocol.hasSessionHeader(headers) && ConnectUdpProtocol.session(headers) != session.id) {
            writeStatus(output, 409, "Conflict", body = "UDP session changed"); return
        }
        if (!session.isActive) { writeStatus(output, 500, "Internal Server Error", body = "UDP relay unavailable"); return }
        val decision = ruleEngine?.decideDetailed(target.host, client.socket().inetAddress)
        val action = decision?.action ?: RuleAction.PROXY
        val trace = RequestTrace.open("HTTP", client.socket().port)
        return requestBoundary(trace, target.host, output, onFailure = { writable, error ->
            if (writable != null) {
                val failure = error as? ProxyException
                writeStatus(writable, failure?.error?.httpStatus ?: 500, failure?.error?.httpReason ?: "Internal Server Error",
                    *(failure?.diagnosticHeaders() ?: emptyArray()))
            }
            Unit
        }) { output ->
            trace.rule(target.host, action, decision?.src)
            tracker?.bindHost(target.host, direct = action == RuleAction.DIRECT)
            if (action == RuleAction.REJECT) {
                tracker?.recordBlocked(target.host)
                writeStatus(output, 403, "Forbidden", ruleHeader(target.host, decision?.src), body = blockedBody(target.host, decision?.src)); return
            }
            val owner = currentCoroutineContext()[Job]
            val lease = session.acquire(authorizationEpoch) { reason ->
                owner?.cancel(CancellationException("UDP $reason"))
                client.closeQuietly()
            }
            if (lease == null) {
                if (session.isStopped || owner?.isCancelled == true) {
                    trace.udpClosed(target.host, "cancelled", null, null, null, 0, 0, 0, 0, 0); return
                }
                if (authorizationEpoch != session.currentEpoch) {
                    trace.udpClosed(target.host, "authorization-or-route-changed", null, null, null, 0, 0, 0, 0, 0)
                    writeStatus(output, 409, "Conflict", body = "UDP authorization or route changed; reconnect"); return
                }
                if (!session.isActive) {
                    trace.failed(target.host, "udp-unavailable", ProxyError.Unknown("UDP relay unavailable"))
                    writeStatus(output, 500, "Internal Server Error", body = "UDP relay unavailable"); return
                }
                trace.failed(target.host, "udp-capacity", ProxyError.TooManyConnections)
                writeStatus(output, 429, "Too Many Requests", body = "UDP flow limit reached"); return
            }
            lease.use {
                var handedToRelay = false
                try {
                    val prepared = try {
                        acquireUpstream(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                            connector.prepareDatagram(target.host, target.port, action == RuleAction.DIRECT, tracker?.let { it::bindEgress }, trace)
                        } ?: throw ProxyException(ProxyError.RemoteTimeout, "udp-setup-timeout")
                    } catch (failure: ProxyException) {
                        currentCoroutineContext().ensureActive()
                        if (!lease.isCurrent) throw CancellationException("UDP ${lease.reason ?: "route-changed"}")
                        trace.failed(target.host, failure.stage ?: "udp-setup", failure.error)
                        writeStatus(output, failure.error.httpStatus, failure.error.httpReason,
                            *(retryAfterHeader(failure) + failure.diagnosticHeaders()), body = failureBody(target.host, target.port, failure.error))
                        return
                    }
                    prepared.use {
                        currentCoroutineContext().ensureActive()
                        if (!lease.isCurrent) {
                            trace.udpClosed(target.host, lease.reason ?: "route-changed", null, null, null, 0, 0, 0, 0, 0)
                            return
                        }
                        val now = ruleEngine?.decideDetailed(target.host, client.socket().inetAddress)
                        if ((now?.action ?: RuleAction.PROXY) != action) {
                            if (now?.action == RuleAction.REJECT) {
                                trace.rule(target.host, now.action, now.src); tracker?.recordBlocked(target.host)
                                writeStatus(output, 403, "Forbidden", ruleHeader(target.host, now.src), body = blockedBody(target.host, now.src))
                            } else {
                                trace.udpClosed(target.host, "rule-changed", null, null, null, 0, 0, 0, 0, 0)
                                writeStatus(output, 409, "Conflict", body = "UDP route policy changed")
                            }
                            return
                        }
                        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                            "Connection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n" +
                            "${ConnectUdpProtocol.SESSION_HEADER}: ${session.id}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.flush()
                        trace.udpReady(target.host, prepared.endpoint.peer, prepared.networkHandle)
                        client.configureBlocking(false)
                        handedToRelay = true
                        session.reactor.relay(client, prepared.endpoint, lease, limitsProvider().idleTimeoutSeconds * 1000L,
                            target.host, trace,
                            policyValid = { (ruleEngine?.decideDetailed(target.host, client.socket().inetAddress)?.action ?: RuleAction.PROXY) == action },
                            onTraffic = tracker?.let { it::add } ?: onTraffic,
                            onFirstReply = {
                                trace.udpReply()
                                if (action == RuleAction.DIRECT) DirectEgressFailures.recordSuccess(target.host)
                            })
                    }
                } catch (cancel: CancellationException) {
                    if (!handedToRelay) trace.udpClosed(target.host, lease.reason ?: "cancelled", null, null, null, 0, 0, 0, 0, 0)
                    throw cancel
                } catch (failure: IOException) {
                    // After101, never write a second HTTP response into the capsule stream.
                    if (!handedToRelay) {
                        if (!lease.isCurrent || owner?.isCancelled == true)
                            trace.udpClosed(target.host, lease.reason ?: "cancelled", null, null, null, 0, 0, 0, 0, 0)
                        else trace.failed(target.host, "udp-handoff", failure.javaClass.simpleName)
                    }
                    throw failure
                }
            }
        }
    }

    private suspend fun handleConnect(channel: SocketChannel, output: OutputStream, target: String, tracker: TrafficAccounting.ConnTracker?) {
        val clientPort = channel.socket().port
        val hp = HttpParsing.parseHostPort(target) ?: run { writeStatus(output, 400, "Bad Request"); return }
        Log.i("hxmyproxy", "CONNECT -> ${hp.first}:${hp.second}")
        val trace = RequestTrace.open("HTTP", clientPort)
        return requestBoundary(trace, hp.first, output, onFailure = { writable, error ->
            if (writable != null) {
                val failure = error as? ProxyException
                writeStatus(writable, failure?.error?.httpStatus ?: 500, failure?.error?.httpReason ?: "Internal Server Error",
                    *(failure?.diagnosticHeaders() ?: emptyArray()))
            }
            Unit
        }) { output ->
            val decision = ruleEngine?.decideDetailed(hp.first, channel.socket().inetAddress)?.also { logDecision("CONNECT", hp.first, it) }
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

            val connectDeadline = ConnectDeadline()
            // NIO binding fallback shares this same budget, including any DNS recovery.
            if (useNioRelay && nioReactor != null) {
                // 阶段名只在**唯一的 catch** 里落一次。此前是「抛出点 .also 记一次 + catch 再记一次」，
                // 0814 实测 750 行 req.failed 只对应 432 次失败（318 个 id 落两行）。
                var timedOut = false
                val upstreamCh = try {
                    // 建连阶段的硬上限（不含之后的 relay，隧道本身必须能长期存在）。
                    // 超时折成 RemoteTimeout 抛出，复用下面既有的错误路径回 504——
                    // 代理**先于客户端放弃**并给出状态码，好过让客户端干等到自己超时（见 CONNECT_PHASE_TIMEOUT_MS）。
                    acquireUpstream(connectDeadline.remainingMs()) {
                        connector.connectChannel(hp.first, hp.second, bypassVpn = bypass, onEgress = onEgress, trace = trace)
                    } ?: run { timedOut = true; throw ProxyException(ProxyError.RemoteTimeout) }
                } catch (e: ProxyException) {
                    // 外层砍断会把内层原因吃掉（withTimeoutOrNull 返回 null，原始异常丢失），
                    // 所以用 timedOut 标记还原「卡在建连阶段、耗尽了 CONNECT_PHASE_TIMEOUT_MS」；
                    // e.stage 非空时优先用它（如 connect-strict，由 OutboundConnector 随异常带上来）。
                    trace.failed(hp.first, e.stage ?: if (timedOut) "connect-phase-timeout" else "connect", e.error)
                    writeStatus(
                        output, e.error.httpStatus, e.error.httpReason,
                        *(retryAfterHeader(e) + e.diagnosticHeaders()),
                        body = failureBody(hp.first, hp.second, e.error),
                    )
                    return
                } catch (e: IOException) {
                    // NIO 绑定失败已在 connector 节流记录，此处保留同一出口的 socketFactory 回退。
                    null
                }
                if (upstreamCh != null) {
                    // relay 挂起至隧道结束；调用前的回复、模式切换及取消也归这个所有权范围。
                    upstreamCh.use {
                        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                        channel.configureBlocking(false)            // 切非阻塞交给 reactor（握手已完成）
                        upstreamCh.configureBlocking(false)
                        nioReactor.relay(
                            channel, upstreamCh, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000,
                            host = hp.first, trace = trace, onTraffic = onBytes,
                        )
                    }
                    return
                }
            }

            // 阻塞 relay（flag 关 / NIO 反射回退）：channel 仍是 blocking，用 channel.socket() 走旧引擎。
            var timedOutBlocking = false
            val upstream = try {
                acquireUpstream(connectDeadline.remainingMs()) {
                    connector.connect(hp.first, hp.second, bypassVpn = bypass, onEgress = onEgress, trace = trace)
                } ?: run { timedOutBlocking = true; throw ProxyException(ProxyError.RemoteTimeout) }
            } catch (e: ProxyException) {
                trace.failed(hp.first, e.stage ?: if (timedOutBlocking) "connect-phase-timeout" else "connect", e.error)
                writeStatus(
                    output, e.error.httpStatus, e.error.httpReason,
                    *(retryAfterHeader(e) + e.diagnosticHeaders()),
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
            upstream.use {
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val client = channel.socket()
                client.soTimeout = 0
                relay.relay(client, upstream, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000, relayDispatcher, onBytes,
                    host = hp.first, trace = trace)
            }
        }
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
        return requestBoundary(trace, host, output, onFailure = { writable, error ->
            if (writable != null) {
                val failure = error as? ProxyException
                writeStatus(writable, failure?.error?.httpStatus ?: 500, failure?.error?.httpReason ?: "Internal Server Error",
                    *(failure?.diagnosticHeaders() ?: emptyArray()))
            }
            false
        }) { output ->
            val decision = ruleEngine?.decideDetailed(host, client.inetAddress)?.also { logDecision("HTTP", host, it) }
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
            var plainTimedOut = false
            val upstream = try {
                acquireUpstream(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                    connector.connect(
                        host, port,
                        bypassVpn = action == RuleAction.DIRECT,
                        onEgress = tracker?.let { it::bindEgress },
                        trace = trace,
                    )
                } ?: run { plainTimedOut = true; throw ProxyException(ProxyError.RemoteTimeout) }
            } catch (e: ProxyException) {
                // 与 CONNECT 路径同一条纪律:失败在 server 层记且只记一次。
                trace.failed(host, e.stage ?: if (plainTimedOut) "connect-phase-timeout" else "connect", e.error)
                writeStatus(
                    output, e.error.httpStatus, e.error.httpReason,
                    *(retryAfterHeader(e) + e.diagnosticHeaders()),
                    body = if (wantsBody) failureBody(host, port, e.error) else null,
                )
                return false
            } catch (e: IOException) {
                trace.failed(host, "connect-io", e.javaClass.simpleName)
                writeStatus(output, 502, "Bad Gateway",
                    body = if (wantsBody) failureBody(host, port, ProxyError.RemoteUnreachable) else null)
                return false
            }
            // 本次转发的收发字节:这条路径不经过 reactor，没有 Tunnel 帮忙记账。
            var fwdUp = 0L
            var fwdDown = 0L
            var closeWhy = "http-done"
            var responseStarted = false
            var requestBodyComplete = false
            runCatching { trace.relayStarted(client, upstream) }
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
                HttpForwarding.copyBody(input, upOut, reqFraming, reqLen, buf) { fwdUp += it; tracker?.add(it, 0) ?: onTraffic(it, 0) }
                upOut.flush()
                requestBodyComplete = true

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
                responseStarted = true
                output.write(rb.toString().toByteArray(Charsets.ISO_8859_1)); output.flush()

                // 6) 响应体（upstream → client）
                HttpForwarding.copyBody(upIn, output, respFraming, respLen, buf) { fwdDown += it; tracker?.add(0, it) ?: onTraffic(0, it) }
                output.flush()

                closeWhy = if (keepAlive) "http-keepalive" else "http-done"
                return keepAlive
            } catch (e: kotlinx.coroutines.CancellationException) {
                closeWhy = "cancelled"
                throw e
            } catch (e: Throwable) {
                if (e !is IOException) { closeWhy = "internal-error"; throw e }
                Log.w("hxmyproxy", "HTTP forward error $host:$port: ${e.message}")
                FileLog.w("hxmyproxy", "HTTP forward error $host:$port", e)
                closeWhy = "http-error"
                trace.failed(host, "http-forward", e.javaClass.simpleName)
                if (e is HttpMetadataLimitException && !responseStarted) {
                    writeStatus(output,if(requestBodyComplete)502 else 400,if(requestBodyComplete)"Bad Gateway" else "Bad Request",
                        body=if(wantsBody) {if(requestBodyComplete) "Upstream HTTP metadata exceeds the proxy limit" else "Request transfer metadata exceeds the proxy limit"} else null)
                }
                return false
            } finally {
                // **明文 HTTP 也要落终局。** trace.tunnelClosed 此前只有 NioRelayReactor 一个调用点，
                // 而这条路径完全不经过 reactor —— 0814 按端口对账:443 是 13364 连 / 13364 闭(gap=0)，
                // 80 是 1978 连 / 1818 闭(gap=160)，那 160 条曾被当成 FD 泄漏的证据，
                // 实际只是这里没记(finally 里的 closeQuietly 一直都在)。
                try { trace.tunnelClosed(host, closeWhy, fwdUp, fwdDown) }
                finally { upstream.closeQuietly() }
            }
        }
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val budget = HttpHeaderBudget()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            budget.observe(line)
            val i = line.indexOf(':')
            if (i > 0) out.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return out
    }

    private fun checkBasicAuth(headers: List<Pair<String, String>>, auth: Authenticator): Boolean {
        val v = headers.filter { it.first.equals("Proxy-Authorization", true) }.singleOrNull()?.second ?: return false
        if (!v.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            decodeCredentialUtf8(Base64.getDecoder().decode(v.substring(6).trim())) ?: return false
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
            if (bodyBytes != null && safe.none { it.startsWith("Content-Type:", ignoreCase = true) }) append("Content-Type: text/plain; charset=utf-8\r\n")
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
    /**
     * 摘网响应上的 `Retry-After`（RFC 9110 §10.2.3，delta-seconds 形式）。
     *
     * 只在 [ProxyError.EgressSidelined] 时出现——它是**唯一**我们知道恢复时刻的失败，
     * 其余失败（目标连不上/超时）我们无从预测，凭空造一个数字是撒谎。
     *
     * **值必须 ≥ 1**：0818 实测 OkHttp 是唯一会读这个头的 HTTP 栈，判据恰恰是 `== 0`，
     * 收到 0 会立即无退避重试。EgressHealth.retryAfterSeconds 已保证下限，这里再兜一次。
     */
    private fun retryAfterHeader(e: ProxyException): Array<String> {
        if (e.error !== ProxyError.EgressSidelined) return emptyArray()
        val secs = (e.retryAfterSeconds ?: return emptyArray()).coerceAtLeast(1)
        return arrayOf("Retry-After: $secs")
    }

    private fun failureBody(host: String, port: Int, err: ProxyError, retryAfter: Int? = null): String =
        "hxmy proxy: upstream connect failed\n" +
            "target: $host:$port\n" +
            "cause: ${err.label} (${err.code})\n" +
            (if (err === ProxyError.LocalNetworkBlocked)
                "action: Configure your client to bypass the proxy for local network targets, or enable Allow local network targets in hxmy proxy. Phone-side DIRECT is not client-side bypass.\n" else "") +
            (if (err === ProxyError.ResolvedLocalNetworkBlocked)
                "action: Check this network's DNS or sign-in page. A private DNS answer was blocked; local network protection is still enabled.\n" else "") +
            (if (retryAfter != null) "retry after: ${retryAfter}s\n" else "")

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
                "Proxy-Authenticate: Basic realm=\"hxmy proxy\", charset=\"UTF-8\"\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1)
        )
        output.flush()
    }
}
