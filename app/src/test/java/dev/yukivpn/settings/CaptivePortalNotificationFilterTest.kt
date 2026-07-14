package dev.yukivpn.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptivePortalNotificationFilterTest {
    @Test
    fun matchesNetworkStackCaptivePortalChannel() {
        assertTrue(
            CaptivePortalNotificationFilter.matches(
                "com.android.networkstack",
                "connected_note_loud",
                "PXXY",
            ),
        )
    }

    @Test
    fun matchesLocalizedCaptivePortalText() {
        assertTrue(CaptivePortalNotificationFilter.matches("android", null, "此 WLAN 网络需要登录"))
        assertTrue(CaptivePortalNotificationFilter.matches("com.android.networkstack", null, "Wi-Fi 等待认证"))
    }

    @Test
    fun preservesUnrelatedNotifications() {
        assertFalse(CaptivePortalNotificationFilter.matches("com.example.app", "connected_note_loud", "登录"))
        assertFalse(CaptivePortalNotificationFilter.matches("android", null, "电话服务登录失败"))
        assertFalse(CaptivePortalNotificationFilter.matches("com.android.networkstack", "connected_note", "网络信息"))
    }
}
