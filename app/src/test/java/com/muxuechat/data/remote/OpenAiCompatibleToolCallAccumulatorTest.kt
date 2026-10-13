package com.muxuechat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleToolCallAccumulatorTest {
    private fun fragment(
        index: Int?,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ) = OpenAiCompatibleToolCallDto(
        id = id,
        function = OpenAiCompatibleFunctionCallDto(name = name, arguments = arguments),
        index = index,
    )

    /** 首片带 id/name、后续片只追加 arguments 是标准 OpenAI SSE 的常见形状。 */
    @Test
    fun accumulatesRealisticStreamFragments() {
        val parser = OpenAiCompatibleStreamParser()
        val accumulator = OpenAiCompatibleToolCallAccumulator()
        val lines = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"create_role_card","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"name\""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"林澈\"}"}}]}}]}""",
        )

        lines.forEach { line ->
            val event = parser.parseLine(line) as OpenAiCompatibleStreamEvent.Delta
            accumulator.accept(event.toolCalls)
        }

        val call = accumulator.build().single()
        assertEquals("call_abc", call.id)
        assertEquals("create_role_card", call.function?.name)
        assertEquals("{\"name\":\"林澈\"}", call.function?.arguments)
    }

    @Test
    fun keepsMultipleCallsSeparateByIndex() {
        val accumulator = OpenAiCompatibleToolCallAccumulator()
        accumulator.accept(
            listOf(
                fragment(0, id = "a", name = "create_role_card", arguments = "{\"name\":"),
                fragment(1, id = "b", name = "create_world_setting", arguments = "{\"title\":"),
            ),
        )
        accumulator.accept(
            listOf(
                fragment(1, arguments = "\"雨城\"}"),
                fragment(0, arguments = "\"林澈\"}"),
            ),
        )

        val calls = accumulator.build()
        assertEquals(2, calls.size)
        assertEquals("{\"name\":\"林澈\"}", calls[0].function?.arguments)
        assertEquals("{\"title\":\"雨城\"}", calls[1].function?.arguments)
    }

    @Test
    fun rejectsCallsThatNeverReceiveIdOrName() {
        val accumulator = OpenAiCompatibleToolCallAccumulator()
        accumulator.accept(listOf(fragment(0, name = "create_role_card", arguments = "{}")))
        accumulator.accept(listOf(fragment(1, id = "has-id-only", arguments = "{}")))

        assertTrue(accumulator.hasCalls())
        val error = runCatching { accumulator.build() }.exceptionOrNull()
        assertTrue(error is OpenAiCompatibleProtocolException)
        assertEquals("供应商返回的工具调用不完整，缺少调用 ID 或函数名称", error?.message)
    }

    @Test
    fun missingIndexFallsBackToFragmentPositionAndResetClearsState() {
        val accumulator = OpenAiCompatibleToolCallAccumulator()
        accumulator.accept(
            listOf(
                fragment(null, id = "a", name = "create_role_card", arguments = "{}"),
                fragment(null, id = "b", name = "create_world_setting", arguments = "{}"),
            ),
        )

        assertEquals(2, accumulator.build().size)
        accumulator.reset()
        assertFalse(accumulator.hasCalls())
    }
}
