package dev.yukivpn.protocol.ipsec

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class IkeTransform(val encryption: Int, val hash: Int, val dhGroup: Int, val keyBits: Int?)

object IkeSa {
    const val AES_CBC = 7
    const val TRIPLE_DES = 5
    const val SHA1 = 2
    const val PSK = 1
    const val GROUP2 = 2
    const val GROUP14 = 14

    private const val ENCRYPTION = 1
    private const val HASH = 2
    private const val AUTH = 3
    private const val GROUP = 4
    private const val LIFE_TYPE = 11
    private const val LIFE_DURATION = 12
    private const val KEY_LENGTH = 14

    fun phase1(transforms: List<IkeTransform>): IkePayload {
        val encodedTransforms = transforms.mapIndexed { index, transform ->
            val attributes = mutableListOf(
                IkeAttribute.basic(ENCRYPTION, transform.encryption),
                IkeAttribute.basic(HASH, transform.hash),
                IkeAttribute.basic(AUTH, PSK),
                IkeAttribute.basic(GROUP, transform.dhGroup),
                IkeAttribute.basic(LIFE_TYPE, 1),
                IkeAttribute(LIFE_DURATION, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(28_800).array(), false),
            )
            transform.keyBits?.let { attributes += IkeAttribute.basic(KEY_LENGTH, it) }
            val body = byteArrayOf((index + 1).toByte(), 1, 0, 0) + attributes.fold(byteArrayOf()) { out, attr -> out + attr.encode() }
            IkePacket.generic(if (index == transforms.lastIndex) 0 else IkePayloadType.TRANSFORM, body)
        }.fold(byteArrayOf()) { out, value -> out + value }
        val proposalBody = byteArrayOf(1, 1, 0, transforms.size.toByte()) + encodedTransforms
        val proposal = IkePacket.generic(0, proposalBody)
        return IkePayload(
            IkePayloadType.SA,
            ByteBuffer.allocate(8 + proposal.size).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(1).put(proposal).array(),
        )
    }

    fun selected(sa: IkePayload): IkeTransform {
        require(sa.type == IkePayloadType.SA && sa.body.size >= 24)
        val proposalOffset = 8
        val proposalLength = IkePacket.ushort(sa.body, proposalOffset + 2)
        require(proposalLength >= 16 && proposalOffset + proposalLength <= sa.body.size)
        val transformOffset = proposalOffset + 8
        val transformLength = IkePacket.ushort(sa.body, transformOffset + 2)
        require(transformLength >= 8 && transformOffset + transformLength <= sa.body.size)
        val attrs = IkeAttribute.decodeAll(sa.body.copyOfRange(transformOffset + 8, transformOffset + transformLength))
        fun required(type: Int) = attrs.first { it.type == type }.asInt()
        require(required(AUTH) == PSK)
        return IkeTransform(
            encryption = required(ENCRYPTION),
            hash = required(HASH),
            dhGroup = required(GROUP),
            keyBits = attrs.firstOrNull { it.type == KEY_LENGTH }?.asInt(),
        )
    }
}

