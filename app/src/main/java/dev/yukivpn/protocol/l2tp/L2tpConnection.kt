package dev.yukivpn.protocol.l2tp

import dev.yukivpn.protocol.ppp.PppFrame
import dev.yukivpn.protocol.ppp.PppProtocol
import dev.yukivpn.protocol.ppp.PppSessionMachine
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

class L2tpConnection private constructor(
    private val socket: DatagramSocket,
    private val l2tp: L2tpSessionMachine,
) : Closeable {
    val peerAddress: String = socket.inetAddress.hostAddress.orEmpty()
    val tunnelId: Int get() = requireNotNull(l2tp.remoteTunnelId)
    val sessionId: Int get() = requireNotNull(l2tp.remoteSessionId)

    @Volatile
    private var closed = false

    fun negotiatePpp(username: String, password: String): PppSessionMachine.NetworkConfig {
        val ppp = PppSessionMachine(username, password)
        var pending = ppp.start()
        repeat(PPP_MAX_ATTEMPTS) {
            pending.forEach(::sendPpp)
            try {
                while (!closed && ppp.state != PppSessionMachine.State.OPEN) {
                    val frame = receivePppAndServiceControl()
                    val output = ppp.receive(frame)
                    if (output.isNotEmpty()) {
                        output.forEach(::sendPpp)
                        pending = output
                    }
                    check(ppp.state != PppSessionMachine.State.FAILED) { "PPP 协商或身份认证失败" }
                }
                check(!closed) { "L2TP 连接已关闭" }
                socket.soTimeout = 0
                return requireNotNull(ppp.networkConfig)
            } catch (_: SocketTimeoutException) {
                // Retransmit the last PPP control flight.
            }
        }
        throw SocketTimeoutException("PPP 协商在多次重传后超时")
    }

    @Synchronized
    fun sendIpv4(packet: ByteArray, size: Int = packet.size) {
        check(!closed)
        require(size in 1..packet.size)
        sendPpp(PppFrame(PppProtocol.IPV4, packet.copyOf(size)))
    }

    fun receiveIpv4(): ByteArray {
        while (!closed) {
            val frame = receivePppAndServiceControl()
            if (frame.protocol == PppProtocol.IPV4) return frame.payload
            if (frame.protocol == PppProtocol.LCP) {
                // Keepalive handling after IPCP is delegated to a minimal open-state machine.
                val packet = dev.yukivpn.protocol.ppp.PppControlPacket.decode(frame.payload)
                if (packet.code == 9) {
                    sendPpp(
                        PppFrame(
                            PppProtocol.LCP,
                            dev.yukivpn.protocol.ppp.PppControlPacket(10, packet.id, packet.data).encode(),
                        ),
                    )
                }
            }
        }
        error("L2TP connection is closed")
    }

    @Synchronized
    private fun sendPpp(frame: PppFrame) {
        val data = l2tp.dataPacket(frame.encode()).encode()
        socket.send(DatagramPacket(data, data.size))
    }

    private fun receivePppAndServiceControl(): PppFrame {
        while (!closed) {
            val datagram = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE)
            socket.receive(datagram)
            val flags = ((datagram.data[0].toInt() and 0xff) shl 8) or (datagram.data[1].toInt() and 0xff)
            if (flags and CONTROL_FLAG != 0) {
                l2tp.receive(L2tpPacket.decode(datagram.data, datagram.length)).forEach {
                    val bytes = it.packet.encode()
                    socket.send(DatagramPacket(bytes, bytes.size))
                }
                check(l2tp.state != L2tpSessionMachine.State.CLOSED) { "L2TP 服务端关闭了连接" }
                continue
            }
            val data = L2tpDataPacket.decode(datagram.data, datagram.length)
            require(data.tunnelId == l2tp.localTunnelId) { "收到其他 L2TP 隧道的数据" }
            require(data.sessionId == l2tp.localSessionId) { "收到其他 L2TP 会话的数据" }
            return PppFrame.decode(data.payload)
        }
        error("L2TP connection is closed")
    }

    override fun close() {
        closed = true
        socket.close()
    }

    companion object {
        private const val CONTROL_FLAG = 0x8000
        private const val RETRANSMIT_TIMEOUT_MS = 2_000
        private const val CONTROL_MAX_ATTEMPTS = 3
        private const val PPP_MAX_ATTEMPTS = 5
        private const val MAX_PACKET_SIZE = 65535

        fun connect(
            host: String,
            port: Int,
            protectSocket: (DatagramSocket) -> Boolean,
        ): L2tpConnection {
            require(host.isNotBlank()) { "服务器地址不能为空" }
            require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
            val socket = DatagramSocket()
            try {
                check(protectSocket(socket)) { "无法将 L2TP 连接排除在 VPN 路由之外" }
                val target = InetSocketAddress(host, port)
                check(!target.isUnresolved) { "无法解析服务器地址" }
                socket.connect(target)
                socket.soTimeout = RETRANSMIT_TIMEOUT_MS
                val random = SecureRandom()
                val machine = L2tpSessionMachine(
                    localTunnelId = random.nextInt(65535) + 1,
                    localSessionId = random.nextInt(65535) + 1,
                )
                var pending = listOf(machine.start())
                repeat(CONTROL_MAX_ATTEMPTS) {
                    pending.forEach { output ->
                        val bytes = output.packet.encode()
                        socket.send(DatagramPacket(bytes, bytes.size))
                    }
                    try {
                        while (machine.state != L2tpSessionMachine.State.ESTABLISHED) {
                            val response = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE)
                            socket.receive(response)
                            val output = machine.receive(L2tpPacket.decode(response.data, response.length))
                            if (output.isNotEmpty()) {
                                output.forEach { message ->
                                    val bytes = message.packet.encode()
                                    socket.send(DatagramPacket(bytes, bytes.size))
                                }
                                pending = output.filter { it.retransmittable }
                            }
                        }
                        return L2tpConnection(socket, machine)
                    } catch (_: SocketTimeoutException) {
                        // Retransmit the most recent unacknowledged flight.
                    }
                }
                throw SocketTimeoutException("L2TP 呼叫会话建立超时")
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
    }
}
