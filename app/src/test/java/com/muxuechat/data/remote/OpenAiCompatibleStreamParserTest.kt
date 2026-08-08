package com.muxuechat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiCompatibleStreamParserTest {
    private val parser = OpenAiCompatibleStreamParser()

    @Test
    fun parseLine_returnsContentDeltaAndFinishReason() {
        val event = parser.parseLine(
            """data: {"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}],"usage":{"total_tokens":12}}""",
        )

        val delta = event as OpenAiCompatibleStreamEvent.Delta
        assertEquals("你好", delta.content)
        assertEquals("stop", delta.finishReason)
        assertEquals(12, delta.usage?.totalTokens)
    }

    /** 思考字段名各家不同：DeepSeek 系是 reasoning_content，OpenRouter 是 reasoning。 */
    @Test
    fun parseLine_readsReasoningUnderEitherFieldName() {
        val snakeCase = parser.parseLine(
            """data: {"choices":[{"delta":{"reasoning_content":"先想一下"}}]}""",
        ) as OpenAiCompatibleStreamEvent.Delta
        val openRouter = parser.parseLine(
            """data: {"choices":[{"delta":{"reasoning":"thinking"}}]}""",
        ) as OpenAiCompatibleStreamEvent.Delta
        val plain = parser.parseLine(
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
        ) as OpenAiCompatibleStreamEvent.Delta

        assertEquals("先想一下", snakeCase.reasoningContent)
        assertNull(snakeCase.content)
        assertEquals("thinking", openRouter.reasoningContent)
        // 不返回思考的服务必须保持 null，否则聊天页会展开一个空的思考区。
        assertNull(plain.reasoningContent)
    }

    @Test
    fun parseLine_readsStandardToolCallDeltaAndFinishReason() {
        val event = parser.parseLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_role_card","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
        ) as OpenAiCompatibleStreamEvent.Delta

        val call = event.toolCalls?.single()
        assertEquals("tool_calls", event.finishReason)
        assertEquals("call_1", call?.id)
        assertEquals(0, call?.index)
        assertEquals("create_role_card", call?.function?.name)
        assertEquals("{}", call?.function?.arguments)
    }

    @Test
    fun parseLine_returnsDone() {
        assertEquals(OpenAiCompatibleStreamEvent.Done, parser.parseLine("data: [DONE]"))
    }

    @Test
    fun parseLine_ignoresEmptyAndNonDataLines() {
        assertNull(parser.parseLine("event: message"))
        assertNull(parser.parseLine(""))
        assertNull(parser.parseLine("data:   "))
    }

    @Test
    fun parseLines_parsesSsePayload() {
        val events = parser.parseLines(
            """
            event: message
            data: {"choices":[{"delta":{"content":"A"}}]}

            data: {"choices":[{"delta":{"content":"B"}}]}
            data: [DONE]
            """.trimIndent(),
        )

        assertEquals(3, events.size)
        assertEquals("A", (events[0] as OpenAiCompatibleStreamEvent.Delta).content)
        assertEquals("B", (events[1] as OpenAiCompatibleStreamEvent.Delta).content)
        assertEquals(OpenAiCompatibleStreamEvent.Done, events[2])
    }
}
