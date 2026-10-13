package com.muxuechat.data.repository

import com.muxuechat.data.local.DeepSeekCredentialStore
import com.muxuechat.data.local.LegacyCliProxyCredentialStore
import com.muxuechat.data.local.ProviderCredentialStore
import com.muxuechat.data.local.SettingsDataStore
import com.muxuechat.data.remote.DeepSeekApiClient
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SettingsRepositoryImpl(
    private val settingsDataStore: SettingsDataStore,
    private val apiKeyStore: DeepSeekCredentialStore,
    private val providerKeyStore: ProviderCredentialStore,
    /** 只用于拉取自定义供应商模型列表；聊天请求仍由 ChatRepository 负责。 */
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    /** 只用于拉取 DeepSeek 模型列表；聊天请求仍走 DeepSeek 专属链路。 */
    private val deepSeekApiClient: DeepSeekApiClient,
    /** 仅用于一次性迁移旧的 CLIProxyAPI 代理 Key。 */
    private val legacyCliProxyApiKeyStore: LegacyCliProxyCredentialStore,
    /** 只用于拉取 xAI 订阅供应商的模型列表（`models-v2`）；为 null 时该功能不可用。 */
    private val xaiModelCatalog: XaiModelCatalogSource? = null,
) : SettingsRepository {
    private val settingsMutationMutex = Mutex()
    private val deepSeekKeyMutex = Mutex()

    override val settings: Flow<ChatSettings> = settingsDataStore.settings
    override val displaySettings: Flow<DisplaySettings> = settingsDataStore.displaySettings

    override suspend fun currentSettings(): ChatSettings = settingsMutationMutex.withLock {
        settings.first()
    }

    override suspend fun <T> withCurrentProviderCredentials(
        transform: (settings: ChatSettings, apiKey: String?) -> T,
    ): T =
        settingsMutationMutex.withLock {
            val current = settings.first().normalized()
            val apiKey = when (current.provider) {
                ChatProviderId.DEEPSEEK -> deepSeekKeyMutex.withLock {
                    withContext(Dispatchers.IO) { apiKeyStore.getApiKey() }
                }

                ChatProviderId.CUSTOM_OPENAI -> current.activeCustomProviderId?.let { providerId ->
                    withContext(Dispatchers.IO) { providerKeyStore.getApiKey(providerId) }
                }
            }
            transform(current, apiKey?.takeIf { it.isNotBlank() })
        }

    override suspend fun updateSettings(transform: (ChatSettings) -> ChatSettings) {
        settingsMutationMutex.withLock {
            settingsDataStore.updateSettings(transform)
        }
    }

    override suspend fun updateDisplaySettings(settings: DisplaySettings) {
        settingsDataStore.updateDisplaySettings(settings)
    }

    override suspend fun saveApiKey(apiKey: String) {
        settingsMutationMutex.withLock {
            deepSeekKeyMutex.withLock {
                withContext(Dispatchers.IO) {
                    apiKeyStore.saveApiKey(apiKey.trim())
                }
            }
        }
    }

    override suspend fun getApiKey(): String? = settingsMutationMutex.withLock {
        deepSeekKeyMutex.withLock {
            withContext(Dispatchers.IO) {
                apiKeyStore.getApiKey()?.takeIf { it.isNotBlank() }
            }
        }
    }

    override suspend fun clearApiKey() {
        settingsMutationMutex.withLock {
            deepSeekKeyMutex.withLock {
                withContext(Dispatchers.IO) {
                    apiKeyStore.clearApiKey()
                }
            }
        }
    }

    override suspend fun saveProviderKey(providerId: String, apiKey: String) {
        settingsMutationMutex.withLock {
            require(settings.first().customProviders.any { it.id == providerId }) {
                "供应商不存在，请重新添加"
            }
            withContext(Dispatchers.IO) {
                providerKeyStore.saveApiKey(providerId, apiKey.trim())
            }
        }
    }

    override suspend fun getProviderKey(providerId: String): String? =
        settingsMutationMutex.withLock {
            withContext(Dispatchers.IO) {
                providerKeyStore.getApiKey(providerId)?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun clearProviderKey(providerId: String) {
        settingsMutationMutex.withLock {
            withContext(Dispatchers.IO) {
                providerKeyStore.clearApiKey(providerId)
            }
        }
    }

    override fun hasProviderKey(providerId: String): Boolean = providerKeyStore.hasApiKey(providerId)

    override suspend fun upsertCustomProvider(provider: CustomProvider) {
        settingsMutationMutex.withLock {
            settingsDataStore.updateSettings { current ->
                val existing = current.customProviders.any { it.id == provider.id }
                val updated = if (existing) {
                    current.customProviders.map { if (it.id == provider.id) provider else it }
                } else {
                    current.customProviders + provider
                }
                current.copy(
                    customProviders = updated,
                    // 新建的供应商立即成为当前选中项并接管路由：用户刚添加它就是为了配置和使用，
                    // 否则设置页不会展开它的编辑区，还要再点一次才能填 Key。
                    activeCustomProviderId = if (existing) current.activeCustomProviderId else provider.id,
                    provider = if (existing) current.provider else ChatProviderId.CUSTOM_OPENAI,
                )
            }
        }
    }

    override suspend fun updateCustomProvider(
        providerId: String,
        transform: (CustomProvider) -> CustomProvider,
    ) {
        settingsMutationMutex.withLock {
            settingsDataStore.updateSettings { current ->
                require(current.customProviders.any { it.id == providerId }) {
                    "供应商不存在，请重新添加"
                }
                current.copy(
                    customProviders = current.customProviders.map { provider ->
                        if (provider.id != providerId) {
                            provider
                        } else {
                            transform(provider).copy(id = providerId).normalized()
                        }
                    },
                )
            }
        }
    }

    override suspend fun deleteCustomProvider(providerId: String) {
        settingsMutationMutex.withLock {
            // Key 清理与 Provider 删除共用同一仓库锁，避免并发 save 在两步之间重建孤儿 alias。
            // 先可靠清除密文和 alias；失败时保留供应商记录，允许用户重试且 UI 不会误报。
            withContext(Dispatchers.IO) {
                providerKeyStore.clearApiKey(providerId)
            }
            settingsDataStore.updateSettings { current ->
                val remaining = current.customProviders.filterNot { it.id == providerId }
                current.copy(
                    customProviders = remaining,
                    activeCustomProviderId = current.activeCustomProviderId?.takeIf { it != providerId },
                    // 删光了就必须退回 DeepSeek，否则会停在一个无供应商可用的状态。
                    provider = if (remaining.isEmpty()) ChatProviderId.DEEPSEEK else current.provider,
                )
            }
        }
    }

    override suspend fun selectCustomProviderModel(providerId: String, model: String) {
        settingsMutationMutex.withLock {
            settingsDataStore.updateSettings { current ->
                require(current.customProviders.any { it.id == providerId }) {
                    "供应商不存在，请重新添加"
                }
                current.copy(
                    provider = ChatProviderId.CUSTOM_OPENAI,
                    activeCustomProviderId = providerId,
                    customProviders = current.customProviders.map { provider ->
                        if (provider.id == providerId) provider.copy(selectedModel = model) else provider
                    },
                )
            }
        }
    }

    override suspend fun refreshProviderModels(providerId: String): List<String> {
        if (isXaiProvider(providerId)) return refreshXaiProviderModels(providerId)
        val request = freezeProviderModelsRequest(providerId)

        val models = openAiCompatibleClient.listModels(
            apiKey = request.apiKey,
            baseUrl = request.baseUrl,
        )
        updateProviderModels(
            providerId = providerId,
            expectedBaseUrl = request.baseUrl,
            expectedApiKey = request.apiKey,
            models = models,
            updatedAtMillis = System.currentTimeMillis(),
        )
        return models
    }

    private suspend fun isXaiProvider(providerId: String): Boolean = settingsMutationMutex.withLock {
        val provider = settings.first().customProviders.firstOrNull { it.id == providerId }
            ?: error("供应商不存在，请重新添加")
        provider.usesXaiOAuth
    }

    /**
     * xAI 订阅供应商没有 API Key、地址钉死，普通供应商那套「地址与 Key 未变」的校验对它不适用。
     * 取而代之的守卫是：写回时该供应商仍然存在、仍是 xAI 订阅。token 由目录来源在设置锁外获取。
     */
    private suspend fun refreshXaiProviderModels(providerId: String): List<String> {
        val catalog = xaiModelCatalog ?: error("当前版本无法获取 xAI 模型列表，请手动填写模型名")
        return catalog.withModels { models ->
            settingsMutationMutex.withLock {
                val latest = settings.first().customProviders.firstOrNull { it.id == providerId }
                    ?: error("供应商已被删除，已忽略本次模型列表")
                require(latest.usesXaiOAuth) { "供应商类型已变化，请重新获取模型列表" }
                writeProviderModels(providerId, models, System.currentTimeMillis())
            }
            models
        }
    }

    override suspend fun refreshDeepSeekModels(): List<String> {
        val request = freezeDeepSeekModelsRequest()
        val models = deepSeekApiClient.listModels(
            apiKey = request.apiKey,
            baseUrl = request.baseUrl,
        )
        updateDeepSeekModels(
            expectedBaseUrl = request.baseUrl,
            expectedApiKey = request.apiKey,
            models = models,
            updatedAtMillis = System.currentTimeMillis(),
        )
        return models
    }

    /** Base URL 与 Key 必须来自同一次锁内快照，避免把刚保存的新 Key 发往旧端点。 */
    private suspend fun freezeProviderModelsRequest(providerId: String): ProviderModelsRequestSnapshot =
        settingsMutationMutex.withLock {
            val provider = settings.first().customProviders.firstOrNull { it.id == providerId }
                ?: error("供应商不存在，请重新添加")
            // xAI 订阅走 refreshXaiProviderModels；这里是 API Key 通道，绝不能拿 Key 去请求 models-v2。
            check(!provider.usesXaiOAuth) { "xAI 模型列表不走 API Key 通道" }
            // Vertex 的 openapi 端点没有 /models（实测 404），也没有 API Key：只能手填模型名。
            check(!provider.usesGoogleOAuth) { "Google Vertex 不支持获取模型列表，请手动填写模型名" }
            require(provider.baseUrl.isNotBlank()) { "请先填写该供应商的 Base URL" }
            val apiKey = withContext(Dispatchers.IO) {
                providerKeyStore.getApiKey(providerId)?.takeIf { it.isNotBlank() }
            } ?: error("请先保存该供应商的 API Key")
            ProviderModelsRequestSnapshot(
                baseUrl = provider.baseUrl,
                apiKey = apiKey,
            )
        }

    private suspend fun updateProviderModels(
        providerId: String,
        expectedBaseUrl: String,
        expectedApiKey: String,
        models: List<String>,
        updatedAtMillis: Long,
    ) {
        settingsMutationMutex.withLock {
            val latestProvider = settings.first().customProviders.firstOrNull { it.id == providerId }
                ?: error("供应商已被删除，已忽略本次模型列表")
            val latestApiKey = withContext(Dispatchers.IO) {
                providerKeyStore.getApiKey(providerId)
            }
            require(latestProvider.baseUrl == expectedBaseUrl && latestApiKey == expectedApiKey) {
                "供应商地址或 API Key 已变化，请重新获取模型列表"
            }
            writeProviderModels(providerId, models, updatedAtMillis)
        }
    }

    /** 调用方必须持有 [settingsMutationMutex] 并已完成各自的一致性校验。 */
    private suspend fun writeProviderModels(providerId: String, models: List<String>, updatedAtMillis: Long) {
        settingsDataStore.updateSettings { current ->
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
            )
        }
    }

    private suspend fun freezeDeepSeekModelsRequest(): ProviderModelsRequestSnapshot =
        settingsMutationMutex.withLock {
            val current = settings.first().normalized()
            require(current.baseUrl.isNotBlank()) { "请先填写 DeepSeek 的 Base URL" }
            val apiKey = deepSeekKeyMutex.withLock {
                withContext(Dispatchers.IO) {
                    apiKeyStore.getApiKey()?.takeIf { it.isNotBlank() }
                }
            } ?: error("请先保存 DeepSeek API Key")
            ProviderModelsRequestSnapshot(
                baseUrl = current.baseUrl,
                apiKey = apiKey,
            )
        }

    private suspend fun updateDeepSeekModels(
        expectedBaseUrl: String,
        expectedApiKey: String,
        models: List<String>,
        updatedAtMillis: Long,
    ) {
        settingsMutationMutex.withLock {
            val latest = settings.first().normalized()
            val latestApiKey = deepSeekKeyMutex.withLock {
                withContext(Dispatchers.IO) {
                    apiKeyStore.getApiKey()
                }
            }
            require(latest.baseUrl == expectedBaseUrl && latestApiKey == expectedApiKey) {
                "DeepSeek 地址或 API Key 已变化，请重新获取模型列表"
            }
            settingsDataStore.updateSettings { current ->
                current.copy(
                    deepSeekCachedModelIds = models,
                    deepSeekModelsUpdatedAtMillis = updatedAtMillis,
                    // 当前选择即使暂时不在新列表里也必须保留，不能自动切换。
                )
            }
        }
    }

    override suspend fun migrateLegacyProviderIfNeeded() {
        withContext(Dispatchers.IO) {
            settingsMutationMutex.withLock {
                val migration = settingsDataStore.prepareLegacyCliProxyProviderMigration()
                    ?: return@withLock
                // pending 会在每次启动重试。先持久化并读回验证新 Key，再清旧槽位，最后才完成状态。
                val targetProviderId = migration.targetProviderId
                val targetExists = targetProviderId != null &&
                    settings.first().customProviders.any { it.id == targetProviderId }
                val targetKey = if (targetProviderId != null && targetExists) {
                    providerKeyStore.getApiKey(targetProviderId)
                } else {
                    null
                }
                if (targetExists && targetKey.isNullOrBlank()) {
                    // cleanup-only、目标已删除或目标已有用户的新 Key 时完全不需要解密旧密文。
                    // 即使旧 IV/Base64/alias 已损坏，也应能可靠清除并完成迁移。
                    val legacyKey = legacyCliProxyApiKeyStore.getApiKey()
                    if (!legacyKey.isNullOrBlank()) {
                        val targetId = checkNotNull(targetProviderId)
                        providerKeyStore.saveApiKey(targetId, legacyKey)
                        check(providerKeyStore.getApiKey(targetId) == legacyKey) {
                            "旧供应商 API Key 迁移校验失败，请重试"
                        }
                    }
                }
                if (targetProviderId != null && !targetExists) {
                    // pending 目标已被删除时，该 id 下的 Key 也只能是孤儿；先可靠清除再完成。
                    providerKeyStore.clearApiKey(targetProviderId)
                }
                // 目标已有不同 Key 时视为用户手动保存的新值，绝不能被旧凭据覆盖。
                // 即使上次已删密文、但在删 Keystore alias 时中断，也必须重复执行完整清理。
                legacyCliProxyApiKeyStore.clearApiKey()
                check(legacyCliProxyApiKeyStore.getApiKey() == null) {
                    "旧供应商 API Key 清理失败，请重试"
                }
                settingsDataStore.completeLegacyCliProxyProviderMigration(migration.stateId)
            }
        }
    }

    // 普通类不会像 data class 一样把 Base URL 与 API Key 自动拼进 toString()。
    private class ProviderModelsRequestSnapshot(
        val baseUrl: String,
        val apiKey: String,
    )
}
