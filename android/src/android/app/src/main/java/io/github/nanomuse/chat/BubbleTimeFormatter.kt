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

    const val NM_BUBBLE_TAG = "<nm:bubble/>"
    val BUBBLE_REGEX = Regex("""<\s*nm:bubble\s*/?>|<\s*/\s*nm:bubble\s*>""", RegexOption.IGNORE_CASE)
    const val BUBBLE_CADENCE_DELAY_MS = 450L
    val USER_PROMPT_TIMESTAMP_REGEX = Regex("""\n*\[\d{4}-\d{2}-\d{2}[^\]]+\]$""")

    fun stripUserPromptTimestamp(text: String): String =
        text.replace(USER_PROMPT_TIMESTAMP_REGEX, "").trimEnd()

    fun hasBubbleTag(text: CharSequence): Boolean =
        text.contains("<nm:bubble", ignoreCase = true) || text.contains("</nm:bubble>", ignoreCase = true)

    fun countBubbleTags(text: CharSequence): Int {
        var count = 0
        var idx = 0
        val target = "<nm:bubble"
        while (true) {
            val next = text.indexOf(target, idx, ignoreCase = true)
            if (next < 0) break
            count++
            idx = next + target.length
        }
        return count
    }

    fun sanitizeBubbleTags(text: String): String =
        text.replace(BUBBLE_REGEX, "\n\n")

    fun countOccurrences(text: CharSequence, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val next = text.indexOf(target, idx)
            if (next < 0) break
            count++
            idx = next + target.length
        }
        return count
    }
}
