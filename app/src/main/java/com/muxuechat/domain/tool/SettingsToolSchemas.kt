package com.muxuechat.domain.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 设定工具的 JSON Schema。
 *
 * 注意这里**不使用** DeepSeek 的 strict 模式：它要求把 base_url 换成 `/beta`，
 * 为一个功能改全局地址不划算。因此 schema 只是给模型的提示，
 * 真正的校验一律在本地做（见 SettingsToolArgumentParser）。
 */
object SettingsToolSchemas {

    fun descriptionOf(tool: SettingsTool): String = when (tool) {
        SettingsTool.CREATE_ROLE_CARD ->
            "根据用户提供的需求描述或资料，创建一张角色扮演用的角色卡草稿。" +
                "草稿会先展示给用户预览和修改，用户确认后才会保存，你不需要再次询问是否保存。"

        SettingsTool.CREATE_WORLD_SETTING ->
            "根据用户提供的需求描述或资料，创建一份角色扮演用的世界观设定草稿。" +
                "草稿会先展示给用户预览和修改，用户确认后才会保存。"

        SettingsTool.CREATE_WRITING_STYLE ->
            "根据用户提供的需求描述或资料，创建一份叙事文风设定草稿。" +
                "文风描述整段正文如何书写（叙事视角、时态、节奏、描写密度、对白比例、禁用表达），" +
                "不是某个角色如何说话。草稿会先展示给用户预览和修改，用户确认后才会保存。"

        SettingsTool.CREATE_PROTAGONIST ->
            "根据用户提供的需求描述或资料，创建角色扮演中**玩家自己扮演的主角**的设定草稿。" +
                "主角属于当前会话，保存时会写进这个会话的 RP 设置，因此没有会话时无法保存。" +
                "草稿会先展示给用户预览和修改，用户确认后才会保存。"
    }

    fun parametersOf(tool: SettingsTool): JsonObject = when (tool) {
        SettingsTool.CREATE_ROLE_CARD -> roleCardSchema()
        SettingsTool.CREATE_WORLD_SETTING -> worldSettingSchema()
        SettingsTool.CREATE_WRITING_STYLE -> writingStyleSchema()
        SettingsTool.CREATE_PROTAGONIST -> protagonistSchema()
    }

    private fun roleCardSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "角色名称。必填，简短。")
            stringProperty("description", "角色简介，一两句话说明这是谁。")
            stringProperty("persona", "详细人设：性格、背景、动机、习惯、与他人的关系。")
            stringProperty("speaking_style", "该角色说话的方式：用词、语气、句长、口头禅。不要写整段正文的叙事风格。")
            stringProperty("user_identity", "该角色眼中的玩家是谁，如何称呼玩家。")
            stringProperty("opening_message", "角色的开场白。")
            stringProperty("boundaries", "禁止事项与边界：这个角色不会做什么、不应出现什么内容。")
            putJsonObject("metric_definitions") {
                put("type", "array")
                put("description", "状态条定义，例如好感度、信任度。没有合适的就给空数组，不要硬造。")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        stringProperty("name", "状态条名称。")
                        intProperty("min_value", "最小值。")
                        intProperty("max_value", "最大值，必须大于最小值。")
                        intProperty("initial_value", "初始值，必须在最小值和最大值之间。")
                        stringProperty("description", "这个状态条代表什么。")
                        stringProperty("progress_rule", "什么情况下会增加或减少。")
                    }
                    putJsonArray("required") { add("name") }
                }
            }
        }
        putJsonArray("required") { add("name") }
    }

    private fun worldSettingSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("title", "世界观标题。必填，简短。")
            stringProperty(
                "content",
                "世界观正文：时代背景、地理、势力、规则、禁忌、当前局势。可以用小标题和列表组织。",
            )
        }
        putJsonArray("required") { add("title") }
    }

    private fun writingStyleSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "文风名称。必填，简短，例如「冷峻克制的第三人称」。")
            stringProperty(
                "content",
                "文风要求正文：叙事视角与人称、时态、句子节奏、描写密度、对白与叙述的比例、" +
                    "常用与禁用的表达方式。写成可执行的写作指令，不要写成评论。",
            )
        }
        putJsonArray("required") { add("name") }
    }

    private fun protagonistSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "主角名字，也就是玩家在剧情里的称呼。必填，简短。")
            stringProperty(
                "description",
                "主角是谁：身份、性格、外貌、经历、与角色的关系。写玩家扮演的这个人，不要写对手角色。",
            )
            stringProperty(
                "initial_state",
                "开场时主角的处境：身在何处、带着什么、身心状态、刚发生过什么。只写起点，不要写后续剧情。",
            )
        }
        putJsonArray("required") { add("name") }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(
        name: String,
        description: String,
    ) = putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.intProperty(
        name: String,
        description: String,
    ) = putJsonObject(name) {
        put("type", "integer")
        put("description", description)
    }
}
