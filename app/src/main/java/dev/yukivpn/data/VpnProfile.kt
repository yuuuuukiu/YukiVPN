package dev.yukivpn.data

import java.util.UUID

enum class VpnProtocol {
    L2TP,
    L2TP_IPSEC_PSK,
}

data class VpnProfile(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val preSharedKey: String = "",
    val port: Int = 1701,
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新配置",
    val protocol: VpnProtocol = VpnProtocol.L2TP,
    val dnsServers: List<String> = emptyList(),
)

fun parseDnsServers(value: String): List<String> = value
    .split(Regex("[,，;；\\s]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

fun isValidIpv4Address(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    }
}
