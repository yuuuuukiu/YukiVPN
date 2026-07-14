package dev.yukivpn.protocol.l2tp

import dev.yukivpn.protocol.ppp.PppControlPacket
import dev.yukivpn.protocol.ppp.PppFrame
import dev.yukivpn.protocol.ppp.PppOption
import dev.yukivpn.protocol.ppp.PppProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class L2tpConnectionIntegrationTest {
    @Test
    fun `only forwards valid IPv4 packets through IPCP`() {
        val ipv4 = ByteArray(20).also { it[0] = 0x45 }
        val ipv6 = ByteArray(40).also { it[0] = 0x60 }

        assertEquals(true, L2tpConnection.isIpv4Packet(ipv4))
        assertEquals(false, L2tpConnection.isIpv4Packet(ipv6))
        assertEquals(false, L2tpConnection.isIpv4Packet(byteArrayOf(0x45)))
    }

    @Test
    fun `negotiates L2TP PAP and IPCP over UDP`() {
        DatagramSocket(0).use { server ->
            server.soTimeout = 5_000
            val executor = Executors.newSingleThreadExecutor()
            val serverResult = executor.submit { serveConnection(server) }

            L2tpConnection.connect("127.0.0.1", server.localPort) { true }.use { connection ->
                val config = connection.negotiatePpp("yuki", "secret")
                assertEquals("10.20.30.40", config.address)
                assertEquals(listOf("1.1.1.1"), config.dnsServers)
            }

            serverResult.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun serveConnection(server: DatagramSocket) {
        val sccrqDatagram = receive(server)
        val peer = sccrqDatagram.socketAddress
        val sccrq = L2tpPacket.decode(sccrqDatagram.data, sccrqDatagram.length)
        val clientTunnelId = sccrq.shortAvp(AvpType.ASSIGNED_TUNNEL_ID)
        sendControl(
            server,
            peer,
            L2tpPacket(
                clientTunnelId,
                ns = 0,
                nr = 1,
                avps = listOf(message(MessageType.SCCRP), shortAvp(AvpType.ASSIGNED_TUNNEL_ID, SERVER_TUNNEL_ID)),
            ),
        )

        val first = L2tpPacket.decodeReceived(server)
        val second = L2tpPacket.decodeReceived(server)
        val icrq = listOf(first, second).first { it.messageType == MessageType.ICRQ }
        val clientSessionId = icrq.shortAvp(AvpType.ASSIGNED_SESSION_ID)
        sendControl(
            server,
            peer,
            L2tpPacket(
                tunnelId = clientTunnelId,
                sessionId = clientSessionId,
                ns = 1,
                nr = 3,
                avps = listOf(message(MessageType.ICRP), shortAvp(AvpType.ASSIGNED_SESSION_ID, SERVER_SESSION_ID)),
            ),
        )
        L2tpPacket.decodeReceived(server).also { assertEquals(MessageType.ICCN, it.messageType) }

        val localLcp = receivePpp(server)
        val localLcpPacket = PppControlPacket.decode(localLcp.payload)
        val papOption = PppOption(3, byteArrayOf(0xc0.toByte(), 0x23)).encode()
        sendPpp(server, peer, clientTunnelId, clientSessionId, control(PppProtocol.LCP, 1, 40, papOption))
        sendPpp(
            server,
            peer,
            clientTunnelId,
            clientSessionId,
            control(PppProtocol.LCP, 2, localLcpPacket.id, localLcpPacket.data),
        )
        receivePpp(server).also { assertEquals(2, PppControlPacket.decode(it.payload).code) }
        receivePpp(server).also { assertEquals(PppProtocol.PAP, it.protocol) }
        sendPpp(server, peer, clientTunnelId, clientSessionId, control(PppProtocol.PAP, 2, 2, byteArrayOf(0)))

        val firstIpcp = receivePpp(server)
        val firstIpcpPacket = PppControlPacket.decode(firstIpcp.payload)
        sendPpp(
            server,
            peer,
            clientTunnelId,
            clientSessionId,
            control(PppProtocol.IPCP, 1, 41, PppOption(3, ip("10.20.30.1")).encode()),
        )
        sendPpp(
            server,
            peer,
            clientTunnelId,
            clientSessionId,
            control(
                PppProtocol.IPCP,
                3,
                firstIpcpPacket.id,
                PppOption(3, ip("10.20.30.40")).encode() + PppOption(129, ip("1.1.1.1")).encode(),
            ),
        )
        receivePpp(server).also { assertEquals(2, PppControlPacket.decode(it.payload).code) }
        val retriedIpcp = receivePpp(server)
        val retriedIpcpPacket = PppControlPacket.decode(retriedIpcp.payload)
        sendPpp(
            server,
            peer,
            clientTunnelId,
            clientSessionId,
            control(PppProtocol.IPCP, 2, retriedIpcpPacket.id, retriedIpcpPacket.data),
        )
    }

    private fun receivePpp(server: DatagramSocket): PppFrame {
        val datagram = receive(server)
        return PppFrame.decode(L2tpDataPacket.decode(datagram.data, datagram.length).payload)
    }

    private fun sendPpp(
        server: DatagramSocket,
        peer: java.net.SocketAddress,
        tunnelId: Int,
        sessionId: Int,
        frame: PppFrame,
    ) {
        val bytes = L2tpDataPacket(tunnelId, sessionId, frame.encode()).encode()
        server.send(DatagramPacket(bytes, bytes.size, peer))
    }

    private fun sendControl(server: DatagramSocket, peer: java.net.SocketAddress, packet: L2tpPacket) {
        val bytes = packet.encode()
        server.send(DatagramPacket(bytes, bytes.size, peer))
    }

    private fun receive(server: DatagramSocket) = DatagramPacket(ByteArray(4096), 4096).also(server::receive)
    private fun L2tpPacket.Companion.decodeReceived(server: DatagramSocket): L2tpPacket {
        val datagram = receive(server)
        return decode(datagram.data, datagram.length)
    }

    private fun control(protocol: Int, code: Int, id: Int, data: ByteArray) =
        PppFrame(protocol, PppControlPacket(code, id, data).encode())

    private fun message(type: Int) = shortAvp(AvpType.MESSAGE_TYPE, type)
    private fun shortAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array(),
    )
    private fun L2tpPacket.shortAvp(type: Int) = avps.first { it.type == type }.unsignedShort()
    private fun ip(value: String) = value.split('.').map { it.toInt().toByte() }.toByteArray()

    private companion object {
        const val SERVER_TUNNEL_ID = 300
        const val SERVER_SESSION_ID = 400
    }
}
