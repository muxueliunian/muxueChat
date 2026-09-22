package com.muxuechat.data.remote

import com.muxuechat.domain.model.CustomProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * xAI 订阅链路的传输实现：把 OpenAI 兼容请求翻译成 Responses 协议，再把 SSE 翻译回来。
 *
 * 这是 AGENT.md §2 / §3.3 批准的唯一一个非标准传输实现。**翻译完全封在本类内部**：
 * 上层构造的仍是 [OpenAiCompatibleChatRequest]，收到的仍是 [OpenAiCompatibleStreamEvent]，
 * 调用方不知道自己走的是哪个实现。
 *
 * 一切形状取自 `docs/XAI_WIRE_FORMAT.md` 的实测记录。两处实测缺口按保守方式处理：
 *
 * - **工具调用**：结果回传的 input item 形状未采集（§2.1），因此本实现**拒绝**携带
 *   tools 的请求，而不是照 OpenAI Responses 的公开文档猜 `function_call_output`。
 * - **非流式**：未采集（§3、§4 只覆盖 `stream: true`），因此 [completeChat] 内部跑流式再聚合，
 *   不猜一个没见过的响应体。
 */
class XaiResponsesTransport private constructor(
    private val okHttpClient: OkHttpClient,
    private val logger: RemoteClientLogger,
    private val json: Json,
    private val parser: XaiResponsesStreamParser,
    private val clientVersion: String,
    /**
     * 会话标识。实测 `x-grok-conv-id` 与 `x-grok-session-id` 在同一会话内相同，
     * 且每请求的 `x-grok-req-id` 不同。App 在这一层拿不到会话 id，
     * 因此用进程内稳定的一个值——这不影响语义，只影响上游的前缀缓存命中率。
     */
    private val sessionId: String,
) {
    constructor(
        okHttpClient: OkHttpClient = defaultOkHttpClient(),
        clientVersion: String = DEFAULT_CLIENT_VERSION,
    ) : this(
        okHttpClient = okHttpClient,
        logger = AndroidRemoteClientLogger(TAG),
        json = defaultJson(),
        parser = XaiResponsesStreamParser(defaultJson()),
        clientVersion = clientVersion,
        sessionId = UUID.randomUUID().toString(),
    )

    internal constructor(
        okHttpClient: OkHttpClient,
        logger: RemoteClientLogger,
        clientVersion: String = DEFAULT_CLIENT_VERSION,
        sessionId: String = UUID.randomUUID().toString(),
    ) : this(
        okHttpClient = okHttpClient,
        logger = logger,
        json = defaultJson(),
        parser = XaiResponsesStreamParser(defaultJson()),
        clientVersion = clientVersion,
        sessionId = sessionId,
    )

    /** 与 OpenAI 兼容客户端同理：RP 补写可能与下一条正文请求重叠，必须追踪全部在途 Call。 */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun streamChat(
        accessToken: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
    ): Flow<OpenAiCompatibleStreamEvent> {
        val request = buildRequest(accessToken, baseUrl, requestDto)

        return callbackFlow {
            logger.info("Starting xAI Responses stream request")
            val call = okHttpClient.newCall(request)
            activeCalls.add(call)
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (call.isCanceled()) {
                            close(CancellationException("xAI 请求已取消"))
                            return
                        }
                        logger.warn("xAI Responses stream request failed: ${e.javaClass.simpleName}")
                        close(networkError(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { readStream(it, call) { event -> trySendBlocking(event) } }
                            close()
                        } catch (error: XaiResponsesException) {
                            close(error)
                        } catch (error: IOException) {
                            if (call.isCanceled()) close(CancellationException("xAI 请求已取消")) else {
                                logger.warn("xAI Responses stream read failed: ${error.javaClass.simpleName}")
                                close(networkError(error))
                            }
                        } catch (error: Exception) {
                            if (call.isCanceled()) close(CancellationException("xAI 请求已取消")) else {
                                logger.warn("xAI Responses stream failed: ${error.javaClass.simpleName}")
                                close(XaiResponsesException("xAI 返回内容无法解析"))
                            }
                        }
                    }
                })
            } catch (_: RuntimeException) {
                activeCalls.remove(call)
                call.cancel()
                close(XaiResponsesException("xAI 请求无法启动"))
            }

            awaitClose {
                activeCalls.remove(call)
                call.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 非流式语义，流式实现。
     *
     * 上层的 RP 元数据补写、状态兜底、上下文摘要与设定生成都走这条路，它们只要最终整段文本。
     * 内部把 SSE 聚合成一个 chunk，因此不需要任何未采集的非流式响应体知识。
     */
    suspend fun completeChat(
        accessToken: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
    ): OpenAiCompatibleChatChunkDto {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var usage: OpenAiCompatibleUsageDto? = null
        var finishReason: String? = null

        // 流式标志由本实现决定，上层传进来的 stream=false 在 Responses 上没有对应物。
        streamChat(accessToken, baseUrl, requestDto).collect { event ->
            if (event !is OpenAiCompatibleStreamEvent.Delta) return@collect
            event.content?.let(content::append)
            event.reasoningContent?.let(reasoning::append)
            event.usage?.let { usage = it }
            event.finishReason?.let { finishReason = it }
        }

        return OpenAiCompatibleChatChunkDto(
            choices = listOf(
                OpenAiCompatibleChoiceDto(
                    index = 0,
                    message = OpenAiCompatibleMessageResponseDto(
                        role = ROLE_ASSISTANT,
                        content = content.toString(),
                        reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() },
                    ),
                    finishReason = finishReason,
                ),
            ),
            usage = usage,
        )
    }

    fun cancelActiveRequest() {
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    private fun readStream(
        response: Response,
        call: Call,
        emit: (OpenAiCompatibleStreamEvent) -> ChannelResult<Unit>,
    ) {
        if (!response.isSuccessful) {
            // 只记状态码。**不要记录上游响应体**：它可能回显本次请求的正文或系统提示，
            // 而 AGENT.md 禁止把用户消息与系统提示写进日志。靠几条 token 正则做脱敏
            // 是黑名单思路，覆盖不到裸 JWT、JSON 里的 token 字段与回显的 prompt。
            // 需要诊断上游拒绝原因时，临时打一个带正文日志的调试包，不要常驻。
            logger.warn("xAI Responses stream HTTP ${response.code}")
            throw httpError(response.code)
        }
        val source = response.body?.source() ?: throw XaiResponsesException("xAI 返回了空响应")

        // 解析器持有流级状态（并发正文项检测），每条流必须用独立会话。
        val session = parser.newSession()
        var sawCompleted = false
        var eventCount = 0L
        while (true) {
            val line = readSseLineWithinLimit(source) ?: break
            val parsed = session.parseLine(line) ?: continue
            eventCount++
            if (emit(parsed.delta).isFailure) {
                // 通道只在下游取消或关闭后失败；缓冲区满时会阻塞并把背压传回读取线程。
                call.cancel()
                return
            }
            if (parsed.completed) {
                sawCompleted = true
                break
            }
        }

        if (!sawCompleted) {
            // `response.completed` 是唯一实测过的正常终止信号。没有它就说明流被截断、
            // 出错或被上游中断——这几种事件形状都未采集（线上格式 §3.4），
            // 因此按失败处理并给固定文案，绝不假装这是一次正常结束。
            logger.warn("xAI Responses stream ended without completion: events=$eventCount")
            throw XaiResponsesException("xAI 回复未正常结束，请重试")
        }
        logger.info("xAI Responses stream request finished: events=$eventCount")
        emit(OpenAiCompatibleStreamEvent.Done)
    }

    private fun buildRequest(
        accessToken: String,
        baseUrl: String,
        requestDto: OpenAiCompatibleChatRequest,
    ): Request {
        // 必须抛带标记的异常：普通 IllegalArgumentException 会落到上层按关键字收敛的
        // 兜底映射上，而「订阅」二字会被误判成额度不足，把真正的原因盖掉。
        if (!requestDto.tools.isNullOrEmpty()) {
            throw XaiResponsesException("xAI 链路暂不支持工具调用，请在该供应商设置中关闭工具开关")
        }
        // 发送凭据的最后一道关口自己校验，不依赖调用方自律：
        // 冻结器已经钉死地址，但本类是唯一附加 Authorization 的地方（AGENT.md §2）。
        val normalized = baseUrl.trim().trimEnd('/')
        if (!normalized.equals(CustomProvider.XAI_SUBSCRIPTION_BASE_URL, ignoreCase = true)) {
            throw XaiResponsesException("xAI 服务地址不合法，已中止请求")
        }
        warnUnsupportedSamplingParams(requestDto)
        val payload = json.encodeToString(toResponsesRequest(requestDto))
        return Request.Builder()
            .url("$normalized/responses")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "text/event-stream")
            // 实测：发送方自报身份即可，不需要冒充 Grok CLI（线上格式 §1.1）。
            .header("X-XAI-Token-Auth", "xai-grok-cli")
            .header("x-grok-client-identifier", CLIENT_IDENTIFIER)
            .header("x-grok-client-version", toSemver(clientVersion))
            .header("x-grok-client-mode", "interactive")
            .header("x-grok-conv-id", sessionId)
            .header("x-grok-session-id", sessionId)
            .header("x-grok-req-id", UUID.randomUUID().toString())
            .header("x-grok-model-override", requestDto.model)
            .header("x-authenticateresponse", "authenticate-response")
            .build()
    }

    /**
     * temperature / top_p 在抓包中**从未出现**，因此不翻译。
     *
     * 但也不能静默丢弃：上层如果配置了它们却不生效，用户看到的行为与设置不符。
     * 这里显式记一条日志（只记字段名，不记取值），并在 `docs/XAI_WIRE_FORMAT.md`
     * 标注为未支持。要支持必须先补采，不能照 OpenAI Responses 的文档猜字段名。
     */
    private fun warnUnsupportedSamplingParams(requestDto: OpenAiCompatibleChatRequest) {
        val dropped = buildList {
            if (requestDto.temperature != null) add("temperature")
            if (requestDto.topP != null) add("top_p")
        }
        if (dropped.isNotEmpty()) {
            logger.warn("xAI Responses dropped unsupported sampling params: ${dropped.joinToString()}")
        }
    }

    private fun toResponsesRequest(requestDto: OpenAiCompatibleChatRequest) = XaiResponsesRequest(
        model = requestDto.model,
        input = requestDto.messages.map(::toInputItem),
        maxOutputTokens = requestDto.maxTokens,
        reasoning = requestDto.reasoningEffort
            ?.takeIf { it.isNotBlank() }
            ?.let { XaiReasoningRequestDto(effort = it) },
    )

    /** 三种实测形状见线上格式 §2.1；系统提示的角色是 `developer` 且内容为纯字符串。 */
    private fun toInputItem(message: OpenAiCompatibleMessageDto): XaiInputItem {
        val text = message.content.orEmpty()
        return when (message.role) {
            ROLE_SYSTEM -> XaiInputItem(role = ROLE_DEVELOPER, content = JsonPrimitive(text))

            ROLE_ASSISTANT -> XaiInputItem(
                type = "message",
                role = ROLE_ASSISTANT,
                content = jsonArrayOf(json.encodeToJsonElement(XaiOutputTextPart(text = text))),
            )

            // user 以及任何其它角色都按用户输入发送：Responses 的 input 只认 developer /
            // user / assistant，工具角色已在 buildRequest 处被拒。
            else -> XaiInputItem(
                role = ROLE_USER,
                content = jsonArrayOf(json.encodeToJsonElement(XaiInputTextPart(text = text))),
            )
        }
    }

    private fun jsonArrayOf(vararg elements: JsonElement) = JsonArray(elements.toList())

    private fun httpError(code: Int): XaiResponsesException = when {
        // 401 单独成型：调用方要能据此判断「这个 token 被拒了」，从而刷新后重试一次。
        code == 401 -> XaiUnauthorizedException()
        code == 403 -> XaiResponsesException("xAI 拒绝了本次请求，请重新登录后重试")
        code == 429 -> XaiResponsesException("xAI 请求过于频繁或已达订阅额度，请稍后重试")
        code >= 500 -> XaiResponsesException("xAI 服务暂时不可用（HTTP $code），请稍后重试")
        else -> XaiResponsesException("xAI 请求失败（HTTP $code）")
    }

    private fun networkError(error: IOException): IOException =
        XaiResponsesException("无法连接 xAI，请检查网络后重试").also { it.initCause(error) }

    private fun readSseLineWithinLimit(source: BufferedSource): String? {
        val newlineIndex = source.indexOf('\n'.code.toByte(), 0L, MAX_SSE_LINE_BYTES + 1L)
        if (newlineIndex >= 0L) return source.readUtf8Line()
        if (source.buffer.size > MAX_SSE_LINE_BYTES) {
            throw XaiResponsesException("xAI 流式响应单行过大，已停止解析")
        }
        return if (source.buffer.size == 0L) null else source.readUtf8()
    }

    /**
     * 文案完全由 App 生成，不含 token、地址或上游响应原文，可直接展示。
     * 复用 [OpenAiCompatibleUserFacingError] 标记，走与自定义供应商相同的错误映射。
     */
    internal open class XaiResponsesException(message: String) :
        IOException(message),
        OpenAiCompatibleUserFacingError

    /**
     * 上游以 401 拒绝了本次使用的 access token。
     *
     * 与其它错误分开，是为了让 [com.muxuechat.data.repository.ChatTransportRouter]
     * 能带着被拒的 token 强制刷新一次并重试——token 可能在冻结之后、请求发出之前失效，
     * 尤其是正文结束后才发出的 RP 补写。
     */
    internal class XaiUnauthorizedException :
        XaiResponsesException("xAI 拒绝了本次请求，请重新登录后重试")

    internal companion object {
        private const val TAG = "muxueChat.XaiResponses"
        const val CLIENT_IDENTIFIER = "muxuechat"
        const val DEFAULT_CLIENT_VERSION = "1.0.0"
        const val MAX_SSE_LINE_BYTES = 1_048_576L

        private const val ROLE_SYSTEM = "system"
        private const val ROLE_DEVELOPER = "developer"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_USER = "user"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 把版本号补足成三段 semver。
         *
         * 实测（2026-09-22）：发 `1.0` 会被上游以 HTTP 426 拒绝，回复
         * 「Your Grok CLI version (1.0) is outdated. Please update to version 0.1.202 or later」。
         * 注意 `1.0` 在语义上本就大于 `0.1.202`，被判过期只能是因为两段版本号解析失败。
         *
         * 因此这里只做格式补齐，**不谎报版本、也不去抓上游的版本号**：
         * App 报的始终是自己的 `versionName`，只是写成合法形状。
         * 若将来上游把门槛提到 1.0.0 以上，这里会再次 426，届时应由用户决定怎么办。
         */
        internal fun toSemver(version: String): String {
            // 只取前三段数字，多余的段（4 段版本、prerelease 后缀）一律丢掉，
            // 缺的段补 0。空串或完全不含数字时回退到默认版本。
            val numeric = version.trim()
                .substringBefore('-')
                .substringBefore('+')
                .split('.')
                .map { it.filter(Char::isDigit) }
                .filter { it.isNotEmpty() }
            if (numeric.isEmpty()) return DEFAULT_CLIENT_VERSION
            val padded = (numeric + listOf("0", "0")).take(3)
            return padded.joinToString(".")
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // 与 XaiAuthClient 一致：Authorization 头绝不跟随跳转离开钉死的主机。
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}
