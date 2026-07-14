package dev.yukivpn.protocol.l2tp

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

interface L2tpTransport : Closeable {
    val peerAddress: String
    var timeoutMillis: Int
    fun send(payload: ByteArray)
    fun receive(): ByteArray
}

class UdpL2tpTransport private constructor(
    private val socket: DatagramSocket,
) : L2tpTransport {
    override val peerAddress: String = socket.inetAddress.hostAddress.orEmpty()

    override var timeoutMillis: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    @Synchronized
    override fun send(payload: ByteArray) {
        socket.send(DatagramPacket(payload, payload.size))
    }

    override fun receive(): ByteArray {
        val datagram = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE)
        socket.receive(datagram)
        return datagram.data.copyOf(datagram.length)
    }

    override fun close() = socket.close()

    companion object {
        private const val MAX_PACKET_SIZE = 65535

        fun connect(host: String, port: Int, protectSocket: (DatagramSocket) -> Boolean): UdpL2tpTransport {
            require(host.isNotBlank()) { "服务器地址不能为空" }
            require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
            val socket = DatagramSocket()
            try {
                check(protectSocket(socket)) { "无法将 L2TP 连接排除在 VPN 路由之外" }
                val target = InetSocketAddress(host, port)
                check(!target.isUnresolved) { "无法解析服务器地址" }
                socket.connect(target)
                return UdpL2tpTransport(socket)
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
    }
}

