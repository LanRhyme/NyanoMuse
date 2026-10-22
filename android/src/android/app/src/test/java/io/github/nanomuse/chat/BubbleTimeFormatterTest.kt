package io.github.nanomuse.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BubbleTimeFormatterTest {

    @Test
    fun `formatBubbleTime formats timestamp into HH mm`() {
        // 2026-10-22 16:05:00 UTC+8 (or local zone)
        val epochMs = 1792656300000L
        val expected = Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

        val actual = BubbleTimeFormatter.formatBubbleTime(epochMs)
        assertEquals(expected, actual)
    }

    @Test
    fun `formatBubbleTime returns empty string for zero or negative timestamps`() {
        assertEquals("", BubbleTimeFormatter.formatBubbleTime(0L))
        assertEquals("", BubbleTimeFormatter.formatBubbleTime(-1L))
    }

    @Test
    fun `formatUserPromptTimestamp produces bracketed date weekday time`() {
        val epochMs = 1792656300000L
        val expected = "[" + Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.SIMPLIFIED_CHINESE)) + "]"

        val actual = BubbleTimeFormatter.formatUserPromptTimestamp(epochMs, Locale.SIMPLIFIED_CHINESE)
        assertEquals(expected, actual)
        assertTrue(actual.startsWith("[2026-"))
        assertTrue(actual.endsWith("]"))
    }

    @Test
    fun `formatUserPromptTimestamp returns empty string for invalid timestamp`() {
        assertEquals("", BubbleTimeFormatter.formatUserPromptTimestamp(0L))
        assertEquals("", BubbleTimeFormatter.formatUserPromptTimestamp(-100L))
    }

    @Test
    fun `stripUserPromptTimestamp strips trailing prompt bracketed timestamp`() {
        val original = "你好呀！\n\n[2026-10-22 周四 16:05]"
        val stripped = BubbleTimeFormatter.stripUserPromptTimestamp(original)
        assertEquals("你好呀！", stripped)

        val withoutTimestamp = "普通消息内容"
        assertEquals(withoutTimestamp, BubbleTimeFormatter.stripUserPromptTimestamp(withoutTimestamp))
    }

    @Test
    fun `countOccurrences accurately counts occurrences without regex`() {
        assertEquals(0, BubbleTimeFormatter.countOccurrences("Hello world", "<nm:bubble/>"))
        assertEquals(1, BubbleTimeFormatter.countOccurrences("Hello<nm:bubble/>world", "<nm:bubble/>"))
        assertEquals(3, BubbleTimeFormatter.countOccurrences("A<nm:bubble/>B<nm:bubble/>C<nm:bubble/>D", "<nm:bubble/>"))
        assertEquals(0, BubbleTimeFormatter.countOccurrences("", "<nm:bubble/>"))
    }
}
