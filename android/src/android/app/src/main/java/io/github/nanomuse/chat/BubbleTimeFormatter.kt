package io.github.nanomuse.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object BubbleTimeFormatter {
    private val bubbleTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Short time for chat bubbles: "16:05"
     */
    fun formatBubbleTime(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return runCatching {
            Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(bubbleTimeFormatter)
        }.getOrDefault("")
    }

    /**
     * Full date, weekday, and time for model prompt perception: "[2026-10-22 周四 16:05]" or "[2026-10-22 Thu 16:05]"
     */
    fun formatUserPromptTimestamp(epochMs: Long, locale: Locale = Locale.getDefault()): String {
        if (epochMs <= 0L) return ""
        return runCatching {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", locale)
            val formatted = Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(formatter)
            "[$formatted]"
        }.getOrDefault("")
    }
}
