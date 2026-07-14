package dev.yukivpn.data

data class VpnProfile(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val preSharedKey: String = "",
    val port: Int = 1701,
)

