package com.muxuechat.data.repository

import com.muxuechat.data.remote.OpenAiCompatibleChatChunkDto
import com.muxuechat.data.remote.OpenAiCompatibleChatRequest
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.OpenAiCompatibleStreamEvent
import com.muxuechat.data.remote.XaiResponsesTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * OpenAI 兼容协议的传输入口。
 *
 * 上层只构造一种请求 DTO、只消费一种流式事件；**走哪个实现由凭据类型决定，调用方不感知**
 * （AGENT.md §3.3）。这样 `ChatRepositoryImpl` 不会因为接入 xAI 而多出任何协议分支：
 * 正文聊天、RP 补写、状态兜底、上下文摘要、设定生成五条路径的代码完全不变。
 *
 * 判定依据只能是凭据类型，不得按供应商名称或地址字符串匹配。
 */
internal class ChatTransportRouter(
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    private val xaiResponsesTransport: XaiResponsesTransport,
    private val xaiAccessTokens: XaiAccessTokenSource,
) {
    fun streamChat(
        credential: ProviderCredential,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
        conversationHeaderName: String = "",
        conversationId: String? = null,
    ): Flow<OpenAiCompatibleStreamEvent> = when (credential) {
        is ProviderCredential.ApiKey ->
            openAiCompatibleClient.streamChat(credential.value, baseUrl, requestDto, conversationHeaderName, conversationId)

        is ProviderCredential.OAuthBearer -> flow {
            var lease = credential.lease()
            xaiAccessTokens.validateSession(lease)
            var emitted = false
            try {
                xaiResponsesTransport.streamChat(lease.accessToken, baseUrl, requestDto)
                    .collect {
                        xaiAccessTokens.validateSession(lease)
                        emitted = true
                        emit(it)
                    }
            } catch (error: XaiResponsesTransport.XaiUnauthorizedException) {
                // 已经吐过内容就不能重试：重试会让同一轮出现两段正文。
                if (emitted) throw error
                lease = xaiAccessTokens.refreshAccessToken(lease)
                xaiResponsesTransport.streamChat(lease.accessToken, baseUrl, requestDto)
                    .collect {
                        xaiAccessTokens.validateSession(lease)
                        emit(it)
                    }
            }
        }
    }

    suspend fun completeChat(
        credential: ProviderCredential,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
        conversationHeaderName: String = "",
        conversationId: String? = null,
    ): OpenAiCompatibleChatChunkDto = when (credential) {
        is ProviderCredential.ApiKey ->
            openAiCompatibleClient.completeChat(credential.value, baseUrl, requestDto, conversationHeaderName, conversationId)

        // Responses 的非流式响应未采集，适配器内部用流式聚合出同样的结果。
        is ProviderCredential.OAuthBearer -> {
            var lease = credential.lease()
            xaiAccessTokens.validateSession(lease)
            val result = try {
                xaiResponsesTransport.completeChat(lease.accessToken, baseUrl, requestDto)
            } catch (error: XaiResponsesTransport.XaiUnauthorizedException) {
                lease = xaiAccessTokens.refreshAccessToken(lease)
                xaiResponsesTransport.completeChat(lease.accessToken, baseUrl, requestDto)
            }
            xaiAccessTokens.validateSession(lease)
            result
        }
    }

    /** 停止生成时两个实现都要取消：用户可能刚切过供应商，在途请求未必只在一边。 */
    fun cancelActiveRequest() {
        openAiCompatibleClient.cancelActiveRequest()
        xaiResponsesTransport.cancelActiveRequest()
    }
}
