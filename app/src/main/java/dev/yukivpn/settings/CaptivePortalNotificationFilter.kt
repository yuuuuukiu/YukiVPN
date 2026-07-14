package dev.yukivpn.settings

object CaptivePortalNotificationFilter {
    private val systemPackages = setOf(
        "android",
        "com.android.networkstack",
        "com.google.android.networkstack",
    )
    private val exactChannels = setOf("connected_note_loud")
    private val networkTerms = listOf("wi-fi", "wifi", "wlan", "无线局域网", "网络")
    private val authenticationTerms = listOf(
        "sign in",
        "log in",
        "login",
        "authentication",
        "authenticate",
        "登录",
        "认证",
        "等待认证",
    )

    fun matches(packageName: String, channelId: String?, text: String): Boolean {
        if (packageName !in systemPackages) return false
        if (channelId in exactChannels) return true
        val normalized = text.lowercase()
        return networkTerms.any(normalized::contains) && authenticationTerms.any(normalized::contains)
    }
}
