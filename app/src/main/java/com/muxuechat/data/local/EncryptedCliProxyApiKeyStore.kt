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
 * 一般化改造前写死的 CLIProxyAPI 代理 Key 存储。
 *
 * **仅供一次性迁移使用**：新的自定义供应商一律走 [EncryptedProviderKeyStore]。
 * 迁移完成后这里的密文会被清空，但类本身要保留，
 * 否则从旧版本直接升级上来的用户会丢掉已保存的 Key。
 */
interface LegacyCliProxyCredentialStore {
    suspend fun getApiKey(): String?
    suspend fun saveApiKey(apiKey: String)
    suspend fun clearApiKey()
}

class EncryptedCliProxyApiKeyStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) : LegacyCliProxyCredentialStore {
    constructor(context: Context) : this(context, PREFS_NAME, KEY_ALIAS)

    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun saveApiKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_CIPHERTEXT, encrypted.base64())
                .putString(KEY_IV, cipher.iv.base64())
                .commit(),
        ) { "旧 API Key 密文持久化失败" }
    }

    override suspend fun getApiKey(): String? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)?.fromBase64() ?: return null
        val iv = preferences.getString(KEY_IV, null)?.fromBase64() ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    override suspend fun clearApiKey() {
        check(
            preferences.edit()
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_IV)
                .commit(),
        ) { "旧 API Key 密文清理失败" }
        keyStore.deleteEntry(keyAlias)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
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
        const val PREFS_NAME = "secure_cli_proxy_api_key"
        const val KEY_ALIAS = "muxuechat_cli_proxy_api_key"
        const val KEY_CIPHERTEXT = "cli_proxy_ciphertext"
        const val KEY_IV = "cli_proxy_iv"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
