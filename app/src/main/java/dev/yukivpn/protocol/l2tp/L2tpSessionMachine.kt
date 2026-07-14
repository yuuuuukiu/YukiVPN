package dev.yukivpn.protocol.l2tp

import java.nio.ByteBuffer
import java.nio.ByteOrder

class L2tpSessionMachine(
    val localTunnelId: Int,
    val localSessionId: Int,
    private val hostName: String = "YukiVPN",
) {
    enum class State { IDLE, WAIT_SCCRP, WAIT_ICRP, ESTABLISHED, CLOSED }

    data class Outbound(val packet: L2tpPacket, val retransmittable: Boolean = true)

    var state: State = State.IDLE
        private set
    var remoteTunnelId: Int? = null
        private set
    var remoteSessionId: Int? = null
        private set

    private var nextNs = 0
    private var nextNr = 0
    private var callSerial = 1

    init {
        require(localTunnelId in 1..65535)
        require(localSessionId in 1..65535)
    }

    fun start(): Outbound {
        check(state == State.IDLE) { "L2TP session has already started" }
        state = State.WAIT_SCCRP
        return outbound(
            tunnelId = 0,
            avps = listOf(
                shortAvp(AvpType.MESSAGE_TYPE, MessageType.SCCRQ),
                shortAvp(AvpType.PROTOCOL_VERSION, 0x0100),
                intAvp(AvpType.FRAMING_CAPABILITIES, 3),
                L2tpAvp(AvpType.HOST_NAME, hostName.toByteArray(Charsets.UTF_8)),
                shortAvp(AvpType.ASSIGNED_TUNNEL_ID, localTunnelId),
                shortAvp(AvpType.RECEIVE_WINDOW_SIZE, 4),
            ),
        )
    }

    fun receive(packet: L2tpPacket): List<Outbound> {
        check(state != State.IDLE && state != State.CLOSED) { "L2TP session is not running" }
        if (packet.tunnelId != 0) {
            require(packet.tunnelId == localTunnelId) { "Packet addressed to another tunnel" }
        }
        nextNr = (packet.ns + 1) and 0xffff
        return when (packet.messageType) {
            null -> emptyList()
            MessageType.SCCRP -> if (state == State.WAIT_SCCRP) onSccrp(packet) else emptyList()
            MessageType.ICRP -> if (state == State.WAIT_ICRP) onIcrp(packet) else emptyList()
            MessageType.HELLO -> listOf(zeroLengthAck())
            MessageType.STOP_CCN, MessageType.CDN -> {
                state = State.CLOSED
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun dataPacket(payload: ByteArray): L2tpDataPacket {
        check(state == State.ESTABLISHED) { "L2TP call session is not established" }
        return L2tpDataPacket(
            tunnelId = requireNotNull(remoteTunnelId),
            sessionId = requireNotNull(remoteSessionId),
            payload = payload,
        )
    }

    private fun onSccrp(packet: L2tpPacket): List<Outbound> {
        check(state == State.WAIT_SCCRP) { "Unexpected SCCRP in state $state" }
        remoteTunnelId = requiredShortAvp(packet, AvpType.ASSIGNED_TUNNEL_ID)
        val tunnelId = requireNotNull(remoteTunnelId)
        state = State.WAIT_ICRP
        return listOf(
            outbound(
                tunnelId = tunnelId,
                avps = listOf(shortAvp(AvpType.MESSAGE_TYPE, MessageType.SCCCN)),
            ),
            outbound(
                tunnelId = tunnelId,
                avps = listOf(
                    shortAvp(AvpType.MESSAGE_TYPE, MessageType.ICRQ),
                    shortAvp(AvpType.ASSIGNED_SESSION_ID, localSessionId),
                    intAvp(AvpType.CALL_SERIAL_NUMBER, callSerial++),
                ),
            ),
        )
    }

    private fun onIcrp(packet: L2tpPacket): List<Outbound> {
        check(state == State.WAIT_ICRP) { "Unexpected ICRP in state $state" }
        require(packet.sessionId == localSessionId) { "ICRP addressed to another session" }
        remoteSessionId = requiredShortAvp(packet, AvpType.ASSIGNED_SESSION_ID)
        state = State.ESTABLISHED
        return listOf(
            outbound(
                tunnelId = requireNotNull(remoteTunnelId),
                sessionId = requireNotNull(remoteSessionId),
                avps = listOf(
                    shortAvp(AvpType.MESSAGE_TYPE, MessageType.ICCN),
                    intAvp(AvpType.TX_CONNECT_SPEED, 100_000_000),
                    intAvp(AvpType.FRAMING_TYPE, 1),
                ),
            ),
        )
    }

    private fun outbound(tunnelId: Int, sessionId: Int = 0, avps: List<L2tpAvp>): Outbound {
        val packet = L2tpPacket(tunnelId, sessionId, nextNs, nextNr, avps)
        nextNs = (nextNs + 1) and 0xffff
        return Outbound(packet)
    }

    private fun zeroLengthAck() = Outbound(
        packet = L2tpPacket(
            tunnelId = requireNotNull(remoteTunnelId),
            ns = nextNs,
            nr = nextNr,
            avps = emptyList(),
        ),
        retransmittable = false,
    )

    private fun requiredShortAvp(packet: L2tpPacket, type: Int): Int = packet.avps
        .firstOrNull { it.vendorId == 0 && it.type == type }
        ?.unsignedShort()
        ?: error("L2TP message is missing required AVP $type")

    private fun shortAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array(),
    )

    private fun intAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(),
    )
}
