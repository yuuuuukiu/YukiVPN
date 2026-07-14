package dev.yukivpn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import dev.yukivpn.data.ProfileStore
import dev.yukivpn.data.VpnProfile
import dev.yukivpn.logging.AppLogger
import dev.yukivpn.settings.AppSettings
import dev.yukivpn.ui.YukiVpnApp
import dev.yukivpn.vpn.TunnelStatus
import dev.yukivpn.vpn.YukiVpnService
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val status = MutableStateFlow(TunnelStatus.IDLE)
    private val detail = MutableStateFlow("尚未连接")
    private val profiles = MutableStateFlow<List<VpnProfile>>(emptyList())
    private val activeProfileId = MutableStateFlow<String?>(null)
    private val blockCaptivePortalNotifications = MutableStateFlow(false)
    private val notificationListenerGranted = MutableStateFlow(false)
    private lateinit var profileStore: ProfileStore
    private lateinit var appSettings: AppSettings

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startTunnelService()
        else {
            status.value = TunnelStatus.FAILED
            detail.value = "未授予 Android VPN 权限"
            AppLogger.error("未授予 Android VPN 权限")
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val name = intent?.getStringExtra(YukiVpnService.EXTRA_STATUS) ?: return
            status.value = runCatching { TunnelStatus.valueOf(name) }.getOrDefault(TunnelStatus.FAILED)
            detail.value = intent.getStringExtra(YukiVpnService.EXTRA_DETAIL).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLogger.initialize(this)
        AppLogger.info("应用已启动")
        profileStore = ProfileStore(this)
        appSettings = AppSettings(this)
        blockCaptivePortalNotifications.value = appSettings.blockCaptivePortalNotifications
        refreshNotificationListenerAccess()
        refreshProfiles()
        registerStatusReceiver()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            YukiVpnApp(
                profiles = profiles,
                activeProfileId = activeProfileId,
                status = status,
                detail = detail,
                logs = AppLogger.entries,
                blockCaptivePortalNotifications = blockCaptivePortalNotifications,
                notificationListenerGranted = notificationListenerGranted,
                onConnect = ::requestConnect,
                onStop = ::stopTunnelService,
                onSaveProfile = ::saveProfile,
                onSelectProfile = ::selectProfile,
                onDeleteProfile = ::deleteProfile,
                onClearLogs = AppLogger::clear,
                onSetCaptivePortalBlocking = ::setCaptivePortalBlocking,
                onOpenNotificationAccess = ::openNotificationAccess,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::appSettings.isInitialized) refreshNotificationListenerAccess()
    }

    private fun setCaptivePortalBlocking(enabled: Boolean) {
        appSettings.blockCaptivePortalNotifications = enabled
        blockCaptivePortalNotifications.value = enabled
        if (enabled && !notificationListenerGranted.value) openNotificationAccess()
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun refreshNotificationListenerAccess() {
        notificationListenerGranted.value =
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun requestConnect() {
        if (profileStore.activeProfile() == null) {
            status.value = TunnelStatus.FAILED
            detail.value = "请先添加并选择一个配置"
            AppLogger.error("连接失败：没有活动配置")
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) startTunnelService() else vpnPermission.launch(permissionIntent)
    }

    private fun saveProfile(profile: VpnProfile) {
        profileStore.upsert(profile)
        AppLogger.info("配置已保存：${profile.name} (${profile.protocol.name})")
        selectProfile(profile.id)
    }

    private fun selectProfile(id: String) {
        if (activeProfileId.value != id) stopForProfileChange()
        profileStore.select(id)
        refreshProfiles()
        profileStore.activeProfile()?.let { AppLogger.info("已切换配置：${it.name}") }
    }

    private fun deleteProfile(id: String) {
        val name = profiles.value.firstOrNull { it.id == id }?.name.orEmpty()
        if (activeProfileId.value == id) stopForProfileChange()
        profileStore.delete(id)
        refreshProfiles()
        AppLogger.info("配置已删除：$name")
    }

    private fun stopForProfileChange() {
        if (status.value in setOf(
                TunnelStatus.PROBING,
                TunnelStatus.CONTROL_CONNECTED,
                TunnelStatus.AUTHENTICATING,
                TunnelStatus.CONNECTED,
            )
        ) {
            stopTunnelService()
        }
        status.value = TunnelStatus.IDLE
        detail.value = "配置已切换"
    }

    private fun refreshProfiles() {
        profiles.value = profileStore.profiles()
        activeProfileId.value = profileStore.activeProfile()?.id
    }

    private fun startTunnelService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, YukiVpnService::class.java).setAction(YukiVpnService.ACTION_CONNECT),
        )
    }

    private fun stopTunnelService() {
        startService(Intent(this, YukiVpnService::class.java).setAction(YukiVpnService.ACTION_STOP))
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(YukiVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
