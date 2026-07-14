package dev.yukivpn.settings

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var blockCaptivePortalNotifications: Boolean
        get() = preferences.getBoolean(KEY_BLOCK_CAPTIVE_PORTAL_NOTIFICATIONS, false)
        set(value) {
            preferences.edit().putBoolean(KEY_BLOCK_CAPTIVE_PORTAL_NOTIFICATIONS, value).apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
        const val KEY_BLOCK_CAPTIVE_PORTAL_NOTIFICATIONS = "block_captive_portal_notifications"
    }
}
