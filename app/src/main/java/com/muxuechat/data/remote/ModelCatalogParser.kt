package com.muxuechat.data.remote

import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * `GET /models` 响应的共享解析。
 *
 * DeepSeek 与自定义 OpenAI 兼容供应商返回同一类 list 结构；聊天请求仍走各自协议。
 * 这里只负责「有哪些模型 ID」，不推断上下文、视觉或其他能力。
 */
internal object ModelCatalogParser {
    const val MAX_RESPONSE_BYTES = 2_097_152L
    const val MAX_MODEL_COUNT = 2_000
    const val MAX_MODEL_ID_LENGTH = 256

    fun parse(
        body: String,
        json: Json,
        maxCount: Int = MAX_MODEL_COUNT,
        maxIdLength: Int = MAX_MODEL_ID_LENGTH,
    ): ModelCatalogParseResult {
        val parsed = try {
            json.decodeFromString<OpenAiCompatibleModelsResponseDto>(body)
        } catch (_: Exception) {
            return ModelCatalogParseResult.Failure(ModelCatalogParseResult.Reason.INVALID_JSON)
        }
        if (parsed.data.size > maxCount) {
            return ModelCatalogParseResult.Failure(ModelCatalogParseResult.Reason.TOO_MANY)
        }
        val normalizedIds = parsed.data.map { model -> model.id.trim() }
        if (normalizedIds.any { id -> id.length > maxIdLength }) {
            return ModelCatalogParseResult.Failure(ModelCatalogParseResult.Reason.ID_TOO_LONG)
        }
        val models = normalizedIds
            .filter { id -> id.isNotEmpty() }
            .distinct()
            .sorted()
        if (models.isEmpty()) {
            return ModelCatalogParseResult.Failure(ModelCatalogParseResult.Reason.EMPTY)
        }
        return ModelCatalogParseResult.Success(models)
    }
}

internal sealed class ModelCatalogParseResult {
    data class Success(val ids: List<String>) : ModelCatalogParseResult()
    data class Failure(val reason: Reason) : ModelCatalogParseResult()

    enum class Reason {
        INVALID_JSON,
        TOO_MANY,
        ID_TOO_LONG,
        EMPTY,
    }
}

/** 已整理成用户可读文案的模型目录错误，响应阶段不应再被映射成泛化网络错误。 */
internal interface ModelCatalogUserFacingError

internal class ModelCatalogException(message: String) :
    IOException(message),
    ModelCatalogUserFacingError

internal data class ModelCatalogCopy(
    val missingApiKey: String,
    val invalidApiKey: String,
    val cancelled: String,
    val notFound: String,
    val parseFailed: String,
    val tooLarge: String,
    val tooMany: String,
    val idTooLong: String,
    val empty: String,
    val cannotStart: String,
    val emptyBody: String,
) {
    fun messageFor(reason: ModelCatalogParseResult.Reason): String = when (reason) {
        ModelCatalogParseResult.Reason.INVALID_JSON -> parseFailed
        ModelCatalogParseResult.Reason.TOO_MANY -> tooMany
        ModelCatalogParseResult.Reason.ID_TOO_LONG -> idTooLong
        ModelCatalogParseResult.Reason.EMPTY -> empty
    }
}
