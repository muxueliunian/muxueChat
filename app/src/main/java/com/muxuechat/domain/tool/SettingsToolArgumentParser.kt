package com.muxuechat.domain.tool

import com.muxuechat.domain.model.ProtagonistDraft
import com.muxuechat.domain.model.RoleCardDraft
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.RpSettingKind
import com.muxuechat.domain.model.RpSettingRef
import com.muxuechat.domain.model.SupportingCharacterDraft
import com.muxuechat.domain.model.RpMetricDefinition
import com.muxuechat.domain.model.WorldSettingDraft
import com.muxuechat.domain.model.WritingStyleProfileDraft
import com.muxuechat.domain.rp.RpStateBlockFormat
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

    /** [SettingsTool.LIST_RP_SETTINGS]：要列出的类型，空集合表示全部。 */
    data class ListRequest(val kinds: Set<RpSettingKind>) : SettingsToolParseResult

    /**
     * [SettingsTool.DELETE_RP_SETTINGS]：解析出的删除目标（已去重）。
     * id 是否存在要查库，由调用方解析后再生成删除请求草稿。
     */
    data class DeleteRequest(val refs: List<RpSettingRef>, val warnings: List<String>) : SettingsToolParseResult

    /** [SettingsTool.GET_RP_SETTING]：要读取的一项。 */
    data class GetRequest(val ref: RpSettingRef) : SettingsToolParseResult

    /**
     * [SettingsTool.UPDATE_RP_SETTING]：目标与修改内容。字段是否适用于该类型、
     * 修改后是否真的有变化，要读出原设定后由 [RpSettingPatcher] 判断。
     */
    data class UpdateRequest(
        val ref: RpSettingRef,
        val patch: RpSettingPatch,
        val warnings: List<String>,
    ) : SettingsToolParseResult
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
        // 列表工具没有必填参数，模型常常只给空串。
        if (tool == SettingsTool.LIST_RP_SETTINGS && argumentsJson.isBlank()) {
            return SettingsToolParseResult.ListRequest(emptySet())
        }
        val root = parseObject(argumentsJson)
            ?: return SettingsToolParseResult.Failure("模型返回的${toolLabel(tool)}参数不是合法 JSON。")

        val warnings = mutableListOf<String>()
        val draft = when (tool) {
            SettingsTool.LIST_RP_SETTINGS -> return parseListRequest(root)
            SettingsTool.DELETE_RP_SETTINGS -> return parseDeleteRequest(root)
            SettingsTool.GET_RP_SETTING ->
                return parseTarget(root)?.let(SettingsToolParseResult::GetRequest) ?: targetFailure()
            SettingsTool.UPDATE_RP_SETTING -> return parseUpdateRequest(root)

            SettingsTool.CREATE_SUPPORTING_CHARACTER -> {
                val name = root.string("name").normalizeName(MAX_NAME_LENGTH, warnings, "配角名称")
                if (name.isBlank()) {
                    return SettingsToolParseResult.Failure("模型没有给出配角名称，无法创建配角。")
                }
                SettingsToolDraft(
                    id = idGenerator(),
                    tool = tool,
                    conversationId = conversationId,
                    supportingCharacter = SupportingCharacterDraft(
                        name = name,
                        description = root.string("description").limit(MAX_TEXT_LENGTH, warnings, "配角简介"),
                        entries = parseTextEntries(root["entries"], warnings),
                    ),
                    warnings = warnings,
                    createdAtMillis = clock(),
                )
            }

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
                    // 工具协议规定主角是「为这个故事建的」：保存为档案并绑定到会话，没有会话就没有落点。
                    return SettingsToolParseResult.Failure("当前没有会话，主角设定无处保存。")
                }
                SettingsToolDraft(
                    id = idGenerator(),
                    tool = tool,
                    conversationId = conversationId,
                    protagonist = ProtagonistDraft(
                        name = name,
                        // 上限沿用 v9 会话设置三列的截断长度，草稿里就截好并提示，保存时不再二次截断。
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

    private fun parseListRequest(root: JsonObject): SettingsToolParseResult {
        val types = root["types"] as? JsonArray ?: return SettingsToolParseResult.ListRequest(emptySet())
        val names = types.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val kinds = names.mapNotNull(RpSettingKind::fromApiName).toSet()
        val unknown = names.filter { RpSettingKind.fromApiName(it) == null }
        if (unknown.isNotEmpty()) {
            return SettingsToolParseResult.Failure(
                "不认识的类型：${unknown.joinToString("、")}。可用类型：${RpSettingKind.entries.joinToString("、") { it.apiName }}。",
            )
        }
        return SettingsToolParseResult.ListRequest(kinds)
    }

    private fun parseDeleteRequest(root: JsonObject): SettingsToolParseResult {
        val targets = root["targets"] as? JsonArray
        if (targets.isNullOrEmpty()) {
            return SettingsToolParseResult.Failure("没有给出要删除的设定（targets 为空）。")
        }
        val warnings = mutableListOf<String>()
        if (targets.size > MAX_DELETE_TARGETS) {
            return SettingsToolParseResult.Failure("一次最多删除 $MAX_DELETE_TARGETS 项，请分批请求。")
        }
        val refs = mutableListOf<RpSettingRef>()
        targets.forEachIndexed { index, item ->
            val obj = item as? JsonObject
            val kind = RpSettingKind.fromApiName(obj?.string("type"))
            val id = obj?.string("id")?.trim().orEmpty()
            if (kind == null || id.isBlank()) {
                return SettingsToolParseResult.Failure(
                    "第 ${index + 1} 项缺少合法的 type 或 id。type 可用：" +
                        RpSettingKind.entries.joinToString("、") { it.apiName } + "。",
                )
            }
            val ref = RpSettingRef(kind, id)
            if (ref in refs) warnings += "重复的删除目标已合并：${kind.label} $id。" else refs += ref
        }
        return SettingsToolParseResult.DeleteRequest(refs, warnings)
    }

    private fun parseTarget(root: JsonObject): RpSettingRef? {
        val kind = RpSettingKind.fromApiName(root.string("type")) ?: return null
        val id = root.string("id").trim().takeIf { it.isNotEmpty() } ?: return null
        return RpSettingRef(kind, id)
    }

    private fun targetFailure() = SettingsToolParseResult.Failure(
        "缺少合法的 type 或 id。type 可用：${RpSettingKind.entries.joinToString("、") { it.apiName }}；" +
            "id 请用 list_rp_settings 查询。",
    )

    private fun parseUpdateRequest(root: JsonObject): SettingsToolParseResult {
        val ref = parseTarget(root) ?: return targetFailure()
        val warnings = mutableListOf<String>()
        val fields = buildMap {
            UPDATE_FIELD_KEYS.forEach { key ->
                val raw = (root[key] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val isName = key == "name" || key == "title"
                val limit = when {
                    isName -> MAX_NAME_LENGTH
                    key == "persona" || key == "content" -> MAX_LONG_TEXT_LENGTH
                    else -> MAX_TEXT_LENGTH
                }
                put(key, if (isName) raw.normalizeName(limit, warnings, key) else raw.limit(limit, warnings, key))
            }
        }
        val textEntries = (root["set_text_entries"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name").trim().replace(NEWLINE_REGEX, " ").take(MAX_NAME_LENGTH)
            val value = obj.string("value").trim().take(MAX_TEXT_LENGTH)
            when {
                name.isBlank() -> null
                value.isBlank() -> {
                    warnings += "词条「$name」内容为空，已忽略；要删除词条请用 remove_entries。"
                    null
                }
                else -> name to value
            }
        }.distinctBy { it.first }
        val metrics = (root["set_metrics"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name").trim().replace(NEWLINE_REGEX, " ").take(MAX_NAME_LENGTH)
            if (name.isBlank()) return@mapNotNull null
            MetricPatch(
                name = name,
                minValue = obj.int("min_value"),
                maxValue = obj.int("max_value"),
                initialValue = obj.int("initial_value"),
                description = obj.optionalString("description")?.trim()?.take(MAX_TEXT_LENGTH),
                progressRule = obj.optionalString("progress_rule")?.trim()?.take(MAX_TEXT_LENGTH),
            )
        }.distinctBy { it.name }
        val removals = (root["remove_entries"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        val patch = RpSettingPatch(fields, textEntries, metrics, removals)
        if (patch.isEmpty) {
            return SettingsToolParseResult.Failure("没有给出任何要修改的内容。只需给出要改的字段或词条。")
        }
        return SettingsToolParseResult.UpdateRequest(ref, patch, warnings)
    }

    private fun parseTextEntries(
        element: kotlinx.serialization.json.JsonElement?,
        warnings: MutableList<String>,
    ): List<RpEntry.Text> {
        val array = element as? JsonArray ?: return emptyList()
        if (array.size > MAX_TEXT_ENTRIES) {
            warnings += "模型给出了 ${array.size} 条词条，已保留前 $MAX_TEXT_ENTRIES 条。"
        }
        return array.take(MAX_TEXT_ENTRIES).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name").trim().replace(NEWLINE_REGEX, " ").take(MAX_NAME_LENGTH)
            val value = obj.string("value").trim().take(MAX_TEXT_LENGTH)
            if (name.isBlank() || value.isBlank()) return@mapNotNull null
            RpEntry.Text(id = idGenerator(), name = name, value = value)
        }
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
            val rawName = obj.string("name").trim().take(MAX_NAME_LENGTH)
            // 数值词条名不得含归属分隔符（§3.3），仓库保存时会整笔拒绝；草稿页的状态条只读，
            // 用户没法自己改。名字是模型起的，这里换成「·」并提示，而不是让整份草稿存不进去。
            val name = rawName.replace(RpStateBlockFormat.WIRE_NAME_SEPARATOR, SEPARATOR_REPLACEMENT)
            if (name.isBlank()) return@mapNotNull null
            if (name != rawName) {
                warnings += "状态条「$rawName」含保留符号「${RpStateBlockFormat.WIRE_NAME_SEPARATOR}」，已改为「$name」。"
            }
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

    private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

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
        SettingsTool.CREATE_SUPPORTING_CHARACTER -> "配角"
        SettingsTool.LIST_RP_SETTINGS -> "设定列表"
        SettingsTool.GET_RP_SETTING -> "设定详情"
        SettingsTool.UPDATE_RP_SETTING -> "修改请求"
        SettingsTool.DELETE_RP_SETTINGS -> "删除请求"
    }

    private companion object {
        val NEWLINE_REGEX = Regex("[\\r\\n]+")
        const val MAX_NAME_LENGTH = 80

        /** 工具生成的主角简介与初始状态的长度上限（沿用 v9 会话设置三列的上限）。 */
        const val MAX_PROFILE_LENGTH = 2_000
        /** 状态条名里的归属分隔符替换成它（与面板撞名时的「主角·体力」同形）。 */
        const val SEPARATOR_REPLACEMENT = "·"
        const val MAX_TEXT_LENGTH = 4_000
        const val MAX_LONG_TEXT_LENGTH = 20_000
        const val MAX_METRIC_DEFINITIONS = 12
        const val MAX_TEXT_ENTRIES = 30
        const val MAX_DELETE_TARGETS = 50
        val UPDATE_FIELD_KEYS = listOf(
            "name", "title", "description", "persona", "speaking_style",
            "user_identity", "boundaries", "content",
        )
        const val DEFAULT_METRIC_MIN = 0
        const val DEFAULT_METRIC_MAX = 100
    }
}
