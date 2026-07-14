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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.sp
import dev.yukivpn.data.VpnProfile
import dev.yukivpn.vpn.TunnelStatus
import kotlinx.coroutines.flow.StateFlow

private val Pine = Color(0xFF176B5B)
private val Ink = Color(0xFF17201E)
private val Canvas = Color(0xFFF7F8FA)
private val Warning = Color(0xFFB54708)
private val Error = Color(0xFFB42318)

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
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Pine,
            onPrimary = Color.White,
            background = Canvas,
            surface = Color.White,
            onSurface = Ink,
            error = Error,
        ),
    ) {
        Scaffold(
            containerColor = Canvas,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Pine, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "Y",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text("YukiVPN", modifier = Modifier.padding(start = 12.dp), fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
                )
            },
        ) { padding ->
            ProfileScreen(
                initialProfile,
                tunnelStatus,
                statusDetail,
                onConnect,
                onStop,
                Modifier.padding(padding),
            )
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        StatusPanel(status, detail)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("服务器", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; validation = null },
                    modifier = Modifier.weight(1f),
                    label = { Text("域名或 IP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) port = it },
                    modifier = Modifier.weight(0.38f),
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("身份认证", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; validation = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            SecretField("密码", password, passwordVisible, { password = it }, { passwordVisible = !passwordVisible })
            SecretField("IPsec 预共享密钥", psk, pskVisible, { psk = it }, { pskVisible = !pskVisible })
            Text(
                "敏感信息由 Android Keystore 加密保存。当前里程碑仅验证 L2TP 控制通道，尚未启用 IPsec 与 PPP。",
                color = Warning,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        validation?.let { Text(it, color = Error, fontSize = 14.sp) }

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
            shape = RoundedCornerShape(6.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text(if (status == TunnelStatus.PROBING) "正在探测" else "探测 L2TP 服务", modifier = Modifier.padding(start = 8.dp))
        }
        if (busy) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF535A58)),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Text("停止", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusPanel(status: TunnelStatus, detail: String) {
    val (label, color) = when (status) {
        TunnelStatus.IDLE -> "未连接" to Color(0xFF667085)
        TunnelStatus.PROBING -> "协商中" to Warning
        TunnelStatus.CONTROL_CONNECTED -> "控制通道可达" to Pine
        TunnelStatus.FAILED -> "失败" to Error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp)) {
                Surface(modifier = Modifier.fillMaxSize(), color = color, shape = CircleShape) {}
            }
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color(0xFF667085), fontSize = 13.sp, lineHeight = 18.sp)
            }
            Icon(Icons.Default.Lock, contentDescription = null, tint = color)
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

