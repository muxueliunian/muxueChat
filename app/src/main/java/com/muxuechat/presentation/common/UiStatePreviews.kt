package com.muxuechat.presentation.common

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.muxuechat.domain.model.*
import com.muxuechat.presentation.chat.components.*
import com.muxuechat.presentation.common.components.ModelNoteList
import com.muxuechat.presentation.settings.sections.ThinkingAndSamplingSection
import com.muxuechat.presentation.theme.MuxueChatTheme

/** 全部内容为虚构占位；不使用用户会话、端点或凭据。 */
@Preview(name = "小屏／长模型名", widthDp = 320, heightDp = 640)
@Preview(name = "大字体／深色", widthDp = 360, heightDp = 800, fontScale = 1.6f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReadingDeskPreview() {
    MuxueChatTheme {
        Surface {
            Column {
                ChatTopBar("写作练习", "示例供应商", "fixture-model-with-a-very-long-identifier", false, {}, {}, {}, {}, {})
                MessageList(emptyList(), false, null, emptySet(), false, {}, {}, {}, {}, {}, {}, {}, Modifier.weight(1f))
                MessageInputBar(TextFieldValue(""), {}, false, {}, {}, canAttach = true)
            }
        }
    }
}

@Preview(name = "失败／停止／正文", widthDp = 320, heightDp = 700, fontScale = 1.3f)
@Composable
private fun MessageStatesPreview() {
    MuxueChatTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(MessageStatus.FAILED, MessageStatus.CANCELLED, MessageStatus.COMPLETED).forEach { status ->
                    MessageItem(
                        ChatMessage("fixture-$status", "fixture", ChatRole.ASSISTANT,
                            if (status == MessageStatus.COMPLETED) "这是一段虚构的阅读样文，用于检查行距与文字层级。" else "",
                            "用于检查折叠入口的示例思考内容。", status,
                            if (status == MessageStatus.FAILED) "连接失败，请检查连接后重试。" else null, 0, 0),
                        false, true, false, false, {}, {}, {}, {}, {}, {}, {},
                    )
                }
            }
        }
    }
}

@Preview(name = "未知／旧名／旧选择", widthDp = 320, heightDp = 640)
@Preview(name = "目录状态深色", widthDp = 360, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DirectoryNotesPreview() {
    MuxueChatTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val directory = DeepSeekModels.directory(DeepSeekModels.V4_FLASH_VISION_EXP, listOf("fixture-unknown"))
                Text(describeDirectorySource(directory))
                Text("已迁移的旧名")
                ModelNoteList(ModelNotes.deepSeekNotes(DeepSeekModels.V4_FLASH_VISION_EXP, directory, null))
                Text("未知模型")
                ModelNoteList(ModelNotes.deepSeekNotes("fixture-unknown", directory, null))
                Text("刷新失败，保留旧目录与当前选择", color = MaterialTheme.colorScheme.error)
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(describeRefreshAction(true, false))
                MessageInputBar(TextFieldValue("文档处理示例"), {}, false, {}, {}, isImportingAttachment = true, canAttach = true)
            }
        }
    }
}

@Preview(name = "思考低高最高／大字体", widthDp = 320, heightDp = 1000, fontScale = 1.5f)
@Composable
private fun ThinkingPreview() {
    MuxueChatTheme {
        Surface { ThinkingAndSamplingSection(ChatSettings(), {}, { _, _ -> }) }
    }
}
