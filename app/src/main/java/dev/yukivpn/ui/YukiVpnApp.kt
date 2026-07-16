package dev.yukivpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.yukivpn.data.VpnProfile
import dev.yukivpn.data.VpnProtocol
import dev.yukivpn.data.ProfileExchangeCodec
import dev.yukivpn.BuildConfig
import dev.yukivpn.R
import dev.yukivpn.data.isValidIpv4Address
import dev.yukivpn.data.parseDnsServers
import dev.yukivpn.logging.LogEntry
import dev.yukivpn.logging.LogLevel
import dev.yukivpn.ui.theme.YukiVpnTheme
import dev.yukivpn.vpn.TunnelStatus
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppPage { OVERVIEW, PROFILES, LOGS, SETTINGS, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YukiVpnApp(
    profiles: StateFlow<List<VpnProfile>>,
    activeProfileId: StateFlow<String?>,
    status: StateFlow<TunnelStatus>,
    detail: StateFlow<String>,
    logs: StateFlow<List<LogEntry>>,
    blockCaptivePortalNotifications: StateFlow<Boolean>,
    notificationListenerGranted: StateFlow<Boolean>,
    importStatus: StateFlow<String?>,
    importedProfileDraft: StateFlow<VpnProfile?>,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onSaveProfile: (VpnProfile) -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: (String) -> Unit,
    onScanQr: () -> Unit,
    onDismissImportStatus: () -> Unit,
    onDiscardImportedProfile: () -> Unit,
    onClearLogs: () -> Unit,
    onSetCaptivePortalBlocking: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val profileList by profiles.collectAsState()
    val activeId by activeProfileId.collectAsState()
    val tunnelStatus by status.collectAsState()
    val statusDetail by detail.collectAsState()
    val logEntries by logs.collectAsState()
    val captivePortalBlocking by blockCaptivePortalNotifications.collectAsState()
    val listenerGranted by notificationListenerGranted.collectAsState()
    val importMessage by importStatus.collectAsState()
    val importedDraft by importedProfileDraft.collectAsState()
    val activeProfile = profileList.firstOrNull { it.id == activeId }
    var page by remember { mutableStateOf(AppPage.OVERVIEW) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showUrlImport by remember { mutableStateOf(false) }
    var qrProfile by remember { mutableStateOf<VpnProfile?>(null) }

    LaunchedEffect(importedDraft?.id) {
        importedDraft?.let {
            editingProfile = it
            showEditor = true
        }
    }

    BackHandler(enabled = page == AppPage.SETTINGS || page == AppPage.ABOUT) {
        page = AppPage.OVERVIEW
    }

    YukiVpnTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (page) {
                                AppPage.OVERVIEW -> "YukiVPN"
                                AppPage.PROFILES -> "配置文件"
                                AppPage.LOGS -> "日志"
                                AppPage.SETTINGS -> "设置"
                                AppPage.ABOUT -> "关于"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        if (page == AppPage.SETTINGS || page == AppPage.ABOUT) {
                            IconButton(onClick = { page = AppPage.OVERVIEW }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        when (page) {
                            AppPage.OVERVIEW -> IconButton(onClick = { page = AppPage.ABOUT }) {
                                Icon(Icons.Default.Info, contentDescription = "关于")
                            }
                            AppPage.PROFILES -> IconButton(onClick = { page = AppPage.SETTINGS }) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                            AppPage.LOGS -> IconButton(onClick = onClearLogs) {
                                Icon(Icons.Default.Delete, contentDescription = "清空日志")
                            }
                            AppPage.SETTINGS, AppPage.ABOUT -> Unit
                        }
                    },
                )
            },
            bottomBar = {
                if (page != AppPage.SETTINGS && page != AppPage.ABOUT) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = page == AppPage.OVERVIEW,
                        onClick = { page = AppPage.OVERVIEW },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("概览") },
                    )
                    NavigationBarItem(
                        selected = page == AppPage.PROFILES,
                        onClick = { page = AppPage.PROFILES },
                        icon = { Icon(Icons.Default.Description, contentDescription = null) },
                        label = { Text("配置") },
                    )
                    NavigationBarItem(
                        selected = page == AppPage.LOGS,
                        onClick = { page = AppPage.LOGS },
                        icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                        label = { Text("日志") },
                    )
                }
            },
            floatingActionButton = {
                if (page == AppPage.PROFILES) {
                    FloatingActionButton(
                        onClick = { editingProfile = null; showEditor = true },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加配置")
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                when (page) {
                    AppPage.OVERVIEW -> OverviewScreen(
                        profile = activeProfile,
                        status = tunnelStatus,
                        detail = statusDetail,
                        onConnect = onConnect,
                        onStop = onStop,
                        onOpenProfiles = { page = AppPage.PROFILES },
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    )
                    AppPage.PROFILES -> ProfilesScreen(
                        profiles = profileList,
                        activeId = activeId,
                        onSelect = onSelectProfile,
                        onEdit = { editingProfile = it; showEditor = true },
                        onDelete = { deletingProfile = it },
                        onShowQr = { qrProfile = it },
                        onAdd = { editingProfile = null; showEditor = true },
                        onImportFile = onImportFile,
                        onImportUrl = { showUrlImport = true },
                        onScanQr = onScanQr,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    )
                    AppPage.LOGS -> LogsScreen(
                        entries = logEntries,
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    )
                    AppPage.SETTINGS -> SettingsScreen(
                        blockCaptivePortalNotifications = captivePortalBlocking,
                        notificationListenerGranted = listenerGranted,
                        onSetCaptivePortalBlocking = onSetCaptivePortalBlocking,
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    )
                    AppPage.ABOUT -> AboutScreen(
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    )
                }
            }
        }

        if (showEditor) {
            ProfileEditorDialog(
                profile = editingProfile,
                imported = editingProfile?.id == importedDraft?.id,
                onDismiss = {
                    if (editingProfile?.id == importedDraft?.id) onDiscardImportedProfile()
                    showEditor = false
                },
                onSave = {
                    onSaveProfile(it)
                    showEditor = false
                },
            )
        }
        deletingProfile?.let { profile ->
            AlertDialog(
                onDismissRequest = { deletingProfile = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text("删除配置？") },
                text = { Text("“${profile.name}”将从此设备永久删除。") },
                confirmButton = {
                    TextButton(
                        onClick = { onDeleteProfile(profile.id); deletingProfile = null },
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text("取消") } },
            )
        }
        if (showUrlImport) {
            UrlImportDialog(
                onDismiss = { showUrlImport = false },
                onImport = {
                    showUrlImport = false
                    onImportUrl(it)
                },
            )
        }
        qrProfile?.let { profile ->
            ProfileQrDialog(profile = profile, onDismiss = { qrProfile = null })
        }
        importMessage?.let { message ->
            AlertDialog(
                onDismissRequest = onDismissImportStatus,
                icon = { Icon(Icons.Default.Description, contentDescription = null) },
                title = { Text(if (message.startsWith("正在")) "导入配置" else "导入结果") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = onDismissImportStatus) { Text("确定") } },
            )
        }
    }
}

