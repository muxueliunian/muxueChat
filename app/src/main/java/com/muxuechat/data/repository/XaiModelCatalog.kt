package com.muxuechat.data.repository

import com.muxuechat.data.remote.XaiResponsesTransport
import com.muxuechat.domain.model.CustomProvider

/** xAI 订阅的模型目录来源。单独成接口，方便设置仓库的测试替换它。 */
interface XaiModelCatalogSource {
    suspend fun listModels(): List<String>
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
    override suspend fun listModels(): List<String> {
        val baseUrl = CustomProvider.XAI_SUBSCRIPTION_BASE_URL
        val accessToken = accessTokens.getValidAccessToken(rejectedAccessToken = null)
        return try {
            transport.listModels(accessToken, baseUrl)
        } catch (_: XaiResponsesTransport.XaiUnauthorizedException) {
            transport.listModels(accessTokens.getValidAccessToken(rejectedAccessToken = accessToken), baseUrl)
        }
    }
}
