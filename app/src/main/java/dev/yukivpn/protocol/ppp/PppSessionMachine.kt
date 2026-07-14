package dev.yukivpn.protocol.ppp

import android.annotation.SuppressLint
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class PppSessionMachine(
    private val username: String,
    private val password: String,
    private val random: SecureRandom = SecureRandom(),
    private val event: (String) -> Unit = {},
) {
    enum class State { INITIAL, LCP, AUTHENTICATING, IPCP, OPEN, FAILED }
    enum class Authentication { NONE, PAP, CHAP_MD5, MSCHAP_V2 }

    data class NetworkConfig(val address: String, val dnsServers: List<String>)

    var state = State.INITIAL
        private set
    var authentication = Authentication.NONE
        private set
    var networkConfig: NetworkConfig? = null
        private set

    private var nextId = 1
    private var localLcpId = 0
    private var localLcpAcked = false
    private var peerLcpAcked = false
    private var localIpcpId = 0
    private var localIpcpAcked = false
    private var peerIpcpAcked = false
    private val localLcpOptions = mutableListOf<PppOption>()
    private var lcpOptionsInitialized = false
    private var requestPrimaryDns = true
    private var requestSecondaryDns = true
    private var address = "0.0.0.0"
    private val dns = linkedSetOf<String>()

    fun start(): List<PppFrame> {
        check(state == State.INITIAL)
        state = State.LCP
        event("PPP 开始 LCP 协商")
        return listOf(lcpRequest())
    }

    fun receive(frame: PppFrame): List<PppFrame> {
        val control = runCatching { PppControlPacket.decode(frame.payload) }.getOrNull()
        event("PPP RX ${protocolName(frame.protocol)}${control?.let { " code=${it.code} id=${it.id}" }.orEmpty()} state=$state")
        return when (frame.protocol) {
        PppProtocol.LCP -> receiveLcp(PppControlPacket.decode(frame.payload))
        PppProtocol.PAP -> receivePap(PppControlPacket.decode(frame.payload))
        PppProtocol.CHAP -> receiveChap(PppControlPacket.decode(frame.payload))
        PppProtocol.IPCP -> receiveIpcp(PppControlPacket.decode(frame.payload))
        else -> emptyList()
        }
    }

    private fun receiveLcp(packet: PppControlPacket): List<PppFrame> {
        if (packet.code == TERMINATE_REQUEST) {
            state = State.FAILED
            return listOf(control(PppProtocol.LCP, TERMINATE_ACK, packet.id, packet.data))
        }
        if (state != State.LCP) {
            return if (packet.code == ECHO_REQUEST) {
                listOf(control(PppProtocol.LCP, ECHO_REPLY, packet.id, echoReplyData(packet.data)))
            } else emptyList()
        }
        return when (packet.code) {
            CONFIGURE_REQUEST -> handlePeerLcpRequest(packet)
            CONFIGURE_ACK -> {
                if (packet.id == localLcpId) localLcpAcked = true
                advanceAfterLcp()
            }
            CONFIGURE_NAK -> {
                applyLcpNak(packet.data)
                localLcpAcked = false
                listOf(lcpRequest())
            }
            CONFIGURE_REJECT -> {
                val rejectedTypes = PppOption.decodeAll(packet.data).map { it.type }.toSet()
                localLcpOptions.removeAll { it.type in rejectedTypes }
                localLcpAcked = false
                listOf(lcpRequest())
            }
            ECHO_REQUEST -> listOf(control(PppProtocol.LCP, ECHO_REPLY, packet.id, echoReplyData(packet.data)))
            else -> emptyList()
        }
    }

    private fun handlePeerLcpRequest(packet: PppControlPacket): List<PppFrame> {
        val options = PppOption.decodeAll(packet.data)
        val rejected = mutableListOf<PppOption>()
        var requestedAuth = Authentication.NONE
        options.forEach { option ->
            when (option.type) {
                LCP_MRU, LCP_ACCM, LCP_MAGIC, LCP_PFC, LCP_ACFC -> Unit
                LCP_AUTH -> {
                    requestedAuth = parseAuthentication(option)
                    if (requestedAuth == Authentication.NONE) rejected += option
                }
                else -> rejected += option
            }
        }
        if (rejected.isNotEmpty()) {
            return listOf(
                control(PppProtocol.LCP, CONFIGURE_REJECT, packet.id, rejected.flatMapBytes { it.encode() }),
            )
        }
        authentication = requestedAuth
        event("PPP 对端要求认证方式 $authentication")
        peerLcpAcked = true
        val output = mutableListOf(control(PppProtocol.LCP, CONFIGURE_ACK, packet.id, packet.data))
        output += advanceAfterLcp()
        return output
    }

    private fun advanceAfterLcp(): List<PppFrame> {
        if (!localLcpAcked || !peerLcpAcked || state != State.LCP) return emptyList()
        return when (authentication) {
            Authentication.NONE -> startIpcp()
            Authentication.PAP -> {
                state = State.AUTHENTICATING
                listOf(papRequest())
            }
            Authentication.CHAP_MD5, Authentication.MSCHAP_V2 -> {
                state = State.AUTHENTICATING
                emptyList()
            }
        }
    }

    private fun receivePap(packet: PppControlPacket): List<PppFrame> {
        if (state != State.AUTHENTICATING || authentication != Authentication.PAP) return emptyList()
        return when (packet.code) {
            PAP_AUTH_ACK -> startIpcp()
            PAP_AUTH_NAK -> { state = State.FAILED; emptyList() }
            else -> emptyList()
        }
    }

    private fun receiveChap(packet: PppControlPacket): List<PppFrame> {
        if (state != State.AUTHENTICATING || authentication !in setOf(Authentication.CHAP_MD5, Authentication.MSCHAP_V2)) {
            return emptyList()
        }
        return when (packet.code) {
            CHAP_CHALLENGE -> listOf(
                if (authentication == Authentication.MSCHAP_V2) msChapV2Response(packet) else chapMd5Response(packet),
            )
            CHAP_SUCCESS -> startIpcp()
            CHAP_FAILURE -> { state = State.FAILED; emptyList() }
            else -> emptyList()
        }
    }

    private fun startIpcp(): List<PppFrame> {
        state = State.IPCP
        return listOf(ipcpRequest())
    }

    private fun receiveIpcp(packet: PppControlPacket): List<PppFrame> {
        if (state != State.IPCP && state != State.OPEN) return emptyList()
        return when (packet.code) {
            CONFIGURE_REQUEST -> {
                peerIpcpAcked = true
                val output = mutableListOf(control(PppProtocol.IPCP, CONFIGURE_ACK, packet.id, packet.data))
                finishIpcp()
                output
            }
            CONFIGURE_ACK -> {
                if (packet.id == localIpcpId) localIpcpAcked = true
                finishIpcp()
                emptyList()
            }
            CONFIGURE_NAK -> {
                PppOption.decodeAll(packet.data).forEach(::acceptIpcpOption)
                localIpcpAcked = false
                listOf(ipcpRequest())
            }
            CONFIGURE_REJECT -> {
                PppOption.decodeAll(packet.data).forEach {
                    when (it.type) {
                        IPCP_PRIMARY_DNS -> requestPrimaryDns = false
                        IPCP_SECONDARY_DNS -> requestSecondaryDns = false
                        IPCP_ADDRESS -> state = State.FAILED
                    }
                }
                if (state == State.FAILED) emptyList() else listOf(ipcpRequest())
            }
            else -> emptyList()
        }
    }

    private fun finishIpcp() {
        if (localIpcpAcked && peerIpcpAcked && address != "0.0.0.0") {
            networkConfig = NetworkConfig(address, dns.filter { it != "0.0.0.0" })
            state = State.OPEN
            event("PPP IPCP 已完成，地址 $address，DNS ${networkConfig?.dnsServers.orEmpty().joinToString()}")
        }
    }

    private fun lcpRequest(): PppFrame {
        localLcpId = allocateId()
        if (!lcpOptionsInitialized) {
            lcpOptionsInitialized = true
            localLcpOptions += PppOption(LCP_MRU, shortBytes(1400))
            localLcpOptions += PppOption(LCP_MAGIC, ByteArray(4).also(random::nextBytes))
        }
        return control(PppProtocol.LCP, CONFIGURE_REQUEST, localLcpId, localLcpOptions.flatMapBytes { it.encode() })
    }

    private fun applyLcpNak(data: ByteArray) {
        PppOption.decodeAll(data).forEach { suggested ->
            val index = localLcpOptions.indexOfFirst { it.type == suggested.type }
            when (suggested.type) {
                LCP_MRU -> if (suggested.value.size == 2 && index >= 0) localLcpOptions[index] = suggested
                LCP_MAGIC -> if (index >= 0) {
                    localLcpOptions[index] = PppOption(LCP_MAGIC, ByteArray(4).also(random::nextBytes))
                }
            }
        }
    }

    private fun echoReplyData(requestData: ByteArray): ByteArray {
        val localMagic = localLcpOptions.firstOrNull { it.type == LCP_MAGIC }
            ?.value
            ?.takeIf { it.size == 4 }
            ?: ByteArray(4)
        return localMagic + requestData.drop(4).toByteArray()
    }

    private fun papRequest(): PppFrame {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        require(user.size <= 255 && pass.size <= 255)
        return control(
            PppProtocol.PAP,
            PAP_AUTH_REQUEST,
            allocateId(),
            byteArrayOf(user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass,
        )
    }

    private fun ipcpRequest(): PppFrame {
        localIpcpId = allocateId()
        val options = mutableListOf(PppOption(IPCP_ADDRESS, ipv4Bytes(address)))
        if (dns.isEmpty()) {
            if (requestPrimaryDns) options += PppOption(IPCP_PRIMARY_DNS, ipv4Bytes("0.0.0.0"))
            if (requestSecondaryDns) options += PppOption(IPCP_SECONDARY_DNS, ipv4Bytes("0.0.0.0"))
        } else {
            dns.forEachIndexed { index, value ->
                val type = if (index == 0) IPCP_PRIMARY_DNS else IPCP_SECONDARY_DNS
                if ((type == IPCP_PRIMARY_DNS && requestPrimaryDns) ||
                    (type == IPCP_SECONDARY_DNS && requestSecondaryDns)
                ) {
                    options += PppOption(type, ipv4Bytes(value))
                }
            }
        }
        return control(PppProtocol.IPCP, CONFIGURE_REQUEST, localIpcpId, options.flatMapBytes { it.encode() })
    }

    private fun acceptIpcpOption(option: PppOption) {
        if (option.value.size != 4) return
        val value = InetAddress.getByAddress(option.value).hostAddress.orEmpty()
        when (option.type) {
            IPCP_ADDRESS -> address = value
            IPCP_PRIMARY_DNS, IPCP_SECONDARY_DNS -> if (value != "0.0.0.0") dns += value
        }
    }

    private fun parseAuthentication(option: PppOption): Authentication {
        if (option.value.size < 2) return Authentication.NONE
        val protocol = ((option.value[0].toInt() and 0xff) shl 8) or (option.value[1].toInt() and 0xff)
        return when {
            protocol == PppProtocol.PAP -> Authentication.PAP
            protocol == PppProtocol.CHAP && option.value.getOrNull(2)?.toInt()?.and(0xff) == CHAP_MD5 -> Authentication.CHAP_MD5
            protocol == PppProtocol.CHAP && option.value.getOrNull(2)?.toInt()?.and(0xff) == CHAP_MS_V2 -> Authentication.MSCHAP_V2
            else -> Authentication.NONE
        }
    }

    private fun msChapV2Response(packet: PppControlPacket): PppFrame {
        require(packet.data.isNotEmpty()) { "Missing MS-CHAPv2 challenge" }
        val challengeSize = packet.data[0].toInt() and 0xff
        require(challengeSize == 16 && packet.data.size >= 17) { "Invalid MS-CHAPv2 challenge" }
        val authenticatorChallenge = packet.data.copyOfRange(1, 17)
        val peerChallenge = ByteArray(16).also(random::nextBytes)
        val ntResponse = generateNtResponse(authenticatorChallenge, peerChallenge, username, password)
        val value = peerChallenge + ByteArray(8) + ntResponse + byteArrayOf(0)
        val user = username.toByteArray(Charsets.UTF_8)
        return control(PppProtocol.CHAP, CHAP_RESPONSE, packet.id, byteArrayOf(49) + value + user)
    }

    private fun chapMd5Response(packet: PppControlPacket): PppFrame {
        require(packet.data.isNotEmpty()) { "Missing CHAP challenge" }
        val challengeSize = packet.data[0].toInt() and 0xff
        require(challengeSize > 0 && packet.data.size >= challengeSize + 1) { "Invalid CHAP challenge" }
        val challenge = packet.data.copyOfRange(1, challengeSize + 1)
        val digest = MessageDigest.getInstance("MD5").digest(
            byteArrayOf(packet.id.toByte()) + password.toByteArray(Charsets.UTF_8) + challenge,
        )
        val user = username.toByteArray(Charsets.UTF_8)
        return control(PppProtocol.CHAP, CHAP_RESPONSE, packet.id, byteArrayOf(digest.size.toByte()) + digest + user)
    }

    private fun control(protocol: Int, code: Int, id: Int, data: ByteArray): PppFrame {
        event("PPP TX ${protocolName(protocol)} code=$code id=$id state=$state")
        return PppFrame(protocol, PppControlPacket(code, id, data).encode())
    }

    private fun protocolName(protocol: Int) = when (protocol) {
        PppProtocol.LCP -> "LCP"
        PppProtocol.PAP -> "PAP"
        PppProtocol.CHAP -> "CHAP"
        PppProtocol.IPCP -> "IPCP"
        PppProtocol.IPV4 -> "IPv4"
        else -> "0x${protocol.toString(16)}"
    }

    private fun allocateId() = nextId.also { nextId = (nextId + 1) and 0xff }

    private fun ipv4Bytes(value: String) = InetAddress.getByName(value).address.also { require(it.size == 4) }
    private fun shortBytes(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

    private fun generateNtResponse(
        authenticatorChallenge: ByteArray,
        peerChallenge: ByteArray,
        userName: String,
        password: String,
    ): ByteArray {
        val user = userName.substringAfterLast('\\').toByteArray(Charsets.UTF_8)
        val challenge = MessageDigest.getInstance("SHA-1")
            .digest(peerChallenge + authenticatorChallenge + user)
            .copyOf(8)
        val passwordHash = Md4.digest(password.toByteArray(Charsets.UTF_16LE))
        val padded = passwordHash + ByteArray(5)
        return buildList<Byte> {
            repeat(3) { block -> desEncrypt(challenge, padded.copyOfRange(block * 7, block * 7 + 7)).forEach(::add) }
        }.toByteArray()
    }

    @SuppressLint("GetInstance")
    private fun desEncrypt(challenge: ByteArray, key56: ByteArray): ByteArray {
        val key = ByteArray(8)
        key[0] = (key56[0].toInt() and 0xfe).toByte()
        key[1] = (((key56[0].toInt() shl 7) or ((key56[1].toInt() and 0xff) ushr 1)) and 0xfe).toByte()
        key[2] = (((key56[1].toInt() shl 6) or ((key56[2].toInt() and 0xff) ushr 2)) and 0xfe).toByte()
        key[3] = (((key56[2].toInt() shl 5) or ((key56[3].toInt() and 0xff) ushr 3)) and 0xfe).toByte()
        key[4] = (((key56[3].toInt() shl 4) or ((key56[4].toInt() and 0xff) ushr 4)) and 0xfe).toByte()
        key[5] = (((key56[4].toInt() shl 3) or ((key56[5].toInt() and 0xff) ushr 5)) and 0xfe).toByte()
        key[6] = (((key56[5].toInt() shl 2) or ((key56[6].toInt() and 0xff) ushr 6)) and 0xfe).toByte()
        key[7] = ((key56[6].toInt() shl 1) and 0xfe).toByte()
        // RFC 2759 requires the DES primitive on one challenge block; this is not bulk ECB encryption.
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    private inline fun <T> Iterable<T>.flatMapBytes(transform: (T) -> ByteArray): ByteArray =
        fold(byteArrayOf()) { acc, item -> acc + transform(item) }

    private companion object {
        const val CONFIGURE_REQUEST = 1
        const val CONFIGURE_ACK = 2
        const val CONFIGURE_NAK = 3
        const val CONFIGURE_REJECT = 4
        const val TERMINATE_REQUEST = 5
        const val TERMINATE_ACK = 6
        const val ECHO_REQUEST = 9
        const val ECHO_REPLY = 10
        const val LCP_MRU = 1
        const val LCP_ACCM = 2
        const val LCP_AUTH = 3
        const val LCP_MAGIC = 5
        const val LCP_PFC = 7
        const val LCP_ACFC = 8
        const val PAP_AUTH_REQUEST = 1
        const val PAP_AUTH_ACK = 2
        const val PAP_AUTH_NAK = 3
        const val CHAP_CHALLENGE = 1
        const val CHAP_RESPONSE = 2
        const val CHAP_SUCCESS = 3
        const val CHAP_FAILURE = 4
        const val CHAP_MD5 = 5
        const val CHAP_MS_V2 = 0x81
        const val IPCP_ADDRESS = 3
        const val IPCP_PRIMARY_DNS = 129
        const val IPCP_SECONDARY_DNS = 131
    }
}
