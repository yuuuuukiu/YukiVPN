package dev.yukivpn.protocol.l2tp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class L2tpPacketTest {
    @Test
    fun `control packet round trips`() {
        val packet = L2tpPacket(
            tunnelId = 42,
            sessionId = 0,
            ns = 7,
            nr = 9,
            avps = listOf(
                shortAvp(AvpType.MESSAGE_TYPE, MessageType.SCCRP),
                shortAvp(AvpType.ASSIGNED_TUNNEL_ID, 321),
                L2tpAvp(AvpType.HOST_NAME, "vpn.example".toByteArray(), mandatory = false),
            ),
        )

        val decoded = L2tpPacket.decode(packet.encode())

        assertEquals(42, decoded.tunnelId)
        assertEquals(7, decoded.ns)
        assertEquals(9, decoded.nr)
        assertEquals(MessageType.SCCRP, decoded.messageType)
        assertEquals(321, decoded.avps[1].unsignedShort())
        assertArrayEquals("vpn.example".toByteArray(), decoded.avps[2].value)
    }

    @Test
    fun `rejects truncated packet`() {
        val valid = L2tpPacket(0, ns = 0, nr = 0, avps = emptyList()).encode()
        assertThrows(IllegalArgumentException::class.java) {
            L2tpPacket.decode(valid.copyOf(valid.size - 1))
        }
    }

    @Test
    fun `rejects data packet`() {
        val dataHeader = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x4002.toShort())
            .putShort(12.toShort())
            .putLong(0)
            .array()
        assertThrows(IllegalArgumentException::class.java) { L2tpPacket.decode(dataHeader) }
    }

    private fun shortAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array(),
    )
}
