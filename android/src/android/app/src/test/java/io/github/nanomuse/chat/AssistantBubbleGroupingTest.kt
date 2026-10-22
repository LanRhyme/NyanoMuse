package io.github.nanomuse.chat

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.chat.buildFlatChatItems
import io.github.nanomuse.ui.chat.isBareBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBubbleGroupingTest {

    @Test
    fun `a single assistant markdown turn stays in one bubble across lists headings and code blocks`() {
        val md = """
            - SOUL.md: persona settings
            - Memory system: `/var/minis/memory/`

            ### One design detail worth noting

            Each chat session has its own isolated storage:

            ```text
            /var/minis/
            |-- attachments/
            |-- shared/
            ```
        """.trimIndent()

        val msg = ChatMessage(
            id = "a-1",
            role = "assistant",
            content = md,
            isStreaming = false,
            toolBlocks = listOf(
                AssistantBlock(id = "b-1", kind = "text", content = md),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(1, mdBlocks.size)
        assertEquals(md, mdBlocks.single().rawText)
        assertEquals(0, mdBlocks.single().blockIndex)
        assertTrue(mdBlocks.single().isLastBlockOfMessage)
        assertFalse(isBareBlock(mdBlocks.single().rawText))
    }

    @Test
    fun `streaming assistant markdown turn also stays in one bubble`() {
        val md = "First paragraph\n\n### Heading\n\n```bash\nls -la\n```"
        val msg = ChatMessage(
            id = "a-stream",
            role = "assistant",
            content = md,
            isStreaming = true,
            toolBlocks = listOf(
                AssistantBlock(id = "b-stream", kind = "text", content = md),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(1, mdBlocks.size)
        assertEquals(md, mdBlocks.single().rawText)
        assertTrue(mdBlocks.single().messageIsStreaming)
    }

    @Test
    fun `assistant text before and after a tool call remain separate bubbles`() {
        val before = "Let me check the directory structure first."
        val after = "Here is what I found:\n\n### Summary\n\n- Item A\n- Item B"
        val msg = ChatMessage(
            id = "a-2",
            role = "assistant",
            content = "$before\n\n$after",
            isStreaming = false,
            toolBlocks = listOf(
                AssistantBlock(id = "t-before", kind = "text", content = before),
                AssistantBlock(
                    id = "tool-1",
                    kind = "tool_use",
                    toolName = "bash",
                    toolArgs = "{\"command\":\"ls\"}",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ),
                AssistantBlock(id = "t-after", kind = "text", content = after),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, mdBlocks.size)
        assertEquals(before, mdBlocks[0].rawText)
        assertFalse(mdBlocks[0].isLastBlockOfMessage)
        assertEquals(after, mdBlocks[1].rawText)
        assertTrue(mdBlocks[1].isLastBlockOfMessage)
    }

    @Test
    fun `thinking followed by text fuses into the same assistant markdown bubble as leadingThinking`() {
        val think = "Analyzing user intent and project structure..."
        val text = "Here is the plan to optimize the UI."
        val msg = ChatMessage(
            id = "a-think-fuse",
            role = "assistant",
            content = text,
            isStreaming = false,
            toolBlocks = listOf(
                AssistantBlock(id = "th-1", kind = "thinking", content = think),
                AssistantBlock(id = "tx-1", kind = "text", content = text),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val thinkingItems = items.filterIsInstance<FlatChatItem.AssistantThinking>()
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertEquals(0, thinkingItems.size)
        assertEquals(1, mdBlocks.size)
        assertEquals(text, mdBlocks.single().rawText)
        assertEquals("th-1", mdBlocks.single().leadingThinking?.id)
        assertEquals(think, mdBlocks.single().leadingThinking?.content)
    }

    @Test
    fun `streaming thinking without text yet emits standalone AssistantThinking`() {
        val think = "Live stream reasoning..."
        val msg = ChatMessage(
            id = "a-think-stream",
            role = "assistant",
            content = "",
            isStreaming = true,
            toolBlocks = listOf(
                AssistantBlock(id = "th-live", kind = "thinking", content = think),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val thinkingItems = items.filterIsInstance<FlatChatItem.AssistantThinking>()
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertEquals(1, thinkingItems.size)
        assertEquals(0, mdBlocks.size)
        assertEquals("th-live", thinkingItems.single().block.id)
    }

    @Test
    fun `isBareBlock only unwraps standalone nanomuse protocol fences`() {
        assertTrue(isBareBlock("```nanomuse-naming\n{\"agent_name\":\"Pip\"}\n```"))
        assertTrue(isBareBlock("  ```nanomuse-goal\n{\"title\":\"Read\"}\n```  "))
        assertFalse(isBareBlock("```python\nprint(1)\n```"))
        assertFalse(isBareBlock("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertFalse(isBareBlock("Hello\n\n```nanomuse-goal\n{\"title\":\"Read\"}\n```"))
    }

    @Test
    fun `delimiter splits assistant text into clustered bubbles`() {
        val raw = "Hello!<nm:bubble/>How can I help you today?"
        val msg = ChatMessage(
            id = "a-multi",
            role = "assistant",
            content = raw,
            isStreaming = false,
            toolBlocks = listOf(
                AssistantBlock(id = "b-multi", kind = "text", content = raw),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, mdBlocks.size)

        assertEquals("Hello!", mdBlocks[0].rawText)
        assertEquals(0, mdBlocks[0].clusterIndex)
        assertEquals(2, mdBlocks[0].clusterSize)
        assertEquals(0, mdBlocks[0].blockIndex)
        assertFalse(mdBlocks[0].isLastBlockOfMessage)

        assertEquals("How can I help you today?", mdBlocks[1].rawText)
        assertEquals(1, mdBlocks[1].clusterIndex)
        assertEquals(2, mdBlocks[1].clusterSize)
        assertEquals(1, mdBlocks[1].blockIndex)
        assertTrue(mdBlocks[1].isLastBlockOfMessage)
    }

    @Test
    fun `delimiter caps bubbles to maximum 3`() {
        val raw = "Bubble 1<nm:bubble/>Bubble 2<nm:bubble/>Bubble 3<nm:bubble/>Bubble 4"
        val msg = ChatMessage(
            id = "a-cap",
            role = "assistant",
            content = raw,
            isStreaming = false,
            toolBlocks = listOf(
                AssistantBlock(id = "b-cap", kind = "text", content = raw),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(3, mdBlocks.size)
        assertEquals("Bubble 1", mdBlocks[0].rawText)
        assertEquals(0, mdBlocks[0].clusterIndex)
        assertEquals(3, mdBlocks[0].clusterSize)

        assertEquals("Bubble 2", mdBlocks[1].rawText)
        assertEquals(1, mdBlocks[1].clusterIndex)
        assertEquals(3, mdBlocks[1].clusterSize)

        assertEquals("Bubble 3\n\nBubble 4", mdBlocks[2].rawText)
        assertEquals(2, mdBlocks[2].clusterIndex)
        assertEquals(3, mdBlocks[2].clusterSize)
        assertTrue(mdBlocks[2].isLastBlockOfMessage)
    }

    @Test
    fun `streaming tail preserves trailing empty bubble`() {
        val raw = "Thinking out loud...<nm:bubble/>"
        val msg = ChatMessage(
            id = "a-stream-tail",
            role = "assistant",
            content = raw,
            isStreaming = true,
            toolBlocks = listOf(
                AssistantBlock(id = "b-stream-tail", kind = "text", content = raw),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, mdBlocks.size)
        assertEquals("Thinking out loud...", mdBlocks[0].rawText)
        assertEquals(0, mdBlocks[0].clusterIndex)
        assertFalse(mdBlocks[0].isStreaming)

        assertEquals("", mdBlocks[1].rawText)
        assertEquals(1, mdBlocks[1].clusterIndex)
        assertTrue(mdBlocks[1].isStreaming)
        assertTrue(mdBlocks[1].isLastBlockOfMessage)
    }

    @Test
    fun `thinking with delimiter assigns leadingThinking only to the first bubble`() {
        val think = "Reasoning..."
        val raw = "Part 1<nm:bubble/>Part 2"
        val msg = ChatMessage(
            id = "a-think-multi",
            role = "assistant",
            content = raw,
            isStreaming = false,
            toolBlocks = listOf(
                AssistantBlock(id = "th-m", kind = "thinking", content = think),
                AssistantBlock(id = "tx-m", kind = "text", content = raw),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, mdBlocks.size)
        assertEquals("th-m", mdBlocks[0].leadingThinking?.id)
        assertEquals(null, mdBlocks[1].leadingThinking)
    }

    @Test
    fun `createdAt timestamp is preserved on assistant markdown blocks`() {
        val stamp = 1792656300000L
        val msg = ChatMessage(
            id = "a-time",
            role = "assistant",
            content = "Time test",
            createdAt = stamp,
            toolBlocks = listOf(
                AssistantBlock(id = "tx-time", kind = "text", content = "Time test"),
            ),
        )

        val items = buildFlatChatItems(listOf(msg))
        val mdBlock = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().single()
        assertEquals(stamp, mdBlock.createdAt)
    }
}
