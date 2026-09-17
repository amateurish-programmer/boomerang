package com.boomerang.app.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** The preferences contain ciphertext only; AES key material never leaves Android Keystore. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences("boomerang_auth_v1", Context.MODE_PRIVATE)

    override fun load(): AuthSession? = synchronized(lock) {
        if (!preferences.contains("session")) return@synchronized null
        try {
            val encoded = preferences.getString("session", null) ?: throw CredentialsUnavailable()
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size < 1 + 12 + 16 || packed[0] != 1.toByte()) throw CredentialsUnavailable()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: throw CredentialsUnavailable(), GCMParameterSpec(128, packed.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(packed, 13, packed.size - 13)
            try {
                val json = JSONObject(String(plaintext, Charsets.UTF_8))
                val user = requireUuid(json.getString("user_id"))
                val access = json.getString("access_token")
                val refresh = json.getString("refresh_token")
                val expires = json.getLong("expires_at")
                if (access.isBlank() || refresh.isBlank() || expires <= 0) throw CredentialsUnavailable()
                AuthSession(user, access, refresh, expires)
            } finally { plaintext.fill(0) }
        } catch (_: Exception) {
            // No key regeneration, clear(), fallback identity or anonymous mode on corruption.
            throw CredentialsUnavailable()
        }
    }

    override fun save(session: AuthSession) = synchronized(lock) {
        try {
            requireUuid(session.userId)
            require(session.accessToken.isNotBlank() && session.refreshToken.isNotBlank() && session.expiresAtEpochSeconds > 0)
            val key = existingKey() ?: run {
                if (preferences.contains("session")) throw CredentialsUnavailable()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build())
                }.generateKey()
            }
            val plaintext = JSONObject().put("user_id", session.userId).put("access_token", session.accessToken)
                .put("refresh_token", session.refreshToken).put("expires_at", session.expiresAtEpochSeconds)
                .toString().toByteArray(Charsets.UTF_8)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(aad)
                if (cipher.iv.size != 12) throw CredentialsUnavailable()
                val packed = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
                val value = Base64.encodeToString(packed, Base64.NO_WRAP)
                if (!preferences.edit().putString("session", value).commit()) throw CredentialsUnavailable()
            } finally { plaintext.fill(0) }
        } catch (_: Exception) { throw CredentialsUnavailable() }
    }

    override fun clear() = synchronized(lock) {
        if (!preferences.edit().remove("session").commit()) throw CredentialsUnavailable()
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
    }

    private companion object {
        val lock = Any()
        const val alias = "boomerang.auth.aes.v1"
        val aad = "com.boomerang.app/session/v1".toByteArray(Charsets.UTF_8)
    }
}
