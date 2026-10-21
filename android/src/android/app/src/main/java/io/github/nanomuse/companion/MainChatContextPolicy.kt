package io.github.nanomuse.companion

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * Manages sliding window token budget and distillation triggering for Main Chat.
 * Replaces the destructive compounding compactAll summary loop with clean active sliding window
 * and asynchronous memory distillation into structured memories.
 */
object MainChatContextPolicy {
    private const val TAG = "MainChatCtxPolicy"

    // Default target token budget for Main Chat active sliding window
    // Generous active window dynamically scaling with model capacity (64k - 256k tokens)
    const val DEFAULT_WINDOW_TOKEN_BUDGET = 64_000
    const val MIN_KEEP_MESSAGES = 10

    /**
     * Estimates tokens for a string accurately: CJK character ≈ 1 token, ASCII ≈ 0.25 token.
     */
    fun estimateStringTokens(s: String): Int {
        if (s.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        for (ch in s) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x20000..0x2A6DF || code in 0x3000..0x303F || code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                ascii++
            }
        }
        return cjk + (ascii + 3) / 4
    }

    /**
     * Estimates tokens for an LLMMessage accurately across content and all content parts (Text, ToolResult, ToolUse).
     */
    fun estimateTokens(message: LLMMessage): Int {
        var tokens = estimateStringTokens(message.content)
        for (part in message.contentParts) {
            when (part) {
                is AgentContentPart.Text -> tokens += estimateStringTokens(part.text)
                is AgentContentPart.ToolResult -> tokens += estimateStringTokens(part.content)
                is AgentContentPart.ToolUse -> {
                    tokens += estimateStringTokens(part.name)
                    tokens += estimateStringTokens(part.input.toString())
                }
                else -> {}
            }
        }
        return maxOf(1, tokens)
    }

    /**
     * Slices the history from the tail back to fit within token budget while preserving valid turn structure.
     * Messages pushed out of the window are passed to [onOverflow] for asynchronous distillation.
     */
    fun applySlidingWindow(
        history: List<LLMMessage>,
        contextWindow: Int = 128_000,
        tokenBudget: Int = DEFAULT_WINDOW_TOKEN_BUDGET,
        onOverflow: ((List<LLMMessage>) -> Unit)? = null
    ): List<LLMMessage> {
        if (history.isEmpty()) return emptyList()

        // Modern models support 128k to 1M+ context. Scale active window generously:
        val resolvedBudget = if (tokenBudget != DEFAULT_WINDOW_TOKEN_BUDGET) {
            minOf(tokenBudget, maxOf(2_000, contextWindow / 2))
        } else {
            when {
                contextWindow >= 500_000 -> 256_000
                contextWindow >= 120_000 -> 64_000
                contextWindow >= 60_000 -> 32_000
                else -> maxOf(8_000, contextWindow / 2)
            }
        }
        val effectiveBudget = minOf(resolvedBudget, maxOf(16_000, (contextWindow.toLong() * 3 / 4).toInt()))
        val minKeep = minOf(MIN_KEEP_MESSAGES, history.size)
        var accumulatedTokens = 0
        var keepStartIndex = history.size

        // Walk backwards from tail
        for (i in history.indices.reversed()) {
            val msg = history[i]
            val tokens = estimateTokens(msg)
            val alreadyKept = history.size - keepStartIndex
            if (accumulatedTokens + tokens > effectiveBudget && alreadyKept >= minKeep) {
                break
            }
            accumulatedTokens += tokens
            keepStartIndex = i
        }

        // Avoid starting mid-tool execution
        if (keepStartIndex > 0 && keepStartIndex < history.size) {
            while (keepStartIndex > 0 && history[keepStartIndex].role == LLMMessage.Role.ASSISTANT && history[keepStartIndex - 1].role == LLMMessage.Role.USER) {
                val prevUser = history[keepStartIndex - 1]
                if (prevUser.contentParts.any { it is AgentContentPart.ToolResult }) {
                    keepStartIndex--
                } else {
                    break
                }
            }
        }

        if (keepStartIndex <= 0) {
            return history
        }

        val dropped = history.subList(0, keepStartIndex)
        val kept = history.subList(keepStartIndex, history.size)

        AppLogger.info(TAG, "Main Chat sliding window: total=${history.size} kept=${kept.size} dropped=${dropped.size} keptTokens=$accumulatedTokens")
        onOverflow?.invoke(dropped)

        return kept
    }
}
