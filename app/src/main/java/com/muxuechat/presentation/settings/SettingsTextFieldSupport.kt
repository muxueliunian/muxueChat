package com.muxuechat.presentation.settings

import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay

/**
 * 设置页文本输入的自动保存机制。
 *
 * 从 `SettingsScreen.kt` 拆出来只是为了让页面文件回到可读的长度，行为完全不变：
 * 输入去抖后提交，离页 / 折叠 / 刷新前统一 flush，绝不打断中文 IME 的 composition。
 */
internal const val TEXT_SETTING_DEBOUNCE_MILLIS = 300L

internal class SettingsFlushCoordinator {
    private val callbacks = linkedMapOf<Any, () -> Unit>()

    fun register(key: Any, callback: () -> Unit) {
        callbacks[key] = callback
    }

    fun unregister(key: Any) {
        callbacks.remove(key)
    }

    fun flush() {
        callbacks.values.toList().forEach { it() }
    }
}

internal val LocalSettingsFlushCoordinator = staticCompositionLocalOf { SettingsFlushCoordinator() }

/** 用本地 TextFieldValue 保留光标、selection 与中文 IME composition。 */
@Composable
internal fun rememberSyncedTextFieldValue(external: String): MutableState<TextFieldValue> {
    return remember {
        mutableStateOf(TextFieldValue(text = external, selection = TextRange(external.length)))
    }
}

@Composable
internal fun DebouncedTextSetting(
    fieldValue: MutableState<TextFieldValue>,
    persistedValue: String,
    onCommit: (String) -> Unit,
    canCommit: (String) -> Boolean = { true },
) {
    val flushCoordinator = LocalSettingsFlushCoordinator.current
    val registrationKey = remember { Any() }
    var lastSubmittedValue by remember { mutableStateOf<String?>(null) }
    var lastExternalValue by remember { mutableStateOf(persistedValue) }
    val value = fieldValue.value.text
    val latestValue by rememberUpdatedState(value)
    val latestPersistedValue by rememberUpdatedState(persistedValue)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestCanCommit by rememberUpdatedState(canCommit)

    // 只在没有本地草稿/在途纠正写时接收真正的外部变化；旧写入的回流不能打断 IME。
    LaunchedEffect(persistedValue) {
        val localValue = fieldValue.value.text
        if (persistedValue == lastSubmittedValue && localValue == lastSubmittedValue) {
            lastSubmittedValue = null
        }
        if (
            lastSubmittedValue == null &&
            localValue == lastExternalValue &&
            persistedValue != localValue
        ) {
            fieldValue.value = TextFieldValue(
                text = persistedValue,
                selection = TextRange(persistedValue.length),
            )
        }
        lastExternalValue = persistedValue
    }

    LaunchedEffect(value, persistedValue) {
        if (canCommit(value) && needsTextCommit(value, persistedValue, lastSubmittedValue)) {
            delay(TEXT_SETTING_DEBOUNCE_MILLIS)
            val pendingValue = latestValue
            if (
                latestCanCommit(pendingValue) &&
                needsTextCommit(pendingValue, latestPersistedValue, lastSubmittedValue)
            ) {
                lastSubmittedValue = pendingValue
                latestCommit(pendingValue)
            }
        }
    }
    DisposableEffect(flushCoordinator, registrationKey) {
        val flush = {
            val pendingValue = latestValue
            // debounce 提交没有异步成功回执。离页/刷新时只相信持久化回流；若上次写入失败，
            // 即使值等于 lastSubmittedValue 也必须允许重试，不能永久丢掉本地草稿。
            if (latestCanCommit(pendingValue) && needsTextFlush(pendingValue, latestPersistedValue)) {
                lastSubmittedValue = pendingValue
                latestCommit(pendingValue)
            }
        }
        flushCoordinator.register(registrationKey, flush)
        onDispose {
            flush()
            flushCoordinator.unregister(registrationKey)
        }
    }
}

internal fun needsTextCommit(value: String, persistedValue: String, lastSubmittedValue: String?): Boolean =
    value != lastSubmittedValue && (value != persistedValue || lastSubmittedValue != null)

internal fun needsTextFlush(value: String, persistedValue: String): Boolean = value != persistedValue

@Composable
internal fun quietSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)
