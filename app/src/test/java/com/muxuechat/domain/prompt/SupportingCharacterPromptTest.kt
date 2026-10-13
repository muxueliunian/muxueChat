package com.muxuechat.domain.prompt

import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ConversationRpSettings
import com.muxuechat.domain.model.RpEntry
import com.muxuechat.domain.model.SupportingCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportingCharacterPromptTest {

    @Test
    fun block_rendersNameDescriptionAndNonBlankEntries() {
        val block = SupportingCharacterPrompt.block(
            character("老周", "咖啡店的熟客", "身份" to "退休警察", "备注" to " "),
        )
        assertEquals("#### 老周\n咖啡店的熟客\n- 身份: 退休警察\n", block)
    }

    @Test
    fun block_isNullWhenNothingWouldBeSent() {
        assertNull(SupportingCharacterPrompt.block(character(" ", "", "备注" to " ")))
        assertEquals(0L, SupportingCharacterPrompt.estimateTokens(character(" ", "")))
    }

    /** 估算用的文本必须与正文 prompt 里实际拼出来的逐字一致（§6.2）。 */
    @Test
    fun block_matchesComposedPromptVerbatim() {
        val characters = listOf(
            character("老周", "咖啡店的熟客", "身份" to "退休警察"),
            character("阿绿", ""),
        )
        val system = PromptComposer().compose(
            PromptContext(
                settings = ChatSettings(),
                rpSettings = ConversationRpSettings(conversationId = "c1", isRpEnabled = true, updatedAtMillis = 1L),
                roleCard = null,
                worldSetting = null,
                statusPanel = null,
                history = listOf(PromptMessage(role = "user", content = "你好")),
                supportingCharacters = characters,
            ),
        ).first().content

        characters.forEach { character ->
            val block = SupportingCharacterPrompt.block(character)!!
            assertTrue(block, system.contains(block))
        }
        assertTrue(SupportingCharacterPrompt.estimateTokens(characters.first()) > 0L)
    }

    private fun character(name: String, description: String, vararg texts: Pair<String, String>) =
        SupportingCharacter(
            id = "s-$name",
            name = name,
            description = description,
            entries = texts.mapIndexed { index, (entryName, value) -> RpEntry.Text("t$index", entryName, value) },
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
}
