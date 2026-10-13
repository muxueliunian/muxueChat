package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.muxuechat.presentation.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 模型回复的 Markdown 正文。
 *
 * 渲染交给 multiplatform-markdown-renderer，这里只做两件事：
 * - 在后台线程解析，并在新结果出来前**保留上一次的结果**。库自带的入口每次内容变化都会先回到
 *   「加载中」的空白态，流式输出时会逐帧闪烁、列表高度来回跳；
 * - 第一次解析完成前用纯文本占位，保证刚滚入视野的长回复不会先塌成 0 高度。
 *
 * 单个换行按换行显示（`eolAsNewLine`）：RP 正文与普通回复大量用单换行分行，
 * 按 CommonMark 默认把它们并成一段会改变原文排版。
 */
@Composable
fun MarkdownText(
    content: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val parser = remember { MarkdownParser(GFMFlavourDescriptor()) }
    val parsed by produceState<State.Success?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                State.Success(
                    node = parser.buildMarkdownTreeFromString(content),
                    content = content,
                    linksLookedUp = false,
                )
            }.getOrNull()
        } ?: value
    }

    val state = parsed
    if (state == null) {
        Text(text = content, style = style, color = color, modifier = modifier)
        return
    }

    val typography = MaterialTheme.typography
    val colorScheme = MaterialTheme.colorScheme
    val heading = style.copy(fontWeight = FontWeight.SemiBold)
    Markdown(
        state = state,
        colors = markdownColor(
            text = color,
            codeBackground = colorScheme.surfaceContainerHigh,
            inlineCodeBackground = colorScheme.surfaceContainerHigh,
            dividerColor = colorScheme.outlineVariant,
            tableBackground = colorScheme.surfaceContainerLow,
        ),
        // 默认把 h1 映射到 displayLarge，聊天正文里会大到喧宾夺主；标题只比正文略大一档。
        typography = markdownTypography(
            h1 = typography.titleLarge.copy(color = color),
            h2 = heading.copy(fontSize = typography.titleLarge.fontSize * 0.9f),
            h3 = heading,
            h4 = heading,
            h5 = heading,
            h6 = heading,
            text = style,
            paragraph = style,
            ordered = style,
            bullet = style,
            list = style,
            table = style,
            code = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            inlineCode = style.copy(fontFamily = FontFamily.Monospace),
            quote = style.plus(SpanStyle(fontStyle = FontStyle.Italic, color = colorScheme.onSurfaceVariant)),
            textLink = TextLinkStyles(
                style = SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
            ),
        ),
        padding = markdownPadding(
            block = Spacing.Tight,
            codeBlock = PaddingValues(Spacing.Medium),
        ),
        annotator = markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)),
        modifier = modifier.fillMaxWidth(),
    )
}
