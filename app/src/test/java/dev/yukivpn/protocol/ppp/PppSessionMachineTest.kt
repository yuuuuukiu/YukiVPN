package dev.yukivpn.protocol.ppp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PppSessionMachineTest {
    @Test
    fun `echo reply uses zero magic after local magic is rejected`() {
        val machine = PppSessionMachine("user", "pass")
        val request = PppControlPacket.decode(machine.start().single().payload)
        val magic = PppOption.decodeAll(request.data).first { it.type == 5 }
        val retried = machine.receive(
            PppFrame(PppProtocol.LCP, PppControlPacket(4, request.id, magic.encode()).encode()),
        )
        val retryOptions = PppOption.decodeAll(PppControlPacket.decode(retried.single().payload).data)
        assertTrue(retryOptions.none { it.type == 5 })

        val echo = machine.receive(
            PppFrame(
                PppProtocol.LCP,
                PppControlPacket(9, 44, byteArrayOf(1, 2, 3, 4, 9, 8)).encode(),
            ),
        )
        val reply = PppControlPacket.decode(echo.single().payload)

        assertEquals(10, reply.code)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 9, 8), reply.data)
    }
    @Test
    fun `PPP frame round trips with address and control fields`() {
        val frame = PppFrame(PppProtocol.IPV4, byteArrayOf(0x45, 0, 0, 20))
        val decoded = PppFrame.decode(frame.encode())
        assertEquals(PppProtocol.IPV4, decoded.protocol)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun `negotiates PAP and IPCP address`() {
        val machine = PppSessionMachine("yuki", "secret")
        val localLcp = machine.start().single()
        val localLcpPacket = PppControlPacket.decode(localLcp.payload)
        val authOption = PppOption(3, byteArrayOf(0xc0.toByte(), 0x23)).encode()

        val peerAck = machine.receive(control(PppProtocol.LCP, 1, 44, authOption))
        assertEquals(2, PppControlPacket.decode(peerAck.single().payload).code)
        val authStart = machine.receive(
            control(PppProtocol.LCP, 2, localLcpPacket.id, localLcpPacket.data),
        )
        assertEquals(PppSessionMachine.Authentication.PAP, machine.authentication)
        assertEquals(PppProtocol.PAP, authStart.single().protocol)

        val ipcpStart = machine.receive(control(PppProtocol.PAP, 2, 2, byteArrayOf(0)))
        val firstIpcp = PppControlPacket.decode(ipcpStart.single().payload)
        machine.receive(control(PppProtocol.IPCP, 1, 50, PppOption(3, ip("10.0.0.1")).encode()))
        val retry = machine.receive(
            control(
                PppProtocol.IPCP,
                3,
                firstIpcp.id,
                PppOption(3, ip("10.0.0.42")).encode() + PppOption(129, ip("1.1.1.1")).encode(),
            ),
        )
        val retriedIpcp = PppControlPacket.decode(retry.single().payload)
        machine.receive(control(PppProtocol.IPCP, 2, retriedIpcp.id, retriedIpcp.data))

        assertEquals(PppSessionMachine.State.OPEN, machine.state)
        assertEquals("10.0.0.42", machine.networkConfig?.address)
        assertEquals(listOf("1.1.1.1"), machine.networkConfig?.dnsServers)
    }

    @Test
    fun `MD4 matches NT password hash vector`() {
        val digest = Md4.digest("password".toByteArray(Charsets.UTF_16LE))
        assertEquals("8846f7eaee8fb117ad06bdd830b7586c", digest.toHex())
    }

    @Test
    fun `recognizes MS CHAP v2 authentication option`() {
        val machine = PppSessionMachine("User", "clientPass")
        val local = PppControlPacket.decode(machine.start().single().payload)
        val auth = PppOption(3, byteArrayOf(0xc2.toByte(), 0x23, 0x81.toByte())).encode()
        machine.receive(control(PppProtocol.LCP, 1, 1, auth))
        machine.receive(control(PppProtocol.LCP, 2, local.id, local.data))
        assertEquals(PppSessionMachine.Authentication.MSCHAP_V2, machine.authentication)
        assertEquals(PppSessionMachine.State.AUTHENTICATING, machine.state)
    }

    private fun control(protocol: Int, code: Int, id: Int, data: ByteArray) =
        PppFrame(protocol, PppControlPacket(code, id, data).encode())

    private fun ip(value: String) = value.split('.').map { it.toInt().toByte() }.toByteArray()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
