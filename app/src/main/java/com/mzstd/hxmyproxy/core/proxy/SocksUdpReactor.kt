package com.mzstd.hxmyproxy.core.proxy

import java.io.IOException

import com.mzstd.hxmyproxy.core.util.concurrentSnapshot
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleSrc
import com.mzstd.hxmyproxy.core.security.AccessController
import kotlinx.coroutines.*
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Shared selector for native SOCKS UDP associations. DNS/setup stays off the selector;
 * every target uses the same preparation, quota and route generation as CONNECT-UDP. */
internal class SocksUdpReactor : Closeable {
    private val selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val associations = ConcurrentHashMap.newKeySet<Association>()
    private val stopped = AtomicBoolean()
    private val queuedBytes = AtomicInteger()
    @Volatile private var terminated = false
    private val input = ByteBuffer.allocate(65536)
    private val controlByte = ByteBuffer.allocate(1)
    private val worker = Thread(::loop, "hxmy-socks-udp").apply { isDaemon = true }
    init { try { worker.start() } catch (error: Throwable) { selector.close(); throw error } }
    val isRunning: Boolean get() = !stopped.get() && !terminated && worker.isAlive
    val activeAssociations: Int get() = associations.size
    val bufferedBytes: Int get() = queuedBytes.get()

    suspend fun relay(
        control: SocketChannel, ingress: SocksUdpIngress, session: ConnectUdpSession,
        associationLease: ConnectUdpSession.Lease, requestedSourcePort: Int,
        connector: OutboundConnector, rules: RuleEngine?, registry: ConnectionRegistry,
        accounting: TrafficAccounting?, access: AccessController, idleMs: Long,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val association = Association(control, ingress, session, associationLease, requestedSourcePort,
            connector, rules, registry, accounting, access, idleMs, continuation)
        continuation.invokeOnCancellation { association.close("cancelled") }
        enqueue { association.register() }
    }

