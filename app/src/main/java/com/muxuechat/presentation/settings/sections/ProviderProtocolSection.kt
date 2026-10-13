package com.muxuechat.presentation.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.muxuechat.domain.model.ConversationHeaderPolicy
import com.muxuechat.domain.model.CustomProvider
import com.muxuechat.domain.model.OpenAiOutputTokenField
import com.muxuechat.presentation.common.components.quietTextFieldColors
import com.muxuechat.presentation.settings.CustomProviderMutation
import com.muxuechat.presentation.settings.DebouncedTextSetting
import com.muxuechat.presentation.settings.components.ChoiceOption
import com.muxuechat.presentation.settings.components.ChoiceRow
import com.muxuechat.presentation.settings.components.SettingsSection
import com.muxuechat.presentation.settings.rememberSyncedTextFieldValue
import com.muxuechat.presentation.theme.Corner
import com.muxuechat.presentation.theme.Spacing

@Composable
internal fun ProviderProtocolSection(provider: CustomProvider, onUpdate: (CustomProviderMutation) -> Unit) {
    val header = rememberSyncedTextFieldValue(external = provider.conversationHeaderName)
    val validHeader = ConversationHeaderPolicy.isValid(header.value.text.trim())
    DebouncedTextSetting(
        fieldValue = header,
        persistedValue = provider.conversationHeaderName,
        canCommit = { ConversationHeaderPolicy.isValid(it.trim()) },
        onCommit = { value -> onUpdate { it.copy(conversationHeaderName = value) } },
    )
    SettingsSection(
        title = "接口兼容",
        description = "按服务文档选择。输出上限同样用于 RP 辅助请求；修改后从下一次发送生效。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text("输出上限字段")
            ChoiceRow(
                options = OpenAiOutputTokenField.entries.map { ChoiceOption(it, it.storageValue) },
                selected = provider.outputTokenField,
                onSelect = { field -> onUpdate { it.copy(outputTokenField = field) } },
            )
            OutlinedTextField(
                value = header.value,
                onValueChange = { header.value = it },
                label = { Text("会话标识请求头（可选）") },
                placeholder = { Text("例如 x-opencode-session") },
                supportingText = {
                    Text(if (validHeader) {
                        "留空不发送；填写后正文和辅助请求使用同一会话标识。App 使用自身客户端名称。"
                    } else {
                        "仅支持以 x- 开头的字母、数字和连字符，最长 ${ConversationHeaderPolicy.MAX_LENGTH} 字符。"
                    })
                },
                singleLine = true,
                isError = !validHeader,
                modifier = Modifier.fillMaxWidth(),
                shape = Corner.Control,
                colors = quietTextFieldColors(),
            )
        }
    }
}
