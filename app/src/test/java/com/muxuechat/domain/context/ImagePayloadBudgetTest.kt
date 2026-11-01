package com.muxuechat.domain.context

import com.muxuechat.domain.model.ChatProviderId
import com.muxuechat.domain.model.ChatSettings
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.prompt.PromptImage
import com.muxuechat.domain.prompt.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求体图片上限（MULTIMODAL_PLAN 3.3 第 2 步）与规划器的按图计费。 */
class ImagePayloadBudgetTest {
    private fun image(id: String, bytes: Long) = PromptImage(attachmentId = id, mimeType = "image/jpeg", byteSize = bytes)
    private fun user(text: String, vararg images: PromptImage) = PromptMessage("user", text, images.toList())
    private fun assistant(text: String) = PromptMessage("assistant", text)

    /** base64 后正好 400 字节的图片：300 字节原图。 */
    private val image400 = 300L

    @Test
    fun underLimit_keepsEverythingUntouched() {
        val messages = listOf(user("一", image("a", image400)), assistant("答"), user("二", image("b", image400)))

        val result = ImagePayloadBudget.apply(messages, maxBytes = 800)

        assertSame(messages, result.messages)
        assertEquals(0, result.omittedImageCount)
    }

    @Test
    fun overLimit_omitsOldestFirst_andKeepsCurrentTurnImages() {
        val messages = listOf(
            user("最早", image("a1", image400), image("a2", image400)),
            assistant("答一"),
            user("其次", image("b", image400)),
            assistant("答二"),
            user("本轮", image("c", image400)),
        )

        // 总量 1600；上限 900：省掉最早那条的两张（-800）后剩 800，已放得下，第二条保留。
        val result = ImagePayloadBudget.apply(messages, maxBytes = 900)

        assertEquals(2, result.omittedImageCount)
        assertTrue(result.messages[0].images.isEmpty())
        assertEquals("最早\n[更早的 2 张图片因请求体积上限已省略]", result.messages[0].content)
        assertEquals(listOf("b"), result.messages[2].images.map { it.attachmentId })
        assertEquals(listOf("c"), result.messages[4].images.map { it.attachmentId })
        // assistant 消息不受影响。
        assertEquals(messages[1], result.messages[1])
    }

    @Test
    fun currentTurnAloneOverLimit_isStillSentWhileAllOlderImagesAreOmitted() {
        val messages = listOf(
            user("", image("old", image400)),
            assistant("答"),
            user("本轮很大", image("big1", 3000), image("big2", 3000)),
        )

        val result = ImagePayloadBudget.apply(messages, maxBytes = 1000)

        assertEquals(1, result.omittedImageCount)
        // 纯图片消息被省略后只剩占位，不留空白。
        assertEquals("[更早的 1 张图片因请求体积上限已省略]", result.messages[0].content)
        assertEquals(listOf("big1", "big2"), result.messages[2].images.map { it.attachmentId })
    }

    @Test
    fun defaultLimitIsTwentyMebibytesOfBase64() {
        assertEquals(20L * 1024 * 1024, ImageInputSpec.MAX_IMAGE_PAYLOAD_BYTES)
        assertEquals(4L, ImageInputSpec.base64Size(1))
        assertEquals(4L, ImageInputSpec.base64Size(3))
        assertEquals(8L, ImageInputSpec.base64Size(4))
    }

    @Test
    fun planner_countsImagesPerProtocol_andExposesImageCounts() {
        val planner = ChatContextPlanner()
        val history = listOf(user("看图", image("a", 1000), image("b", 1000)))
        fun plan(protocol: ChatProviderId) = planner.plan(
            ChatContextInput(
                protocol = protocol,
                settings = ChatSettings(),
                rpSettings = null,
                roleCard = null,
                worldSetting = null,
                writingStyle = null,
                statusPanel = null,
                history = history,
                summaryState = ContextSummaryState.EMPTY,
                toolDefinitions = emptyList(),
                toolExchange = emptyList(),
                spec = ModelContextSpec(
                    providerKey = protocol.storageValue,
                    providerLabel = "fixture",
                    modelId = "fixture-model",
                    contextWindowTokens = 1_000_000L,
                    source = ContextCapacitySource.BUILT_IN,
                    maxOutputTokens = 4_096L,
                    costCapTokens = null,
                ),
                missingImageCount = 1,
            ),
        )
        val textOnly = planner.plan(
            ChatContextInput(
                protocol = ChatProviderId.DEEPSEEK,
                settings = ChatSettings(),
                rpSettings = null,
                roleCard = null,
                worldSetting = null,
                writingStyle = null,
                statusPanel = null,
                history = listOf(user("看图")),
                summaryState = ContextSummaryState.EMPTY,
                toolDefinitions = emptyList(),
                toolExchange = emptyList(),
                spec = plan(ChatProviderId.DEEPSEEK).spec,
            ),
        )

        val deepSeek = plan(ChatProviderId.DEEPSEEK)
        val openAi = plan(ChatProviderId.CUSTOM_OPENAI)

        assertEquals(2, deepSeek.imageCount)
        assertEquals(0, deepSeek.omittedImageCount)
        assertEquals(1, deepSeek.missingImageCount)
        // 图片 token 计入历史：每张按协议常量估算（DeepSeek 1024，OpenAI 兼容 1600）。
        assertTrue(deepSeek.estimatedInputTokens - textOnly.estimatedInputTokens >= 2 * ImageTokenEstimate.DEEPSEEK_TOKENS_PER_IMAGE)
        assertTrue(openAi.estimatedInputTokens > deepSeek.estimatedInputTokens)
        // 请求消息原样带着图片引用，交给请求层映射。
        assertEquals(listOf("a", "b"), deepSeek.requestMessages.last().images.map { it.attachmentId })
    }
}
