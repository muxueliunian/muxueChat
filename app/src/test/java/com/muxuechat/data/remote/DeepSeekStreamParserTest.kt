package com.muxuechat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekStreamParserTest {
    private val parser = DeepSeekStreamParser()

    @Test
    fun parseLine_returnsContentDelta() {
        val event = parser.parseLine(
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
        )

        val delta = event as DeepSeekStreamEvent.Delta
        assertEquals("你好", delta.content)
        assertNull(delta.reasoningContent)
        assertNull(delta.finishReason)
    }

    @Test
    fun parseLine_returnsReasoningDeltaAndFinishReason() {
        val event = parser.parseLine(
            """data: {"choices":[{"delta":{"reasoning_content":"思考"},"finish_reason":"stop"}],"usage":{"total_tokens":12}}""",
        )

        val delta = event as DeepSeekStreamEvent.Delta
        assertEquals("思考", delta.reasoningContent)
        assertEquals("stop", delta.finishReason)
        assertEquals(12, delta.usage?.totalTokens)
    }

    @Test
    fun parseLine_returnsDone() {
        assertEquals(DeepSeekStreamEvent.Done, parser.parseLine("data: [DONE]"))
    }

    @Test
    fun parseLine_ignoresNonDataLines() {
        assertNull(parser.parseLine("event: ping"))
        assertNull(parser.parseLine(""))
    }

    @Test
    fun parseLines_parsesSsePayload() {
        val events = parser.parseLines(
            """
            data: {"choices":[{"delta":{"content":"A"}}]}
            data: {"choices":[{"delta":{"content":"B"}}]}
            data: [DONE]
            """.trimIndent(),
        )

        assertEquals(3, events.size)
        assertEquals("A", (events[0] as DeepSeekStreamEvent.Delta).content)
        assertEquals("B", (events[1] as DeepSeekStreamEvent.Delta).content)
        assertEquals(DeepSeekStreamEvent.Done, events[2])
    }
}
