package dev.yukivpn.protocol.ipsec

import java.net.DatagramPacket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

data class EspSecurityAssociations(
    val inboundSpi: Int,
    val inboundEncryptionKey: ByteArray,
    val inboundAuthenticationKey: ByteArray,
    val outboundSpi: Int,
    val outboundEncryptionKey: ByteArray,
    val outboundAuthenticationKey: ByteArray,
    val transform: EspTransform,
)

class IkeQuickMode(
    private val random: SecureRandom = SecureRandom(),
    private val event: (String) -> Unit = {},
) {
    fun negotiate(session: IkeV1PskClient.Phase1Session): EspSecurityAssociations {
        val messageId = random.nextInt().let { if (it == 0) 1 else it }
        val inboundSpi = random.nextInt().let { if (it == 0) 1 else it }
        val nonceI = ByteArray(20).also(random::nextBytes)
        val sa = IpsecSa.quickMode(
            inboundSpi,
            listOf(
                EspTransform(IpsecSa.ESP_AES_CBC, 128),
                EspTransform(IpsecSa.ESP_3DES, null),
            ),
        )
        val idLocal = ipv4Id(session.localAddress.address)
        val idRemote = ipv4Id(session.remoteAddress.address)
        val requestRest = listOf(sa, IkePayload(IkePayloadType.NONCE, nonceI), idLocal, idRemote)
        val hash1 = IkeCrypto.hmacSha1(
            session.keys.skeyidA,
            intBytes(messageId) + IkePacket.encodePayloads(requestRest),
        )
        val requestPayloads = listOf(IkePayload(IkePayloadType.HASH, hash1)) + requestRest
        val requestPlaintext = IkePacket.encodePayloads(requestPayloads)
        val blockSize = blockSize(session.transform)
        val requestIv = MessageDigest.getInstance("SHA-1")
            .digest(session.phase1Iv + intBytes(messageId)).copyOf(blockSize)
        val requestCiphertext = IkeCrypto.crypt(
            true,
            session.transform,
            session.keys.encryptionKey,
            requestIv,
            requestPlaintext,
        )
        val request = encryptedPacket(session, messageId, IkePayloadType.HASH, requestCiphertext)
        val response = exchange(session, request)
        val responseCiphertext = requireNotNull(response.encryptedBody)
        val responseIv = requestCiphertext.takeLast(blockSize).toByteArray()
        val responsePlaintext = IkeCrypto.crypt(
            false,
            session.transform,
            session.keys.encryptionKey,
            responseIv,
            responseCiphertext,
        )
        val responsePayloads = IkePacket.decodePayloads(response.header.firstPayload, responsePlaintext)
        val receivedHash = responsePayloads.firstOrNull { it.type == IkePayloadType.HASH }?.body
            ?: error("Quick Mode response has no HASH")
        val responseRest = responsePayloads.drop(1)
        val responseSa = responseRest.firstOrNull { it.type == IkePayloadType.SA }
            ?: error("Quick Mode response has no SA")
        val nonceR = responseRest.firstOrNull { it.type == IkePayloadType.NONCE }?.body
            ?: error("Quick Mode response has no nonce")
        val expectedHash = IkeCrypto.hmacSha1(
            session.keys.skeyidA,
            intBytes(messageId) + nonceI + IkePacket.encodePayloads(responseRest),
        )
        require(MessageDigest.isEqual(receivedHash, expectedHash)) { "Quick Mode HASH(2) validation failed" }
        val (outboundSpi, espTransform) = IpsecSa.selected(responseSa)
        event("Quick Mode 已建立 ${espTransform.description()} ESP transport SA")

        val hash3 = IkeCrypto.hmacSha1(
            session.keys.skeyidA,
            byteArrayOf(0) + intBytes(messageId) + nonceI + nonceR,
        )
        val ackPlaintext = IkePacket.encodePayloads(listOf(IkePayload(IkePayloadType.HASH, hash3)))
        val ackIv = responseCiphertext.takeLast(blockSize).toByteArray()
        val ackCiphertext = IkeCrypto.crypt(
            true,
            session.transform,
            session.keys.encryptionKey,
            ackIv,
            ackPlaintext,
        )
        send(session, encryptedPacket(session, messageId, IkePayloadType.HASH, ackCiphertext))

        val encryptionBytes = when (espTransform.encryption) {
            IpsecSa.ESP_AES_CBC -> (espTransform.keyBits ?: 128) / 8
            IpsecSa.ESP_3DES -> 24
            else -> error("Unsupported ESP encryption ${espTransform.encryption}")
        }
        val keyBytes = encryptionBytes + AUTH_KEY_BYTES
        val inboundKeymat = keymat(session.keys.skeyidD, inboundSpi, nonceI, nonceR, keyBytes)
        val outboundKeymat = keymat(session.keys.skeyidD, outboundSpi, nonceI, nonceR, keyBytes)
        return EspSecurityAssociations(
            inboundSpi,
            inboundKeymat.copyOf(encryptionBytes),
            inboundKeymat.copyOfRange(encryptionBytes, keyBytes),
            outboundSpi,
            outboundKeymat.copyOf(encryptionBytes),
            outboundKeymat.copyOfRange(encryptionBytes, keyBytes),
            espTransform,
        )
    }

    private fun keymat(
        skeyidD: ByteArray,
        spi: Int,
        nonceI: ByteArray,
        nonceR: ByteArray,
        size: Int,
    ): ByteArray {
        val seed = byteArrayOf(3) + intBytes(spi) + nonceI + nonceR
        var previous = byteArrayOf()
        var output = byteArrayOf()
        while (output.size < size) {
            previous = IkeCrypto.hmacSha1(skeyidD, previous + seed)
            output += previous
        }
        return output.copyOf(size)
    }

    private fun exchange(session: IkeV1PskClient.Phase1Session, request: ByteArray): IkePacket {
        repeat(MAX_RETRIES) {
            send(session, request)
            try {
                val datagram = DatagramPacket(ByteArray(8192), 8192)
                session.socket.receive(datagram)
                val wire = datagram.data.copyOf(datagram.length)
                if (wire.size <= 4 || !wire.copyOfRange(0, 4).contentEquals(ByteArray(4))) return@repeat
                val response = IkePacket.decode(wire.copyOfRange(4, wire.size))
                require(response.header.initiatorCookie.contentEquals(session.initiatorCookie))
                require(response.header.responderCookie.contentEquals(session.responderCookie))
                require(response.header.exchangeType == IkePacket.QUICK_MODE)
                return response
            } catch (_: SocketTimeoutException) {
                // Retransmit Quick Mode request.
            }
        }
        throw SocketTimeoutException("IPsec Quick Mode timed out")
    }

    private fun send(session: IkeV1PskClient.Phase1Session, packet: ByteArray) {
        val wire = ByteArray(4) + packet
        session.socket.send(DatagramPacket(wire, wire.size))
    }

    private fun encryptedPacket(
        session: IkeV1PskClient.Phase1Session,
        messageId: Int,
        firstPayload: Int,
        ciphertext: ByteArray,
    ) = IkePacket(
        IkeHeader(
            session.initiatorCookie,
            session.responderCookie,
            firstPayload,
            IkePacket.QUICK_MODE,
            IkePacket.FLAG_ENCRYPTED,
            messageId,
            0,
        ),
        emptyList(),
        ciphertext,
    ).encode()

    private fun ipv4Id(address: ByteArray) = IkePayload(
        IkePayloadType.ID,
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .put(1).put(17).putShort(1701.toShort()).put(address).array(),
    )

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    private fun blockSize(transform: IkeTransform) = if (transform.encryption == IkeSa.AES_CBC) 16 else 8
    private fun EspTransform.description() =
        if (encryption == IpsecSa.ESP_AES_CBC) "AES-${keyBits ?: 128}/HMAC-SHA1" else "3DES/HMAC-SHA1"

    private companion object {
        const val MAX_RETRIES = 3
        const val AUTH_KEY_BYTES = 20
    }
}
