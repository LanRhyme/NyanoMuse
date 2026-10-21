package io.github.nanomuse.companion

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainChatContextPolicyTest {

    @Test
    fun testEmptyHistoryReturnsEmpty() {
        val result = MainChatContextPolicy.applySlidingWindow(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testHistoryUnderBudgetKeptIntact() {
        val messages = (1..5).map { i ->
            LLMMessage(
                role = if (i % 2 == 1) LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,
                content = "Message number $i short content"
            )
        }

        var overflowCalled = false
        val kept = MainChatContextPolicy.applySlidingWindow(
            history = messages,
            contextWindow = 128_000,
            tokenBudget = 10_000,
            onOverflow = { overflowCalled = true }
        )

        assertEquals(5, kept.size)
        assertTrue(!overflowCalled)
    }

    @Test
    fun testHistoryOverBudgetSlicesTailAndInvokesOverflow() {
        // Create 20 large messages
        val messages = (1..20).map { i ->
            LLMMessage(
                role = if (i % 2 == 1) LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,
                content = "Message $i with long text: " + "a".repeat(1000)
            )
        }

        var droppedCount = 0
        val kept = MainChatContextPolicy.applySlidingWindow(
            history = messages,
            contextWindow = 128_000,
            tokenBudget = 2_000, // Small budget that cannot fit all 20
            onOverflow = { dropped -> droppedCount = dropped.size }
        )

        assertTrue(kept.size >= MainChatContextPolicy.MIN_KEEP_MESSAGES)
        assertEquals(20, kept.size + droppedCount)
        assertTrue(droppedCount > 0)
        assertEquals("Message 20", kept.last().content.take(10))
    }
}
