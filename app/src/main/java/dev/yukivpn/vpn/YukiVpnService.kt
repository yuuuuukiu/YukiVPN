package dev.yukivpn.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.yukivpn.MainActivity
import dev.yukivpn.R
import dev.yukivpn.data.ProfileStore
import dev.yukivpn.protocol.l2tp.L2tpConnection
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class YukiVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var connection: L2tpConnection? = null

    @Volatile
    private var tunnelInterface: ParcelFileDescriptor? = null

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            shutdownTunnel()
            publish(TunnelStatus.IDLE, "已停止")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (connection != null) return Service.START_NOT_STICKY
        stopRequested = false
        startForeground(NOTIFICATION_ID, notification("正在连接 L2TP 服务器"))
        executor.submit { runTunnel() }
        return Service.START_NOT_STICKY
    }

    private fun runTunnel() {
        val profile = ProfileStore(this).load()
        try {
            check(profile.preSharedKey.isBlank()) {
                "此配置要求 IPsec PSK；为防止凭据明文降级，当前版本已拒绝连接"
            }
            publish(TunnelStatus.PROBING, "正在建立 L2TP 隧道与呼叫会话")
            val activeConnection = L2tpConnection.connect(profile.server, profile.port, ::protect)
            connection = activeConnection
            publish(
                TunnelStatus.CONTROL_CONNECTED,
                "L2TP 会话已建立 · ${activeConnection.peerAddress} · ${activeConnection.tunnelId}/${activeConnection.sessionId}",
            )

            publish(TunnelStatus.AUTHENTICATING, "正在协商 PPP 并验证身份")
            val network = activeConnection.negotiatePpp(profile.username, profile.password)
            val builder = Builder()
                .setSession(profile.name)
                .setMtu(MTU)
                .setBlocking(true)
                .addAddress(network.address, 32)
                .addRoute("0.0.0.0", 0)
            network.dnsServers.forEach(builder::addDnsServer)
            val tun = checkNotNull(builder.establish()) { "Android 未能建立 TUN 接口" }
            tunnelInterface = tun

            val connectedText = buildString {
                append("已连接 · ")
                append(network.address)
                if (network.dnsServers.isNotEmpty()) append(" · DNS ${network.dnsServers.joinToString()}")
            }
            publish(TunnelStatus.CONNECTED, connectedText)
            updateNotification(connectedText)
            bridgePackets(activeConnection, tun)
        } catch (_: SocketTimeoutException) {
            if (!stopRequested) fail("服务器在协议协商期间超时")
        } catch (_: SocketException) {
            if (!stopRequested) fail("L2TP 网络连接已中断")
        } catch (error: Exception) {
            if (!stopRequested) fail(error.message ?: "VPN 连接失败")
        } finally {
            shutdownTunnel()
        }
    }

    private fun bridgePackets(activeConnection: L2tpConnection, tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val uplink = Thread({
            val packet = ByteArray(MAX_IP_PACKET_SIZE)
            try {
                while (!stopRequested) {
                    val length = input.read(packet)
                    if (length <= 0) break
                    activeConnection.sendIpv4(packet, length)
                }
            } catch (_: Exception) {
                if (!stopRequested) activeConnection.close()
            }
        }, "YukiVPN-uplink").apply { start() }

        try {
            while (!stopRequested) {
                val packet = activeConnection.receiveIpv4()
                output.write(packet)
            }
        } finally {
            activeConnection.close()
            runCatching { input.close() }
            runCatching { output.close() }
            uplink.interrupt()
        }
    }

    private fun shutdownTunnel() {
        connection?.close()
        connection = null
        tunnelInterface?.close()
        tunnelInterface = null
    }

    private fun fail(message: String) {
        publish(TunnelStatus.FAILED, message)
        updateNotification(message)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun publish(status: TunnelStatus, detail: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_DETAIL, detail),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN 连接状态", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("YukiVPN")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "停止",
            PendingIntent.getService(
                this,
                1,
                Intent(this, YukiVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    override fun onDestroy() {
        stopRequested = true
        shutdownTunnel()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "dev.yukivpn.action.CONNECT"
        const val ACTION_STOP = "dev.yukivpn.action.STOP"
        const val ACTION_STATUS = "dev.yukivpn.action.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val MTU = 1400
        private const val MAX_IP_PACKET_SIZE = 32767
    }
}
