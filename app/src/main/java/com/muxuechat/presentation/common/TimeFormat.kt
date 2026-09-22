package com.muxuechat.presentation.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatConversationTime(timestampMillis: Long): String {
    // Locale 与时区都可能在进程存活期间变化；同时避免跨线程复用非线程安全的 SimpleDateFormat。
    val locale = Locale.getDefault()
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return format(timestampMillis, "HH:mm", locale)
    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天 " + format(timestampMillis, "HH:mm", locale)
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val pattern = if (sameYear) "MM-dd" else "yyyy-MM-dd"
    return format(timestampMillis, pattern, locale)
}

private fun format(timestampMillis: Long, pattern: String, locale: Locale): String =
    SimpleDateFormat(pattern, locale).format(Date(timestampMillis))
