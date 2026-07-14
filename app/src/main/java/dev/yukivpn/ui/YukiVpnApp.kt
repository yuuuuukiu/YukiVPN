package dev.yukivpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.yukivpn.data.VpnProfile
import dev.yukivpn.ui.theme.YukiVpnTheme
import dev.yukivpn.vpn.TunnelStatus
import kotlinx.coroutines.flow.StateFlow

private enum class AppPage { OVERVIEW, PROFILES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YukiVpnApp(
    profiles: StateFlow<List<VpnProfile>>,
    activeProfileId: StateFlow<String?>,
    status: StateFlow<TunnelStatus>,
    detail: StateFlow<String>,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onSaveProfile: (VpnProfile) -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    val profileList by profiles.collectAsState()
    val activeId by activeProfileId.collectAsState()
    val tunnelStatus by status.collectAsState()
    val statusDetail by detail.collectAsState()
    val activeProfile = profileList.firstOrNull { it.id == activeId }
    var page by remember { mutableStateOf(AppPage.OVERVIEW) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<VpnProfile?>(null) }

    YukiVpnTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            if (page == AppPage.OVERVIEW) "YukiVPN" else "配置文件",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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
                        onAdd = { editingProfile = null; showEditor = true },
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    )
                }
            }
        }

        if (showEditor) {
            ProfileEditorDialog(
                profile = editingProfile,
                onDismiss = { showEditor = false },
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
                "已支持明文 L2TP + PPP 数据隧道。填写 PSK 的配置会等待 IPsec 支持，绝不会自动降级为明文。",
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
                    "${profile.server}:${profile.port}",
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
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (profiles.isEmpty()) {
        Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            EmptyProfiles(onAdd)
        }
        return
    }
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
                "${profiles.size} 个本地配置 · 点按即可切换",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        items(profiles, key = { it.id }) { profile ->
            ProfileListItem(
                profile = profile,
                selected = profile.id == activeId,
                onSelect = { onSelect(profile.id) },
                onEdit = { onEdit(profile) },
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
                    "${profile.server}:${profile.port}",
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

@Composable
private fun ProfileEditorDialog(
    profile: VpnProfile?,
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
    var passwordVisible by remember(draft.id) { mutableStateOf(false) }
    var pskVisible by remember(draft.id) { mutableStateOf(false) }
    var validation by remember(draft.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (profile == null) Icons.Default.Add else Icons.Default.Edit, contentDescription = null) },
        title = { Text(if (profile == null) "添加配置" else "编辑配置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                SecretField("IPsec 预共享密钥（开发中）", psk, pskVisible, { psk = it }, { pskVisible = !pskVisible })
                validation?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedPort = port.toIntOrNull()
                    validation = when {
                        name.isBlank() -> "请输入配置名称"
                        server.isBlank() -> "请输入服务器地址"
                        parsedPort == null || parsedPort !in 1..65535 -> "请输入有效端口"
                        username.isBlank() -> "请输入用户名"
                        password.isBlank() -> "请输入密码"
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
                            ),
                        )
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