    private fun enqueue(action: () -> Unit) {
        tasks.add(action); runCatching { selector.wakeup() }
        if (terminated || !worker.isAlive) drain()
    }
    private fun drain() { while (true) { val action = tasks.poll() ?: return; runCatching { action() } } }
    private fun reserve(size: Int): Boolean {
        while (true) {
            val old = queuedBytes.get()
            if (size > 8 * 1024 * 1024 - old) return false
            if (queuedBytes.compareAndSet(old, old + size)) return true
        }
    }
    private fun release(size: Int) { queuedBytes.addAndGet(-size) }
    private data class Attachment(val association: Association, val kind: Int, val transport: Transport? = null)
    private fun loop() {
        try {
            bumpToForegroundPriority()
            var lastSweep = System.nanoTime()
            while (!stopped.get()) {
                drain(); if (stopped.get()) break
                selector.select(100)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext() && !stopped.get()) {
                    val key = keys.next(); keys.remove()
                    if (key.isValid) (key.attachment() as Attachment).let { it.association.selected(key, it.kind, it.transport) }
                }
                val now = System.nanoTime()
                if (now - lastSweep >= 100_000_000L) { concurrentSnapshot(associations).forEach { it.sweep(now) }; lastSweep = now }
            }
        } catch (error: Throwable) {
            runCatching { com.mzstd.hxmyproxy.core.log.Ev.e(com.mzstd.hxmyproxy.core.log.LogCat.RELAY, "socks.udp.worker.died", error) }
        } finally {
            stopped.set(true); concurrentSnapshot(associations).forEach { it.close("worker-stopped") }
            runCatching { selector.close() }; terminated = true; drain()
        }
    }
    override fun close() {
        stopped.set(true); concurrentSnapshot(associations).forEach { it.close("stopped") }
        runCatching { selector.wakeup() }
        if (Thread.currentThread() !== worker) runCatching { worker.join(1000) }
    }

    private data class Target(val host: String, val port: Int)
    private data class Reply(val flow: Flow, val wire: ByteArray, val payloadBytes: Int)
    private data class Pending(val payload: ByteArray, val port: Int)

    private inner class Association(
        val control: SocketChannel, val ingress: SocksUdpIngress, val session: ConnectUdpSession,
        val lease: ConnectUdpSession.Lease, requestedPort: Int,
        val connector: OutboundConnector, val rules: RuleEngine?, val registry: ConnectionRegistry,
        val accounting: TrafficAccounting?, val access: AccessController, val idleMs: Long,
        val continuation: CancellableContinuation<Unit>,
    ) {
        val lock = Any()
        val remote = control.remoteAddress as InetSocketAddress
        val local = control.localAddress as InetSocketAddress
        val scope = CoroutineScope(continuation.context + SupervisorJob(continuation.context[Job]))
        val flows = LinkedHashMap<Target, Flow>()
        val transports = LinkedHashSet<Transport>()
        private val transferPeers = LinkedHashMap<Target, Flow>()
        private val replies = ArrayDeque<Reply>()
        private var replyBytes = 0
        var closed = false
        private var sourcePort = requestedPort
        private var lastActivity = System.nanoTime()
        private var controlKey: SelectionKey? = null
        private var ingressKey: SelectionKey? = null
        private var rejected: Target? = null
        private var rejectedAt = 0L

        fun register() = synchronized(lock) {
            try {
                if (!valid()) return@synchronized
                control.configureBlocking(false); ingress.channel.configureBlocking(false)
                controlKey = control.register(selector, SelectionKey.OP_READ, Attachment(this, 0))
                ingressKey = ingress.channel.register(selector, SelectionKey.OP_READ, Attachment(this, 1))
                associations.add(this)
            } catch (_: Throwable) { close("register-error") }
        }
        fun valid(): Boolean {
            if (closed) return false
            if (!isRunning || !lease.isCurrent || !continuation.isActive) { close(lease.reason ?: "stopped"); return false }
            if (!access.admit(local.address, remote.address)) { close("not-admitted"); return false }
            return true
        }
        fun selected(key: SelectionKey, kind: Int, transport: Transport?) = synchronized(lock) {
            try {
                if (!valid()) return@synchronized
                when (kind) {
                    0 -> { controlByte.clear(); if (control.read(controlByte) != 0) close("control-closed") }
                    1 -> { if (key.isReadable) readRequests(); if (!closed && key.isValid && key.isWritable) flushReplies() }
                    2 -> if (transport != null && !transport.closed) {
                        if (key.isReadable) transport.receive()
                        if (!transport.closed && key.isValid && key.isWritable) transport.flush()
                    }
                }
            } catch (error: Throwable) {
                if (transport != null) transport.close("io-error", error) else close("io-error")
            }
        }
        fun attach(flow: Flow, prepared: OutboundConnector.PreparedDatagram): Transport {
            val existing = transports.firstOrNull { it.canShare(flow,prepared) }
            if(existing!=null) {
                // Preparation still validates each target's rule/DNS/actual Network and owns
                // a real quota until handoff. Only the redundant empty socket is closed.
                prepared.close();existing.members.add(flow);return existing
            }
            val created=Transport(this,prepared,flow.action)
            created.members.add(flow);transports.add(created)
            try{created.register();return created}catch(error:Throwable){created.close("register-error",error);throw error}
        }
        private fun readRequests() {
            repeat(16) {
                if (!valid()) return
                input.clear()
                val source = ingress.channel.receive(input) as? InetSocketAddress ?: return
                if (source.address != remote.address || (sourcePort != 0 && source.port != sourcePort)) return@repeat
                input.flip(); val packet = SocksUdpCodec.decode(input) ?: return@repeat
                if (sourcePort == 0) sourcePort = source.port
                lastActivity = System.nanoTime()
                val target = Target(packet.host, packet.port)
                val existing = flows[target] ?: transferPeers[target]
                if (existing != null && existing.valid()) {
                    if (target != existing.target && !existing.authorizeTransferHost(packet.host)) return@repeat
                    try { existing.sendOrQueue(packet.payload, packet.port) } catch (error: Throwable) { existing.close("send-error", error) }
                    return@repeat
                }
                // A cancelled native setup retains its real quota until its finally completes.
                if (flows.containsKey(target) || flows.size >= 16) return@repeat
                val decision = rules?.decideDetailed(packet.host, remote.address)
                val action = decision?.action ?: RuleAction.PROXY
                if (action == RuleAction.REJECT) {
                    if (rejected != target || lastActivity - rejectedAt >= 1_000_000_000L) {
                        val trace = RequestTrace.open("SOCKS5", remote.port); trace.rule(packet.host, action, decision?.src)
                        accounting?.openConnection(remote.address, ProxyProtocol.SOCKS5)?.let { it.recordBlocked(packet.host); it.close() }
                        rejected = target; rejectedAt = lastActivity
                    }
                    return@repeat
                }
                val quota = session.acquire { close(it) } ?: return@repeat
                val connection = registry.tryAcquire(remote.address)
                if (connection == null) { quota.close(); return@repeat }
                var tracker: TrafficAccounting.ConnTracker? = null
                try {
                    tracker = accounting?.openConnection(remote.address, ProxyProtocol.SOCKS5)
                    tracker?.bindHost(packet.host, action == RuleAction.DIRECT)
                    val trace = RequestTrace.open("SOCKS5", remote.port); trace.rule(packet.host, action, decision?.src)
                    val created = Flow(this, target, action, quota, connection, tracker, trace)
                    flows[target] = created
                    created.sendOrQueue(packet.payload)
                    created.start()
                } catch (error: Throwable) {
                    tracker?.close(); connection.close(); quota.close(); throw error
                }
            }
        }
        /** SOCKS replies can come from a new port (e.g. a TFTP transfer ID). Only observed
         * ports at the already-approved IP are eligible, and each maps back to its original
         * socket/route. Never apply this to CONNECT-UDP's strict requested five-tuple. */
        fun learnTransferPeer(flow: Flow, peer: InetSocketAddress): Boolean {
            if (peer.port !in 1..65535) return false
            val keys = setOf(Target(flow.target.host, peer.port), Target(peer.address.hostAddress ?: return false, peer.port))
            if (keys.any { (flows[it] ?: transferPeers[it])?.let { owner -> owner !== flow } == true }) return false
            val added = keys.count { !flows.containsKey(it) && !transferPeers.containsKey(it) }
            if (transferPeers.size + added > 128) return false
            keys.filterNot { flows.containsKey(it) }.forEach { transferPeers[it] = flow }
            return true
        }
        fun removeTransferPeers(flow: Flow) { transferPeers.entries.removeIf { it.value === flow } }
        fun transferOwner(source:InetSocketAddress):Flow? = source.address.hostAddress?.let{transferPeers[Target(it,source.port)]}
        fun offerReply(flow: Flow, source: InetSocketAddress, payload: ByteArray) {
            val wire = SocksUdpCodec.encode(source, payload)
            if (wire.size > SocksUdpCodec.MAX_WIRE_BYTES || replies.size >= 4 || wire.size > 256 * 1024 - replyBytes || !reserve(wire.size)) { flow.drops++; return }
            try { replies.addLast(Reply(flow, wire, payload.size)); replyBytes += wire.size }
            catch (error: Throwable) { release(wire.size); throw error }
            flushReplies()
        }
        private fun flushReplies() {
            repeat(16) {
                val reply = replies.peekFirst() ?: return
                if (!valid()) return
                if (!reply.flow.valid()) { removeReply(reply); return@repeat }
                when (ingress.send(reply.wire, InetSocketAddress(remote.address, sourcePort))) {
                    DatagramSendResult.WOULD_BLOCK -> { interests(); return }
                    DatagramSendResult.TOO_LARGE -> { reply.flow.drops++; removeReply(reply) }
                    DatagramSendResult.SENT -> {
                        removeReply(reply); lastActivity = System.nanoTime(); reply.flow.lastActivity = lastActivity
                        reply.flow.down += reply.payloadBytes; reply.flow.packetsDown++
                        reply.flow.tracker?.add(0, reply.payloadBytes.toLong())
                    }
                }
            }
            interests()
        }
        private fun removeReply(reply: Reply) {
            if (replies.remove(reply)) { replyBytes -= reply.wire.size; release(reply.wire.size) }
            interests()
        }
        fun removeReplies(flow: Flow) { replies.toList().filter { it.flow === flow }.forEach(::removeReply) }
        private fun interests() { ingressKey?.takeIf { it.isValid }?.interestOps(SelectionKey.OP_READ or if (replies.isEmpty()) 0 else SelectionKey.OP_WRITE) }
        fun sweep(now: Long) = synchronized(lock) {
            try {
                if (!valid()) return@synchronized
                if (idleMs > 0 && now - lastActivity >= idleMs * 1_000_000L) close("idle")
                else flows.values.toList().forEach { if (it.valid() && idleMs > 0 && now - it.lastActivity >= idleMs * 1_000_000L) it.close("idle") }
            } catch (_: Throwable) { close("policy-error") }
        }
        fun close(reason: String) = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            runCatching { controlKey?.cancel() }; runCatching { ingressKey?.cancel() }
            runCatching { control.close() }; runCatching { ingress.close() }
            scope.cancel(); flows.values.toList().forEach { it.close(reason) }
            transports.toList().forEach { it.close(reason) }
            transferPeers.clear()
            replies.forEach { release(it.wire.size) }; replies.clear(); replyBytes = 0
            associations.remove(this)
            if (continuation.isActive) continuation.resume(Unit)
            runCatching { selector.wakeup() }
        }
    }

    /** One unconnected native socket per compatible association route/family. Logical target
     * leases, rules, queues and accounting remain separate. A socket lives while at least one
     * target holds its existing endpoint budget; it does not retain unaccounted idle FDs. */
    private inner class Transport(val association:Association,val prepared:OutboundConnector.PreparedDatagram,val action:RuleAction) {
        val members=LinkedHashSet<Flow>()
        var closed=false
        private var key:SelectionKey?=null
        fun canShare(flow:Flow,candidate:OutboundConnector.PreparedDatagram):Boolean = !closed &&
            prepared.endpoint.supportsMultipleTargets && candidate.endpoint.supportsMultipleTargets &&
            prepared.networkHandle==candidate.networkHandle && action==flow.action &&
            prepared.endpoint.peer.address.address.size==candidate.endpoint.peer.address.address.size &&
            // Different domain identities at the same IP need distinct reply/transfer ownership.
            // Preserve that older isolation; IP-only capture and distinct peers share normally.
            members.none { it.peer?.address==candidate.endpoint.peer.address && it.target.host!=flow.target.host }
        fun register(){key=prepared.endpoint.channel.register(selector,SelectionKey.OP_READ,Attachment(association,2,this))}
        fun interests(){key?.takeIf{it.isValid}?.interestOps(SelectionKey.OP_READ or if(members.any{it.hasPending})SelectionKey.OP_WRITE else 0)}
        fun flush(){members.toList().forEach{flow->if(flow.valid())try{flow.flush()}catch(error:Throwable){flow.close("send-error",error)}};interests()}
        fun receive(){
            repeat(16){
                members.toList().forEach{it.valid()};if(closed)return
                input.clear();val source=prepared.endpoint.channel.receive(input) as? InetSocketAddress?:return
                val permitted=members.filter{!it.closed&&it.peer?.address==source.address}
                val exact=permitted.firstOrNull{it.peer?.port==source.port}
                val learned=association.transferOwner(source)?.takeIf{it in permitted}
                // If several ports use the same approved host, reply bytes have the same
                // route/domain attribution. Most recent activity owns an observed transfer
                // port; later outbound packets reuse this shared socket regardless of owner.
                val owner=exact?:learned?:permitted.maxByOrNull{it.lastActivity}?.takeIf{association.learnTransferPeer(it,source)}
                if(owner==null)return@repeat
                owner.acceptReply(source)
            }
        }
        fun detach(flow:Flow){members.remove(flow);if(members.isEmpty())close("empty")else interests()}
        fun close(reason:String,error:Throwable?=null){
            if(closed)return;closed=true;runCatching{key?.cancel()};runCatching{prepared.close()};association.transports.remove(this)
            members.toList().forEach{it.close(reason,error)};members.clear()
        }
    }

    private inner class Flow(
        val association: Association, val target: Target, val action: RuleAction,
        val lease: ConnectUdpSession.Lease, val connection: ConnectionRegistry.Lease,
        val tracker: TrafficAccounting.ConnTracker?, val trace: RequestTrace,
    ) {
        var prepared: OutboundConnector.PreparedDatagram? = null
        var transport: Transport? = null
        val peer:InetSocketAddress? get()=prepared?.endpoint?.peer
        val hasPending:Boolean get()=pending.isNotEmpty()
        private var job: Job? = null
        private var setupDone = false
        private var released = false
        var closed = false
        private val pending = ArrayDeque<Pending>()
        private val transferRuleHosts = LinkedHashSet<String>()
        private var pendingBytes = 0
        var lastActivity = System.nanoTime()
        var drops = 0L; var up = 0L; var down = 0L; var packetsUp = 0L; var packetsDown = 0L
        private var replied = false

        fun start() {
            var value: OutboundConnector.PreparedDatagram? = null
            var failure: Throwable? = null
            job = association.scope.launch(start = CoroutineStart.LAZY) {
                try {
                    value = acquireUpstream(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                        association.connector.prepareDatagram(target.host, target.port, action == RuleAction.DIRECT,
                            tracker?.let { it::bindEgress }, trace, nativeSocks = true)
                    } ?: throw ProxyException(ProxyError.RemoteTimeout, "udp-setup-timeout")
                } catch (error: Throwable) { failure = error }
            }
            // Completion also runs when a lazy job is cancelled before its body ever starts.
            // A body-only finally would leak the pending flow's real quota in that race.
            job!!.invokeOnCompletion { cause ->
                    val result = value; val error = failure ?: cause
                    enqueue {
                        synchronized(association.lock) {
                            setupDone = true
                            try {
                                if (closed || !association.valid() || !lease.isCurrent || !valid()) { result?.close(); close(lease.reason ?: "cancelled"); releaseOwnership() }
                                else if (error != null || result == null) {
                                    if (error is ProxyException) trace.failed(target.host, error.stage ?: "udp-setup", error.error)
                                    else if (error is IOException) trace.failed(target.host, "udp-setup", error.javaClass.simpleName)
                                    else if (error !is CancellationException) trace.internalError(target.host, error ?: IllegalStateException("UDP setup produced no result"))
                                    result?.close(); close("setup-failed")
                                } else {
                                    prepared = result
                                    transport = association.attach(this@Flow,result)
                                    trace.udpReady(target.host, result.endpoint.peer, result.networkHandle)
                                    flush()
                                }
                            } catch (problem: Throwable) { result?.close(); close("setup-handoff", problem) }
                        }
                    }
            }
            job!!.start()
        }
        fun valid(): Boolean {
            if (closed) return false
            if (association.closed || !lease.isCurrent || !isRunning) { close(lease.reason ?: "stopped"); return false }
            if ((association.rules?.decideDetailed(target.host, association.remote.address)?.action ?: RuleAction.PROXY) != action) { close("rule-changed"); return false }
            if (transferRuleHosts.any { !transferPolicyAllows(it) }) { close("transfer-rule-changed"); return false }
            return true
        }
        private fun transferPolicyAllows(host: String): Boolean {
            val decision = association.rules?.decideDetailed(host, association.remote.address) ?: return true
            // An observed reply port continues the original flow, including its explicit
            // domain route. A new explicit IP rule may deny it, but never silently switch it.
            return decision.src == RuleSrc.DEFAULT || decision.action == action
        }
        fun authorizeTransferHost(host: String): Boolean {
            if (!transferPolicyAllows(host)) {
                association.rules?.decideDetailed(host, association.remote.address)?.let { decision ->
                    RequestTrace.open("SOCKS5", association.remote.port).rule(host, decision.action, decision.src)
                    if (decision.action == RuleAction.REJECT) tracker?.recordBlocked(host)
                }
                close("transfer-rule-conflict"); return false
            }
            transferRuleHosts.add(host)
            return true
        }
        fun sendOrQueue(payload: ByteArray, destinationPort: Int = target.port) {
            if (!valid()) return
            lastActivity = System.nanoTime()
            if (transport != null && pending.isEmpty()) when (transport!!.prepared.endpoint.sendTo(payload, InetSocketAddress(peer!!.address, destinationPort))) {
                DatagramSendResult.SENT -> { sent(payload.size); return }
                DatagramSendResult.TOO_LARGE -> { drops++; return }
                DatagramSendResult.WOULD_BLOCK -> Unit
            }
            if (pending.size >= 4 || payload.size > 256 * 1024 - pendingBytes || !reserve(payload.size)) { drops++; return }
            try { pending.addLast(Pending(payload, destinationPort)); pendingBytes += payload.size }
            catch (error: Throwable) { release(payload.size); throw error }
            interests()
        }
        private fun sent(size: Int) { up += size; packetsUp++; tracker?.add(size.toLong(), 0) }
        fun flush() {
            val endpoint = transport?.prepared?.endpoint ?: return
            repeat(16) {
                if (!valid()) return
                val packet = pending.peekFirst() ?: run { interests(); return }
                val payload = packet.payload
                when (endpoint.sendTo(payload, InetSocketAddress(peer!!.address, packet.port))) {
                    DatagramSendResult.WOULD_BLOCK -> { interests(); return }
                    DatagramSendResult.TOO_LARGE -> drops++
                    DatagramSendResult.SENT -> sent(payload.size)
                }
                pending.removeFirst(); pendingBytes -= payload.size; release(payload.size)
            }
            interests()
        }
        fun acceptReply(source:InetSocketAddress) {
            if(!valid())return
            lastActivity=System.nanoTime()
            if(input.position()>ConnectUdpProtocol.MAX_PAYLOAD){drops++;return}
            if(!replied){replied=true;trace.udpReply();if(action==RuleAction.DIRECT)DirectEgressFailures.recordSuccess(target.host)}
            input.flip();association.offerReply(this,source,ByteArray(input.remaining()).also{input.get(it)})
        }
        private fun interests() { transport?.interests() }
        fun close(reason: String, error: Throwable? = null) = synchronized(association.lock) {
            if (!closed) {
                closed = true
                job?.cancel(); transport?.detach(this)
                pending.forEach { release(it.payload.size) }; pending.clear(); pendingBytes = 0
                association.removeReplies(this)
                association.removeTransferPeers(this)
                runCatching { trace.udpClosed(target.host, reason, if (error != null) "relay" else null, null, error, up, down, packetsUp, packetsDown, drops) }
            }
            if (setupDone) releaseOwnership()
        }
        private fun releaseOwnership() {
            if (released) return
            released = true
            association.flows.remove(target, this)
            try { runCatching { tracker?.close() } } finally { connection.close(); lease.close() }
        }
    }
}
