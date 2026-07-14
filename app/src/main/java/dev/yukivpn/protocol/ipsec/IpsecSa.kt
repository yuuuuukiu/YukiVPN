package dev.yukivpn.protocol.ipsec

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EspTransform(val encryption: Int, val keyBits: Int?)

object IpsecSa {
    const val ESP_3DES = 3
    const val ESP_AES_CBC = 12
    const val AUTH_HMAC_SHA1 = 2
    const val UDP_TRANSPORT = 4

    private const val LIFE_TYPE = 1
    private const val LIFE_DURATION = 2
    private const val ENCAPSULATION_MODE = 4
    private const val AUTH_ALGORITHM = 5
    private const val KEY_LENGTH = 6

    fun quickMode(spi: Int, transforms: List<EspTransform>): IkePayload {
        require(spi != 0 && transforms.isNotEmpty())
        val encoded = transforms.mapIndexed { index, transform ->
            val attributes = mutableListOf(
                IkeAttribute.basic(LIFE_TYPE, 1),
                IkeAttribute(LIFE_DURATION, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(3_600).array(), false),
                IkeAttribute.basic(ENCAPSULATION_MODE, UDP_TRANSPORT),
                IkeAttribute.basic(AUTH_ALGORITHM, AUTH_HMAC_SHA1),
            )
            transform.keyBits?.let { attributes += IkeAttribute.basic(KEY_LENGTH, it) }
            val body = byteArrayOf((index + 1).toByte(), transform.encryption.toByte(), 0, 0) +
                attributes.fold(byteArrayOf()) { out, attr -> out + attr.encode() }
            IkePacket.generic(if (index == transforms.lastIndex) 0 else IkePayloadType.TRANSFORM, body)
        }.fold(byteArrayOf()) { out, item -> out + item }
        val proposal = byteArrayOf(1, 3, 4, transforms.size.toByte()) + intBytes(spi) + encoded
        val genericProposal = IkePacket.generic(0, proposal)
        return IkePayload(
            IkePayloadType.SA,
            ByteBuffer.allocate(8 + genericProposal.size).order(ByteOrder.BIG_ENDIAN)
                .putInt(1).putInt(1).put(genericProposal).array(),
        )
    }

    fun selected(sa: IkePayload): Pair<Int, EspTransform> {
        require(sa.type == IkePayloadType.SA && sa.body.size >= 28)
        val proposal = 8
        val proposalLength = IkePacket.ushort(sa.body, proposal + 2)
        require(proposalLength >= 20 && proposal + proposalLength <= sa.body.size)
        val spiSize = sa.body[proposal + 6].toInt() and 0xff
        require(spiSize == 4) { "Unexpected ESP SPI size" }
        val spi = ByteBuffer.wrap(sa.body, proposal + 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val transform = proposal + 8 + spiSize
        val transformLength = IkePacket.ushort(sa.body, transform + 2)
        require(transformLength >= 8 && transform + transformLength <= sa.body.size)
        val encryption = sa.body[transform + 5].toInt() and 0xff
        val attributes = IkeAttribute.decodeAll(sa.body.copyOfRange(transform + 8, transform + transformLength))
        require(attributes.first { it.type == ENCAPSULATION_MODE }.asInt() == UDP_TRANSPORT)
        require(attributes.first { it.type == AUTH_ALGORITHM }.asInt() == AUTH_HMAC_SHA1)
        return spi to EspTransform(
            encryption,
            attributes.firstOrNull { it.type == KEY_LENGTH }?.asInt(),
        )
    }

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
}

