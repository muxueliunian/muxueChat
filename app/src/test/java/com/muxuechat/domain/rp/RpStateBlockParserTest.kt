package com.muxuechat.domain.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RpStateBlockParserTest {
    private val parser = RpStateBlockParser()

    @Test
    fun extract_removesStateBlockAndParsesMetrics() {
        val extraction = parser.extract(
            """
            她把杯子推到你面前。
            <muxue_state>{"metrics":[{"name":"好感度","value":42,"min":0,"max":100},{"name":"欲望值","value":"7","min":-10,"max":100}],"relationship":"熟人","scene":"吧台"}</muxue_state>
            """.trimIndent(),
        )

        assertEquals("她把杯子推到你面前。", extraction.cleanContent)
        assertNotNull(extraction.panel)
        assertEquals(2, extraction.panel?.metrics?.size)
        assertEquals(42, extraction.panel?.metrics?.first()?.value)
        assertEquals(-10, extraction.panel?.metrics?.last()?.min)
        assertEquals("熟人", extraction.panel?.relationship)
        assertEquals("吧台", extraction.panel?.scene)
    }

    @Test
    fun extract_withoutStateBlock_keepsContent() {
        val extraction = parser.extract("普通回复")

        assertEquals("普通回复", extraction.cleanContent)
        assertNull(extraction.panel)
    }
}
