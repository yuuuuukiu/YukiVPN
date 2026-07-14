package dev.yukivpn.data

import java.util.UUID

data class VpnProfile(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val preSharedKey: String = "",
    val port: Int = 1701,
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新配置",
)
