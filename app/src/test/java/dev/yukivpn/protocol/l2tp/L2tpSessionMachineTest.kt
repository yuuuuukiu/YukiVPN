package dev.yukivpn.protocol.l2tp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class L2tpSessionMachineTest {
    @Test
    fun `establishes tunnel and incoming call session`() {
        val machine = L2tpSessionMachine(localTunnelId = 100, localSessionId = 200)

        val sccrq = machine.start().packet
        assertEquals(MessageType.SCCRQ, sccrq.messageType)
        assertEquals(0, sccrq.tunnelId)
        assertEquals(100, sccrq.shortAvp(AvpType.ASSIGNED_TUNNEL_ID))

        val tunnelFlight = machine.receive(
            control(100, 0, 0, 1, MessageType.SCCRP, shortAvp(AvpType.ASSIGNED_TUNNEL_ID, 300)),
        )
        assertEquals(listOf(MessageType.SCCCN, MessageType.ICRQ), tunnelFlight.map { it.packet.messageType })
        assertEquals(200, tunnelFlight[1].packet.shortAvp(AvpType.ASSIGNED_SESSION_ID))
        assertEquals(300, tunnelFlight[1].packet.tunnelId)

        val callFlight = machine.receive(
            control(100, 200, 1, 3, MessageType.ICRP, shortAvp(AvpType.ASSIGNED_SESSION_ID, 400)),
        )
        assertEquals(L2tpSessionMachine.State.ESTABLISHED, machine.state)
        assertEquals(MessageType.ICCN, callFlight.single().packet.messageType)
        assertEquals(300, callFlight.single().packet.tunnelId)
        assertEquals(400, callFlight.single().packet.sessionId)
    }

    @Test
    fun `wraps payload for established peer session`() {
        val machine = establishedMachine()
        val payload = byteArrayOf(0xff.toByte(), 0x03, 0xc0.toByte(), 0x21)

        val encoded = machine.dataPacket(payload).encode()
        val decoded = L2tpDataPacket.decode(encoded)

        assertEquals(300, decoded.tunnelId)
        assertEquals(400, decoded.sessionId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `sends hello without advancing receive sequence for zlb acknowledgements`() {
        val machine = establishedMachine()

        val firstHello = machine.hello().packet
        machine.receive(L2tpPacket(100, ns = 2, nr = firstHello.ns + 1, avps = emptyList()))
        val secondHello = machine.hello().packet

        assertEquals(MessageType.HELLO, firstHello.messageType)
        assertEquals(true, machine.hello().retransmittable)
        assertEquals(firstHello.ns + 1, secondHello.ns)
        assertEquals(firstHello.nr, secondHello.nr)
    }

    @Test
    fun `records close result details`() {
        val machine = establishedMachine()
        val result = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort(3).putShort(0).array() + "closed by admin".toByteArray()

        machine.receive(control(100, 200, 2, 4, MessageType.CDN, L2tpAvp(AvpType.RESULT_CODE, result)))

        assertEquals(L2tpSessionMachine.State.CLOSED, machine.state)
        assertEquals(
            "CDN result=3 error=0 message=closed by admin avps=[0:0/2, 0:1/19]",
            machine.closeReason,
        )
    }

    @Test
    fun `records CDN cause code and AVP inventory`() {
        val machine = establishedMachine()
        val result = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(3).putShort(0).array()
        val cause = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN).putShort(8).put(2).array() +
            "idle timeout".toByteArray()

        machine.receive(
            control(
                100,
                200,
                2,
                4,
                MessageType.CDN,
                L2tpAvp(AvpType.RESULT_CODE, result),
                L2tpAvp(AvpType.CAUSE_CODE, cause),
            ),
        )

        assertEquals(
            "CDN result=3 error=0 cause=8 causeMessage=2 diagnostic=idle timeout " +
                "avps=[0:0/2, 0:1/4, 0:12/15]",
            machine.closeReason,
        )
    }

    @Test
    fun `recognizes cumulative acknowledgements across sequence wrap`() {
        assertEquals(true, L2tpConnection.isAcknowledged(10, 11))
        assertEquals(true, L2tpConnection.isAcknowledged(0xffff, 0))
        assertEquals(false, L2tpConnection.isAcknowledged(11, 11))
        assertEquals(false, L2tpConnection.isAcknowledged(12, 11))
    }

    @Test
    fun `rejects call response for another local session`() {
        val machine = L2tpSessionMachine(100, 200)
        machine.start()
        machine.receive(
            control(100, 0, 0, 1, MessageType.SCCRP, shortAvp(AvpType.ASSIGNED_TUNNEL_ID, 300)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            machine.receive(
                control(100, 201, 1, 3, MessageType.ICRP, shortAvp(AvpType.ASSIGNED_SESSION_ID, 400)),
            )
        }
    }

    private fun establishedMachine() = L2tpSessionMachine(100, 200).also { machine ->
        machine.start()
        machine.receive(
            control(100, 0, 0, 1, MessageType.SCCRP, shortAvp(9, 300)),
        )
        machine.receive(
            control(100, 200, 1, 3, MessageType.ICRP, shortAvp(14, 400)),
        )
    }

    private fun control(
        tunnelId: Int,
        sessionId: Int = 0,
        ns: Int,
        nr: Int,
        messageType: Int,
        vararg avps: L2tpAvp,
    ) = L2tpPacket(
        tunnelId = tunnelId,
        sessionId = sessionId,
        ns = ns,
        nr = nr,
        avps = listOf(shortAvp(AvpType.MESSAGE_TYPE, messageType), *avps),
    )

    private fun shortAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array(),
    )

    private fun L2tpPacket.shortAvp(type: Int) = avps.first { it.type == type }.unsignedShort()
}
