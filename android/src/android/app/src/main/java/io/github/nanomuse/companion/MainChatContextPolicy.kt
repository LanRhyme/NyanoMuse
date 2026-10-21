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
    // Keeps ~16,000 tokens of high-fidelity recent history (approx 20-30 conversational turns)
    const val DEFAULT_WINDOW_TOKEN_BUDGET = 16_000
    const val MIN_KEEP_MESSAGES = 10

    /**
     * Estimates tokens for an LLMMessage conservatively (approx 1 token ~= 4 chars or CJK char ~= 1 token).
     */
    fun estimateTokens(message: LLMMessage): Int {
        var len = message.content.length
        for (part in message.contentParts) {
            when (part) {
                is AgentContentPart.Text -> len += part.text.length
                is AgentContentPart.ToolResult -> len += part.content.length
                else -> {}
            }
        }
        return maxOf(1, len / 3)
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

        val effectiveBudget = minOf(tokenBudget, contextWindow / 2)
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
