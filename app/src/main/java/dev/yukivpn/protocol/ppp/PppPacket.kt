package dev.yukivpn.protocol.ppp

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PppProtocol {
    const val IPV4 = 0x0021
    const val LCP = 0xc021
    const val PAP = 0xc023
    const val CHAP = 0xc223
    const val IPCP = 0x8021
}

data class PppFrame(val protocol: Int, val payload: ByteArray) {
    fun encode(includeAddressControl: Boolean = true): ByteArray {
        val headerSize = if (includeAddressControl) 4 else 2
        return ByteBuffer.allocate(headerSize + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            if (includeAddressControl) {
                put(0xff.toByte())
                put(0x03)
            }
            putShort(protocol.toShort())
            put(payload)
        }.array()
    }

    companion object {
        fun decode(bytes: ByteArray): PppFrame {
            require(bytes.isNotEmpty()) { "Empty PPP frame" }
            var offset = if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0x03.toByte()) 2 else 0
            require(offset < bytes.size) { "Truncated PPP frame" }
            val protocol = if (bytes[offset].toInt() and 1 != 0) {
                bytes[offset++].toInt() and 0xff
            } else {
                require(offset + 2 <= bytes.size) { "Truncated PPP protocol" }
                val value = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
                offset += 2
                value
            }
            return PppFrame(protocol, bytes.copyOfRange(offset, bytes.size))
        }
    }
}

data class PppControlPacket(val code: Int, val id: Int, val data: ByteArray = byteArrayOf()) {
    fun encode(): ByteArray = ByteBuffer.allocate(4 + data.size).order(ByteOrder.BIG_ENDIAN).apply {
        put(code.toByte())
        put(id.toByte())
        putShort((4 + data.size).toShort())
        put(data)
    }.array()

    companion object {
        fun decode(bytes: ByteArray): PppControlPacket {
            require(bytes.size >= 4) { "Truncated PPP control packet" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val code = buffer.get().toInt() and 0xff
            val id = buffer.get().toInt() and 0xff
            val length = buffer.short.toInt() and 0xffff
            require(length in 4..bytes.size) { "Invalid PPP control packet length" }
            return PppControlPacket(code, id, bytes.copyOfRange(4, length))
        }
    }
}

data class PppOption(val type: Int, val value: ByteArray) {
    fun encode(): ByteArray {
        require(value.size <= 253)
        return byteArrayOf(type.toByte(), (value.size + 2).toByte()) + value
    }

    companion object {
        fun decodeAll(bytes: ByteArray): List<PppOption> {
            val result = mutableListOf<PppOption>()
            var offset = 0
            while (offset < bytes.size) {
                require(offset + 2 <= bytes.size) { "Truncated PPP option" }
                val type = bytes[offset].toInt() and 0xff
                val length = bytes[offset + 1].toInt() and 0xff
                require(length >= 2 && offset + length <= bytes.size) { "Invalid PPP option length" }
                result += PppOption(type, bytes.copyOfRange(offset + 2, offset + length))
                offset += length
            }
            return result
        }
    }
}

