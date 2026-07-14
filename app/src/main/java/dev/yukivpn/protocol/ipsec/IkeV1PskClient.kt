package dev.yukivpn.protocol.ipsec

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

class IkeV1PskClient(
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val random: SecureRandom = SecureRandom(),
    private val event: (String) -> Unit = {},
) {
    data class Phase1Session(
        val socket: DatagramSocket,
        val remoteAddress: InetAddress,
        val localAddress: Inet4Address,
        val initiatorCookie: ByteArray,
        val responderCookie: ByteArray,
        val transform: IkeTransform,
        val keys: IkeCrypto.Phase1Keys,
        val phase1Iv: ByteArray,
    ) : Closeable {
        override fun close() = socket.close()
    }

    fun negotiate(host: String, preSharedKey: String): Phase1Session {
        require(preSharedKey.isNotEmpty()) { "IPsec 预共享密钥不能为空" }
        val remote = InetAddress.getByName(host)
        require(remote is Inet4Address) { "当前 IKEv1 实现仅支持 IPv4 网关" }
        val socket = DatagramSocket()
        try {
            check(protectSocket(socket)) { "无法将 IKE socket 排除在 VPN 路由之外" }
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(remote, IKE_PORT))
            val local = socket.localAddress as? Inet4Address ?: error("无法确定 IKE 本地 IPv4 地址")
            val cookieI = randomBytes(8).also { if (it.all { byte -> byte == 0.toByte() }) it[0] = 1 }
            val sa = IkeSa.phase1(
                listOf(
                    IkeTransform(IkeSa.AES_CBC, IkeSa.SHA1, IkeSa.GROUP14, 128),
                    IkeTransform(IkeSa.AES_CBC, IkeSa.SHA1, IkeSa.GROUP2, 128),
                    IkeTransform(IkeSa.TRIPLE_DES, IkeSa.SHA1, IkeSa.GROUP2, null),
                ),
            )
            val firstRequest = packet(
                cookieI,
                ByteArray(8),
                listOf(sa, IkePayload(IkePayloadType.VENDOR_ID, NAT_T_VENDOR_ID)),
            ).encode()
            val firstResponse = exchange(socket, firstRequest, natT = false)
            validateResponse(firstResponse, cookieI, ByteArray(8), allowZeroResponder = true)
            val cookieR = firstResponse.header.responderCookie
            require(cookieR.any { it != 0.toByte() }) { "IKE responder cookie is empty" }
            val selectedSa = firstResponse.payloads.firstOrNull { it.type == IkePayloadType.SA }
                ?: error("IKE responder did not select a Phase 1 proposal")
            val transform = IkeSa.selected(selectedSa)
            require(transform.hash == IkeSa.SHA1) { "Unsupported IKE hash ${transform.hash}" }
            event("IKEv1 Phase 1 已选择 ${transform.description()}")

            val dh = IkeCrypto.generateDh(transform.dhGroup, random)
            val nonceI = randomBytes(20)
            val natRemote = natDetection(cookieI, cookieR, remote, IKE_PORT)
            val secondRequest = packet(
                cookieI,
                cookieR,
                listOf(
                    IkePayload(IkePayloadType.KEY_EXCHANGE, dh.publicValue),
                    IkePayload(IkePayloadType.NONCE, nonceI),
                    IkePayload(IkePayloadType.NAT_D, randomBytes(20)),
                    IkePayload(IkePayloadType.NAT_D, natRemote),
                ),
            ).encode()
            val secondResponse = exchange(socket, secondRequest, natT = false)
            validateResponse(secondResponse, cookieI, cookieR)
            val publicR = secondResponse.required(IkePayloadType.KEY_EXCHANGE).body
            val nonceR = secondResponse.required(IkePayloadType.NONCE).body
            val shared = IkeCrypto.sharedSecret(dh, publicR)
            val blockSize = if (transform.encryption == IkeSa.AES_CBC) 16 else 8
            val keyBytes = if (transform.encryption == IkeSa.AES_CBC) (transform.keyBits ?: 128) / 8 else 24
            val keys = IkeCrypto.derivePhase1(
                preSharedKey.toByteArray(Charsets.UTF_8),
                nonceI,
                nonceR,
                shared,
                cookieI,
                cookieR,
                keyBytes,
            )

            socket.disconnect()
            socket.connect(InetSocketAddress(remote, NAT_T_PORT))
            val idI = ipv4Id(local)
            val hashI = IkeCrypto.hmacSha1(
                keys.skeyid,
                dh.publicValue + publicR + cookieI + cookieR + sa.body + idI.body,
            )
            val identityPlaintext = IkePacket.encodePayloads(
                listOf(idI, IkePayload(IkePayloadType.HASH, hashI)),
            )
            val initialIv = IkeCrypto.initialIv(dh.publicValue, publicR, blockSize)
            val identityCiphertext = IkeCrypto.crypt(true, transform, keys.encryptionKey, initialIv, identityPlaintext)
            val identityRequest = encryptedPacket(cookieI, cookieR, IkePayloadType.ID, identityCiphertext)
            val identityResponse = exchange(socket, identityRequest, natT = true)
            validateResponse(identityResponse, cookieI, cookieR)
            val responseCiphertext = requireNotNull(identityResponse.encryptedBody)
            val responseIv = identityCiphertext.copyOfRange(identityCiphertext.size - blockSize, identityCiphertext.size)
            val responsePlaintext = IkeCrypto.crypt(
                false,
                transform,
                keys.encryptionKey,
                responseIv,
                responseCiphertext,
            )
            val responsePayloads = IkePacket.decodePayloads(identityResponse.header.firstPayload, responsePlaintext)
            val idR = responsePayloads.firstOrNull { it.type == IkePayloadType.ID }
                ?: error("IKE responder identity is missing")
            val receivedHashR = responsePayloads.firstOrNull { it.type == IkePayloadType.HASH }?.body
                ?: error("IKE responder authentication hash is missing")
            val expectedHashR = IkeCrypto.hmacSha1(
                keys.skeyid,
                publicR + dh.publicValue + cookieR + cookieI + sa.body + idR.body,
            )
            require(MessageDigest.isEqual(receivedHashR, expectedHashR)) { "IKE PSK authentication failed" }
            event("IKEv1 PSK 身份认证成功，NAT-T 已启用")
            val finalIv = responseCiphertext.copyOfRange(responseCiphertext.size - blockSize, responseCiphertext.size)
            socket.soTimeout = TIMEOUT_MS
            return Phase1Session(socket, remote, local, cookieI, cookieR, transform, keys, finalIv)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    private fun exchange(socket: DatagramSocket, request: ByteArray, natT: Boolean): IkePacket {
        val wireRequest = if (natT) NON_ESP_MARKER + request else request
        repeat(MAX_RETRIES) {
            socket.send(DatagramPacket(wireRequest, wireRequest.size))
            try {
                val response = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE)
                socket.receive(response)
                var bytes = response.data.copyOf(response.length)
                if (natT) {
                    require(bytes.size > 4 && bytes.copyOfRange(0, 4).contentEquals(NON_ESP_MARKER)) {
                        "Expected a NAT-T IKE packet"
                    }
                    bytes = bytes.copyOfRange(4, bytes.size)
                }
                return IkePacket.decode(bytes)
            } catch (_: SocketTimeoutException) {
                // Retry the complete IKE request.
            }
        }
        throw SocketTimeoutException("IKEv1 negotiation timed out")
    }

    private fun packet(cookieI: ByteArray, cookieR: ByteArray, payloads: List<IkePayload>) = IkePacket(
        IkeHeader(cookieI, cookieR, payloads.first().type, IkePacket.MAIN_MODE, 0, 0, 0),
        payloads,
    )

    private fun encryptedPacket(cookieI: ByteArray, cookieR: ByteArray, firstType: Int, ciphertext: ByteArray) =
        IkePacket(
            IkeHeader(cookieI, cookieR, firstType, IkePacket.MAIN_MODE, IkePacket.FLAG_ENCRYPTED, 0, 0),
            emptyList(),
            ciphertext,
        ).encode()

    private fun validateResponse(
        packet: IkePacket,
        cookieI: ByteArray,
        cookieR: ByteArray,
        allowZeroResponder: Boolean = false,
    ) {
        require(packet.header.initiatorCookie.contentEquals(cookieI)) { "IKE initiator cookie mismatch" }
        if (!allowZeroResponder) {
            require(packet.header.responderCookie.contentEquals(cookieR)) { "IKE responder cookie mismatch" }
        }
        require(packet.header.exchangeType == IkePacket.MAIN_MODE && packet.header.messageId == 0) {
            "Unexpected IKE exchange"
        }
    }

    private fun ipv4Id(address: Inet4Address): IkePayload = IkePayload(
        IkePayloadType.ID,
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .put(1).put(0).putShort(0).put(address.address).array(),
    )

    private fun natDetection(cookieI: ByteArray, cookieR: ByteArray, address: InetAddress, port: Int): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(
            cookieI + cookieR + address.address +
                ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(port.toShort()).array(),
        )

    private fun IkePacket.required(type: Int) = payloads.firstOrNull { it.type == type }
        ?: error("IKE response is missing payload $type")

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun IkeTransform.description(): String {
        val encryption = if (this.encryption == IkeSa.AES_CBC) "AES-${keyBits ?: 128}" else "3DES"
        return "$encryption/SHA1/DH$dhGroup"
    }

    private companion object {
        const val IKE_PORT = 500
        const val NAT_T_PORT = 4500
        const val TIMEOUT_MS = 3_000
        const val MAX_RETRIES = 3
        const val MAX_PACKET_SIZE = 8192
        val NON_ESP_MARKER = ByteArray(4)
        val NAT_T_VENDOR_ID = "4a131c81070358455c5728f20e95452f".chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
    }
}
