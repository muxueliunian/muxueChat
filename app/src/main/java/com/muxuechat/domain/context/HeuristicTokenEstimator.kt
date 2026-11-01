package com.muxuechat.domain.context

import com.muxuechat.domain.prompt.PromptMessage

/**
 * 发送前的 token 估算器。
 *
 * **为什么不引入真正的 tokenizer**：DeepSeek 与各家 OpenAI 兼容上游用的分词器互不相同，
 * 想准就得为每个上游各带一份词表，体积和维护成本都不划算，而 App 需要的只是
 * 「这一轮大概占多少、要不要压缩」。所以这里用一个无依赖的启发式规则，
 * 并统一往上多算 [SAFETY_MARGIN_PERCENT]，宁可高估也不要因为低估而被服务端拒绝。
 *
 * 估算结果在 UI 上一律标注「预计」，绝不能称为 API 精确值——真实值只来自响应里的 usage。
 */
object HeuristicTokenEstimator {

    /**
     * 每条消息的固定协议开销：role 字段、角色分隔符、消息边界。
     * 短消息很多时这部分并不可忽略。
     */
    const val PER_MESSAGE_OVERHEAD_TOKENS = 4L

    /** 请求级固定开销：对话结尾的 assistant 起始标记等。 */
    const val REQUEST_OVERHEAD_TOKENS = 3L

    /** 每个工具定义的固定开销：函数包装结构本身。 */
    const val PER_TOOL_OVERHEAD_TOKENS = 8L

    /** 最终结果的安全余量，110 表示上浮 10%。 */
    const val SAFETY_MARGIN_PERCENT = 110L

    /**
     * 连续 ASCII 文本按每 4 字符 1 token 折算。
     * 这是英文 BPE 的经验值，中文不适用，因此非 ASCII 单独处理。
     */
    private const val ASCII_CHARS_PER_TOKEN = 4

    /**
     * 估算一段纯文本，不含任何消息或请求开销。
     *
     * 规则：
     * - ASCII 字母/数字/普通空白：按连续段计 `ceil(段长 / 4)`。
     * - ASCII 标点与符号：每字符 1 token。BPE 很少把标点和邻词合并。
     * - 非 ASCII code point：每个 1 token。中文、日文、emoji 都落在这里，
     *   按 code point 而非 char 遍历，避免 emoji 的代理对被当成 2 个 token。
     */
    fun estimateText(text: String): Long {
        if (text.isEmpty()) return 0L

        var tokens = 0L
        var asciiRun = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)

            if (codePoint < 0x80 && isAsciiWordish(codePoint)) {
                asciiRun++
                continue
            }

            tokens += asciiRunTokens(asciiRun)
            asciiRun = 0
            tokens += 1L
        }
        tokens += asciiRunTokens(asciiRun)
        return tokens
    }

    /** 单条消息：正文 + 固定协议开销。 */
    fun estimateMessage(role: String, content: String): Long =
        estimateText(role) + estimateText(content) + PER_MESSAGE_OVERHEAD_TOKENS

    fun estimateMessage(message: PromptMessage): Long =
        estimateMessage(role = message.role, content = message.content)

    /**
     * 带图片的消息：文字 + 每张图固定的 [tokensPerImage]。
     *
     * 每张图计多少由规划器按协议传入（[ImageTokenEstimate]），估算器本身不判断模型。
     */
    fun estimateMessage(message: PromptMessage, tokensPerImage: Long): Long =
        estimateMessage(message) + message.images.size * tokensPerImage

    fun estimateMessages(messages: List<PromptMessage>): Long =
        messages.sumOf { estimateMessage(it) }

    /**
     * 单个工具定义。名称、描述和参数 JSON 全部计入——工具 schema 是实打实发出去的字节，
     * 旧实现完全没算它，非 RP 模式下等于凭空少算几百 token。
     */
    fun estimateToolDefinition(name: String, description: String, parametersJson: String): Long =
        estimateText(name) +
            estimateText(description) +
            estimateText(parametersJson) +
            PER_TOOL_OVERHEAD_TOKENS

    /** 对已经累加好的原始估算值统一上浮安全余量。整个请求只应调用一次。 */
    fun withSafetyMargin(rawTokens: Long): Long =
        if (rawTokens <= 0L) 0L else rawTokens * SAFETY_MARGIN_PERCENT / 100

    private fun asciiRunTokens(runLength: Int): Long =
        if (runLength <= 0) 0L else ((runLength + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN).toLong()

    /** 字母、数字、下划线与普通空白算作可合并的连续段；其余 ASCII 字符单独成 token。 */
    private fun isAsciiWordish(codePoint: Int): Boolean {
        val char = codePoint.toChar()
        return char.isLetterOrDigit() || char == '_' || char == ' ' || char == '\t'
    }
}
