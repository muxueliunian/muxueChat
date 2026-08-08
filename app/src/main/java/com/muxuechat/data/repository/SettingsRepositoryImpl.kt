package com.muxuechat.data.repository

import com.muxuechat.data.local.EncryptedApiKeyStore
import com.muxuechat.data.local.EncryptedCliProxyApiKeyStore
import com.muxuechat.data.local.EncryptedProviderKeyStore
import com.muxuechat.data.local.SettingsDataStore
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SettingsRepositoryImpl(
    private val settingsDataStore: SettingsDataStore,
    private val apiKeyStore: EncryptedApiKeyStore,
    private val providerKeyStore: EncryptedProviderKeyStore,
    /** 只用于拉取模型列表；聊天请求仍由 ChatRepository 负责。 */
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    /** 仅用于一次性迁移旧的 CLIProxyAPI 代理 Key。 */
    private val legacyCliProxyApiKeyStore: EncryptedCliProxyApiKeyStore,
) : SettingsRepository {
    override val settings: Flow<ChatSettings> = settingsDataStore.settings
    override val displaySettings: Flow<DisplaySettings> = settingsDataStore.displaySettings

    override suspend fun updateSettings(settings: ChatSettings) {
        settingsDataStore.updateSettings(settings)
    }

    override suspend fun updateDisplaySettings(settings: DisplaySettings) {
        settingsDataStore.updateDisplaySettings(settings)
    }

    override suspend fun saveApiKey(apiKey: String) {
        apiKeyStore.saveApiKey(apiKey.trim())
    }

    override suspend fun getApiKey(): String? = apiKeyStore.getApiKey()?.takeIf { it.isNotBlank() }

    override suspend fun clearApiKey() {
        apiKeyStore.clearApiKey()
    }

    override suspend fun saveProviderKey(providerId: String, apiKey: String) {
        providerKeyStore.saveApiKey(providerId, apiKey.trim())
    }

    override suspend fun getProviderKey(providerId: String): String? =
        providerKeyStore.getApiKey(providerId)?.takeIf { it.isNotBlank() }

    override suspend fun clearProviderKey(providerId: String) {
        providerKeyStore.clearApiKey(providerId)
    }

    override fun hasProviderKey(providerId: String): Boolean = providerKeyStore.hasApiKey(providerId)

    override suspend fun upsertCustomProvider(provider: CustomProvider) {
        val current = settings.first()
        val existing = current.customProviders.any { it.id == provider.id }
        val updated = if (existing) {
            current.customProviders.map { if (it.id == provider.id) provider else it }
        } else {
            current.customProviders + provider
        }
        settingsDataStore.updateSettings(
            current.copy(
                customProviders = updated,
                // 新建的供应商立即成为当前选中项并接管路由：用户刚添加它就是为了配置和使用，
                // 否则设置页不会展开它的编辑区，还要再点一次才能填 Key。
                activeCustomProviderId = if (existing) current.activeCustomProviderId else provider.id,
                provider = if (existing) current.provider else ChatProviderId.CUSTOM_OPENAI,
            ),
        )
    }

    override suspend fun deleteCustomProvider(providerId: String) {
        val current = settings.first()
        val remaining = current.customProviders.filterNot { it.id == providerId }
        // 先落库再删 Key：即使删 Key 失败，也不会留下一个指向已删记录的可用凭据入口。
        settingsDataStore.updateSettings(
            current.copy(
                customProviders = remaining,
                activeCustomProviderId = current.activeCustomProviderId?.takeIf { it != providerId },
                // 删光了就必须退回 DeepSeek，否则会停在一个无供应商可用的状态。
                provider = if (remaining.isEmpty()) ChatProviderId.DEEPSEEK else current.provider,
            ),
        )
        providerKeyStore.clearApiKey(providerId)
    }

    override suspend fun selectCustomProviderModel(providerId: String, model: String) {
        val current = settings.first()
        settingsDataStore.updateSettings(
            current.copy(
                provider = ChatProviderId.CUSTOM_OPENAI,
                activeCustomProviderId = providerId,
                customProviders = current.customProviders.map { provider ->
                    if (provider.id == providerId) provider.copy(selectedModel = model) else provider
                },
            ),
        )
    }

    override suspend fun refreshProviderModels(providerId: String): List<String> {
        val provider = settings.first().customProviders.firstOrNull { it.id == providerId }
            ?: error("供应商不存在，请重新添加")
        require(provider.baseUrl.isNotBlank()) { "请先填写该供应商的 Base URL" }
        val apiKey = getProviderKey(providerId) ?: error("请先保存该供应商的 API Key")

        val models = openAiCompatibleClient.listModels(apiKey = apiKey, baseUrl = provider.baseUrl)
        updateProviderModels(providerId, models, System.currentTimeMillis())
        return models
    }

    private suspend fun updateProviderModels(
        providerId: String,
        models: List<String>,
        updatedAtMillis: Long,
    ) {
        val current = settings.first()
        settingsDataStore.updateSettings(
            current.copy(
                customProviders = current.customProviders.map { provider ->
                    if (provider.id != providerId) {
                        provider
                    } else {
                        provider.copy(
                            models = models,
                            // 原选中模型仍在新列表里就保留，否则退回列表首项，避免指向一个已下线的模型。
                            selectedModel = provider.selectedModel
                                .takeIf { it.isNotBlank() && models.contains(it) }
                                ?: models.firstOrNull().orEmpty(),
                            modelsUpdatedAtMillis = updatedAtMillis,
                        )
                    }
                },
            ),
        )
    }

    override suspend fun migrateLegacyProviderIfNeeded() {
        val migratedProviderId = settingsDataStore.migrateLegacyCliProxyProviderIfNeeded() ?: return
        // 旧 Key 存在才搬运；搬完清掉旧槽位，避免同一份凭据留两份密文。
        val legacyKey = runCatching { legacyCliProxyApiKeyStore.getApiKey() }.getOrNull()
        if (!legacyKey.isNullOrBlank()) {
            providerKeyStore.saveApiKey(migratedProviderId, legacyKey)
            legacyCliProxyApiKeyStore.clearApiKey()
        }
    }
}
