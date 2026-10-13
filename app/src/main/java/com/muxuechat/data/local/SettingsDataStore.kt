package com.muxuechat.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.muxuechat.domain.context.ContextCompressionMode
import com.muxuechat.domain.context.ContextCompressionPolicy
import com.muxuechat.domain.context.ModelContextCatalog
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.ProviderAuthKind
import com.muxuechat.domain.model.OpenAiOutputTokenField
import com.muxuechat.domain.model.DeepSeekModels
import com.muxuechat.domain.model.DisplayScale
import com.muxuechat.domain.model.DisplaySettings
import com.muxuechat.domain.model.ReasoningEffort
import com.muxuechat.domain.model.ThinkingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore(
    name = "chat_settings",
    produceMigrations = { listOf(DeepSeekModelIdMigration) },
)

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
    /** 旧供应商升级后保持既有请求行为；用户可在设置中显式关闭。 */
    val sendTemperature: Boolean = true,
    val sendTopP: Boolean = true,
    val reasoningEffort: String = "",
    /** 旧 JSON 缺字段时为空表，等价于「该供应商所有模型都用回退窗口」。 */
    val modelContextWindows: Map<String, Long> = emptyMap(),
    /** 旧 JSON 缺字段时保持 false，不能让升级后的旧反代突然收到 stream_options。 */
    val streamUsageEnabled: Boolean = false,
    /**
     * 认证方式。旧 JSON 缺字段时按 API Key 处理；默认值不写入 JSON（encodeDefaults=false），
     * 因此已有 API Key 供应商的存储内容与改动前逐字节一致。
     */
    val authKind: String = "api_key",
    val outputTokenField: String = "max_tokens",
    val conversationHeaderName: String = "",
)

/**
 * 一次旧凭据迁移重试的稳定计划。
 *
 * [stateId] 用于完成 pending 状态；[targetProviderId] 为 null 表示只清理旧凭据，
 * 从而避免把内部 cleanup 标记误当成真实供应商 id。
 */
@ConsistentCopyVisibility
data class LegacyProviderMigrationPlan internal constructor(
    val stateId: String,
    val targetProviderId: String?,
)

class SettingsDataStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.settingsDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<ChatSettings> = dataStore.data.map(::decodeSettings)

    /**
     * 在 DataStore 自己的串行 edit 临界区内读取并修改最新设置。
     *
     * 调用方只能表达本次需要改变的字段，不能再用 `first() -> copy() -> 全量覆盖`
     * 把并发写入的其他字段回滚到旧快照。
     */
    suspend fun updateSettings(transform: (ChatSettings) -> ChatSettings) {
        dataStore.edit { prefs ->
            val normalized = transform(decodeSettings(prefs)).normalized()
            writeSettings(prefs, normalized)
        }
    }

    // 旧的字符预算已经不参与任何决策，但仍要读写，否则升级后再降级会丢掉用户数据。
    @Suppress("DEPRECATION")
    private fun decodeSettings(prefs: Preferences): ChatSettings =
        ChatSettings(
            baseUrl = prefs[BASE_URL] ?: ChatSettings.DEFAULT_BASE_URL,
            model = DeepSeekModels.normalizeSelectedId(prefs[MODEL]),
            deepSeekCachedModelIds = decodeStringList(prefs[DEEPSEEK_CACHED_MODELS]),
            deepSeekModelsUpdatedAtMillis = prefs[DEEPSEEK_MODELS_UPDATED_AT] ?: 0L,
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
            contextTargetPercent = prefs[CONTEXT_TARGET_PERCENT]
                ?: ContextCompressionPolicy.DEFAULT_TARGET_PERCENT,
            // 旧字符预算只读不用：留着是为了不在升级时丢数据，任何决策都不再看它。
            contextBudgetChars = prefs[CONTEXT_BUDGET_CHARS]
                ?: ChatSettings.LEGACY_DEFAULT_BUDGET_CHARS,
            deepSeekModelContextWindows = decodeContextWindows(prefs[DEEPSEEK_MODEL_CONTEXT_WINDOWS]),
            contextCostCapEnabled = prefs[CONTEXT_COST_CAP_ENABLED] ?: false,
            contextCostCapTokens = prefs[CONTEXT_COST_CAP_TOKENS]
                ?: ModelContextCatalog.DEFAULT_COST_CAP_TOKENS,
            settingsToolsEnabled = prefs[SETTINGS_TOOLS_ENABLED] ?: true,
            // 旧版不存在这些键时必须继续使用 DeepSeek，避免升级后路由发生变化。
            provider = ChatProviderId.fromStorage(prefs[CHAT_PROVIDER]),
            customProviders = decodeProviders(prefs[CUSTOM_PROVIDERS]),
            activeCustomProviderId = prefs[ACTIVE_CUSTOM_PROVIDER_ID],
            rpAuxiliaryModel = prefs[RP_AUXILIARY_MODEL].orEmpty(),
            defaultProtagonistProfileId = prefs[DEFAULT_PROTAGONIST_PROFILE_ID]?.takeIf { it.isNotBlank() },
        ).normalized()

    @Suppress("DEPRECATION")
    private fun writeSettings(prefs: MutablePreferences, normalized: ChatSettings) {
        prefs[BASE_URL] = normalized.baseUrl
        prefs[MODEL] = normalized.model
        prefs[DEEPSEEK_CACHED_MODELS] = encodeStringList(normalized.deepSeekCachedModelIds)
        prefs[DEEPSEEK_MODELS_UPDATED_AT] = normalized.deepSeekModelsUpdatedAtMillis
        prefs[SYSTEM_PROMPT] = normalized.systemPrompt
        prefs[STREAM_ENABLED] = normalized.streamEnabled
        prefs[MAX_TOKENS] = normalized.maxTokens
        prefs[THINKING_MODE] = normalized.thinkingMode.apiType
        prefs[REASONING_EFFORT] = normalized.reasoningEffort.apiName
        prefs[TEMPERATURE] = normalized.temperature
        prefs[TOP_P] = normalized.topP
        prefs[CONTEXT_COMPRESSION_MODE] = normalized.contextCompressionMode.storageValue
        prefs[CONTEXT_AUTO_TRIGGER_PERCENT] = normalized.contextAutoTriggerPercent
        prefs[CONTEXT_TARGET_PERCENT] = normalized.contextTargetPercent
        prefs[CONTEXT_BUDGET_CHARS] = normalized.contextBudgetChars
        prefs[DEEPSEEK_MODEL_CONTEXT_WINDOWS] = encodeContextWindows(normalized.deepSeekModelContextWindows)
        prefs[CONTEXT_COST_CAP_ENABLED] = normalized.contextCostCapEnabled
        prefs[CONTEXT_COST_CAP_TOKENS] = normalized.contextCostCapTokens
        prefs[SETTINGS_TOOLS_ENABLED] = normalized.settingsToolsEnabled
        prefs[CHAT_PROVIDER] = normalized.provider.storageValue
        prefs[CUSTOM_PROVIDERS] = encodeProviders(normalized.customProviders)
        prefs[RP_AUXILIARY_MODEL] = normalized.rpAuxiliaryModel
        normalized.defaultProtagonistProfileId?.takeIf { it.isNotBlank() }
            ?.let { prefs[DEFAULT_PROTAGONIST_PROFILE_ID] = it }
            ?: prefs.remove(DEFAULT_PROTAGONIST_PROFILE_ID)
        normalized.activeCustomProviderId
            ?.let { prefs[ACTIVE_CUSTOM_PROVIDER_ID] = it }
            ?: prefs.remove(ACTIVE_CUSTOM_PROVIDER_ID)
        // 旧版本写入的 presence_penalty / frequency_penalty 键不再读写，
        // 留在 DataStore 里不影响功能，会随下次清数据自然消失。
    }

    /**
     * 准备旧 CLIProxyAPI 迁移，并把稳定的目标 providerId 持久化为 pending。
     *
     * 供应商配置与 pending 标记在同一个 DataStore edit 中提交；真正的 Key 搬运完成后，
     * 调用方必须再调用 [completeLegacyCliProxyProviderMigration]。进程在任一步骤退出时，
     * 下次启动都会得到同一个 id 并继续，而不会重复创建供应商。
     */
    suspend fun prepareLegacyCliProxyProviderMigration(): LegacyProviderMigrationPlan? {
        var pendingProviderId: String? = null
        dataStore.edit { prefs ->
            if (prefs[LEGACY_MIGRATION_STATE] == LEGACY_MIGRATION_COMPLETED) return@edit

            val providers = decodeProviders(prefs[CUSTOM_PROVIDERS])
            val existingPendingId = prefs[LEGACY_MIGRATION_PROVIDER_ID]
            if (existingPendingId != null) {
                // 目标若已被用户删除，仍返回同一 pending id 让 Repository 只清旧凭据；
                // 不重建 Provider，也不能提前 completed 后把旧密文/alias 永久遗留。
                prefs[LEGACY_MIGRATION_STATE] = LEGACY_MIGRATION_PENDING
                pendingProviderId = existingPendingId
                return@edit
            }

            val legacyBaseUrl = prefs[LEGACY_CLI_PROXY_BASE_URL].orEmpty()
            if (legacyBaseUrl.isBlank()) {
                // 地址缺失不代表旧 Key 一定不存在；统一走幂等清理，fresh install 无 alias 时也安全。
                prepareLegacyCredentialCleanup(prefs)
                pendingProviderId = LEGACY_CLEANUP_ONLY_PROVIDER_ID
                return@edit
            }

            // 兼容旧实现已经原子创建 Provider、但在随后搬 Key 前崩溃的状态。
            val existingLegacyProvider = providers.firstOrNull { provider ->
                provider.name == LEGACY_PROVIDER_NAME &&
                    provider.baseUrl.trimEnd('/') == legacyBaseUrl.trim().trimEnd('/')
            }
            if (prefs[LEGACY_MIGRATED] == true && existingLegacyProvider == null) {
                // 旧实现已标记完成、且当前不存在当时创建的目标，说明迁移曾被跳过或
                // 用户后来主动删除了供应商。新版不能复活它，但仍要清理旧凭据。
                prepareLegacyCredentialCleanup(prefs)
                pendingProviderId = LEGACY_CLEANUP_ONLY_PROVIDER_ID
                return@edit
            }
            if (providers.isNotEmpty() && existingLegacyProvider == null) {
                // 用户已有新格式供应商时沿用旧版策略，不擅自把旧 Key 绑定到任意记录。
                prepareLegacyCredentialCleanup(prefs)
                pendingProviderId = LEGACY_CLEANUP_ONLY_PROVIDER_ID
                return@edit
            }

            val providerId = existingLegacyProvider?.id ?: UUID.randomUUID().toString()
            val legacyModel = prefs[LEGACY_CLI_PROXY_MODEL].orEmpty().trim()
            if (existingLegacyProvider == null) {
                val provider = CustomProvider(
                    id = providerId,
                    name = LEGACY_PROVIDER_NAME,
                    baseUrl = legacyBaseUrl,
                    models = listOfNotNull(legacyModel.ifBlank { null }),
                    selectedModel = legacyModel,
                ).normalized()

                prefs[CUSTOM_PROVIDERS] = encodeProviders(listOf(provider))
                prefs[ACTIVE_CUSTOM_PROVIDER_ID] = providerId
            }
            prefs[LEGACY_MIGRATION_PROVIDER_ID] = providerId
            prefs[LEGACY_MIGRATION_STATE] = LEGACY_MIGRATION_PENDING
            pendingProviderId = providerId
            Log.i(TAG, "Legacy provider migration prepared")
        }
        return pendingProviderId?.let(::legacyMigrationPlan)
    }

    suspend fun completeLegacyCliProxyProviderMigration(providerId: String) {
        dataStore.edit { prefs ->
            if (prefs[LEGACY_MIGRATION_STATE] == LEGACY_MIGRATION_COMPLETED) return@edit
            check(prefs[LEGACY_MIGRATION_PROVIDER_ID] == providerId) {
                "旧供应商迁移状态不一致，请重试"
            }
            markLegacyMigrationCompleted(prefs)
            Log.i(TAG, "Legacy provider migration completed")
        }
    }

    val displaySettings: Flow<DisplaySettings> = dataStore.data.map { prefs ->
        DisplaySettings(
            displayScale = DisplayScale.fromStorageKey(prefs[DISPLAY_SCALE]),
        )
    }

    suspend fun updateDisplaySettings(settings: DisplaySettings) {
        dataStore.edit { prefs ->
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
                    sendTemperature = dto.sendTemperature,
                    sendTopP = dto.sendTopP,
                    reasoningEffort = dto.reasoningEffort,
                    modelContextWindows = dto.modelContextWindows,
                    streamUsageEnabled = dto.streamUsageEnabled,
                    authKind = ProviderAuthKind.fromStorage(dto.authKind),
                    outputTokenField = OpenAiOutputTokenField.fromStorage(dto.outputTokenField),
                    conversationHeaderName = dto.conversationHeaderName,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Decoding custom providers failed: ${error.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    /** 存储格式损坏时回到空表，绝不能让一段坏 JSON 把整个设置流打挂。 */
    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .onFailure { error ->
                Log.w(TAG, "Decoding DeepSeek cached models failed: ${error.javaClass.simpleName}")
            }
            .getOrDefault(emptyList())
    }

    /** 存储格式损坏时回到空表，绝不能让一段坏 JSON 把整个设置流打挂。 */
    private fun decodeContextWindows(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }
            .onFailure { error ->
                Log.w(TAG, "Decoding model context windows failed: ${error.javaClass.simpleName}")
            }
            .getOrDefault(emptyMap())
    }

    private fun thinkingFromApiType(value: String) =
        ThinkingMode.entries.firstOrNull { it.apiType == value } ?: ThinkingMode.DISABLED

    private fun effortFromApiName(value: String) =
        ReasoningEffort.entries.firstOrNull { it.apiName == value } ?: ReasoningEffort.HIGH

    private fun markLegacyMigrationCompleted(prefs: MutablePreferences) {
        prefs[LEGACY_MIGRATION_STATE] = LEGACY_MIGRATION_COMPLETED
        prefs[LEGACY_MIGRATED] = true
        prefs.remove(LEGACY_MIGRATION_PROVIDER_ID)
    }

    private fun prepareLegacyCredentialCleanup(prefs: MutablePreferences) {
        prefs[LEGACY_MIGRATION_STATE] = LEGACY_MIGRATION_PENDING
        prefs[LEGACY_MIGRATION_PROVIDER_ID] = LEGACY_CLEANUP_ONLY_PROVIDER_ID
    }

    private fun legacyMigrationPlan(stateId: String) = LegacyProviderMigrationPlan(
        stateId = stateId,
        targetProviderId = stateId.takeUnless { it == LEGACY_CLEANUP_ONLY_PROVIDER_ID },
    )

    companion object {
        private const val TAG = "muxueChat.SettingsDataStore"
        private val defaultJson = Json { ignoreUnknownKeys = true }

        private val BASE_URL = stringPreferencesKey("base_url")
        private val MODEL = stringPreferencesKey("model")
        private val DEEPSEEK_CACHED_MODELS = stringPreferencesKey("deepseek_cached_models")
        private val DEEPSEEK_MODELS_UPDATED_AT = longPreferencesKey("deepseek_models_updated_at")
        private val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val STREAM_ENABLED = booleanPreferencesKey("stream_enabled")
        private val MAX_TOKENS = intPreferencesKey("max_tokens")
        private val THINKING_MODE = stringPreferencesKey("thinking_mode")
        private val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        private val TEMPERATURE = doublePreferencesKey("temperature")
        private val TOP_P = doublePreferencesKey("top_p")
        private val CONTEXT_COMPRESSION_MODE = stringPreferencesKey("context_compression_mode")
        private val CONTEXT_AUTO_TRIGGER_PERCENT = intPreferencesKey("context_auto_trigger_percent")
        private val CONTEXT_TARGET_PERCENT = intPreferencesKey("context_target_percent")

        /**
         * 旧的字符预算。已不参与任何决策，只为不在升级时丢用户数据而继续读写。
         */
        private val CONTEXT_BUDGET_CHARS = intPreferencesKey("context_budget_chars")
        private val DEEPSEEK_MODEL_CONTEXT_WINDOWS = stringPreferencesKey("deepseek_model_context_windows")
        private val CONTEXT_COST_CAP_ENABLED = booleanPreferencesKey("context_cost_cap_enabled")
        private val CONTEXT_COST_CAP_TOKENS = longPreferencesKey("context_cost_cap_tokens")
        private val SETTINGS_TOOLS_ENABLED = booleanPreferencesKey("settings_tools_enabled")
        private val CHAT_PROVIDER = stringPreferencesKey("chat_provider")
        private val CUSTOM_PROVIDERS = stringPreferencesKey("custom_providers")
        private val ACTIVE_CUSTOM_PROVIDER_ID = stringPreferencesKey("active_custom_provider_id")

        /** RP 辅助请求的模型覆盖。空串表示跟随正文模型，是升级后的默认行为。 */
        private val RP_AUXILIARY_MODEL = stringPreferencesKey("rp_auxiliary_model")
        private val DEFAULT_PROTAGONIST_PROFILE_ID = stringPreferencesKey("default_protagonist_profile_id")
        private val DISPLAY_SCALE = stringPreferencesKey("display_scale")

        /** 一般化改造前的写死配置，仅用于一次性迁移。 */
        private val LEGACY_CLI_PROXY_BASE_URL = stringPreferencesKey("cli_proxy_api_base_url")
        private val LEGACY_CLI_PROXY_MODEL = stringPreferencesKey("cli_proxy_api_model")
        private val LEGACY_MIGRATED = booleanPreferencesKey("custom_providers_migrated")
        private val LEGACY_MIGRATION_STATE = stringPreferencesKey("custom_providers_migration_state")
        private val LEGACY_MIGRATION_PROVIDER_ID = stringPreferencesKey("custom_providers_migration_provider_id")

        private const val LEGACY_PROVIDER_NAME = "CLIProxyAPI"
        private const val LEGACY_CLEANUP_ONLY_PROVIDER_ID = "legacy-cleanup-only"
        private const val LEGACY_MIGRATION_PENDING = "pending"
        private const val LEGACY_MIGRATION_COMPLETED = "completed"

        private fun encodeContextWindows(windows: Map<String, Long>): String =
            defaultJson.encodeToString(windows)

        private fun encodeStringList(values: List<String>): String =
            defaultJson.encodeToString(values)

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
                        sendTemperature = provider.sendTemperature,
                        sendTopP = provider.sendTopP,
                        reasoningEffort = provider.reasoningEffort,
                        modelContextWindows = provider.modelContextWindows,
                        streamUsageEnabled = provider.streamUsageEnabled,
                        authKind = provider.authKind.storageValue,
                        outputTokenField = provider.outputTokenField.storageValue,
                        conversationHeaderName = provider.conversationHeaderName,
                    )
                },
            )
    }
}
