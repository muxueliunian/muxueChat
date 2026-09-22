package com.muxuechat.data.repository

import com.muxuechat.data.remote.OpenAiCompatibleChatChunkDto
import com.muxuechat.data.remote.OpenAiCompatibleChatRequest
import com.muxuechat.data.remote.OpenAiCompatibleClient
import com.muxuechat.data.remote.OpenAiCompatibleStreamEvent
import com.muxuechat.data.remote.XaiResponsesTransport
import kotlinx.coroutines.flow.Flow

/**
 * OpenAI 兼容协议的传输入口。
 *
 * 上层只构造一种请求 DTO、只消费一种流式事件；**走哪个实现由凭据类型决定，调用方不感知**
 * （AGENT.md §6）。这样 `ChatRepositoryImpl` 不会因为接入 xAI 而多出任何协议分支：
 * 正文聊天、RP 补写、状态兜底、上下文摘要、设定生成五条路径的代码完全不变。
 *
 * 判定依据只能是凭据类型，不得按供应商名称或地址字符串匹配。
 */
internal class ChatTransportRouter(
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    private val xaiResponsesTransport: XaiResponsesTransport,
) {
    fun streamChat(
        credential: ProviderCredential,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
    ): Flow<OpenAiCompatibleStreamEvent> = when (credential) {
        is ProviderCredential.ApiKey ->
            openAiCompatibleClient.streamChat(credential.value, baseUrl, requestDto)

        is ProviderCredential.OAuthBearer ->
            xaiResponsesTransport.streamChat(credential.accessToken, baseUrl, requestDto)
    }

    suspend fun completeChat(
        credential: ProviderCredential,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
    ): OpenAiCompatibleChatChunkDto = when (credential) {
        is ProviderCredential.ApiKey ->
            openAiCompatibleClient.completeChat(credential.value, baseUrl, requestDto)

        // Responses 的非流式响应未采集，适配器内部用流式聚合出同样的结果。
        is ProviderCredential.OAuthBearer ->
            xaiResponsesTransport.completeChat(credential.accessToken, baseUrl, requestDto)
    }

    /** 停止生成时两个实现都要取消：用户可能刚切过供应商，在途请求未必只在一边。 */
    fun cancelActiveRequest() {
        openAiCompatibleClient.cancelActiveRequest()
        xaiResponsesTransport.cancelActiveRequest()
    }
}
