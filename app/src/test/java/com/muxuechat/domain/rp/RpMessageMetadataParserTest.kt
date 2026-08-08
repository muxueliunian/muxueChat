package com.muxuechat.domain.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RpMessageMetadataParserTest {
    private val parser = RpMessageMetadataParser()

    @Test
    fun parse_readsInnerThoughtOptionsAndState() {
        val metadata = parser.parse(
            """
            {"inner_thought":"他暂时不想表现得太在意。","options":["追问原因","先沉默观察","换个话题"],"state":{"metrics":[{"name":"信任度","value":4,"min":-10,"max":10}],"relationship":"试探","scene":"雨巷"}}
            """.trimIndent(),
        )

        assertNotNull(metadata)
        assertEquals("他暂时不想表现得太在意。", metadata?.innerThought)
        assertEquals(listOf("追问原因", "先沉默观察", "换个话题"), metadata?.options)
        assertEquals("试探", metadata?.statusPanel?.relationship)
        assertEquals("雨巷", metadata?.statusPanel?.scene)
        assertEquals(4, metadata?.statusPanel?.metrics?.first()?.value)
    }

    @Test
    fun parse_acceptsWrappedMetadataBlock() {
        val metadata = parser.parse(
            """
            <muxue_meta>{"innerThought":"她其实已经开始动摇。","options":["靠近一步","退后等待","直接询问"],"state":{"metrics":[],"relationship":"暧昧","scene":"古树旁"}}</muxue_meta>
            """.trimIndent(),
        )

        assertNotNull(metadata)
        assertEquals("她其实已经开始动摇。", metadata?.innerThought)
        assertEquals(3, metadata?.options?.size)
        assertEquals("暧昧", metadata?.statusPanel?.relationship)
    }

    @Test
    fun parse_acceptsCodeFenceWithExtraText() {
        val metadata = parser.parse(
            """
            下面是结果：
            ```json
            {"inner_thought":"他仍在判断你的意图。","options":["继续靠近","停在原地观察","换个轻松话题"],"state":{"metrics":[],"relationship":"试探","scene":"门廊"}}
            ```
            """.trimIndent(),
        )

        assertNotNull(metadata)
        assertEquals(listOf("继续靠近", "停在原地观察", "换个轻松话题"), metadata?.options)
        assertEquals("门廊", metadata?.statusPanel?.scene)
    }

    @Test
    fun parse_limitsOptionsToThreeAndDropsBlankItems() {
        val metadata = parser.parse(
            """
            {"inner_thought":"","options":["追问她刚才停顿的原因"," ","看向窗外等待她先开口","把话题转向旧照片","多余建议"],"state":{"metrics":[],"relationship":"","scene":""}}
            """.trimIndent(),
        )

        assertNotNull(metadata)
        assertEquals(
            listOf("追问她刚才停顿的原因", "看向窗外等待她先开口", "把话题转向旧照片"),
            metadata?.options,
        )
    }
}
