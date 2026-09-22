package com.muxuechat.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 间距、形状与触控目标的统一口径。
 *
 * 之前每个组件各写一套 10.dp / 12.dp / 14.dp / 16.dp / 18.dp / 22.dp，
 * 结果是「到处都是尺寸略有差异的圆角卡片」。这里把可用值收敛成很少的几档：
 * 界面的层次靠留白与分隔线表达，而不是靠给每一块内容套一个卡片。
 */
object Spacing {
    /** 紧邻的一对元素，例如标题与其副文案。 */
    val Tight = 4.dp

    /** 同一组内的元素间距。 */
    val Small = 8.dp

    /** 控件内部的横向留白。 */
    val Medium = 12.dp

    /** 页面横向边距，也是行内容的标准内缩。 */
    val Content = 16.dp

    /** 组与组之间。 */
    val Section = 24.dp
}

/**
 * 只保留三档圆角。
 *
 * - [Control]：按钮、输入框、行选项等可点区域。
 * - [Container]：把一组内容围起来的容器，例如输入栏、抽屉里的卡片。
 * - [Bubble]：用户消息气泡，唯一一个刻意不对称的形状。
 */
object Corner {
    val Control = RoundedCornerShape(10.dp)
    val Container = RoundedCornerShape(14.dp)
    val Bubble = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = 14.dp,
        bottomEnd = 4.dp,
    )
}

/**
 * 触控目标下限。
 *
 * 规范要求不小于 44–48dp；这里统一取 48dp，图标按钮取 [MinIconTouchTarget]。
 * 不要在组件里再写别的数字。
 */
val MinTouchTarget = 48.dp
val MinIconTouchTarget = 48.dp
