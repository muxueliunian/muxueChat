package com.muxuechat.presentation.common

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 上下文占用达到多少百分比时视为告警。
 *
 * 顶栏图标和底部面板共用同一个阈值——两处各写一个数字迟早会出现「顶栏红了面板没红」。
 */
const val CONTEXT_WARNING_USAGE_PERCENT = 90

/**
 * 紧凑写法：12.4K、945.9K、1M。
 *
 * 主行要一眼看出量级，逐位数字留给容量说明。整数倍不补 `.0`，避免出现「1.0M」这种噪音。
 */
fun formatCompactTokens(tokens: Long): String {
    val value = tokens.coerceAtLeast(0L)
    return when {
        value < 1_000L -> value.toString()
        value < 1_000_000L -> compact(value / 1_000.0, "K")
        else -> compact(value / 1_000_000.0, "M")
    }
}

/**
 * 逐位写法：945,904。
 *
 * 固定用 [Locale.US] 的千分位：这里是给用户核对数字用的，不该随系统语言变成别的分组方式。
 */
fun formatExactTokens(tokens: Long): String =
    String.format(Locale.US, "%,d", tokens.coerceAtLeast(0L))

private fun compact(value: Double, unit: String): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}$unit"
    } else {
        String.format(Locale.US, "%.1f%s", rounded, unit)
    }
}
