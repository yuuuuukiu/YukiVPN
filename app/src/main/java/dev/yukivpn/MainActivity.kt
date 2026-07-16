package dev.yukivpn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.app.NotificationManagerCompat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.yukivpn.data.ProfileExchangeCodec
import dev.yukivpn.data.ProfileStore
import dev.yukivpn.data.VpnProfile
import dev.yukivpn.logging.AppLogger
import dev.yukivpn.settings.AppSettings
import dev.yukivpn.ui.QrCaptureActivity
import dev.yukivpn.ui.YukiVpnApp
import dev.yukivpn.vpn.TunnelStatus
import dev.yukivpn.vpn.YukiVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val status = MutableStateFlow(TunnelStatus.IDLE)
    private val detail = MutableStateFlow("尚未连接")
    private val profiles = MutableStateFlow<List<VpnProfile>>(emptyList())
    private val activeProfileId = MutableStateFlow<String?>(null)
    private val blockCaptivePortalNotifications = MutableStateFlow(false)
    private val notificationListenerGranted = MutableStateFlow(false)
    private val importStatus = MutableStateFlow<String?>(null)
    private val importedProfileDraft = MutableStateFlow<VpnProfile?>(null)
    private val pendingImportedProfiles = ArrayDeque<VpnProfile>()
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

    private val profileFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importProfileUri)
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { importProfileText(it, "二维码") }
    }

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
                importStatus = importStatus,
                importedProfileDraft = importedProfileDraft,
                onConnect = ::requestConnect,
                onStop = ::stopTunnelService,
                onSaveProfile = ::saveProfile,
                onSelectProfile = ::selectProfile,
                onDeleteProfile = ::deleteProfile,
                onImportFile = ::openProfileFile,
                onImportUrl = ::importProfileUrl,
                onScanQr = ::scanProfileQr,
                onDismissImportStatus = { importStatus.value = null },
                onDiscardImportedProfile = ::discardImportedProfile,
                onClearLogs = AppLogger::clear,
                onSetCaptivePortalBlocking = ::setCaptivePortalBlocking,
                onOpenNotificationAccess = ::openNotificationAccess,
                onOpenUrl = ::openUrl,
            )
        }
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
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

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { importStatus.value = "无法打开链接：${it.message ?: "没有可用的浏览器"}" }
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
        if (importedProfileDraft.value?.id == profile.id) advanceImportedProfile()
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

    private fun openProfileFile() {
        profileFilePicker.launch(arrayOf(ProfileExchangeCodec.MIME_TYPE, "application/json", "text/plain"))
    }

    private fun scanProfileQr() {
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描 YukiVPN 配置二维码")
                .setBeepEnabled(false)
                .setCaptureActivity(QrCaptureActivity::class.java)
                .setOrientationLocked(true)
                .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN),
        )
    }

    private fun importProfileUrl(value: String) {
        val url = value.trim()
        if (!url.startsWith("https://", ignoreCase = true)) {
            importStatus.value = "仅支持 HTTPS 配置地址"
            return
        }
        importStatus.value = "正在从 URL 导入..."
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = true
                        connection.setRequestProperty("Accept", "${ProfileExchangeCodec.MIME_TYPE}, application/json")
                        require(connection.responseCode in 200..299) { "服务器返回 HTTP ${connection.responseCode}" }
                        connection.inputStream.use(::readLimitedUtf8)
                    } finally {
                        connection.disconnect()
                    }
                }
            }.onSuccess { importProfileText(it, "URL") }
                .onFailure { importStatus.value = "URL 导入失败：${it.message ?: "未知错误"}" }
        }
    }

    private fun importProfileUri(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use(::readLimitedUtf8)
                ?: error("无法读取所选文件")
        }.onSuccess { importProfileText(it, "文件") }
            .onFailure { importStatus.value = "文件导入失败：${it.message ?: "未知错误"}" }
    }

    private fun importProfileText(content: String, source: String) {
        runCatching { ProfileExchangeCodec.decode(content) }
            .onSuccess { imported ->
                pendingImportedProfiles.clear()
                pendingImportedProfiles.addAll(imported)
                AppLogger.info("已从$source 读取 ${imported.size} 个配置，等待补全凭据")
                importStatus.value = null
                advanceImportedProfile()
            }
            .onFailure { importStatus.value = "$source 导入失败：${it.message ?: "配置格式无效"}" }
    }

    private fun discardImportedProfile() {
        if (importedProfileDraft.value != null) advanceImportedProfile()
    }

    private fun advanceImportedProfile() {
        importedProfileDraft.value = pendingImportedProfiles.removeFirstOrNull()
    }

    private fun handleImportIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW && intent?.action != Intent.ACTION_SEND) return
        val uri = intent.data ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        importProfileUri(uri)
        intent.action = null
    }

    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_IMPORT_BYTES) { "配置文件不能超过 1 MB" }
        }
        return output.toString(Charsets.UTF_8.name())
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

    private companion object {
        const val MAX_IMPORT_BYTES = 1_048_576
    }
}
