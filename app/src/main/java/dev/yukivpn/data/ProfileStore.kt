package dev.yukivpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_profile", Context.MODE_PRIVATE)

    @Synchronized
    fun profiles(): List<VpnProfile> {
        val encoded = prefs.getString(KEY_PROFILES, null)
        if (encoded == null) return migrateLegacyProfile()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                repeat(array.length()) { add(array.getJSONObject(it).toProfile()) }
            }
        }.getOrDefault(emptyList())
    }

    fun load(): VpnProfile = activeProfile() ?: VpnProfile()

    @Synchronized
    fun activeProfile(): VpnProfile? {
        val profiles = profiles()
        val activeId = prefs.getString(KEY_ACTIVE_ID, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    @Synchronized
    fun upsert(profile: VpnProfile): List<VpnProfile> {
        val normalized = profile.copy(name = profile.name.trim(), server = profile.server.trim())
        val profiles = profiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == normalized.id }
        if (index >= 0) profiles[index] = normalized else profiles += normalized
        persist(profiles)
        if (prefs.getString(KEY_ACTIVE_ID, null) == null) select(normalized.id)
        return profiles
    }

    @Synchronized
    fun select(id: String): Boolean {
        if (profiles().none { it.id == id }) return false
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        return true
    }

    @Synchronized
    fun delete(id: String): List<VpnProfile> {
        val remaining = profiles().filterNot { it.id == id }
        val activeId = prefs.getString(KEY_ACTIVE_ID, null)
        persist(remaining)
        if (activeId == id) {
            prefs.edit().putString(KEY_ACTIVE_ID, remaining.firstOrNull()?.id).apply()
        }
        return remaining
    }

    private fun persist(profiles: List<VpnProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("server", profile.server)
                    .put("port", profile.port)
                    .put("username", encrypt(profile.username))
                    .put("password", encrypt(profile.password))
                    .put("psk", encrypt(profile.preSharedKey)),
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun JSONObject.toProfile() = VpnProfile(
        server = optString("server"),
        username = decrypt(optString("username")),
        password = decrypt(optString("password")),
        preSharedKey = decrypt(optString("psk")),
        port = optInt("port", 1701),
        id = getString("id"),
        name = optString("name", "未命名配置"),
    )

    private fun migrateLegacyProfile(): List<VpnProfile> {
        val server = prefs.getString(LEGACY_SERVER, "").orEmpty()
        val migrated = if (server.isBlank()) {
            emptyList()
        } else {
            listOf(
                VpnProfile(
                    server = server,
                    username = decrypt(prefs.getString(LEGACY_USERNAME, null)),
                    password = decrypt(prefs.getString(LEGACY_PASSWORD, null)),
                    preSharedKey = decrypt(prefs.getString(LEGACY_PSK, null)),
                    port = prefs.getInt(LEGACY_PORT, 1701),
                    name = server,
                ),
            )
        }
        persist(migrated)
        prefs.edit().putString(KEY_ACTIVE_ID, migrated.firstOrNull()?.id).apply()
        return migrated
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, payload.copyOfRange(0, IV_LENGTH)),
            )
            String(cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_PROFILES = "profiles_v2"
        const val KEY_ACTIVE_ID = "active_profile_id"
        const val LEGACY_SERVER = "server"
        const val LEGACY_USERNAME = "username"
        const val LEGACY_PASSWORD = "password"
        const val LEGACY_PSK = "psk"
        const val LEGACY_PORT = "port"
        const val KEY_ALIAS = "yukivpn.profile.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
