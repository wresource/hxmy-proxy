package com.mzstd.hxmyproxy.testsupport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 模拟器专用VPN夹具。真实Android TUN/Network上，对203.0.113.7的TCP提供受控回显。
 * 不连接外网、不读取用户VPN配置；只验证路由/生命周期，不冒充商业VPN的加密传输。
 */
class ControlledVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        check(Build.HARDWARE == "ranchu") { "Controlled VPN is emulator-only" }
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn-verification", "VPN verification", NotificationManager.IMPORTANCE_LOW))
        startForeground(991, Notification.Builder(this, "vpn-verification")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Local VPN verification").setOngoing(true).build())
        try {
            check(prepare(this) == null) { "Test VPN not prepared" }
            val fd = checkNotNull(Builder().setSession("hxmy emulator verification")
                .addAddress("10.200.0.2", 32).addRoute("203.0.113.0", 24)
                .addAllowedApplication(packageName).allowBypass().setMtu(1500).setBlocking(false).establish())
            tun = fd; running = true; lastFailure = null
            packets.set(0); echoedBytes.set(0)
            worker = thread(isDaemon = true, name = "controlled-vpn") {
                val peer = TcpEchoPeer()
                val buffer = ByteArray(65535)
                val poll = StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                try {
                    active = true
                    while (running) {
                        if (Os.poll(arrayOf(poll), 250) <= 0) continue
                        val count = try { Os.read(fd.fileDescriptor, buffer, 0, buffer.size) }
                        catch (error: ErrnoException) { if (error.errno == OsConstants.EAGAIN) continue else throw error }
                        if (count <= 0) continue
                        packets.incrementAndGet()
                        peer.reply(buffer.copyOf(count))?.let { response ->
                            check(Os.write(fd.fileDescriptor, response, 0, response.size) == response.size)
                        }
                    }
                } catch (error: Throwable) {
                    if (running) lastFailure = error.toString()
                } finally { active = false }
            }
        } catch (error: Throwable) { lastFailure = error.toString(); stopSelf() }
        return START_NOT_STICKY
    }
    private fun shutdown() {
        running = false
        worker?.join(1500)
        tun?.close(); tun = null
        active = false
    }
    override fun onRevoke() { shutdown(); stopSelf() }
    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.mzstd.hxmyproxy.test.STOP_VPN"
        @Volatile var active = false
        @Volatile var lastFailure: String? = null
        val packets = AtomicLong()
        val echoedBytes = AtomicLong()
    }

    private class TcpEchoPeer {
        private data class Flow(var clientNext: Int, var serverNext: Int = 1001)
        private val flows = HashMap<String, Flow>()
        fun reply(packet: ByteArray): ByteArray? {
            if (packet.size < 40 || (packet[0].toInt() ushr 4) != 4 || packet[9].toInt() != 6) return null
            val ip = (packet[0].toInt() and 15) * 4
            val total = word(packet, 2)
            if (ip < 20 || total > packet.size || total < ip + 20) return null
            val tcp = (packet[ip + 12].toInt() ushr 4 and 15) * 4
            if (tcp < 20 || ip + tcp > total) return null
            val flags = packet[ip + 13].toInt() and 255
            val key = packet.copyOfRange(12, 20).joinToString(".") + ":" + word(packet, ip) + ":" + word(packet, ip + 2)
            val sequence = ByteBuffer.wrap(packet, ip + 4, 4).int
            if (flags and 4 != 0) { flows.remove(key); return null }
            if (flags and 2 != 0) {
                val flow = flows.getOrPut(key) { Flow(sequence + 1) }
                return response(packet, ip, 1000, flow.clientNext, 0x12, ByteArray(0))
            }
            val flow = flows[key] ?: return null
            val bytes = packet.copyOfRange(ip + tcp, total)
            if (bytes.isEmpty() && flags and 1 == 0) return null
            if (sequence != flow.clientNext) return response(packet, ip, flow.serverNext, flow.clientNext, 0x10, ByteArray(0))
            flow.clientNext += bytes.size
            val seq = flow.serverNext
            flow.serverNext += bytes.size
            var replyFlags = if (bytes.isEmpty()) 0x10 else 0x18
            if (flags and 1 != 0) {
                flow.clientNext++; flow.serverNext++; replyFlags = replyFlags or 1
                flows.remove(key)
            }
            echoedBytes.addAndGet(bytes.size.toLong())
            return response(packet, ip, seq, flow.clientNext, replyFlags, bytes)
        }
        private fun response(request: ByteArray, ip: Int, seq: Int, ack: Int, flags: Int, data: ByteArray): ByteArray {
            val output = ByteArray(40 + data.size)
            val b = ByteBuffer.wrap(output)
            output[0] = 0x45; b.putShort(2, output.size.toShort())
            output[8] = 64; output[9] = 6
            request.copyInto(output, 12, 16, 20); request.copyInto(output, 16, 12, 16)
            b.putShort(20, word(request, ip + 2).toShort()); b.putShort(22, word(request, ip).toShort())
            b.putInt(24, seq); b.putInt(28, ack)
            output[32] = 0x50; output[33] = flags.toByte(); b.putShort(34, 65535.toShort())
            data.copyInto(output, 40)
            b.putShort(10, checksum(output, 0, 20, 0).toShort())
            var pseudo = 6 + 20 + data.size
            for (i in 12 until 20 step 2) pseudo += word(output, i)
            b.putShort(36, checksum(output, 20, output.size, pseudo).toShort())
            return output
        }
        private fun word(data: ByteArray, offset: Int) = (data[offset].toInt() and 255 shl 8) or (data[offset + 1].toInt() and 255)
        private fun checksum(data: ByteArray, from: Int, to: Int, seed: Int): Int {
            var sum = seed
            var i = from
            while (i + 1 < to) { sum += word(data, i); i += 2 }
            if (i < to) sum += (data[i].toInt() and 255) shl 8
            while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
            return sum.inv() and 65535
        }
    }
}
