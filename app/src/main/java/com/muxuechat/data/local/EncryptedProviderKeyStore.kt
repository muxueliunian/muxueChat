package com.muxuechat.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 按供应商 id 分别保存自定义 OpenAI 兼容供应商的 API Key。
 *
 * 每个供应商使用**独立的 Keystore alias 与独立的密文键**，
 * 供应商之间、以及与 DeepSeek 的 [EncryptedApiKeyStore] 之间都无法互读或误清。
 */
class EncryptedProviderKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    suspend fun saveApiKey(providerId: String, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(providerId))

        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(ciphertextKey(providerId), encrypted.base64())
            .putString(ivKey(providerId), cipher.iv.base64())
            .apply()
    }

    suspend fun getApiKey(providerId: String): String? {
        val ciphertext = preferences.getString(ciphertextKey(providerId), null)?.fromBase64() ?: return null
        val iv = preferences.getString(ivKey(providerId), null)?.fromBase64() ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(providerId),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    /** 供 UI 展示"是否已保存"，不返回明文。 */
    fun hasApiKey(providerId: String): Boolean =
        preferences.contains(ciphertextKey(providerId))

    /** 删除供应商时必须调用，避免密文和 Keystore 条目成为孤儿。 */
    suspend fun clearApiKey(providerId: String) {
        preferences.edit()
            .remove(ciphertextKey(providerId))
            .remove(ivKey(providerId))
            .apply()
        runCatching { keyStore.deleteEntry(alias(providerId)) }
    }

    private fun getOrCreateSecretKey(providerId: String): SecretKey {
        val alias = alias(providerId)
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "secure_provider_api_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        fun alias(providerId: String) = "muxuechat_provider_key_$providerId"
        fun ciphertextKey(providerId: String) = "${providerId}_ciphertext"
        fun ivKey(providerId: String) = "${providerId}_iv"
    }
}
