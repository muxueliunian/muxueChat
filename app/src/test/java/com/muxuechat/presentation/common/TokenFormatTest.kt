package com.muxuechat.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenFormatTest {

    @Test
    fun smallValues_areShownAsIs() {
        assertEquals("0", formatCompactTokens(0L))
        assertEquals("999", formatCompactTokens(999L))
    }

    @Test
    fun thousands_useOneDecimal() {
        assertEquals("12.4K", formatCompactTokens(12_400L))
        assertEquals("945.9K", formatCompactTokens(945_904L))
    }

    /** 整数倍不补 `.0`，避免主行出现「950.0K」这种噪音。 */
    @Test
    fun exactMultiples_dropTheDecimal() {
        assertEquals("1K", formatCompactTokens(1_000L))
        assertEquals("950K", formatCompactTokens(950_000L))
        assertEquals("1M", formatCompactTokens(1_000_000L))
    }

    @Test
    fun millions_useOneDecimal() {
        assertEquals("1.5M", formatCompactTokens(1_500_000L))
    }

    /** 负数只可能来自计算错误，不该以「-」开头吓到用户。 */
    @Test
    fun negativeValues_areClampedToZero() {
        assertEquals("0", formatCompactTokens(-1L))
        assertEquals("0", formatExactTokens(-1L))
    }

    /** 千分位固定用 Locale.US，不跟随系统语言变成别的分组方式。 */
    @Test
    fun exactValues_useGroupedDigits() {
        assertEquals("945,904", formatExactTokens(945_904L))
        assertEquals("4,096", formatExactTokens(4_096L))
        assertEquals("32,768", formatExactTokens(32_768L))
    }

    /** 顶栏和底部面板必须共用同一条告警线。 */
    @Test
    fun warningThreshold_isNinetyPercent() {
        assertEquals(90, CONTEXT_WARNING_USAGE_PERCENT)
    }
}
