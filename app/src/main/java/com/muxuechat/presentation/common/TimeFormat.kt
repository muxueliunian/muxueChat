package com.muxuechat.presentation.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val hourMinute = SimpleDateFormat("HH:mm", Locale.getDefault())
private val monthDay = SimpleDateFormat("MM-dd", Locale.getDefault())
private val fullDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

fun formatConversationTime(timestampMillis: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return hourMinute.format(Date(timestampMillis))
    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天 " + hourMinute.format(Date(timestampMillis))
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    return if (sameYear) monthDay.format(Date(timestampMillis)) else fullDate.format(Date(timestampMillis))
}
