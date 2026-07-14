package dev.yukivpn.protocol.l2tp

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class L2tpAvp(
    val type: Int,
    val value: ByteArray,
    val vendorId: Int = 0,
    val mandatory: Boolean = true,
) {
    fun unsignedShort(): Int {
        require(value.size == 2) { "AVP $type is not a 16-bit value" }
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    }
}

data class L2tpPacket(
    val tunnelId: Int,
    val sessionId: Int = 0,
    val ns: Int,
    val nr: Int,
    val avps: List<L2tpAvp>,
) {
    val messageType: Int?
        get() = avps.firstOrNull { it.vendorId == 0 && it.type == AvpType.MESSAGE_TYPE }
            ?.unsignedShort()

    fun encode(): ByteArray {
        val length = HEADER_LENGTH + avps.sumOf { AVP_HEADER_LENGTH + it.value.size }
        require(length <= 65535) { "L2TP packet is too large" }
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(CONTROL_FLAGS.toShort())
        buffer.putShort(length.toShort())
        buffer.putShort(tunnelId.toShort())
        buffer.putShort(sessionId.toShort())
        buffer.putShort(ns.toShort())
        buffer.putShort(nr.toShort())
        avps.forEach { avp ->
            require(avp.value.size + AVP_HEADER_LENGTH <= AVP_LENGTH_MASK)
            val flags = (if (avp.mandatory) AVP_MANDATORY else 0) or
                (avp.value.size + AVP_HEADER_LENGTH)
            buffer.putShort(flags.toShort())
            buffer.putShort(avp.vendorId.toShort())
            buffer.putShort(avp.type.toShort())
            buffer.put(avp.value)
        }
        return buffer.array()
    }

    companion object {
        private const val CONTROL_FLAGS = 0xC802
        private const val HEADER_LENGTH = 12
        private const val AVP_HEADER_LENGTH = 6
        private const val AVP_MANDATORY = 0x8000
        private const val AVP_HIDDEN = 0x4000
        private const val AVP_LENGTH_MASK = 0x03ff

        fun decode(bytes: ByteArray, size: Int = bytes.size): L2tpPacket {
            require(size >= HEADER_LENGTH && size <= bytes.size) { "Invalid L2TP packet length" }
            val buffer = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.BIG_ENDIAN)
            val flags = buffer.short.toInt() and 0xffff
            require(flags and 0x8000 != 0) { "Data packets are not supported here" }
            require(flags and 0x4000 != 0) { "Control packet has no length field" }
            require(flags and 0x0800 != 0) { "Control packet has no sequence fields" }
            require(flags and 0x000f == 2) { "Unsupported L2TP version" }
            val declaredLength = buffer.short.toInt() and 0xffff
            require(declaredLength in HEADER_LENGTH..size) { "Truncated L2TP packet" }
            val tunnelId = buffer.short.toInt() and 0xffff
            val sessionId = buffer.short.toInt() and 0xffff
            val ns = buffer.short.toInt() and 0xffff
            val nr = buffer.short.toInt() and 0xffff
            val avps = mutableListOf<L2tpAvp>()
            while (buffer.position() < declaredLength) {
                require(declaredLength - buffer.position() >= AVP_HEADER_LENGTH) { "Truncated AVP" }
                val avpFlags = buffer.short.toInt() and 0xffff
                val avpLength = avpFlags and AVP_LENGTH_MASK
                require(avpFlags and AVP_HIDDEN == 0) { "Hidden AVPs require tunnel secret handling" }
                require(avpLength >= AVP_HEADER_LENGTH && buffer.position() + avpLength - 2 <= declaredLength) {
                    "Invalid AVP length"
                }
                val vendor = buffer.short.toInt() and 0xffff
                val type = buffer.short.toInt() and 0xffff
                val value = ByteArray(avpLength - AVP_HEADER_LENGTH)
                buffer.get(value)
                avps += L2tpAvp(type, value, vendor, avpFlags and AVP_MANDATORY != 0)
            }
            return L2tpPacket(tunnelId, sessionId, ns, nr, avps)
        }
    }
}

object AvpType {
    const val MESSAGE_TYPE = 0
    const val PROTOCOL_VERSION = 2
    const val FRAMING_CAPABILITIES = 3
    const val HOST_NAME = 7
    const val ASSIGNED_TUNNEL_ID = 9
    const val RECEIVE_WINDOW_SIZE = 10
}

object MessageType {
    const val SCCRQ = 1
    const val SCCRP = 2
    const val SCCCN = 3
}

