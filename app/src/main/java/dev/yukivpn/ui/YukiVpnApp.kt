package dev.yukivpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YukiVpnApp(
    initialProfile: VpnProfile,
    status: StateFlow<TunnelStatus>,
    detail: StateFlow<String>,
    onConnect: (VpnProfile) -> Unit,
    onStop: () -> Unit,
) {
    val tunnelStatus by status.collectAsState()
    val statusDetail by detail.collectAsState()
    YukiVpnTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = CircleShape,
                            ) {
                                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                    Text("Y", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                "YukiVPN",
                                modifier = Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                ProfileScreen(
                    initialProfile,
                    tunnelStatus,
                    statusDetail,
                    onConnect,
                    onStop,
                    Modifier.fillMaxWidth().widthIn(max = 680.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    initial: VpnProfile,
    status: TunnelStatus,
    detail: String,
    onConnect: (VpnProfile) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var server by remember { mutableStateOf(initial.server) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var psk by remember { mutableStateOf(initial.preSharedKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    var pskVisible by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    val busy = status == TunnelStatus.PROBING || status == TunnelStatus.CONTROL_CONNECTED

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        StatusPanel(status, detail)

        FormSection(title = "服务器") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; validation = null },
                    modifier = Modifier.weight(1f),
                    label = { Text("域名或 IP") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) port = it },
                    modifier = Modifier.weight(0.42f),
                    label = { Text("端口") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
        }

        FormSection(title = "身份认证") {
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
            SecretField("IPsec 预共享密钥", psk, pskVisible, { psk = it }, { pskVisible = !pskVisible })
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "敏感信息由 Android Keystore 加密保存。当前版本仅验证 L2TP 控制通道，尚未启用 IPsec 与 PPP。",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        validation?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val parsedPort = port.toIntOrNull()
                    validation = when {
                        server.isBlank() -> "请输入服务器地址"
                        parsedPort == null || parsedPort !in 1..65535 -> "请输入有效端口"
                        username.isBlank() -> "请输入用户名"
                        password.isBlank() -> "请输入密码"
                        else -> null
                    }
                    if (validation == null) {
                        onConnect(VpnProfile(server.trim(), username, password, psk, parsedPort!!))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !busy,
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
                    if (status == TunnelStatus.PROBING) "正在探测" else "探测 L2TP 服务",
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
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun StatusPanel(status: TunnelStatus, detail: String) {
    val colors = MaterialTheme.colorScheme
    val label = when (status) {
        TunnelStatus.IDLE -> "未连接"
        TunnelStatus.PROBING -> "协商中"
        TunnelStatus.CONTROL_CONNECTED -> "控制通道可达"
        TunnelStatus.FAILED -> "连接失败"
    }
    val accent = when (status) {
        TunnelStatus.IDLE -> colors.outline
        TunnelStatus.PROBING -> colors.tertiary
        TunnelStatus.CONTROL_CONNECTED -> colors.primary
        TunnelStatus.FAILED -> colors.error
    }
    val container = when (status) {
        TunnelStatus.IDLE -> colors.surfaceVariant
        TunnelStatus.PROBING -> colors.tertiaryContainer
        TunnelStatus.CONTROL_CONNECTED -> colors.primaryContainer
        TunnelStatus.FAILED -> colors.errorContainer
    }
    val onContainer = when (status) {
        TunnelStatus.IDLE -> colors.onSurfaceVariant
        TunnelStatus.PROBING -> colors.onTertiaryContainer
        TunnelStatus.CONTROL_CONNECTED -> colors.onPrimaryContainer
        TunnelStatus.FAILED -> colors.onErrorContainer
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = container),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(accent)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = accent, shape = CircleShape) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    if (status == TunnelStatus.PROBING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = container,
                            strokeWidth = 2.dp,
                        )
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
