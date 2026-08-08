package com.muxuechat.data.repository

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<ChatSettings>
    val displaySettings: Flow<DisplaySettings>

    suspend fun updateSettings(settings: ChatSettings)
    suspend fun updateDisplaySettings(settings: DisplaySettings)

    /** DeepSeek API Key。 */
    suspend fun saveApiKey(apiKey: String)
    suspend fun getApiKey(): String?
    suspend fun clearApiKey()

    /** 自定义供应商的 API Key，按供应商 id 各自独立存储。 */
    suspend fun saveProviderKey(providerId: String, apiKey: String)
    suspend fun getProviderKey(providerId: String): String?
    suspend fun clearProviderKey(providerId: String)

    /** 仅用于 UI 展示"是否已保存"，不返回明文。 */
    fun hasProviderKey(providerId: String): Boolean

    /** 新增或更新一个供应商；新增时自动设为当前选中。 */
    suspend fun upsertCustomProvider(provider: CustomProvider)

    /** 删除供应商，并同时清除其 Keystore 条目。 */
    suspend fun deleteCustomProvider(providerId: String)

    /** 切换当前使用的供应商与模型；同时把 provider 路由切到 OpenAI 兼容链路。 */
    suspend fun selectCustomProviderModel(providerId: String, model: String)

    /**
     * 拉取并缓存某个供应商的可用模型列表。
     *
     * 服务不支持 `/models` 时会抛出可读异常，由调用方提示用户改为手填；
     * 此时已缓存的列表保持不变。
     */
    suspend fun refreshProviderModels(providerId: String): List<String>

    /** 把一般化改造前写死的 CLIProxyAPI 配置迁移成普通供应商记录；只执行一次。 */
    suspend fun migrateLegacyProviderIfNeeded()
}
