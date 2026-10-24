package io.github.nanomuse.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `bubble tag helpers handle variants and spaces`() {
        assertTrue(BubbleTimeFormatter.hasBubbleTag("Hello<nm:bubble/>world"))
        assertTrue(BubbleTimeFormatter.hasBubbleTag("Hello<nm:bubble />world"))
        assertTrue(BubbleTimeFormatter.hasBubbleTag("Hello<NM:BUBBLE>world"))
        assertFalse(BubbleTimeFormatter.hasBubbleTag("Hello world"))

        assertEquals(2, BubbleTimeFormatter.countBubbleTags("A<nm:bubble/>B<nm:bubble />C"))
        assertEquals(1, BubbleTimeFormatter.countBubbleTags("<nm:bubble>D"))

        val sanitized = BubbleTimeFormatter.sanitizeBubbleTags("A<nm:bubble/>B<nm:bubble />C")
        assertEquals("A\n\nB\n\nC", sanitized)
    }

    // The shapes a model actually produces when it writes the hidden mood tag.
    // Only the first was understood before; the rest silently dropped the whole
    // turn's feedback, which is what left the dashboard frozen on the device.
    @Test
    fun `mood tag is read in every shape a model writes it`() {
        val cases = listOf(
            "晚安，早点睡。<nm:mood feel=\"tired\" reason=\"深夜\"/>",
            "晚安，早点睡。<nm:mood feel=\"tired\" reason=\"深夜\" />",
            "晚安，早点睡。<nm:mood feel=\"tired\" reason=\"深夜\"></nm:mood>",
            "晚安，早点睡。`<nm:mood feel=\"tired\" reason=\"深夜\"/>`",
            "晚安，早点睡。\n```\n<nm:mood feel=\"tired\" reason=\"深夜\"/>\n```",
            "晚安，早点睡。\n<nm:mood feel='tired' reason='深夜'/>",
            "晚安，早点睡。\n<nm:mood\n  feel=\"tired\"\n  reason=\"深夜\"/>",
        )
        for (raw in cases) {
            val feedback = BubbleTimeFormatter.extractMoodFeedback(raw)
            assertTrue("not read: $raw", feedback != null)
            assertEquals("not read: $raw", "tired", feedback!!.feel)
            assertEquals("not read: $raw", "深夜", feedback.reason)
        }
    }

    @Test
    fun `mood tag the model forgot to close is still read`() {
        // A truncated final line: the closing slash never arrived.
        val feedback = BubbleTimeFormatter.extractMoodFeedback("好呀。<nm:mood feel=\"happy\" reason=\"被夸\">")
            ?: return
        assertEquals("happy", feedback.feel)
    }

    @Test
    fun `attribute named like feel is not mistaken for it`() {
        // `feedback` ends in `feel`; matching it would read the wrong value.
        val feedback = BubbleTimeFormatter.extractMoodFeedback("<nm:mood feedback=\"x\"/>")
        assertTrue(feedback == null || feedback.feel != "x")
    }

    // The invariant the multi-bubble bootstrap rests on: the model's own turns are replayed
    // with the mood tag removed but the bubble delimiter KEPT. Erase the delimiter too and
    // the model reads its earlier replies back as plain paragraphs and stops emitting it,
    // which is why splitting used to work only on the first reply of a session.
    @Test
    fun `stripping the mood tag leaves the bubble delimiter in place`() {
        assertEquals(
            "在吗<nm:bubble/>在呀",
            BubbleTimeFormatter.stripMoodTags("在吗<nm:bubble/>在呀<nm:mood feel=\"happy\" reason=\"被叫\"/>")
        )
        assertEquals(
            "第一段<nm:bubble />第二段",
            BubbleTimeFormatter.stripMoodTags("第一段<nm:bubble />第二段<nm:mood feel=\"sulk\"/>")
        )
        // No mood tag at all: the text is returned untouched, delimiter included.
        val plain = "在吗<nm:bubble/>在呀"
        assertEquals(plain, BubbleTimeFormatter.stripMoodTags(plain))
    }

    @Test
    fun `stripping a mood tag removes its wrappers but leaves real code alone`() {
        assertEquals(
            "晚安，早点睡。",
            BubbleTimeFormatter.stripMoodTags("晚安，早点睡。\n`<nm:mood feel=\"tired\"/>`")
        )
        assertEquals(
            "晚安，早点睡。",
            BubbleTimeFormatter.stripMoodTags("晚安，早点睡。\n```\n<nm:mood feel=\"tired\"/>\n```")
        )
        // An ordinary code block must come back untouched.
        val code = "示例：\n```kotlin\nval x = 1\n```"
        assertEquals(code, BubbleTimeFormatter.stripMoodTags(code))
    }
}
