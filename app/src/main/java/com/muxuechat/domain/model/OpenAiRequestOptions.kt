package com.muxuechat.domain.model

/** 由用户按端点能力选择，不能按供应商或模型名字猜测。 */
enum class OpenAiOutputTokenField(val storageValue: String) {
    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens");

    companion object {
        fun fromStorage(value: String?): OpenAiOutputTokenField =
            entries.firstOrNull { it.storageValue == value } ?: MAX_TOKENS
    }
}

/** 会话标识只允许独立扩展头，不能覆盖认证、主机、内容类型等协议头。 */
object ConversationHeaderPolicy {
    const val MAX_LENGTH = 64
    private val pattern = Regex("x-[a-z0-9]+(?:-[a-z0-9]+)*", RegexOption.IGNORE_CASE)

    fun isValid(value: String): Boolean =
        value.isEmpty() || (value.length <= MAX_LENGTH && pattern.matches(value))

    fun normalize(value: String): String = value.trim().lowercase().takeIf(::isValid).orEmpty()
}