@Composable
private fun AboutScreen(onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(88.dp),
        )
        Text("YukiVPN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "L2TP 与 L2TP/IPsec PSK 客户端",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("作者", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "yuuuuukiu · GitHub @yuuuuukiu",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onOpenUrl("https://github.com/yuuuuukiu") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Text("GitHub 主页", modifier = Modifier.padding(horizontal = 8.dp).weight(1f))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        }
        OutlinedButton(
            onClick = { onOpenUrl("https://github.com/yuuuuukiu/YukiVPN") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Default.Code, contentDescription = null)
            Text("YukiVPN 项目仓库", modifier = Modifier.padding(horizontal = 8.dp).weight(1f))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        }
    }
}

@Composable
private fun SettingsScreen(
    blockCaptivePortalNotifications: Boolean,
    notificationListenerGranted: Boolean,
    onSetCaptivePortalBlocking: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("通知", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.NotificationsOff,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Text("屏蔽 Wi-Fi 等待认证通知", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (notificationListenerGranted) "通知使用权已授予" else "需要通知使用权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = blockCaptivePortalNotifications,
                onCheckedChange = onSetCaptivePortalBlocking,
            )
        }
        if (!notificationListenerGranted) {
            OutlinedButton(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text("授予通知使用权")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    var level by remember { mutableStateOf(LogLevel.INFO) }
    val filtered = remember(entries, level) {
        entries.filter { it.level.priority <= level.priority }.asReversed()
    }
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
        ) {
            LogLevel.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = level == option,
                    onClick = { level = option },
                    shape = SegmentedButtonDefaults.itemShape(index, LogLevel.entries.size),
                ) {
                    Text(
                        when (option) {
                            LogLevel.ERROR -> "错误"
                            LogLevel.INFO -> "信息"
                            LogLevel.DEBUG -> "调试"
                        },
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            ) {
                items(filtered, key = { it.id }) { entry ->
                    LogRow(entry, formatter)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, formatter: SimpleDateFormat) {
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
    }
    SelectionContainer {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatter.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.level.name,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(entry.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OverviewScreen(
    profile: VpnProfile?,
    status: TunnelStatus,
    detail: String,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onOpenProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = status in setOf(
        TunnelStatus.PROBING,
        TunnelStatus.CONTROL_CONNECTED,
        TunnelStatus.AUTHENTICATING,
        TunnelStatus.CONNECTED,
    )
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPanel(status, detail)
        Text(
            "当前配置",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (profile == null) {
            EmptyProfiles(onAdd = onOpenProfiles)
        } else {
            ActiveProfileCard(profile, onOpenProfiles)
        }
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = profile != null && !busy,
            shape = RoundedCornerShape(8.dp),
        ) {
            if (status == TunnelStatus.PROBING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
            Text(
                if (status == TunnelStatus.PROBING) "正在连接" else "连接",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (busy) {
            FilledTonalButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Text("停止", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                when (profile?.protocol) {
                    VpnProtocol.L2TP -> "当前配置使用 L2TP + PPP 数据隧道。"
                    VpnProtocol.L2TP_IPSEC_PSK -> "当前配置使用 IKEv1 PSK、ESP NAT-T transport 和 L2TP + PPP 数据隧道。"
                    null -> "添加配置并选择连接协议。"
                },
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ActiveProfileCard(profile: VpnProfile, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                shape = CircleShape,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
            }
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${protocolLabel(profile.protocol)} · ${profile.server}:${profile.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    profile.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Icon(Icons.Default.Check, contentDescription = "当前配置", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProfilesScreen(
    profiles: List<VpnProfile>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onEdit: (VpnProfile) -> Unit,
    onDelete: (VpnProfile) -> Unit,
    onShowQr: (VpnProfile) -> Unit,
    onAdd: () -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 12.dp,
            end = 20.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                if (profiles.isEmpty()) "导入或创建配置" else "${profiles.size} 个本地配置 · 点按即可切换",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        item {
            ProfileImportActions(
                onImportFile = onImportFile,
                onImportUrl = onImportUrl,
                onScanQr = onScanQr,
            )
        }
        if (profiles.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    EmptyProfiles(onAdd)
                }
            }
        }
        items(profiles, key = { it.id }) { profile ->
            ProfileListItem(
                profile = profile,
                selected = profile.id == activeId,
                onSelect = { onSelect(profile.id) },
                onEdit = { onEdit(profile) },
                onShowQr = { onShowQr(profile) },
                onDelete = { onDelete(profile) },
            )
        }
    }
}

@Composable
private fun ProfileListItem(
    profile: VpnProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(if (selected) Icons.Default.Check else Icons.Default.Description, contentDescription = null)
                }
            }
            Column(modifier = Modifier.padding(horizontal = 14.dp).weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${protocolLabel(profile.protocol)} · ${profile.server}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "配置操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("显示二维码") },
                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                        onClick = { menuExpanded = false; onShowQr() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileImportActions(
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit,
    onScanQr: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Text("从文件", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onImportUrl, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.Link, contentDescription = null)
                Text("从 URL", modifier = Modifier.padding(start = 8.dp))
            }
        }
        FilledTonalButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Text("扫描二维码", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun UrlImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text("从 URL 导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; validation = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS 配置地址") },
                    placeholder = { Text("https://example.com/profile.json") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                validation?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                validation = if (url.trim().startsWith("https://", ignoreCase = true)) null else "请输入 HTTPS 地址"
                if (validation == null) onImport(url.trim())
            }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ProfileQrDialog(profile: VpnProfile, onDismiss: () -> Unit) {
    val payload = remember(profile) { ProfileExchangeCodec.encode(profile) }
    val qrCode = remember(payload) { runCatching { createQrCode(payload) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
        title = { Text(profile.name) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                qrCode.getOrNull()?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "${profile.name} 配置二维码",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(12.dp),
                    )
                } ?: Text("无法生成二维码：${qrCode.exceptionOrNull()?.message.orEmpty()}")
                Text(
                    "二维码仅包含服务器、协议、端口和 DNS，不包含账号、密码或 PSK。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun EmptyProfiles(onAdd: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        }
        Text("还没有配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "添加 L2TP 服务器后即可在这里快速切换",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onAdd, shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("添加配置", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorDialog(
    profile: VpnProfile?,
    imported: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (VpnProfile) -> Unit,
) {
    val draft = profile ?: VpnProfile()
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var server by remember(draft.id) { mutableStateOf(draft.server) }
    var port by remember(draft.id) { mutableStateOf(draft.port.toString()) }
    var username by remember(draft.id) { mutableStateOf(draft.username) }
    var password by remember(draft.id) { mutableStateOf(draft.password) }
    var psk by remember(draft.id) { mutableStateOf(draft.preSharedKey) }
    var dns by remember(draft.id) { mutableStateOf(draft.dnsServers.joinToString(", ")) }
    var protocol by remember(draft.id) { mutableStateOf(draft.protocol) }
    var passwordVisible by remember(draft.id) { mutableStateOf(false) }
    var pskVisible by remember(draft.id) { mutableStateOf(false) }
    var validation by remember(draft.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (profile == null) Icons.Default.Add else Icons.Default.Edit, contentDescription = null) },
        title = { Text(if (imported) "完成导入" else if (profile == null) "添加配置" else "编辑配置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (imported) {
                    Text(
                        "可传递配置不包含账号、密码或 PSK，请补全后保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    VpnProtocol.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = protocol == option,
                            onClick = { protocol = option; validation = null },
                            shape = SegmentedButtonDefaults.itemShape(index, VpnProtocol.entries.size),
                        ) {
                            Text(if (option == VpnProtocol.L2TP) "L2TP" else "L2TP/IPsec")
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; validation = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("配置名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it; validation = null },
                        modifier = Modifier.weight(1f),
                        label = { Text("服务器") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) port = it },
                        modifier = Modifier.weight(0.45f),
                        label = { Text("端口") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    )
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; validation = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用户名") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                SecretField("密码", password, passwordVisible, { password = it }, { passwordVisible = !passwordVisible })
                if (protocol == VpnProtocol.L2TP_IPSEC_PSK) {
                    SecretField("IPsec 预共享密钥", psk, pskVisible, { psk = it }, { pskVisible = !pskVisible })
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = dns,
                    onValueChange = { dns = it; validation = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义 DNS（可选）") },
                    placeholder = { Text("1.1.1.1, 8.8.8.8") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                validation?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedPort = port.toIntOrNull()
                    val parsedDns = parseDnsServers(dns)
                    validation = when {
                        name.isBlank() -> "请输入配置名称"
                        server.isBlank() -> "请输入服务器地址"
                        parsedPort == null || parsedPort !in 1..65535 -> "请输入有效端口"
                        username.isBlank() -> "请输入用户名"
                        password.isBlank() -> "请输入密码"
                        protocol == VpnProtocol.L2TP_IPSEC_PSK && psk.isBlank() -> "请输入 IPsec 预共享密钥"
                        parsedDns.any { !isValidIpv4Address(it) } -> "请输入有效的 IPv4 DNS 地址"
                        else -> null
                    }
                    if (validation == null) {
                        onSave(
                            draft.copy(
                                name = name.trim(),
                                server = server.trim(),
                                port = parsedPort!!,
                                username = username,
                                password = password,
                                preSharedKey = psk,
                                protocol = protocol,
                                dnsServers = parsedDns,
                            ),
                        )
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun protocolLabel(protocol: VpnProtocol) = when (protocol) {
    VpnProtocol.L2TP -> "L2TP"
    VpnProtocol.L2TP_IPSEC_PSK -> "L2TP/IPsec PSK"
}

@Composable
private fun StatusPanel(status: TunnelStatus, detail: String) {
    val colors = MaterialTheme.colorScheme
    val label = when (status) {
        TunnelStatus.IDLE -> "未连接"
        TunnelStatus.PROBING -> "协商中"
        TunnelStatus.CONTROL_CONNECTED -> "L2TP 会话已建立"
        TunnelStatus.AUTHENTICATING -> "正在认证"
        TunnelStatus.CONNECTED -> "已连接"
        TunnelStatus.FAILED -> "连接失败"
    }
    val accent = when (status) {
        TunnelStatus.IDLE -> colors.outline
        TunnelStatus.PROBING -> colors.tertiary
        TunnelStatus.CONTROL_CONNECTED, TunnelStatus.AUTHENTICATING -> colors.tertiary
        TunnelStatus.CONNECTED -> colors.primary
        TunnelStatus.FAILED -> colors.error
    }
    val container = when (status) {
        TunnelStatus.IDLE -> colors.surfaceVariant
        TunnelStatus.PROBING -> colors.tertiaryContainer
        TunnelStatus.CONTROL_CONNECTED, TunnelStatus.AUTHENTICATING -> colors.tertiaryContainer
        TunnelStatus.CONNECTED -> colors.primaryContainer
        TunnelStatus.FAILED -> colors.errorContainer
    }
    val onContainer = when (status) {
        TunnelStatus.IDLE -> colors.onSurfaceVariant
        TunnelStatus.PROBING -> colors.onTertiaryContainer
        TunnelStatus.CONTROL_CONNECTED, TunnelStatus.AUTHENTICATING -> colors.onTertiaryContainer
        TunnelStatus.CONNECTED -> colors.onPrimaryContainer
        TunnelStatus.FAILED -> colors.onErrorContainer
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = accent, shape = CircleShape) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    if (status == TunnelStatus.PROBING || status == TunnelStatus.AUTHENTICATING) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = container, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = container)
                    }
                }
            }
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(label, color = onContainer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, color = onContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    visible: Boolean,
    onChange: (String) -> Unit,
    onToggle: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "隐藏" else "显示",
                )
            }
        },
    )
}
