package com.muxuechat.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun success_trimsDedupesAndSortsNonEmptyIds() {
        val body = """
            {
              "object": "list",
              "data": [
                {"id":" deepseek-v4-pro ","owned_by":"deepseek"},
                {"id":"deepseek-v4-flash"},
                {"id":"deepseek-v4-flash"},
                {"id":"  "},
                {"id":"deepseek-v4-flash-vision-exp"}
              ]
            }
        """.trimIndent()

        val parsed = ModelCatalogParser.parse(body, json)

        assertEquals(
            ModelCatalogParseResult.Success(
                listOf(
                    "deepseek-v4-flash",
                    "deepseek-v4-flash-vision-exp",
                    "deepseek-v4-pro",
                ),
            ),
            parsed,
        )
    }

    @Test
    fun emptyOrMissingData_isFailureWithoutThrowing() {
        assertEquals(
            ModelCatalogParseResult.Reason.EMPTY,
            (ModelCatalogParser.parse("""{"object":"list"}""", json) as ModelCatalogParseResult.Failure).reason,
        )
        assertEquals(
            ModelCatalogParseResult.Reason.EMPTY,
            (ModelCatalogParser.parse("""{"data":[{"id":"  "}]}""", json) as ModelCatalogParseResult.Failure).reason,
        )
    }

    @Test
    fun invalidJson_isParseFailure() {
        val parsed = ModelCatalogParser.parse("{not json", json)
        assertEquals(ModelCatalogParseResult.Reason.INVALID_JSON, (parsed as ModelCatalogParseResult.Failure).reason)
    }

    @Test
    fun tooManyOrTooLongIds_areRejected() {
        val tooMany = buildString {
            append("""{"data":[""")
            append((0..ModelCatalogParser.MAX_MODEL_COUNT).joinToString(",") { """{"id":"m$it"}""" })
            append("]}")
        }
        val longId = "m".repeat(ModelCatalogParser.MAX_MODEL_ID_LENGTH + 1)

        assertEquals(
            ModelCatalogParseResult.Reason.TOO_MANY,
            (ModelCatalogParser.parse(tooMany, json) as ModelCatalogParseResult.Failure).reason,
        )
        assertEquals(
            ModelCatalogParseResult.Reason.ID_TOO_LONG,
            (
                ModelCatalogParser.parse("""{"data":[{"id":"$longId"}]}""", json)
                    as ModelCatalogParseResult.Failure
                ).reason,
        )
    }

    @Test
    fun extraFieldsAreIgnored() {
        val parsed = ModelCatalogParser.parse(
            """{"data":[{"id":"keep","object":"model","owned_by":"x","created":1}]}""",
            json,
        )
        assertEquals(ModelCatalogParseResult.Success(listOf("keep")), parsed)
        assertTrue(parsed is ModelCatalogParseResult.Success)
    }
}
