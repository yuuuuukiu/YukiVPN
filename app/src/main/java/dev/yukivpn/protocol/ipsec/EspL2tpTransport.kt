package dev.yukivpn.protocol.ipsec

import dev.yukivpn.protocol.l2tp.L2tpTransport
import java.net.DatagramPacket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class EspL2tpTransport(
    private val phase1: IkeV1PskClient.Phase1Session,
    private val sa: EspSecurityAssociations,
    private val random: SecureRandom = SecureRandom(),
) : L2tpTransport {
    override val peerAddress: String = phase1.remoteAddress.hostAddress.orEmpty()

    @Volatile
    private var logicalTimeout = 0
    private var outboundSequence = 0
    private val replayWindow = EspReplayWindow()

    override var timeoutMillis: Int
        get() = logicalTimeout
        set(value) {
            logicalTimeout = value
            phase1.socket.soTimeout = if (value == 0) KEEPALIVE_INTERVAL_MS else value
        }

    @Synchronized
    override fun send(payload: ByteArray) {
        val udp = ByteBuffer.allocate(UDP_HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(L2TP_PORT.toShort()).putShort(L2TP_PORT.toShort())
            .putShort((UDP_HEADER_SIZE + payload.size).toShort()).putShort(0)
            .put(payload).array()
        val sequence = ++outboundSequence
        check(sequence != 0) { "ESP sequence space exhausted" }
        val blockSize = if (sa.transform.encryption == IpsecSa.ESP_AES_CBC) 16 else 8
        val paddingSize = (blockSize - (udp.size + 2) % blockSize) % blockSize
        val padding = ByteArray(paddingSize) { (it + 1).toByte() }
        val plaintext = udp + padding + byteArrayOf(paddingSize.toByte(), UDP_NEXT_HEADER.toByte())
        val iv = ByteArray(blockSize).also(random::nextBytes)
        val ikeTransform = IkeTransform(
            if (sa.transform.encryption == IpsecSa.ESP_AES_CBC) IkeSa.AES_CBC else IkeSa.TRIPLE_DES,
            IkeSa.SHA1,
            IkeSa.GROUP2,
            sa.transform.keyBits,
        )
        val ciphertext = IkeCrypto.crypt(true, ikeTransform, sa.outboundEncryptionKey, iv, plaintext)
        val authenticated = intBytes(sa.outboundSpi) + intBytes(sequence) + iv + ciphertext
        val packet = authenticated + hmacSha1(sa.outboundAuthenticationKey, authenticated).copyOf(AUTH_TAG_SIZE)
        phase1.socket.send(DatagramPacket(packet, packet.size))
    }

    override fun receive(): ByteArray {
        while (true) {
            try {
                val datagram = DatagramPacket(ByteArray(65535), 65535)
                phase1.socket.receive(datagram)
                val packet = datagram.data.copyOf(datagram.length)
                if (packet.size == 1 && packet[0] == 0xff.toByte()) continue
                if (packet.size > 4 && packet.copyOfRange(0, 4).contentEquals(ByteArray(4))) continue
                decodeEsp(packet)?.let { return it }
            } catch (timeout: SocketTimeoutException) {
                if (logicalTimeout != 0) throw timeout
                phase1.socket.send(DatagramPacket(byteArrayOf(0xff.toByte()), 1))
            }
        }
    }

    private fun decodeEsp(packet: ByteArray): ByteArray? {
        require(packet.size >= 8 + AUTH_TAG_SIZE) { "Truncated ESP packet" }
        val spi = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        require(spi == sa.inboundSpi) { "Unexpected inbound ESP SPI" }
        val sequence = ByteBuffer.wrap(packet, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val authenticated = packet.copyOfRange(0, packet.size - AUTH_TAG_SIZE)
        val receivedTag = packet.copyOfRange(packet.size - AUTH_TAG_SIZE, packet.size)
        val expectedTag = hmacSha1(sa.inboundAuthenticationKey, authenticated).copyOf(AUTH_TAG_SIZE)
        require(MessageDigest.isEqual(receivedTag, expectedTag)) { "ESP authentication failed" }
        if (!replayWindow.accept(sequence)) return null
        val blockSize = if (sa.transform.encryption == IpsecSa.ESP_AES_CBC) 16 else 8
        require(authenticated.size >= 8 + blockSize * 2)
        val iv = authenticated.copyOfRange(8, 8 + blockSize)
        val ciphertext = authenticated.copyOfRange(8 + blockSize, authenticated.size)
        val ikeTransform = IkeTransform(
            if (sa.transform.encryption == IpsecSa.ESP_AES_CBC) IkeSa.AES_CBC else IkeSa.TRIPLE_DES,
            IkeSa.SHA1,
            IkeSa.GROUP2,
            sa.transform.keyBits,
        )
        val plaintext = IkeCrypto.crypt(false, ikeTransform, sa.inboundEncryptionKey, iv, ciphertext)
        require(plaintext.size >= UDP_HEADER_SIZE + 2)
        val paddingSize = plaintext[plaintext.size - 2].toInt() and 0xff
        val nextHeader = plaintext.last().toInt() and 0xff
        require(nextHeader == UDP_NEXT_HEADER && paddingSize + 2 <= plaintext.size)
        val udp = plaintext.copyOfRange(0, plaintext.size - paddingSize - 2)
        val sourcePort = IkePacket.ushort(udp, 0)
        val destinationPort = IkePacket.ushort(udp, 2)
        val udpLength = IkePacket.ushort(udp, 4)
        require(sourcePort == L2TP_PORT && destinationPort == L2TP_PORT)
        require(udpLength in UDP_HEADER_SIZE..udp.size)
        return udp.copyOfRange(UDP_HEADER_SIZE, udpLength)
    }

    override fun close() = phase1.close()

    private fun hmacSha1(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(key, "HmacSHA1"))
        doFinal(input)
    }

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private companion object {
        const val L2TP_PORT = 1701
        const val UDP_HEADER_SIZE = 8
        const val UDP_NEXT_HEADER = 17
        const val AUTH_TAG_SIZE = 12
        const val KEEPALIVE_INTERVAL_MS = 15_000
    }
}
