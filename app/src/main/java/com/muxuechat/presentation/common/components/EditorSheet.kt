package com.muxuechat.presentation.common.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.muxuechat.presentation.theme.Spacing

/** 编辑器统一使用固定操作区；字段独立滚动，IME 与导航栏取并集。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 点遮罩与系统返回在 ModalBottomSheet 内部都会先请求「隐藏」，确认后才回调 onDismissRequest。
    // 如果放行隐藏，未保存确认框弹出时 Sheet 已经不可见，选「继续编辑」就回不去了。
    // 因此这里一律否决隐藏，把请求交给 [onDismiss]（通常是 [rememberGuardedDismiss]）决定：
    // 干净时由调用方把 Sheet 移出组合，有草稿时只弹确认框，Sheet 保持原样。
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden) {
                latestOnDismiss()
                false
            } else {
                true
            }
        },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(Spacing.Content),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                content = content,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.Content, vertical = Spacing.Small)) {
                actions()
            }
        }
    }
}

/** 返回、点遮罩、取消共用同一个出口，不在关闭后才尝试补救草稿。 */
@Composable
fun rememberGuardedDismiss(
    dirty: Boolean,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
): () -> Unit {
    var confirming by remember { mutableStateOf(false) }
    val request: () -> Unit = {
        if (enabled) {
            if (dirty) confirming = true else onDismiss()
        }
    }
    BackHandler(onBack = request)
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("离开后，本次编辑的内容不会保存。") },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDismiss() }) {
                    Text("放弃修改", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("继续编辑") }
            },
        )
    }
    return request
}
