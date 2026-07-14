package dev.yukivpn.protocol.l2tp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

class L2tpControlClient(
    private val protectSocket: (DatagramSocket) -> Boolean,
) {
    data class ProbeResult(val serverTunnelId: Int, val peerAddress: String)

    fun probe(host: String, port: Int): ProbeResult {
        require(host.isNotBlank()) { "服务器地址不能为空" }
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
        val localTunnelId = SecureRandom().nextInt(65535) + 1
        DatagramSocket().use { socket ->
            check(protectSocket(socket)) { "无法将控制连接排除在 VPN 路由之外" }
            socket.soTimeout = TIMEOUT_MS
            val target = InetSocketAddress(host, port)
            check(!target.isUnresolved) { "无法解析服务器地址" }
            socket.connect(target)
            val request = sccrq(localTunnelId).encode()
            socket.send(DatagramPacket(request, request.size))

            val receiveBuffer = ByteArray(4096)
            val response = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(response)
            val packet = L2tpPacket.decode(response.data, response.length)
            check(packet.messageType == MessageType.SCCRP) { "服务器未返回 SCCRP" }
            check(packet.tunnelId == localTunnelId) { "服务器响应了错误的隧道 ID" }
            val serverTunnelId = packet.avps
                .firstOrNull { it.vendorId == 0 && it.type == AvpType.ASSIGNED_TUNNEL_ID }
                ?.unsignedShort()
                ?: error("SCCRP 缺少 Assigned Tunnel ID")

            val connected = L2tpPacket(
                tunnelId = serverTunnelId,
                ns = 1,
                nr = (packet.ns + 1) and 0xffff,
                avps = listOf(shortAvp(AvpType.MESSAGE_TYPE, MessageType.SCCCN)),
            ).encode()
            socket.send(DatagramPacket(connected, connected.size))
            return ProbeResult(serverTunnelId, response.address.hostAddress.orEmpty())
        }
    }

    private fun sccrq(tunnelId: Int) = L2tpPacket(
        tunnelId = 0,
        ns = 0,
        nr = 0,
        avps = listOf(
            shortAvp(AvpType.MESSAGE_TYPE, MessageType.SCCRQ),
            shortAvp(AvpType.PROTOCOL_VERSION, 0x0100),
            intAvp(AvpType.FRAMING_CAPABILITIES, 3),
            L2tpAvp(AvpType.HOST_NAME, "YukiVPN".toByteArray()),
            shortAvp(AvpType.ASSIGNED_TUNNEL_ID, tunnelId),
            shortAvp(AvpType.RECEIVE_WINDOW_SIZE, 4),
        ),
    )

    private fun shortAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array(),
    )

    private fun intAvp(type: Int, value: Int) = L2tpAvp(
        type,
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(),
    )

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
