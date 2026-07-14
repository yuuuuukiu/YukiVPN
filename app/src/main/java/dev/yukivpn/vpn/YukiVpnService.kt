package dev.yukivpn.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import dev.yukivpn.MainActivity
import dev.yukivpn.R
import dev.yukivpn.data.ProfileStore
import dev.yukivpn.protocol.l2tp.L2tpControlClient
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class YukiVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            publish(TunnelStatus.IDLE, "已停止")
            stopSelf()
            return Service.START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("正在探测 L2TP 服务器"))
        executor.submit { runProbe() }
        return Service.START_NOT_STICKY
    }

    private fun runProbe() {
        publish(TunnelStatus.PROBING, "正在协商 L2TP 控制通道")
        val profile = ProfileStore(this).load()
        try {
            val result = L2tpControlClient(::protect).probe(profile.server, profile.port)
            val detail = "控制通道已响应 · ${result.peerAddress} · Tunnel ${result.serverTunnelId}"
            publish(TunnelStatus.CONTROL_CONNECTED, detail)
            updateNotification(detail)
        } catch (_: SocketTimeoutException) {
            fail("服务器在 5 秒内未响应；L2TP/IPsec 服务器通常不会接受明文探测")
        } catch (error: Exception) {
            fail(error.message ?: "L2TP 探测失败")
        }
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
    }
}
