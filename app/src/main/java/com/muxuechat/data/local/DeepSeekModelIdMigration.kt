package com.muxuechat.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.DeepSeekModels
import kotlinx.serialization.json.Json

/**
 * 一次性把已保存的旧 DeepSeek Flash 名（`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp`）
 * 迁移到 [DeepSeekModels.FLASH]。
 *
 * 由 DataStore 在首次读取前原子执行；完成标记保证只跑一次，之后真实刷新得到的列表
 * 即使仍含旧名也不再被改动。
 *
 * 明确不动：自定义供应商的模型与窗口（那是用户中转服务的 ID）、历史用量与消息内容。
 * 键名必须与 [SettingsDataStore] 保持一致。
 */
internal object DeepSeekModelIdMigration : DataMigration<Preferences> {
    private val MIGRATED = booleanPreferencesKey("deepseek_model_ids_migrated_v1")
    private val MODEL = stringPreferencesKey("model")
    private val RP_AUXILIARY_MODEL = stringPreferencesKey("rp_auxiliary_model")
    private val CHAT_PROVIDER = stringPreferencesKey("chat_provider")
    private val DEEPSEEK_MODEL_CONTEXT_WINDOWS = stringPreferencesKey("deepseek_model_context_windows")
    private val DEEPSEEK_CACHED_MODELS = stringPreferencesKey("deepseek_cached_models")
    private val DEEPSEEK_MODELS_UPDATED_AT = longPreferencesKey("deepseek_models_updated_at")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[MIGRATED] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { migrateInPlace(this) }.toPreferences()

    override suspend fun cleanUp() = Unit

    fun migrateInPlace(prefs: MutablePreferences) {
        if (prefs[MIGRATED] == true) return

        prefs[MODEL]?.let { model ->
            if (DeepSeekModels.isLegacyFlashId(model)) prefs[MODEL] = DeepSeekModels.FLASH
        }

        // RP 辅助模型由所有供应商共用：当前是自定义供应商时，它填的是中转服务的 ID，
        // 中转未必认识 deepseek-flash，而旧名在 DeepSeek 官方仍被接受，因此保持原值。
        val onDeepSeek = ChatProviderId.fromStorage(prefs[CHAT_PROVIDER]) == ChatProviderId.DEEPSEEK
        prefs[RP_AUXILIARY_MODEL]?.let { auxiliary ->
            if (onDeepSeek && DeepSeekModels.isLegacyFlashId(auxiliary)) {
                prefs[RP_AUXILIARY_MODEL] = DeepSeekModels.FLASH
            }
        }

        prefs[DEEPSEEK_MODEL_CONTEXT_WINDOWS]?.let { raw ->
            decodeOrNull<Map<String, Long>>(raw)?.let { windows ->
                migrateContextWindows(windows)?.let { prefs[DEEPSEEK_MODEL_CONTEXT_WINDOWS] = json.encodeToString(it) }
            }
        }

        // 缓存里有旧名说明它来自旧服务器目录；清空后回到内置兜底，等待下次真实刷新，不伪造服务器结果。
        prefs[DEEPSEEK_CACHED_MODELS]?.let { raw ->
            val cached = decodeOrNull<List<String>>(raw)
            if (cached != null && cached.any(DeepSeekModels::isLegacyFlashId)) {
                prefs[DEEPSEEK_CACHED_MODELS] = json.encodeToString(emptyList<String>())
                prefs[DEEPSEEK_MODELS_UPDATED_AT] = 0L
            }
        }

        prefs[MIGRATED] = true
    }

    /**
     * 旧名的手填窗口在新 ID 尚无覆盖时迁移过去，否则丢弃旧键。没有旧键时返回 null 表示无需改写。
     * 两个旧名都有值时取先出现的那个。
     */
    internal fun migrateContextWindows(windows: Map<String, Long>): Map<String, Long>? {
        val legacyKeys = windows.keys.filter(DeepSeekModels::isLegacyFlashId)
        if (legacyKeys.isEmpty()) return null
        val result = LinkedHashMap<String, Long>()
        windows.forEach { (key, value) ->
            if (key in legacyKeys) return@forEach
            result[key] = value
        }
        if (result.keys.none { it.trim() == DeepSeekModels.FLASH }) {
            result[DeepSeekModels.FLASH] = windows.getValue(legacyKeys.first())
        }
        return result
    }

    /** 存储格式损坏时不改写，交给 [SettingsDataStore] 读取时的既有容错处理（它会记录日志）。 */
    private inline fun <reified T> decodeOrNull(raw: String): T? =
        runCatching { json.decodeFromString<T>(raw) }.getOrNull()
}
