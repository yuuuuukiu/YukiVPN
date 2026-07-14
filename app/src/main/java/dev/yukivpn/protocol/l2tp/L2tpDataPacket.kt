package dev.yukivpn.protocol.l2tp

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class L2tpDataPacket(
    val tunnelId: Int,
    val sessionId: Int,
    val payload: ByteArray,
    val ns: Int? = null,
    val nr: Int? = null,
) {
    fun encode(): ByteArray {
        require((ns == null) == (nr == null)) { "Ns and Nr must either both be present or absent" }
        val sequenced = ns != null
        val headerLength = if (sequenced) 10 else 6
        val buffer = ByteBuffer.allocate(headerLength + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort((VERSION or if (sequenced) SEQUENCE_FLAG else 0).toShort())
        buffer.putShort(tunnelId.toShort())
        buffer.putShort(sessionId.toShort())
        if (sequenced) {
            buffer.putShort(ns!!.toShort())
            buffer.putShort(nr!!.toShort())
        }
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        private const val TYPE_FLAG = 0x8000
        private const val LENGTH_FLAG = 0x4000
        private const val SEQUENCE_FLAG = 0x0800
        private const val OFFSET_FLAG = 0x0200
        private const val VERSION = 0x0002

        fun decode(bytes: ByteArray, size: Int = bytes.size): L2tpDataPacket {
            require(size in 6..bytes.size) { "Invalid L2TP data packet size" }
            val buffer = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.BIG_ENDIAN)
            val flags = buffer.short.toInt() and 0xffff
            require(flags and TYPE_FLAG == 0) { "Control packet passed to data decoder" }
            require(flags and 0x000f == VERSION) { "Unsupported L2TP version" }
            val declaredLength = if (flags and LENGTH_FLAG != 0) {
                (buffer.short.toInt() and 0xffff).also { require(it <= size) { "Truncated L2TP data packet" } }
            } else {
                size
            }
            val tunnelId = buffer.short.toInt() and 0xffff
            val sessionId = buffer.short.toInt() and 0xffff
            val sequenced = flags and SEQUENCE_FLAG != 0
            val ns = if (sequenced) buffer.short.toInt() and 0xffff else null
            val nr = if (sequenced) buffer.short.toInt() and 0xffff else null
            if (flags and OFFSET_FLAG != 0) {
                val offset = buffer.short.toInt() and 0xffff
                require(buffer.position() + offset <= declaredLength) { "Invalid L2TP payload offset" }
                buffer.position(buffer.position() + offset)
            }
            require(buffer.position() <= declaredLength) { "Invalid L2TP data header" }
            return L2tpDataPacket(
                tunnelId = tunnelId,
                sessionId = sessionId,
                payload = ByteArray(declaredLength - buffer.position()).also(buffer::get),
                ns = ns,
                nr = nr,
            )
        }
    }
}

