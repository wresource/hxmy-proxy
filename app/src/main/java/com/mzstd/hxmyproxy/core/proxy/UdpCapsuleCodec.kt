package com.mzstd.hxmyproxy.core.proxy

import java.io.IOException
import java.nio.ByteBuffer

internal class CapsuleProtocolException(message: String) : IOException(message)

/** Streaming RFC9297/9298 decoder. Unknown fields are discarded without payload allocation. */
internal class UdpCapsuleDecoder {
    private enum class State { TYPE, LENGTH, CONTEXT, PAYLOAD, SKIP }
    private var state = State.TYPE
    private var capsuleType = 0L
    private var remaining = 0L
    private var number = 0L
    private var numberBytes = 0
    private var numberRemaining = 0
    private var payload: ByteArray? = null
    private var payloadAt = 0

    /** Returns completed datagrams/drop events; callers can bound work per reactor turn. */
    fun consume(input: ByteBuffer, maxEvents: Int = Int.MAX_VALUE, emit: (ByteArray) -> Unit, drop: () -> Unit = {}): Int {
        var events = 0
        while (input.hasRemaining() && events < maxEvents) {
            when (state) {
                State.TYPE, State.LENGTH, State.CONTEXT -> {
                    val currentState = state
                    if (currentState == State.CONTEXT && remaining <= 0) throw CapsuleProtocolException("missing-context")
                    val byte = input.get().toInt() and 255
                    if (currentState == State.CONTEXT) remaining--
                    if (numberBytes == 0) {
                        numberRemaining = (1 shl (byte ushr 6)) - 1
                        number = (byte and 63).toLong()
                    } else {
                        number = (number shl 8) or byte.toLong()
                        numberRemaining--
                    }
                    numberBytes++
                    if (numberRemaining != 0) {
                        if (currentState == State.CONTEXT && remaining == 0L) throw CapsuleProtocolException("truncated-context")
                        continue
                    }
                    val value = number
                    number = 0; numberBytes = 0
                    when (currentState) {
                        State.TYPE -> { capsuleType = value; state = State.LENGTH }
                        State.LENGTH -> {
                            if (value > MAX_CAPSULE_LENGTH) throw CapsuleProtocolException("capsule-resource-limit")
                            remaining = value
                            state = if (capsuleType == 0L) State.CONTEXT else State.SKIP
                            if (remaining == 0L) {
                                if (state == State.CONTEXT) throw CapsuleProtocolException("missing-context")
                                state = State.TYPE
                            }
                        }
                        State.CONTEXT -> {
                            if (value == 0L && remaining > ConnectUdpProtocol.PROTOCOL_PAYLOAD_CEILING) throw CapsuleProtocolException("udp-protocol-size")
                            if (value != 0L || remaining > ConnectUdpProtocol.MAX_PAYLOAD) {
                                state = if (remaining == 0L) State.TYPE else State.SKIP
                                events++; drop()
                            } else if (remaining == 0L) {
                                state = State.TYPE; events++; emit(EMPTY)
                            } else {
                                payload = ByteArray(remaining.toInt()); payloadAt = 0; state = State.PAYLOAD
                            }
                        }
                        else -> error("unreachable")
                    }
                }
                State.PAYLOAD -> {
                    val count = minOf(remaining, input.remaining().toLong()).toInt()
                    input.get(payload!!, payloadAt, count); payloadAt += count; remaining -= count
                    if (remaining == 0L) {
                        val ready = payload!!; payload = null; state = State.TYPE
                        events++; emit(ready)
                    }
                }
                State.SKIP -> {
                    val count = minOf(remaining, input.remaining().toLong()).toInt()
                    input.position(input.position() + count); remaining -= count
                    if (remaining == 0L) state = State.TYPE
                }
            }
        }
        return events
    }

    fun finish() {
        if (state != State.TYPE || numberBytes != 0) throw CapsuleProtocolException("truncated-capsule")
    }

    companion object {
        const val MAX_CAPSULE_LENGTH = 1024L * 1024
        private val EMPTY = ByteArray(0)
    }
}

internal object UdpCapsuleEncoder {
    data class Frame(val bytes: ByteBuffer, val payloadOffset: Int)

    fun datagram(payload: ByteArray): Frame {
        require(payload.size <= ConnectUdpProtocol.MAX_PAYLOAD)
        val length = varint(payload.size.toLong() + 1)
        val output = ByteBuffer.allocate(2 + length.size + payload.size)
        output.put(0).put(length).put(0)
        val offset = output.position()
        output.put(payload).flip()
        return Frame(output, offset)
    }

    fun varint(value: Long): ByteArray {
        require(value in 0..((1L shl 62) - 1))
        val size = when { value < 64 -> 1; value < 16384 -> 2; value < (1L shl 30) -> 4; else -> 8 }
        val output = ByteArray(size)
        var n = value
        for (i in size - 1 downTo 0) { output[i] = n.toByte(); n = n ushr 8 }
        val prefix = when (size) { 1 -> 0; 2 -> 0x40; 4 -> 0x80; else -> 0xc0 }
        output[0] = (output[0].toInt() or prefix).toByte()
        return output
    }
}
