package dev.yukivpn.protocol.ipsec

import java.nio.ByteBuffer
import java.nio.ByteOrder

object IkePayloadType {
    const val NONE = 0
    const val SA = 1
    const val PROPOSAL = 2
    const val TRANSFORM = 3
    const val KEY_EXCHANGE = 4
    const val ID = 5
    const val HASH = 8
    const val NONCE = 10
    const val NOTIFY = 11
    const val DELETE = 12
    const val VENDOR_ID = 13
    const val NAT_D = 20
}

data class IkePayload(val type: Int, val body: ByteArray)

data class IkeHeader(
    val initiatorCookie: ByteArray,
    val responderCookie: ByteArray,
    val firstPayload: Int,
    val exchangeType: Int,
    val flags: Int,
    val messageId: Int,
    val length: Int,
) {
    init { require(initiatorCookie.size == 8 && responderCookie.size == 8) }
}

data class IkePacket(val header: IkeHeader, val payloads: List<IkePayload>, val encryptedBody: ByteArray? = null) {
    fun encode(): ByteArray {
        val body = encryptedBody ?: encodePayloads(payloads)
        val firstPayload = payloads.firstOrNull()?.type ?: header.firstPayload
        return ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(header.initiatorCookie)
            put(header.responderCookie)
            put(firstPayload.toByte())
            put(VERSION.toByte())
            put(header.exchangeType.toByte())
            put(header.flags.toByte())
            putInt(header.messageId)
            putInt(HEADER_SIZE + body.size)
            put(body)
        }.array()
    }

    companion object {
        const val HEADER_SIZE = 28
        const val VERSION = 0x10
        const val FLAG_ENCRYPTED = 1
        const val MAIN_MODE = 2
        const val QUICK_MODE = 32

        fun decode(bytes: ByteArray): IkePacket {
            require(bytes.size >= HEADER_SIZE) { "Truncated ISAKMP header" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val cookieI = ByteArray(8).also(buffer::get)
            val cookieR = ByteArray(8).also(buffer::get)
            val first = buffer.get().toInt() and 0xff
            val version = buffer.get().toInt() and 0xff
            require(version ushr 4 == 1) { "Unsupported IKE version" }
            val exchange = buffer.get().toInt() and 0xff
            val flags = buffer.get().toInt() and 0xff
            val messageId = buffer.int
            val length = buffer.int
            require(length in HEADER_SIZE..bytes.size) { "Invalid ISAKMP length" }
            val body = bytes.copyOfRange(HEADER_SIZE, length)
            val header = IkeHeader(cookieI, cookieR, first, exchange, flags, messageId, length)
            return if (flags and FLAG_ENCRYPTED != 0) {
                IkePacket(header, emptyList(), body)
            } else {
                IkePacket(header, decodePayloads(first, body))
            }
        }

        fun encodePayloads(payloads: List<IkePayload>): ByteArray =
            ByteBuffer.allocate(payloads.sumOf { 4 + it.body.size }).order(ByteOrder.BIG_ENDIAN).apply {
                payloads.forEachIndexed { index, payload ->
                    put((payloads.getOrNull(index + 1)?.type ?: IkePayloadType.NONE).toByte())
                    put(0)
                    putShort((payload.body.size + 4).toShort())
                    put(payload.body)
                }
            }.array()

        fun decodePayloads(firstType: Int, bytes: ByteArray): List<IkePayload> {
            val result = mutableListOf<IkePayload>()
            var type = firstType
            var offset = 0
            while (type != IkePayloadType.NONE) {
                require(offset + 4 <= bytes.size) { "Truncated IKE payload" }
                val next = bytes[offset].toInt() and 0xff
                val length = ushort(bytes, offset + 2)
                require(length >= 4 && offset + length <= bytes.size) { "Invalid IKE payload length" }
                result += IkePayload(type, bytes.copyOfRange(offset + 4, offset + length))
                offset += length
                type = next
            }
            return result
        }

        internal fun generic(nextType: Int, body: ByteArray): ByteArray =
            ByteBuffer.allocate(body.size + 4).order(ByteOrder.BIG_ENDIAN)
                .put(nextType.toByte()).put(0).putShort((body.size + 4).toShort()).put(body).array()

        internal fun ushort(bytes: ByteArray, offset: Int) =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }
}

data class IkeAttribute(val type: Int, val value: ByteArray, val basic: Boolean) {
    fun encode(): ByteArray = if (basic) {
        require(value.size == 2)
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort((type or 0x8000).toShort()).put(value).array()
    } else {
        ByteBuffer.allocate(value.size + 4).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort()).putShort(value.size.toShort()).put(value).array()
    }

    fun asInt(): Int {
        require(value.size == 2)
        return IkePacket.ushort(value, 0)
    }

    companion object {
        fun basic(type: Int, value: Int) = IkeAttribute(
            type,
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array(),
            true,
        )

        fun decodeAll(bytes: ByteArray): List<IkeAttribute> {
            val result = mutableListOf<IkeAttribute>()
            var offset = 0
            while (offset < bytes.size) {
                require(offset + 4 <= bytes.size)
                val rawType = IkePacket.ushort(bytes, offset)
                val basic = rawType and 0x8000 != 0
                val type = rawType and 0x7fff
                val lengthOrValue = IkePacket.ushort(bytes, offset + 2)
                if (basic) {
                    result += basic(type, lengthOrValue)
                    offset += 4
                } else {
                    require(offset + 4 + lengthOrValue <= bytes.size)
                    result += IkeAttribute(type, bytes.copyOfRange(offset + 4, offset + 4 + lengthOrValue), false)
                    offset += 4 + lengthOrValue
                }
            }
            return result
        }
    }
}

