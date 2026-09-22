package com.muxuechat.data.repository

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.DisplaySettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<ChatSettings>
    val displaySettings: Flow<DisplaySettings>

    /** 等待已排队的设置写入完成后返回一致快照，发送请求前应使用它。 */
    suspend fun currentSettings(): ChatSettings

    /**
     * 在同一临界区内读取当前路由设置与对应 API Key，并立即交给 [transform] 冻结请求。
     *
     * 不返回含 Key 的通用 data class，避免它被意外 `toString()` 到日志；[transform] 也不得
     * 把 Key 写入日志、Room 或 DataStore。实现必须阻止设置或 Key 修改插入两次读取之间。
     */
    suspend fun <T> withCurrentProviderCredentials(
        transform: (settings: ChatSettings, apiKey: String?) -> T,
    ): T
    /** 基于持久化层最新值原子修改，避免并发字段更新互相覆盖。 */
    suspend fun updateSettings(transform: (ChatSettings) -> ChatSettings)
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

    /** 基于最新供应商记录原子修改单个供应商，模型刷新与字段编辑不会互相覆盖。 */
    suspend fun updateCustomProvider(
        providerId: String,
        transform: (CustomProvider) -> CustomProvider,
    )

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

    /**
     * 用 DeepSeek 自己的 Base URL 与 API Key 请求 `GET /models` 并缓存结果。
     *
     * 失败、空列表或解析错误时保留旧缓存，且不会改掉当前选中的模型。
     * 刷新途中若 Base URL 或 Key 已变化，旧请求结果不会写回新配置。
     */
    suspend fun refreshDeepSeekModels(): List<String>

    /** 把一般化改造前写死的 CLIProxyAPI 配置迁移成普通供应商记录；只执行一次。 */
    suspend fun migrateLegacyProviderIfNeeded()
}
