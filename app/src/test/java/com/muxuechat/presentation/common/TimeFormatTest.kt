package com.muxuechat.presentation.common

import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun `formatting observes locale changes while process remains alive`() {
        val originalLocale = Locale.getDefault()
        val timestamp = System.currentTimeMillis()

        try {
            Locale.setDefault(Locale.US)
            val westernDigits = formatConversationTime(timestamp)

            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val arabicDigits = formatConversationTime(timestamp)

            assertNotEquals(westernDigits, arabicDigits)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
