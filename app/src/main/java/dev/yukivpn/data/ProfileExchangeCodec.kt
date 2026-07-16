package dev.yukivpn.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ProfileExchangeCodec {
    const val MIME_TYPE = "application/vnd.yukivpn.profile+json"
    private const val FORMAT = "yukivpn-profile"
    private const val VERSION = 1
    private const val MAX_PROFILES = 50

    fun encode(profile: VpnProfile): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("profile", profile.toPortableJson())
        .toString()

    fun decode(content: String): List<VpnProfile> {
        require(content.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "配置文件不能超过 1 MB" }
        val root = JSONObject(content.trim())
        require(root.optString("format") == FORMAT) { "不是 YukiVPN 配置文件" }
        require(root.optInt("version") == VERSION) { "不支持此配置文件版本" }
        val objects = when {
            root.has("profile") -> listOf(root.getJSONObject("profile"))
            root.has("profiles") -> root.getJSONArray("profiles").toObjectList()
            else -> error("配置文件中没有配置")
        }
        require(objects.isNotEmpty()) { "配置文件中没有配置" }
        require(objects.size <= MAX_PROFILES) { "一次最多导入 $MAX_PROFILES 个配置" }
        return objects.map { it.toImportedProfile() }
    }

    private fun VpnProfile.toPortableJson() = JSONObject()
        .put("name", name)
        .put("protocol", protocol.name)
        .put("server", server)
        .put("port", port)
        .put("dnsServers", JSONArray(dnsServers))

    private fun JSONObject.toImportedProfile(): VpnProfile {
        val name = optString("name").trim()
        val server = optString("server").trim()
        val port = optInt("port", 1701)
        val protocol = runCatching { VpnProtocol.valueOf(getString("protocol")) }
            .getOrElse { throw IllegalArgumentException("配置协议无效") }
        val dns = optJSONArray("dnsServers")?.let { array ->
            buildList { repeat(array.length()) { add(array.optString(it).trim()) } }
                .filter(String::isNotEmpty)
                .distinct()
        }.orEmpty()

        require(name.isNotBlank()) { "配置名称不能为空" }
        require(server.isNotBlank()) { "服务器地址不能为空" }
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
        require(dns.all(::isValidIpv4Address)) { "配置包含无效的 DNS 地址" }

        return VpnProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = protocol,
            server = server,
            port = port,
            username = "",
            password = "",
            preSharedKey = "",
            dnsServers = dns,
        )
    }

    private fun JSONArray.toObjectList(): List<JSONObject> =
        buildList { repeat(length()) { add(getJSONObject(it)) } }
}
