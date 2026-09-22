package com.muxuechat.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * xAI OAuth 凭据。
 *
 * 整份凭据作为**一个** JSON 加密成一段密文保存：refresh token 轮换时新旧 access / refresh token
 * 在同一次 `commit()` 里整体替换，中途崩溃只会留下完整的旧凭据或完整的新凭据，
 * 不可能出现「新 access token + 旧 refresh token」。
 */
@Serializable
data class XaiCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAtMillis: Long,
    val account: String? = null,
) {
    /** data class 默认的 toString 会带出全部 token；任何日志或异常拼接都只能看到这个。 */
    override fun toString(): String =
        "XaiCredential(expiresAtMillis=$expiresAtMillis, hasRefreshToken=${refreshToken != null})"
}

interface XaiCredentialStore {
    /** 读取凭据。没有凭据、或密文已无法解密时返回 null；后者会顺带清理残留。 */
    suspend fun load(): XaiCredential?

    suspend fun save(credential: XaiCredential)

    /** 删除密文与 Keystore alias。 */
    suspend fun clear()

    /**
     * 设备码登录是否正在进行。进程被杀后这个标记会残留，下次启动据此提示「上次登录未完成」。
     * 标记本身不含任何凭据或验证码。
     */
    fun isLoginInProgress(): Boolean

    fun setLoginInProgress(inProgress: Boolean)
}

/**
 * 与 [EncryptedApiKeyStore] 同一套做法：Keystore 独立 alias 持有 AES 密钥，
 * AES/GCM 密文与 IV 写入应用私有 SharedPreferences（已被 data_extraction_rules 排除在备份之外）。
 */
class EncryptedXaiCredentialStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) : XaiCredentialStore {
    constructor(context: Context) : this(context, PREFS_NAME, KEY_ALIAS)

    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): XaiCredential? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
        val iv = preferences.getString(KEY_IV, null)
        if (ciphertext == null || iv == null) {
            // 两半只剩一半（写入中途被杀、或上次清理只成功了一部分）：
            // 这份凭据已经不可能解开，直接按未登录处理并把残留一并清掉，
            // 否则 alias 与半截密文会一直留着，下次 save 还会复用一个错误的密钥状态。
            if (ciphertext != null || iv != null) {
                Log.w(TAG, "xAI credential storage is half-written, clearing residue")
                runCatching { clear() }
                    .onFailure { Log.w(TAG, "xAI residue cleanup failed: ${it.javaClass.simpleName}") }
            }
            return null
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = existingSecretKey() ?: error("Keystore entry missing")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv.fromBase64()))
            val plaintext = cipher.doFinal(ciphertext.fromBase64()).toString(Charsets.UTF_8)
            json.decodeFromString(XaiCredential.serializer(), plaintext)
        } catch (error: Exception) {
            // 密钥失效、密文损坏或格式变更都会走到这里；此时密文无法再解开，
            // 按未登录处理并清理残留，而不是每次启动都崩一次。
            // （本实现未要求用户认证，因此「改锁屏密码必然使密钥失效」并非已验证前提，
            //  不要据此推断具体成因，只按「解不开」处理。）
            Log.w(TAG, "xAI credential could not be decrypted: ${error.javaClass.simpleName}")
            // 清理失败不能静默吞掉：留着残留会让下一次启动重复走这条路径。
            runCatching { clear() }
                .onFailure { Log.w(TAG, "xAI credential cleanup failed: ${it.javaClass.simpleName}") }
            null
        }
    }

    override suspend fun save(credential: XaiCredential) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val plaintext = json.encodeToString(XaiCredential.serializer(), credential)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_CIPHERTEXT, encrypted.base64())
                .putString(KEY_IV, cipher.iv.base64())
                .commit(),
        ) { "xAI 凭据密文持久化失败" }
    }

    override suspend fun clear() {
        // 密文与 Keystore alias 是两类资源，必须**分别尝试**：
        // 先删密文失败就直接抛，会把 alias 永久留成孤儿。两边都试完再报第一个错误。
        val removedCiphertext = runCatching {
            check(
                preferences.edit()
                    .remove(KEY_CIPHERTEXT)
                    .remove(KEY_IV)
                    .commit(),
            ) { "xAI 凭据密文清理失败" }
        }
        val removedAlias = runCatching { keyStore.deleteEntry(keyAlias) }

        removedCiphertext.exceptionOrNull()?.let { throw it }
        removedAlias.exceptionOrNull()?.let {
            throw IllegalStateException("xAI 凭据密钥清理失败", it)
        }
    }

    override fun isLoginInProgress(): Boolean = preferences.getBoolean(KEY_LOGIN_IN_PROGRESS, false)

    override fun setLoginInProgress(inProgress: Boolean) {
        preferences.edit().putBoolean(KEY_LOGIN_IN_PROGRESS, inProgress).commit()
    }

    private fun existingSecretKey(): SecretKey? =
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateSecretKey(): SecretKey {
        existingSecretKey()?.let { return it }

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
        const val TAG = "muxueChat.XaiCredential"
        const val PREFS_NAME = "secure_xai_oauth"
        const val KEY_ALIAS = "muxuechat_xai_oauth"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_LOGIN_IN_PROGRESS = "login_in_progress"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
