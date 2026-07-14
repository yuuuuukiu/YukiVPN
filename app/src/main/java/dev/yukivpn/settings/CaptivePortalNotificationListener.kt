package dev.yukivpn.settings

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.yukivpn.logging.AppLogger

class CaptivePortalNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (AppSettings(this).blockCaptivePortalNotifications) {
            activeNotifications.orEmpty().forEach(::cancelIfCaptivePortal)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (AppSettings(this).blockCaptivePortalNotifications) cancelIfCaptivePortal(sbn)
    }

    private fun cancelIfCaptivePortal(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val text = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).filterNotNull().joinToString(" ")
        if (CaptivePortalNotificationFilter.matches(sbn.packageName, notification.channelId, text)) {
            cancelNotification(sbn.key)
            AppLogger.info("已屏蔽 Wi-Fi 认证通知 · ${sbn.packageName} · ${notification.channelId}")
        }
    }
}
