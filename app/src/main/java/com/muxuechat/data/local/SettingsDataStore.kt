package com.muxuechat.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextCompressionPolicy
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DeepSeekModel
import com.muxuechat.domain.model.DisplayScale
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.ReasoningEffort
import com.muxuechat.domain.model.ThinkingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore(name = "chat_settings")

/**
 * 供应商列表的持久化形态。
 *
 * **不含 API Key**：Key 只走 [EncryptedProviderKeyStore]。
 * 单独定义一个 DTO 而不给 domain 模型加 @Serializable，是为了让存储格式
 * 可以独立演进，不牵动 domain。
 */
@Serializable
private data class CustomProviderDto(
    val id: String,
    val name: String = "",
    val baseUrl: String = "",
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val modelsUpdatedAtMillis: Long = 0L,
    /** 旧 JSON 缺字段时保持 false，不能让升级后的普通聊天反代突然收到 tools。 */
    val toolCallingEnabled: Boolean = false,
    val reasoningEffort: String = "",
)

class SettingsDataStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<ChatSettings> = context.settingsDataStore.data.map { prefs ->
        ChatSettings(
            baseUrl = prefs[BASE_URL] ?: ChatSettings.DEFAULT_BASE_URL,
            model = prefs[MODEL]?.let(::modelFromApiName) ?: DeepSeekModel.V4_FLASH,
            systemPrompt = prefs[SYSTEM_PROMPT].orEmpty(),
            streamEnabled = prefs[STREAM_ENABLED] ?: true,
            maxTokens = ChatSettings.coerceMaxTokens(prefs[MAX_TOKENS] ?: ChatSettings.DEFAULT_MAX_TOKENS),
            thinkingMode = prefs[THINKING_MODE]?.let(::thinkingFromApiType) ?: ThinkingMode.DISABLED,
            reasoningEffort = prefs[REASONING_EFFORT]?.let(::effortFromApiName) ?: ReasoningEffort.HIGH,
            temperature = prefs[TEMPERATURE] ?: 0.7,
            topP = prefs[TOP_P] ?: 1.0,
            contextCompressionMode = ContextCompressionMode.fromStorage(prefs[CONTEXT_COMPRESSION_MODE]),
            contextAutoTriggerPercent = prefs[CONTEXT_AUTO_TRIGGER_PERCENT]
                ?: ContextCompressionPolicy.DEFAULT_AUTO_TRIGGER_PERCENT,
            contextBudgetChars = prefs[CONTEXT_BUDGET_CHARS]
                ?: ContextCompressionPolicy.DEFAULT_BUDGET_CHARS,
            settingsToolsEnabled = prefs[SETTINGS_TOOLS_ENABLED] ?: true,
            // 旧版不存在这些键时必须继续使用 DeepSeek，避免升级后路由发生变化。
            provider = ChatProviderId.fromStorage(prefs[CHAT_PROVIDER]),
            customProviders = decodeProviders(prefs[CUSTOM_PROVIDERS]),
            activeCustomProviderId = prefs[ACTIVE_CUSTOM_PROVIDER_ID],
        ).normalized()
    }

    suspend fun updateSettings(settings: ChatSettings) {
        // 持久化前统一收敛，避免非法值经由 UI 或旧数据一路带到请求体。
        val normalized = settings.normalized()
        context.settingsDataStore.edit { prefs ->
            prefs[BASE_URL] = normalized.baseUrl
            prefs[MODEL] = normalized.model.apiName
            prefs[SYSTEM_PROMPT] = normalized.systemPrompt
            prefs[STREAM_ENABLED] = normalized.streamEnabled
            prefs[MAX_TOKENS] = normalized.maxTokens
            prefs[THINKING_MODE] = normalized.thinkingMode.apiType
            prefs[REASONING_EFFORT] = normalized.reasoningEffort.apiName
            prefs[TEMPERATURE] = normalized.temperature
            prefs[TOP_P] = normalized.topP
            prefs[CONTEXT_COMPRESSION_MODE] = normalized.contextCompressionMode.storageValue
            prefs[CONTEXT_AUTO_TRIGGER_PERCENT] = normalized.contextAutoTriggerPercent
            prefs[CONTEXT_BUDGET_CHARS] = normalized.contextBudgetChars
            prefs[SETTINGS_TOOLS_ENABLED] = normalized.settingsToolsEnabled
            prefs[CHAT_PROVIDER] = normalized.provider.storageValue
            prefs[CUSTOM_PROVIDERS] = encodeProviders(normalized.customProviders)
            normalized.activeCustomProviderId
                ?.let { prefs[ACTIVE_CUSTOM_PROVIDER_ID] = it }
                ?: prefs.remove(ACTIVE_CUSTOM_PROVIDER_ID)
            // 旧版本写入的 presence_penalty / frequency_penalty 键不再读写，
            // 留在 DataStore 里不影响功能，会随下次清数据自然消失。
        }
    }

    /**
     * 把一般化改造前写死的 CLIProxyAPI 配置迁移成一条普通的自定义供应商记录。
     *
     * 只执行一次。返回新供应商 id 表示调用方还需要把旧 Keystore 里的代理 Key
     * 复制到对应的 per-provider alias；返回 null 表示无需迁移。
     */
    suspend fun migrateLegacyCliProxyProviderIfNeeded(): String? {
        if (context.settingsDataStore.data.first()[LEGACY_MIGRATED] == true) return null

        var createdProviderId: String? = null
        context.settingsDataStore.edit { prefs ->
            if (prefs[LEGACY_MIGRATED] == true) return@edit
            prefs[LEGACY_MIGRATED] = true

            val legacyBaseUrl = prefs[LEGACY_CLI_PROXY_BASE_URL].orEmpty()
            if (legacyBaseUrl.isBlank() || !prefs[CUSTOM_PROVIDERS].isNullOrBlank()) return@edit

            val providerId = UUID.randomUUID().toString()
            val legacyModel = prefs[LEGACY_CLI_PROXY_MODEL].orEmpty().trim()
            val provider = CustomProvider(
                id = providerId,
                name = "CLIProxyAPI",
                baseUrl = legacyBaseUrl,
                models = listOfNotNull(legacyModel.ifBlank { null }),
                selectedModel = legacyModel,
            ).normalized()

            prefs[CUSTOM_PROVIDERS] = encodeProviders(listOf(provider))
            prefs[ACTIVE_CUSTOM_PROVIDER_ID] = providerId
            createdProviderId = providerId
            Log.i(TAG, "Migrated legacy CLIProxyAPI config into a custom provider")
        }
        return createdProviderId
    }

    val displaySettings: Flow<DisplaySettings> = context.settingsDataStore.data.map { prefs ->
        DisplaySettings(
            displayScale = DisplayScale.fromStorageKey(prefs[DISPLAY_SCALE]),
        )
    }

    suspend fun updateDisplaySettings(settings: DisplaySettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[DISPLAY_SCALE] = settings.displayScale.storageKey
        }
    }

    private fun decodeProviders(raw: String?): List<CustomProvider> {
        if (raw.isNullOrBlank()) return emptyList()
        // 存储格式损坏时宁可回到空列表，也不要让整个设置流崩掉导致 App 打不开。
        return runCatching {
            json.decodeFromString<List<CustomProviderDto>>(raw).map { dto ->
                CustomProvider(
                    id = dto.id,
                    name = dto.name,
                    baseUrl = dto.baseUrl,
                    models = dto.models,
                    selectedModel = dto.selectedModel,
                    modelsUpdatedAtMillis = dto.modelsUpdatedAtMillis,
                    toolCallingEnabled = dto.toolCallingEnabled,
                    reasoningEffort = dto.reasoningEffort,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Decoding custom providers failed: ${error.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    private fun modelFromApiName(value: String) =
        DeepSeekModel.entries.firstOrNull { it.apiName == value } ?: DeepSeekModel.V4_FLASH

    private fun thinkingFromApiType(value: String) =
        ThinkingMode.entries.firstOrNull { it.apiType == value } ?: ThinkingMode.DISABLED

    private fun effortFromApiName(value: String) =
        ReasoningEffort.entries.firstOrNull { it.apiName == value } ?: ReasoningEffort.HIGH

    companion object {
        private const val TAG = "muxueChat.SettingsDataStore"
        private val defaultJson = Json { ignoreUnknownKeys = true }

        private val BASE_URL = stringPreferencesKey("base_url")
        private val MODEL = stringPreferencesKey("model")
        private val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val STREAM_ENABLED = booleanPreferencesKey("stream_enabled")
        private val MAX_TOKENS = intPreferencesKey("max_tokens")
        private val THINKING_MODE = stringPreferencesKey("thinking_mode")
        private val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        private val TEMPERATURE = doublePreferencesKey("temperature")
        private val TOP_P = doublePreferencesKey("top_p")
        private val CONTEXT_COMPRESSION_MODE = stringPreferencesKey("context_compression_mode")
        private val CONTEXT_AUTO_TRIGGER_PERCENT = intPreferencesKey("context_auto_trigger_percent")
        private val CONTEXT_BUDGET_CHARS = intPreferencesKey("context_budget_chars")
        private val SETTINGS_TOOLS_ENABLED = booleanPreferencesKey("settings_tools_enabled")
        private val CHAT_PROVIDER = stringPreferencesKey("chat_provider")
        private val CUSTOM_PROVIDERS = stringPreferencesKey("custom_providers")
        private val ACTIVE_CUSTOM_PROVIDER_ID = stringPreferencesKey("active_custom_provider_id")
        private val DISPLAY_SCALE = stringPreferencesKey("display_scale")

        /** 一般化改造前的写死配置，仅用于一次性迁移。 */
        private val LEGACY_CLI_PROXY_BASE_URL = stringPreferencesKey("cli_proxy_api_base_url")
        private val LEGACY_CLI_PROXY_MODEL = stringPreferencesKey("cli_proxy_api_model")
        private val LEGACY_MIGRATED = booleanPreferencesKey("custom_providers_migrated")

        private fun encodeProviders(providers: List<CustomProvider>): String =
            defaultJson.encodeToString(
                providers.map { provider ->
                    CustomProviderDto(
                        id = provider.id,
                        name = provider.name,
                        baseUrl = provider.baseUrl,
                        models = provider.models,
                        selectedModel = provider.selectedModel,
                        modelsUpdatedAtMillis = provider.modelsUpdatedAtMillis,
                        toolCallingEnabled = provider.toolCallingEnabled,
                        reasoningEffort = provider.reasoningEffort,
                    )
                },
            )
    }
}
