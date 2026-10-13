package com.muxuechat.data.repository

import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.CustomProvider

/** xAI 订阅的模型目录来源。单独成接口，方便设置仓库的测试替换它。 */
interface XaiModelCatalogSource {
    suspend fun listModels(): List<String>

    /** 获取目录并在所属登录会话仍有效时执行写回；动作不得再调用认证方法。 */
    suspend fun <T> withModels(block: suspend (List<String>) -> T): T = block(listModels())
}

/**
 * 用当前登录拉取 `models-v2`。
 *
 * token 在设置锁外获取（刷新是网络请求）；401 时带着被拒的 token 强制刷新一次再重试，
 * 与 [ChatTransportRouter] 处理聊天请求的方式一致。地址只用钉死常量，不取自用户数据。
 */
class XaiModelCatalog(
    private val transport: XaiResponsesTransport,
    private val accessTokens: XaiAccessTokenSource,
) : XaiModelCatalogSource {
    override suspend fun listModels(): List<String> = withModels { it }

    override suspend fun <T> withModels(block: suspend (List<String>) -> T): T {
        val baseUrl = CustomProvider.XAI_SUBSCRIPTION_BASE_URL
        var lease = accessTokens.acquireAccessToken()
        accessTokens.validateSession(lease)
        val models = try {
            transport.listModels(lease.accessToken, baseUrl)
        } catch (_: XaiResponsesTransport.XaiUnauthorizedException) {
            lease = accessTokens.refreshAccessToken(lease)
            transport.listModels(lease.accessToken, baseUrl)
        }
        return accessTokens.withCurrentSession(lease) { block(models) }
    }
}
