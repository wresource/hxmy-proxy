package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.security.AccessController
import com.mzstd.hxmyproxy.core.security.Authenticator
import kotlinx.coroutines.CoroutineDispatcher
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * SOCKS5 代理（RFC1928 + RFC1929）。V1 仅支持 CONNECT；不支持 BIND / UDP ASSOCIATE。
 *
 * 流程：greeting(方法协商) → [可选]用户名/密码子协商 → request(CONNECT) →
 * 经 [OutboundConnector] 连上游（走默认网=VPN）→ 回复 → [RelayEngine] 双向转发。
 */
class Socks5ProxyServer(
    ioDispatcher: CoroutineDispatcher,
    accessController: AccessController,
    registry: ConnectionRegistry,
    private val connector: OutboundConnector,
    private val relay: RelayEngine,
    private val authProvider: () -> Authenticator,
    private val limitsProvider: () -> ConnectionLimits,
) : TcpProxyServerBase(ProxyProtocol.SOCKS5, ioDispatcher, accessController, registry) {

    override suspend fun handle(client: Socket) {
        client.soTimeout = ProxyTuning.HANDSHAKE_TIMEOUT_MS
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val auth = authProvider()

        // 1) greeting: VER NMETHODS METHODS...
        if (input.read() != 0x05) return
        val nMethods = input.read()
        if (nMethods <= 0) return
        val methods = ByteArray(nMethods)
        readFully(input, methods)
        val offersNoAuth = methods.any { (it.toInt() and 0xFF) == 0x00 }
        val offersUserPass = methods.any { (it.toInt() and 0xFF) == 0x02 }

        // 2) 方法选择 + [可选] RFC1929 子协商
        if (auth.enabled) {
            if (!offersUserPass) { output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush(); return }
            output.write(byteArrayOf(0x05, 0x02)); output.flush()
            if (!subnegotiateUserPass(input, output, auth)) return
        } else {
            if (!offersNoAuth) { output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush(); return }
            output.write(byteArrayOf(0x05, 0x00)); output.flush()
        }

        // 3) request: VER CMD RSV ATYP DST.ADDR DST.PORT
        if (input.read() != 0x05) return
        val cmd = input.read()
        input.read() // RSV
        val atyp = input.read()
        var host: String? = null
        var addr: InetAddress? = null
        when (atyp) {
            0x01 -> { val b = ByteArray(4); readFully(input, b); addr = InetAddress.getByAddress(b) }
            0x04 -> { val b = ByteArray(16); readFully(input, b); addr = InetAddress.getByAddress(b) }
            0x03 -> { val len = input.read(); if (len <= 0) return; val b = ByteArray(len); readFully(input, b); host = String(b, Charsets.US_ASCII) }
            else -> { reply(output, 0x08); return }   // address type not supported
        }
        val portHi = input.read(); val portLo = input.read()
        if (portHi < 0 || portLo < 0) return
        val port = ((portHi and 0xFF) shl 8) or (portLo and 0xFF)

        if (cmd != 0x01) { reply(output, 0x07); return }   // 仅 CONNECT

        // 4) 连上游
        val upstream = try {
            if (addr != null) connector.connect(addr, port) else connector.connect(host!!, port)
        } catch (e: ProxyException) {
            reply(output, e.error.socksReply); return
        }

        // 5) 成功回复 + 双向转发
        reply(output, 0x00)
        client.soTimeout = 0
        val limits = limitsProvider()
        relay.relay(client, upstream, limits.relayBufferBytes, limits.idleTimeoutSeconds * 1000)
    }

    /** RFC1929：VER(0x01) ULEN UNAME PLEN PASSWD → VER(0x01) STATUS。 */
    private fun subnegotiateUserPass(input: InputStream, output: OutputStream, auth: Authenticator): Boolean {
        if (input.read() != 0x01) return false
        val ulen = input.read(); if (ulen < 0) return false
        val u = ByteArray(ulen); readFully(input, u)
        val plen = input.read(); if (plen < 0) return false
        val p = ByteArray(plen); readFully(input, p)
        val ok = auth.verify(String(u, Charsets.UTF_8), String(p, Charsets.UTF_8))
        output.write(byteArrayOf(0x01, (if (ok) 0 else 1).toByte()))
        output.flush()
        return ok
    }

    /** 回复：VER REP RSV ATYP=IPv4 BND.ADDR=0.0.0.0 BND.PORT=0。 */
    private fun reply(output: OutputStream, rep: Int) {
        output.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }
}
