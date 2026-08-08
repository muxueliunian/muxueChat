package com.muxuechat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekToolCallAccumulatorTest {

    private fun fragment(
        index: Int?,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ) = DeepSeekToolCallDto(
        id = id,
        function = DeepSeekFunctionCallDto(name = name, arguments = arguments),
        index = index,
    )

    /**
     * 回归：真实的续传分片**不带 `id`，也可能不带完整 function 对象**。
     * DTO 曾把两者都声明成必填，反序列化直接抛
     * 「Field 'id' is required for type ... at path: $.choices[0].delta.tool_calls[0]」，
     * 每次调用工具必崩。这里用真实响应形状端到端跑一遍解析 + 累积。
     */
    @Test
    fun parsesRealStreamFragmentsWithoutIdAndAccumulates() {
        val parser = DeepSeekStreamParser()
        val accumulator = DeepSeekToolCallAccumulator()

        val lines = listOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc",""" +
                """"type":"function","function":{"name":"create_role_card","arguments":""}}]}}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,""" +
                """"function":{"arguments":"{\"name\""}}]}}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,""" +
                """"function":{"arguments":":\"林澈\"}"}}]}}]}""",
        )

        lines.forEach { line ->
            val event = parser.parseLine(line)
            assertTrue(event is DeepSeekStreamEvent.Delta)
            accumulator.accept((event as DeepSeekStreamEvent.Delta).toolCalls)
        }

        val calls = accumulator.build()
        assertEquals(1, calls.size)
        assertEquals("call_abc", calls.first().id)
        assertEquals("create_role_card", calls.first().function?.name)
        assertEquals("""{"name":"林澈"}""", calls.first().function?.arguments)
    }

    /** 分片完全没有 id（首片丢失或乱序）时不能产出无法回传的调用。 */
    @Test
    fun dropsCallWhenIdNeverArrives() {
        val accumulator = DeepSeekToolCallAccumulator()

        accumulator.accept(listOf(fragment(0, name = "create_role_card", arguments = "{}")))

        assertTrue(accumulator.hasCalls())
        assertTrue(accumulator.build().isEmpty())
    }

    /** 流式下 arguments 是一小段一小段到达的，直接取最后一片只会得到残缺 JSON。 */
    @Test
    fun concatenatesArgumentFragmentsInOrder() {
        val accumulator = DeepSeekToolCallAccumulator()

        accumulator.accept(listOf(fragment(0, id = "call_1", name = "create_world_setting", arguments = "{\"tit")))
        accumulator.accept(listOf(fragment(0, arguments = "le\":\"雨")))
        accumulator.accept(listOf(fragment(0, arguments = "城\"}")))

        val calls = accumulator.build()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls.first().id)
        assertEquals("create_world_setting", calls.first().function?.name)
        assertEquals("{\"title\":\"雨城\"}", calls.first().function?.arguments)
    }

    @Test
    fun keepsMultipleCallsSeparateByIndex() {
        val accumulator = DeepSeekToolCallAccumulator()

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
        assertEquals("a", calls[0].id)
        assertEquals("{\"name\":\"林澈\"}", calls[0].function?.arguments)
        assertEquals("b", calls[1].id)
        assertEquals("{\"title\":\"雨城\"}", calls[1].function?.arguments)
    }

    @Test
    fun laterIdAndNameFragmentsFillInMissingValues() {
        val accumulator = DeepSeekToolCallAccumulator()

        // 首片只有 arguments，id/name 在后续分片才出现。
        accumulator.accept(listOf(fragment(0, arguments = "{}")))
        accumulator.accept(listOf(fragment(0, id = "call_9", name = "create_writing_style")))

        val call = accumulator.build().single()
        assertEquals("call_9", call.id)
        assertEquals("create_writing_style", call.function?.name)
    }

    /** 没有 id 或 name 就无法回传 tool 结果，只能丢弃而不是发一个必然失败的请求。 */
    @Test
    fun dropsIncompleteCalls() {
        val accumulator = DeepSeekToolCallAccumulator()

        accumulator.accept(listOf(fragment(0, arguments = "{\"a\":1}")))
        accumulator.accept(listOf(fragment(1, id = "has-id-no-name", arguments = "{}")))

        assertTrue(accumulator.hasCalls())
        assertTrue("不完整的调用必须被丢弃", accumulator.build().isEmpty())
    }

    @Test
    fun missingIndexFallsBackToFragmentPosition() {
        val accumulator = DeepSeekToolCallAccumulator()

        accumulator.accept(
            listOf(
                fragment(null, id = "a", name = "create_role_card", arguments = "{}"),
                fragment(null, id = "b", name = "create_world_setting", arguments = "{}"),
            ),
        )

        val calls = accumulator.build()
        assertEquals("index 缺失时不能互相覆盖", 2, calls.size)
    }

    @Test
    fun emptyAndNullInputProduceNoCalls() {
        val accumulator = DeepSeekToolCallAccumulator()

        accumulator.accept(null)
        accumulator.accept(emptyList())

        assertFalse(accumulator.hasCalls())
        assertTrue(accumulator.build().isEmpty())
    }

    @Test
    fun resetClearsState() {
        val accumulator = DeepSeekToolCallAccumulator()
        accumulator.accept(listOf(fragment(0, id = "a", name = "create_role_card", arguments = "{}")))

        accumulator.reset()

        assertFalse(accumulator.hasCalls())
    }
}
