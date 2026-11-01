package com.muxuechat.domain.model

/**
 * 自定义供应商（OpenAI 兼容）下，已核实模型可用的 `reasoning_effort` 取值。
 *
 * 只认**完整模型 ID**（AGENT.md：不按名字猜能力）。中转常给模型 ID 加一段命名空间，
 * 如 `google/gemini-4-argon`、`x-ai/grok-4.7`；这里只去掉最前面一段 `命名空间/`
 * 再精确匹配，去掉后仍须是下表里的完整 ID。不在表里的模型由用户自填字符串。
 *
 * 取值按各家官方文档逐个核实（2026-11-01）：
 * - OpenAI developers.openai.com/api/docs/models/<id>；
 * - xAI docs.x.ai/developers/model-capabilities/text/reasoning；
 * - Gemini ai.google.dev/gemini-api/docs/thinking（3.8 Flash / 4 Argon 不支持 minimal）；
 * - Claude platform.claude.com/docs/en/build-with-claude/effort（经中转映射到 output_config.effort）；
 * - DeepSeek api-docs.deepseek.com/guides/thinking_mode。
 * 新模型发布后在这里补完整 ID，不要改成前缀或版本号匹配。
 */
object ReasoningEffortCatalog {
    private val OPENAI_WITH_NONE = listOf("none", "low", "medium", "high", "xhigh", "max")
    private val OPENAI_WITHOUT_NONE = listOf("low", "medium", "high", "xhigh", "max")
    private val GROK_4_5 = listOf("low", "medium", "high")
    private val GROK_4_6_PLUS = listOf("low", "medium", "high", "xhigh")
    private val GEMINI_3_8_PLUS = listOf("low", "medium", "high")
    private val CLAUDE_5 = listOf("low", "medium", "high", "xhigh", "max")
    private val DEEPSEEK_V4 = listOf("low", "high", "max")

    private val byModelId: Map<String, List<String>> = buildMap {
        listOf("gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-terra")
            .forEach { put(it, OPENAI_WITH_NONE) }
        listOf("gpt-6-astra", "gpt-6.1-sol").forEach { put(it, OPENAI_WITHOUT_NONE) }
        put("grok-4.5", GROK_4_5)
        listOf("grok-4.6", "grok-4.7").forEach { put(it, GROK_4_6_PLUS) }
        listOf("gemini-3.8-flash", "gemini-4-argon").forEach { put(it, GEMINI_3_8_PLUS) }
        listOf("claude-opus-5", "claude-opus-5-5").forEach { put(it, CLAUDE_5) }
        listOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro").forEach { put(it, DEEPSEEK_V4) }
    }

    /** 已核实的取值，按强度从低到高；不在表里返回 null（由用户自填）。 */
    fun optionsFor(modelId: String): List<String>? {
        val trimmed = modelId.trim()
        byModelId[trimmed]?.let { return it }
        val withoutNamespace = trimmed.substringAfter('/', missingDelimiterValue = "")
        return withoutNamespace.takeIf { it.isNotEmpty() && '/' !in it }?.let(byModelId::get)
    }
}
