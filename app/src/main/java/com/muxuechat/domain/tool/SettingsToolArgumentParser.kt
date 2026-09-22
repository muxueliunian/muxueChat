package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfileDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** 解析失败的原因，用于给用户可读提示。 */
sealed interface SettingsToolParseResult {
    data class Success(val draft: SettingsToolDraft) : SettingsToolParseResult
    data class Failure(val reason: String) : SettingsToolParseResult
}

/**
 * 把模型返回的 tool call 参数解析成草稿。
 *
 * **模型输出一律视为不可信**：字段可能缺失、类型可能不对、数值可能越界、
 * 名称可能超长、状态条可能重名或数量爆炸。这里全部做本地归一化，
 * 并把修正过的地方记进 warnings 让用户知情。
 */
class SettingsToolArgumentParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    fun parse(
        tool: SettingsTool,
        argumentsJson: String,
        conversationId: String?,
    ): SettingsToolParseResult {
        val root = parseObject(argumentsJson)
            ?: return SettingsToolParseResult.Failure("模型返回的${toolLabel(tool)}参数不是合法 JSON。")

        val warnings = mutableListOf<String>()
        val draft = when (tool) {
            SettingsTool.CREATE_ROLE_CARD -> {
                val name = root.string("name").normalizeName(MAX_NAME_LENGTH, warnings, "角色名称")
                if (name.isBlank()) {
                    return SettingsToolParseResult.Failure("模型没有给出角色名称，无法创建角色卡。")
                }
                SettingsToolDraft(
                    id = idGenerator(),
                    tool = tool,
                    conversationId = conversationId,
                    roleCard = RoleCardDraft(
                        name = name,
                        description = root.string("description").limit(MAX_TEXT_LENGTH, warnings, "角色简介"),
                        persona = root.string("persona").limit(MAX_LONG_TEXT_LENGTH, warnings, "人设描述"),
                        speakingStyle = root.string("speaking_style").limit(MAX_TEXT_LENGTH, warnings, "说话风格"),
                        userIdentity = root.string("user_identity").limit(MAX_TEXT_LENGTH, warnings, "用户身份"),
                        openingMessage = root.string("opening_message").limit(MAX_TEXT_LENGTH, warnings, "开场白"),
                        boundaries = root.string("boundaries").limit(MAX_TEXT_LENGTH, warnings, "边界"),
                        metricDefinitions = parseMetricDefinitions(root["metric_definitions"], warnings),
                    ),
                    warnings = warnings,
                    createdAtMillis = clock(),
                )
            }

            SettingsTool.CREATE_WORLD_SETTING -> {
                val title = root.string("title").normalizeName(MAX_NAME_LENGTH, warnings, "世界观标题")
                if (title.isBlank()) {
                    return SettingsToolParseResult.Failure("模型没有给出世界观标题，无法创建世界观。")
                }
                SettingsToolDraft(
                    id = idGenerator(),
                    tool = tool,
                    conversationId = conversationId,
                    worldSetting = WorldSettingDraft(
                        title = title,
                        content = root.string("content").limit(MAX_LONG_TEXT_LENGTH, warnings, "世界观正文"),
                    ),
                    warnings = warnings,
                    createdAtMillis = clock(),
                )
            }

            SettingsTool.CREATE_WRITING_STYLE -> {
                val name = root.string("name").normalizeName(MAX_NAME_LENGTH, warnings, "文风名称")
                if (name.isBlank()) {
                    return SettingsToolParseResult.Failure("模型没有给出文风名称，无法创建文风。")
                }
                SettingsToolDraft(
                    id = idGenerator(),
                    tool = tool,
                    conversationId = conversationId,
                    writingStyle = WritingStyleProfileDraft(
                        name = name,
                        content = root.string("content").limit(MAX_LONG_TEXT_LENGTH, warnings, "文风正文"),
                    ),
                    warnings = warnings,
                    createdAtMillis = clock(),
                )
            }

            SettingsTool.CREATE_PROTAGONIST -> {
                val name = root.string("name").normalizeName(MAX_NAME_LENGTH, warnings, "主角名字")
                if (name.isBlank()) {
                    return SettingsToolParseResult.Failure("模型没有给出主角名字，无法创建主角设定。")
                }
                if (conversationId == null) {
                    // 主角没有独立实体，只能落到某个会话的 RP 设置上；没有会话就没有落点。
                    return SettingsToolParseResult.Failure("当前没有会话，主角设定无处保存。")
                }
                SettingsToolDraft(
                    id = idGenerator(),
                    tool = tool,
                    conversationId = conversationId,
                    protagonist = ProtagonistDraft(
                        name = name,
                        // 上限与 RpRepositoryImpl 写入会话设置时的截断一致，避免保存时二次静默截断。
                        description = root.string("description")
                            .limit(MAX_PROFILE_LENGTH, warnings, "主角描述"),
                        initialState = root.string("initial_state")
                            .limit(MAX_PROFILE_LENGTH, warnings, "主角初始状态"),
                    ),
                    warnings = warnings,
                    createdAtMillis = clock(),
                )
            }
        }

        return SettingsToolParseResult.Success(draft)
    }

    /** 兼容模型偶尔把参数包在 Markdown 代码围栏里的情况。 */
    private fun parseObject(raw: String): JsonObject? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.isBlank()) return null

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return runCatching {
            json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }

    private fun parseMetricDefinitions(
        element: kotlinx.serialization.json.JsonElement?,
        warnings: MutableList<String>,
    ): List<RpMetricDefinition> {
        val array = element as? JsonArray ?: return emptyList()
        if (array.size > MAX_METRIC_DEFINITIONS) {
            warnings += "模型给出了 ${array.size} 个状态条，已保留前 $MAX_METRIC_DEFINITIONS 个。"
        }

        val usedNames = mutableSetOf<String>()
        return array.take(MAX_METRIC_DEFINITIONS).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name").trim().take(MAX_NAME_LENGTH)
            if (name.isBlank()) return@mapNotNull null
            if (!usedNames.add(name)) {
                warnings += "状态条「$name」重复，已忽略重复项。"
                return@mapNotNull null
            }

            val min = obj.int("min_value") ?: DEFAULT_METRIC_MIN
            // 上限必须严格大于下限，否则 coerceIn 会抛异常。
            val max = (obj.int("max_value") ?: DEFAULT_METRIC_MAX).let { candidate ->
                if (candidate > min) {
                    candidate
                } else {
                    warnings += "状态条「$name」的最大值不大于最小值，已调整为 ${min + 1}。"
                    min + 1
                }
            }
            val rawInitial = obj.int("initial_value") ?: min
            val initial = rawInitial.coerceIn(min, max)
            if (initial != rawInitial) {
                warnings += "状态条「$name」的初始值 $rawInitial 超出范围，已调整为 $initial。"
            }

            RpMetricDefinition(
                id = idGenerator(),
                name = name,
                minValue = min,
                maxValue = max,
                initialValue = initial,
                description = obj.string("description").trim().take(MAX_TEXT_LENGTH),
                progressRule = obj.string("progress_rule").trim().take(MAX_TEXT_LENGTH),
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun String.normalizeName(
        limit: Int,
        warnings: MutableList<String>,
        label: String,
    ): String {
        // 名称里的换行会破坏后续 prompt 的分节结构。
        val singleLine = trim().replace(NEWLINE_REGEX, " ")
        return singleLine.limit(limit, warnings, label)
    }

    private fun String.limit(limit: Int, warnings: MutableList<String>, label: String): String {
        val trimmed = trim()
        if (trimmed.length <= limit) return trimmed
        warnings += "$label 超过 $limit 字，已截断。"
        return trimmed.take(limit)
    }

    private fun toolLabel(tool: SettingsTool): String = when (tool) {
        SettingsTool.CREATE_ROLE_CARD -> "角色卡"
        SettingsTool.CREATE_WORLD_SETTING -> "世界观"
        SettingsTool.CREATE_WRITING_STYLE -> "文风"
        SettingsTool.CREATE_PROTAGONIST -> "主角"
    }

    private companion object {
        val NEWLINE_REGEX = Regex("[\\r\\n]+")
        const val MAX_NAME_LENGTH = 80

        /** 与 `RpRepositoryImpl.MAX_PROFILE_LENGTH` 同值：主角三段文本存在会话设置里。 */
        const val MAX_PROFILE_LENGTH = 2_000
        const val MAX_TEXT_LENGTH = 4_000
        const val MAX_LONG_TEXT_LENGTH = 20_000
        const val MAX_METRIC_DEFINITIONS = 12
        const val DEFAULT_METRIC_MIN = 0
        const val DEFAULT_METRIC_MAX = 100
    }
}
