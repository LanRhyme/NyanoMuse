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

    // nanoMuse: 三处放宽都来自真机实测。模型见过各种 XML、Markdown 写法，
    // 只认 <nm:mood feel=".."/> 一种会静默丢掉整轮反馈：带上收尾标签、
    // 用单引号、被包进反引号或代码块，任何一种都收不到。属性名和 id 都不会
    // 跨行断开，所以标签内部允许折行；属性只在前一个字符不是字母时算数，
    // 否则 <nm:mood feedback="x"/> 也会被当成 feel。
    val MOOD_TAG_REGEX = Regex("""<\s*nm:mood\b([^>]*)>\s*(?:<\s*/\s*nm:mood\s*>)?""", RegexOption.IGNORE_CASE)
    private val ATTR_FEEL_REGEX = Regex(
        """(?:^|[^\w])(?:feel|mood|emotion|type)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s/>]+))""",
        RegexOption.IGNORE_CASE
    )
    private val ATTR_REASON_REGEX = Regex(
        """(?:^|[^\w])reason\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s/>]+))""",
        RegexOption.IGNORE_CASE
    )
    // The wrappers a model reaches for around a hidden tag: a bare backtick pair
    // around a single tag, or a fenced block that holds nothing else.
    private val MOOD_FENCE_REGEX =
        Regex("""```[a-zA-Z]*[ \t]*\n?[ \t]*(?:<\s*nm:mood\b[^>]*/\s*>)[ \t]*\n?```""", RegexOption.IGNORE_CASE)
    private val MOOD_BARE_TICK_REGEX =
        Regex("""`\s*(<\s*nm:mood\b[^>]*/\s*>)\s*`""", RegexOption.IGNORE_CASE)

    data class MoodFeedbackRaw(val feel: String, val reason: String?)

    fun extractMoodFeedback(text: CharSequence): MoodFeedbackRaw? {
        val match = MOOD_TAG_REGEX.findAll(text).lastOrNull() ?: return null
        val attrs = match.groupValues.getOrNull(1) ?: return null
        val feelMatch = ATTR_FEEL_REGEX.find(attrs) ?: return null
        val feel = (
            feelMatch.groupValues[1]
                .ifEmpty { feelMatch.groupValues[2] }
                .ifEmpty { feelMatch.groupValues[3] }
        ).trim()
        if (feel.isEmpty()) return null
        val reasonMatch = ATTR_REASON_REGEX.find(attrs)
        val reason = reasonMatch?.let {
            it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { it.groupValues[3] }
        }?.trim()
        return MoodFeedbackRaw(feel = feel, reason = reason)
    }

    fun hasMoodTag(text: CharSequence): Boolean =
        text.contains("<nm:mood", ignoreCase = true)

    fun stripMoodTags(text: String): String {
        // Do not fight a Markdown code block: a fence around ordinary code has to
        // survive untouched. A fence that holds nothing but the mood tag, and a
        // plain backtick pair wrapped around one, are the tag's clothes and come off.
        var out = text
        var guard = 0
        while (guard++ < 8) {
            val next = MOOD_FENCE_REGEX.replace(out, "")
                .let { MOOD_BARE_TICK_REGEX.replace(it, "$1") }
            if (next == out) break
            out = next
        }
        return out.replace(MOOD_TAG_REGEX, "").trimEnd()
    }

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
