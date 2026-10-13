package com.muxuechat.presentation.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.muxuechat.domain.model.GoogleAuthState
import com.muxuechat.presentation.common.components.MetaLine
import com.muxuechat.presentation.settings.GoogleAuthUiState
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing

/** 折叠摘要：写当前登录状态，不写功能介绍。 */
fun googleAuthSummary(state: GoogleAuthState): String = when (state) {
    is GoogleAuthState.SignedOut -> "未登录"
    GoogleAuthState.SignedIn -> "已登录"
}

/**
 * Google Vertex 登录。AGENT.md §2 / §9 允许的第二个 OAuth 入口：
 * 不提供 Token 导入；App 不保存任何 Google token；费用与权限提示常驻显示。
 */
@Composable
fun GoogleAuthSection(
    state: GoogleAuthUiState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
) {
    var signOutDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = Spacing.Content, vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(
            text = "费用与权限：调用按你自己的 GCP 项目计费，登录的 Google 账号需要该项目的 Vertex AI 权限" +
                "（例如 Vertex AI User）。登录使用你自己项目里的 Android OAuth 客户端，需要手机有 Google Play 服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        MetaLine("App 不保存任何 Google token：每次发送消息时才向 Play 服务静默获取。授权失效时需要回到这里重新登录。")

        when (val auth = state.auth) {
            is GoogleAuthState.SignedOut -> {
                MetaLine("状态：未登录")
                auth.notice?.let { MetaLine(it) }
                Button(
                    onClick = onLogin,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                ) {
                    Text(if (state.busy) "正在登录…" else "登录 Google")
                }
            }

            GoogleAuthState.SignedIn -> {
                MetaLine("状态：已登录（本机只保存一个「已登录」标记）")
                TextButton(
                    onClick = { signOutDialogVisible = true },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text("退出登录")
                }
            }
        }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (signOutDialogVisible) {
        AlertDialog(
            onDismissRequest = { signOutDialogVisible = false },
            title = { Text("退出 Google 登录？") },
            text = {
                Text("退出后会撤销本 App 对该 Google 账号的授权（尽力而为），再次使用需要重新登录。已添加的 Vertex 供应商配置不会被删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signOutDialogVisible = false
                        onSignOut()
                    },
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { signOutDialogVisible = false }) { Text("取消") }
            },
        )
    }
}
