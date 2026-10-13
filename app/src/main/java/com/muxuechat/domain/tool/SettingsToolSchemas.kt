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
                "主角保存为主角档案并绑定到当前会话，因此没有会话时无法保存。" +
                "草稿会先展示给用户预览和修改，用户确认后才会保存。"

        SettingsTool.CREATE_SUPPORTING_CHARACTER ->
            "根据用户提供的需求描述或资料，创建一个**配角**的设定草稿。配角是剧情里除角色卡人物与玩家主角之外的人物，" +
                "只有名称、简介和文本词条，没有状态数值。不要用角色卡代替配角。" +
                "草稿会先展示给用户预览和修改，用户确认后才会保存。"

        SettingsTool.LIST_RP_SETTINGS ->
            "只读：列出用户已有的角色扮演设定（角色卡、配角、主角、世界观、文风）的完整内容、id 以及被几个会话使用。" +
                "修改或删除设定前必须先调用它拿到 id。内容较多时请用 types 只查需要的类型。"

        SettingsTool.GET_RP_SETTING ->
            "只读：按类型和 id 读取一项角色扮演设定的完整内容（含全部词条与状态条）。" +
                "只在 list_rp_settings 因内容过长没有列出该项、或只需要查看一项时使用；list_rp_settings 已给出全文时不必再调用。"

        SettingsTool.UPDATE_RP_SETTING ->
            "请求修改一项已有的角色扮演设定。id 必须来自 list_rp_settings 的结果，不要猜测。" +
                "只给出要改的部分：普通字段给出该字段的**完整新文本**，没给的字段保持原样；" +
                "词条按名称定位，set_text_entries / set_metrics 同名则修改、不同名则新增，remove_entries 按名称删除；" +
                "要给词条改名，就用 remove_entries 删掉旧名、再用新名添加。" +
                "可改字段：角色卡 name、description、persona、speaking_style、user_identity、boundaries；" +
                "配角与主角 name、description；世界观 title、content；文风 name、content。" +
                "状态条（set_metrics）只有角色卡和主角有，配角只有文本词条，世界观与文风没有词条。" +
                "状态条定义被修改后，所有使用这项设定的会话里该状态条会回到新的初始值。" +
                "调用后只会生成修改请求，用户在预览界面核对并确认后才会覆盖原设定；你不需要再次询问用户是否确认，也不要声称已经修改。"

        SettingsTool.DELETE_RP_SETTINGS ->
            "请求删除一个或多个角色扮演设定。只能删除用户在对话里明确要求删除的项，" +
                "id 必须来自 list_rp_settings 的结果，不要猜测。调用后只会生成删除请求，" +
                "用户在确认界面核对并同意后才会真正删除，删除不可撤销；你不需要再次询问用户是否确认。"
    }

    fun parametersOf(tool: SettingsTool): JsonObject = when (tool) {
        SettingsTool.CREATE_ROLE_CARD -> roleCardSchema()
        SettingsTool.CREATE_WORLD_SETTING -> worldSettingSchema()
        SettingsTool.CREATE_WRITING_STYLE -> writingStyleSchema()
        SettingsTool.CREATE_PROTAGONIST -> protagonistSchema()
        SettingsTool.CREATE_SUPPORTING_CHARACTER -> supportingCharacterSchema()
        SettingsTool.LIST_RP_SETTINGS -> listSchema()
        SettingsTool.GET_RP_SETTING -> getSchema()
        SettingsTool.UPDATE_RP_SETTING -> updateSchema()
        SettingsTool.DELETE_RP_SETTINGS -> deleteSchema()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.targetProperties() {
        putJsonObject("type") {
            put("type", "string")
            put("description", "设定类型。")
            kindEnum(this)
        }
        stringProperty("id", "设定的 id，来自 list_rp_settings。")
    }

    private fun getSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { targetProperties() }
        putJsonArray("required") {
            add("type")
            add("id")
        }
    }

    private fun updateSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            targetProperties()
            stringProperty("name", "新名称（角色卡、配角、主角、文风）。")
            stringProperty("title", "新标题（世界观）。")
            stringProperty("description", "新简介（角色卡、配角、主角）。")
            stringProperty("persona", "新人设全文（角色卡）。")
            stringProperty("speaking_style", "新说话风格（角色卡）。")
            stringProperty("user_identity", "新用户身份（角色卡）。")
            stringProperty("boundaries", "新禁止事项与边界（角色卡）。")
            stringProperty("content", "新正文全文（世界观、文风）。")
            putJsonObject("set_text_entries") {
                put("type", "array")
                put("description", "要新增或改写的文本词条（角色卡、配角、主角），按名称匹配。")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        stringProperty("name", "词条名称。")
                        stringProperty("value", "词条的完整新内容。")
                    }
                    putJsonArray("required") {
                        add("name")
                        add("value")
                    }
                }
            }
            putJsonObject("set_metrics") {
                put("type", "array")
                put(
                    "description",
                    "要新增或修改的状态条（仅角色卡、主角），按名称匹配。修改时没给的属性沿用原值；" +
                        "新增时没给的属性默认范围 0～100、初始值等于最小值。",
                )
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        stringProperty("name", "状态条名称。")
                        intProperty("min_value", "最小值。")
                        intProperty("max_value", "最大值，必须大于最小值。")
                        intProperty("initial_value", "初始值。")
                        stringProperty("description", "这个状态条代表什么。")
                        stringProperty("progress_rule", "什么情况下会增加或减少。")
                    }
                    putJsonArray("required") { add("name") }
                }
            }
            putJsonObject("remove_entries") {
                put("type", "array")
                put("description", "要删除的词条或状态条名称。")
                putJsonObject("items") { put("type", "string") }
            }
        }
        putJsonArray("required") {
            add("type")
            add("id")
        }
    }

    private fun kindEnum(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        builder.putJsonArray("enum") {
            com.muxuechat.domain.model.RpSettingKind.entries.forEach { add(it.apiName) }
        }
    }

    private fun supportingCharacterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "配角名称。必填，简短。")
            stringProperty("description", "配角是谁：身份、性格、外貌，以及与主角、角色卡人物的关系。")
            putJsonObject("entries") {
                put("type", "array")
                put("description", "补充的文本词条，例如年龄、职业、口头禅。没有就给空数组。配角没有状态数值。")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        stringProperty("name", "词条名称。")
                        stringProperty("value", "词条内容。")
                    }
                    putJsonArray("required") {
                        add("name")
                        add("value")
                    }
                }
            }
        }
        putJsonArray("required") { add("name") }
    }

    private fun listSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("types") {
                put("type", "array")
                put("description", "只列这些类型；省略或给空数组表示全部类型。")
                putJsonObject("items") {
                    put("type", "string")
                    kindEnum(this)
                }
            }
        }
    }

    private fun deleteSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("targets") {
                put("type", "array")
                put("description", "要删除的设定，每项给出类型与 list_rp_settings 返回的 id。")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type") {
                            put("type", "string")
                            put("description", "设定类型。")
                            kindEnum(this)
                        }
                        stringProperty("id", "设定的 id。")
                    }
                    putJsonArray("required") {
                        add("type")
                        add("id")
                    }
                }
            }
        }
        putJsonArray("required") { add("targets") }
    }

    private fun roleCardSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "角色名称。必填，简短。")
            stringProperty("description", "角色简介，一两句话说明这是谁。")
            stringProperty("persona", "详细人设：性格、背景、动机、习惯、与他人的关系。")
            stringProperty("speaking_style", "该角色说话的方式：用词、语气、句长、口头禅。不要写整段正文的叙事风格。")
            stringProperty("user_identity", "该角色眼中的玩家是谁，如何称呼玩家。")
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
