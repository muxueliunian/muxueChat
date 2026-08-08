package com.muxuechat.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

val MuxueChatTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

/**
 * 按倍率重建 [Typography]。
 *
 * 设计目标：仅缩放字号与行高，不影响字体家族 / 字重 / 字间距。
 * 倍率会被夹紧到 [0.75, 1.5]，避免极端值与系统字体倍率叠加后视觉破损。
 * 默认值（multiplier == 1.0f）会直接返回原 Typography，避免不必要的对象分配。
 */
fun Typography.scaledBy(multiplier: Float): Typography {
    val factor = multiplier.coerceIn(0.75f, 1.5f)
    if (factor == 1.0f) return this
    return copy(
        displayLarge = displayLarge.scaled(factor),
        displayMedium = displayMedium.scaled(factor),
        displaySmall = displaySmall.scaled(factor),
        headlineLarge = headlineLarge.scaled(factor),
        headlineMedium = headlineMedium.scaled(factor),
        headlineSmall = headlineSmall.scaled(factor),
        titleLarge = titleLarge.scaled(factor),
        titleMedium = titleMedium.scaled(factor),
        titleSmall = titleSmall.scaled(factor),
        bodyLarge = bodyLarge.scaled(factor),
        bodyMedium = bodyMedium.scaled(factor),
        bodySmall = bodySmall.scaled(factor),
        labelLarge = labelLarge.scaled(factor),
        labelMedium = labelMedium.scaled(factor),
        labelSmall = labelSmall.scaled(factor),
    )
}

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaled(factor),
    lineHeight = lineHeight.scaled(factor),
)

private fun TextUnit.scaled(factor: Float): TextUnit {
    if (!isSpecified) return this
    // 仅 sp/em 受倍率影响；其他类型保持原样以避免误伤未来扩展。
    return when (type) {
        TextUnitType.Sp -> (value * factor).sp
        else -> this
    }
}
