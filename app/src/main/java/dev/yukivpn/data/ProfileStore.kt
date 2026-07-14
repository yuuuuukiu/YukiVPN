package dev.yukivpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_profile", Context.MODE_PRIVATE)

    fun load(): VpnProfile = VpnProfile(
        server = prefs.getString(KEY_SERVER, "").orEmpty(),
        username = decrypt(prefs.getString(KEY_USERNAME, null)),
        password = decrypt(prefs.getString(KEY_PASSWORD, null)),
        preSharedKey = decrypt(prefs.getString(KEY_PSK, null)),
        port = prefs.getInt(KEY_PORT, 1701),
    )

    fun save(profile: VpnProfile) {
        prefs.edit()
            .putString(KEY_SERVER, profile.server.trim())
            .putString(KEY_USERNAME, encrypt(profile.username))
            .putString(KEY_PASSWORD, encrypt(profile.password))
            .putString(KEY_PSK, encrypt(profile.preSharedKey))
            .putInt(KEY_PORT, profile.port)
            .apply()
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
        const val KEY_SERVER = "server"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PSK = "psk"
        const val KEY_PORT = "port"
        const val KEY_ALIAS = "yukivpn.profile.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
