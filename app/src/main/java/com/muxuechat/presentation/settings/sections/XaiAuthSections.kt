package com.muxuechat.presentation.settings.sections

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.muxuechat.domain.model.XaiAuthState
import com.muxuechat.presentation.common.components.MetaLine
import com.muxuechat.presentation.settings.XaiAuthUiState
import com.muxuechat.presentation.theme.MinTouchTarget
import com.muxuechat.presentation.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 折叠摘要：写当前登录状态，不写功能介绍。 */
fun xaiAuthSummary(state: XaiAuthState): String = when (state) {
    XaiAuthState.Loading -> "正在读取登录状态"
    is XaiAuthState.SignedOut -> "未登录"
    XaiAuthState.RequestingCode, is XaiAuthState.AwaitingUser -> "登录进行中"
    is XaiAuthState.SignedIn -> "已登录${state.account?.let { " · $it" }.orEmpty()}"
}

/**
 * xAI 订阅登录（设备码流程）。
 *
 * 这是 AGENT.md 允许的唯一 OAuth 入口：不提供 Token 导入，不读取任何第三方 CLI 的本地凭据。
 * 风险提示常驻显示，不因登录状态而隐藏。
 */
@Composable
fun XaiAuthSection(
    state: XaiAuthUiState,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onSignOut: () -> Unit,
    onBrowserUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    var signOutDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = Spacing.Content, vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(
            text = "风险提示：此登录复用 Grok CLI 的公开客户端身份访问 xAI 订阅额度，可能违反 xAI 服务条款，" +
                "最坏情况下账号会被限制或封禁。风险由你自行承担。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        MetaLine("目前只完成登录与凭据管理，聊天中暂时还不能选择 xAI。")

        when (val auth = state.auth) {
            XaiAuthState.Loading -> MetaLine("状态：正在读取…")

            is XaiAuthState.SignedOut -> {
                MetaLine("状态：未登录")
                auth.notice?.let { MetaLine(it) }
                Button(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                ) {
                    Text("登录 xAI")
                }
            }

            XaiAuthState.RequestingCode -> {
                MetaLine("状态：正在向 xAI 申请验证码…")
                CancelLoginButton(onCancelLogin)
            }

            is XaiAuthState.AwaitingUser -> {
                MetaLine("在浏览器中确认下面的验证码，确认后这里会自动完成登录。")
                SelectionContainer {
                    Text(
                        text = auth.userCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                MetaLine("验证码在 ${formatClock(auth.expiresAtMillis)} 前有效。")
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(auth.verificationUri)))
                        } catch (_: ActivityNotFoundException) {
                            onBrowserUnavailable()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                ) {
                    Text("打开验证页面")
                }
                CancelLoginButton(onCancelLogin)
            }

            is XaiAuthState.SignedIn -> {
                MetaLine("状态：已登录${auth.account?.let { "（$it）" } ?: "（账号信息不可用）"}，凭据已加密保存在本机")
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
            title = { Text("退出 xAI 登录？") },
            text = { Text("退出后本机删除已保存的 xAI 凭据，再次使用需要重新登录。") },
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

@Composable
private fun CancelLoginButton(onCancelLogin: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onCancelLogin,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        ) {
            Text("取消登录")
        }
    }
}

private fun formatClock(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))
