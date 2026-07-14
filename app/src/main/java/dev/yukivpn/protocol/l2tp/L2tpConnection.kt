package dev.yukivpn.protocol.l2tp

import dev.yukivpn.protocol.ppp.PppFrame
import dev.yukivpn.protocol.ppp.PppProtocol
import dev.yukivpn.protocol.ppp.PppSessionMachine
import java.io.Closeable
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom

class L2tpConnection private constructor(
    private val transport: L2tpTransport,
    private val l2tp: L2tpSessionMachine,
) : Closeable {
    private data class PendingControl(
        val packet: L2tpPacket,
        var attempts: Int,
        var sentAtNanos: Long,
    )

    val peerAddress: String = transport.peerAddress
    val tunnelId: Int get() = requireNotNull(l2tp.remoteTunnelId)
    val sessionId: Int get() = requireNotNull(l2tp.remoteSessionId)

    @Volatile
    private var closed = false
    private var pppMachine: PppSessionMachine? = null
    private var event: (String) -> Unit = {}
    private val pendingControl = linkedMapOf<Int, PendingControl>()
    private var lastControlActivityNanos = System.nanoTime()

    fun negotiatePpp(
        username: String,
        password: String,
        event: (String) -> Unit = {},
    ): PppSessionMachine.NetworkConfig {
        this.event = event
        val ppp = PppSessionMachine(username, password, event = event)
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
                pppMachine = ppp
                lastControlActivityNanos = System.nanoTime()
                transport.timeoutMillis = CONTROL_POLL_INTERVAL_MS
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
        if (!isIpv4Packet(packet, size)) return
        sendPpp(PppFrame(PppProtocol.IPV4, packet.copyOf(size)))
    }

    fun receiveIpv4(): ByteArray {
        while (!closed) {
            val frame = receivePppAndServiceControl()
            if (frame.protocol == PppProtocol.IPV4) return frame.payload
            if (frame.protocol == PppProtocol.LCP) {
                val ppp = pppMachine ?: continue
                ppp.receive(frame).forEach(::sendPpp)
                check(ppp.state != PppSessionMachine.State.FAILED) { "PPP 服务端终止了连接" }
            }
        }
        error("L2TP connection is closed")
    }

    @Synchronized
    private fun sendPpp(frame: PppFrame) {
        val data = l2tp.dataPacket(frame.encode()).encode()
        transport.send(data)
    }

    private fun receivePppAndServiceControl(): PppFrame {
        while (!closed) {
            serviceControlTimers()
            val datagram = try {
                transport.receive()
            } catch (error: SocketTimeoutException) {
                if (pppMachine == null) throw error
                continue
            }
            require(datagram.size >= 2) { "收到过短的 L2TP 数据报" }
            val flags = ((datagram[0].toInt() and 0xff) shl 8) or (datagram[1].toInt() and 0xff)
            if (flags and CONTROL_FLAG != 0) {
                val packet = L2tpPacket.decode(datagram)
                lastControlActivityNanos = System.nanoTime()
                acknowledgeControl(packet.nr)
                l2tp.receive(packet).forEach {
                    sendControl(it)
                }
                check(l2tp.state != L2tpSessionMachine.State.CLOSED) {
                    "L2TP 服务端关闭了连接 · ${l2tp.closeReason.orEmpty()}"
                }
                continue
            }
            val data = L2tpDataPacket.decode(datagram)
            require(data.tunnelId == l2tp.localTunnelId) { "收到其他 L2TP 隧道的数据" }
            require(data.sessionId == l2tp.localSessionId) { "收到其他 L2TP 会话的数据" }
            return PppFrame.decode(data.payload)
        }
        error("L2TP connection is closed")
    }

    @Synchronized
    private fun sendControl(output: L2tpSessionMachine.Outbound) {
        val now = System.nanoTime()
        transport.send(output.packet.encode())
        lastControlActivityNanos = now
        if (output.retransmittable) {
            pendingControl[output.packet.ns] = PendingControl(output.packet, attempts = 1, sentAtNanos = now)
        }
        if (output.packet.messageType == MessageType.HELLO) {
            event("L2TP TX HELLO Ns=${output.packet.ns} Nr=${output.packet.nr}")
        }
    }

    private fun acknowledgeControl(nr: Int) {
        val acknowledged = pendingControl.keys.filter { ns -> isAcknowledged(ns, nr) }
        pendingControl.keys.removeAll(acknowledged.toSet())
        if (acknowledged.isNotEmpty()) {
            event("L2TP RX ACK Nr=$nr，已确认 Ns=${acknowledged.joinToString()}")
        }
    }

    private fun serviceControlTimers() {
        if (pppMachine == null) return
        val now = System.nanoTime()
        pendingControl.values.forEach { pending ->
            if (now - pending.sentAtNanos >= RETRANSMIT_TIMEOUT_NANOS) {
                check(pending.attempts < CONTROL_MAX_ATTEMPTS) {
                    "L2TP 控制消息 Ns=${pending.packet.ns} 未被服务端确认"
                }
                transport.send(pending.packet.encode())
                pending.attempts++
                pending.sentAtNanos = now
                event("L2TP 重传控制消息 Ns=${pending.packet.ns}，第 ${pending.attempts} 次")
            }
        }
        if (pendingControl.isEmpty() && now - lastControlActivityNanos >= HELLO_INTERVAL_NANOS) {
            sendControl(l2tp.hello())
        }
    }

    override fun close() {
        closed = true
        transport.close()
    }

    companion object {
        private const val CONTROL_FLAG = 0x8000
        private const val RETRANSMIT_TIMEOUT_MS = 2_000
        private const val CONTROL_POLL_INTERVAL_MS = 2_000
        private const val HELLO_INTERVAL_MS = 60_000L
        private const val RETRANSMIT_TIMEOUT_NANOS = RETRANSMIT_TIMEOUT_MS * 1_000_000L
        private const val HELLO_INTERVAL_NANOS = HELLO_INTERVAL_MS * 1_000_000L
        private const val CONTROL_MAX_ATTEMPTS = 3
        private const val PPP_MAX_ATTEMPTS = 5
        internal fun isIpv4Packet(packet: ByteArray, size: Int = packet.size): Boolean =
            size >= 20 && size <= packet.size && (packet[0].toInt() ushr 4) == 4

        internal fun isAcknowledged(ns: Int, nr: Int): Boolean =
            ((nr - ns) and 0xffff) in 1..0x8000

        fun connect(
            host: String,
            port: Int,
            protectSocket: (DatagramSocket) -> Boolean,
        ): L2tpConnection = connect(UdpL2tpTransport.connect(host, port, protectSocket))

        fun connect(transport: L2tpTransport): L2tpConnection {
            try {
                transport.timeoutMillis = RETRANSMIT_TIMEOUT_MS
                val random = SecureRandom()
                val machine = L2tpSessionMachine(
                    localTunnelId = random.nextInt(65535) + 1,
                    localSessionId = random.nextInt(65535) + 1,
                )
                var pending = listOf(machine.start())
                repeat(CONTROL_MAX_ATTEMPTS) {
                    pending.forEach { output ->
                        val bytes = output.packet.encode()
                        transport.send(bytes)
                    }
                    try {
                        while (machine.state != L2tpSessionMachine.State.ESTABLISHED) {
                            val response = transport.receive()
                            val output = machine.receive(L2tpPacket.decode(response))
                            if (output.isNotEmpty()) {
                                output.forEach { message ->
                                    val bytes = message.packet.encode()
                                    transport.send(bytes)
                                }
                                pending = output.filter { it.retransmittable }
                            }
                        }
                        return L2tpConnection(transport, machine)
                    } catch (_: SocketTimeoutException) {
                        // Retransmit the most recent unacknowledged flight.
                    }
                }
                throw SocketTimeoutException("L2TP 呼叫会话建立超时")
            } catch (error: Exception) {
                transport.close()
                throw error
            }
        }
    }
}
