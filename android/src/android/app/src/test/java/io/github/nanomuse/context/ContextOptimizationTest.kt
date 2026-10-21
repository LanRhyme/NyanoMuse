package io.github.nanomuse.context

import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.chat.ChatViewModel
import io.github.nanomuse.memory.MemoryCandidate
import io.github.nanomuse.memory.MemoryEntity
import io.github.nanomuse.memory.MemoryScorer
import io.github.nanomuse.memory.MemoryTokenizer
import io.github.nanomuse.memory.MemoryMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOptimizationTest {

    @Test
    fun testQueryAwareCandidateRankingPrioritizesTopicRelevantMemories() {
        val candidates = listOf(
            MemoryCandidate(
                id = "mem_pinned",
                content = "偏好极简回答，禁止使用表情符号",
                searchText = MemoryTokenizer.tokenizeForSearch("偏好极简回答，禁止使用表情符号"),
                isPinned = true,
                importance = 0.9f,
                updatedAt = 1000L
            ),
            MemoryCandidate(
                id = "mem_arch",
                content = "主力操作系统为 Arch Linux，合成器使用 Niri",
                searchText = MemoryTokenizer.tokenizeForSearch("主力操作系统为 Arch Linux，合成器使用 Niri"),
                isPinned = false,
                importance = 0.8f,
                updatedAt = 2000L
            ),
            MemoryCandidate(
                id = "mem_coffee",
                content = "平时最喜欢喝无糖生椰拿铁",
                searchText = MemoryTokenizer.tokenizeForSearch("平时最喜欢喝无糖生椰拿铁"),
                isPinned = false,
                importance = 0.7f,
                updatedAt = 3000L
            )
        )

        // 1. Query for drinks -> latte must be top match
        val drinkMatches = MemoryScorer.rankCandidates("想喝点热饮", candidates)
        assertTrue(drinkMatches.isNotEmpty())
        assertEquals("mem_coffee", drinkMatches[0].item.id)

        // 2. Query for OS -> Arch Linux must be top match
        val osMatches = MemoryScorer.rankCandidates("电脑系统配置", candidates)
        assertTrue(osMatches.isNotEmpty())
        assertEquals("mem_arch", osMatches[0].item.id)

        // 3. Pinned candidates have quality multiplier boost
        val pinnedCandidates = candidates.filter { it.isPinned }
        assertEquals(1, pinnedCandidates.size)
        assertEquals("mem_pinned", pinnedCandidates[0].id)
    }

    @Test
    fun testSelectMemoriesForPromptPinnedCappingAndFallback() {
        // Construct 10 pinned memories
        val memories = (1..10).map { i ->
            MemoryEntity(
                id = "pin_$i",
                content = "Pinned memory rule #$i",
                searchText = "",
                category = "preference",
                source = "person",
                isPinned = true,
                isArchived = false,
                importance = 0.9f,
                useCount = 0,
                lastUsedAt = 0L,
                confidence = 1f,
                createdAt = 1000L + i,
                updatedAt = 1000L + i,
                deleted = false
            )
        }

        val selected = MemoryRepository.selectMemoriesForPrompt(
            hotMemories = memories,
            cleanQuery = null,
            searchFn = null
        )

        // Must be capped strictly at MAX_PINNED_MEMORIES (6), unselected pinned items must NOT leak via fallback
        assertEquals(MemoryRepository.MAX_PINNED_MEMORIES, selected.size)
        assertEquals("pin_1", selected[0].id)
        assertEquals("pin_6", selected[5].id)
    }

    @Test
    fun testSelectMemoriesForPromptQueryRecallAndBudget() {
        val memPinned = MemoryEntity(
            id = "mem_pinned",
            content = "偏好极简回答，禁止使用表情符号",
            searchText = MemoryTokenizer.tokenizeForSearch("偏好极简回答，禁止使用表情符号"),
            category = "preference",
            source = "person",
            isPinned = true,
            isArchived = false,
            importance = 0.9f,
            useCount = 0,
            lastUsedAt = 0L,
            confidence = 1f,
            createdAt = 1000L,
            updatedAt = 1000L,
            deleted = false
        )
        val memCoffee = MemoryEntity(
            id = "mem_coffee",
            content = "平时最喜欢喝无糖生椰拿铁",
            searchText = MemoryTokenizer.tokenizeForSearch("平时最喜欢喝无糖生椰拿铁"),
            category = "preference",
            source = "person",
            isPinned = false,
            isArchived = false,
            importance = 0.7f,
            useCount = 0,
            lastUsedAt = 0L,
            confidence = 1f,
            createdAt = 2000L,
            updatedAt = 2000L,
            deleted = false
        )
        val memArch = MemoryEntity(
            id = "mem_arch",
            content = "主力操作系统为 Arch Linux，合成器使用 Niri",
            searchText = MemoryTokenizer.tokenizeForSearch("主力操作系统为 Arch Linux，合成器使用 Niri"),
            category = "device",
            source = "person",
            isPinned = false,
            isArchived = false,
            importance = 0.8f,
            useCount = 0,
            lastUsedAt = 0L,
            confidence = 1f,
            createdAt = 3000L,
            updatedAt = 3000L,
            deleted = false
        )

        val hot = listOf(memPinned, memCoffee, memArch)

        // Mock searchFn that finds mem_arch for "电脑系统"
        val selected = MemoryRepository.selectMemoriesForPrompt(
            hotMemories = hot,
            cleanQuery = "电脑系统",
            searchFn = { query, limit ->
                val candidateArch = MemoryCandidate(
                    id = memArch.id,
                    content = memArch.content,
                    searchText = memArch.searchText,
                    isPinned = memArch.isPinned,
                    importance = memArch.importance,
                    updatedAt = memArch.updatedAt
                )
                listOf(MemoryMatch(candidateArch, 1.5f, 1))
            },
            resolveEntityFn = { id -> hot.find { it.id == id } }
        )

        // Pinned comes first, query match comes second
        assertEquals(3, selected.size)
        assertEquals("mem_pinned", selected[0].id)
        assertEquals("mem_arch", selected[1].id)
        assertEquals("mem_coffee", selected[2].id)

        // Test character budget truncation
        val budgetSelected = MemoryRepository.selectMemoriesForPrompt(
            hotMemories = hot,
            cleanQuery = null,
            searchFn = null,
            maxTotalChars = 20 // Strict char ceiling: only first item (17 chars) fits; second (12 chars) exceeds 20
        )
        assertEquals(1, budgetSelected.size)
        assertEquals("mem_pinned", budgetSelected[0].id)
    }

    @Test
    fun testStructuredCompactionPromptHeadingsDefinition() {
        // Verify real ChatViewModel COMPACT_SUMMARY_SYSTEM_PROMPT contains all 4 required headings
        val prompt = ChatViewModel.COMPACT_SUMMARY_SYSTEM_PROMPT
        assertNotNull(prompt)
        assertTrue(prompt.isNotEmpty())

        val requiredHeadings = ChatViewModel.COMPACT_REQUIRED_HEADINGS
        assertEquals(4, requiredHeadings.size)

        for (heading in requiredHeadings) {
            assertTrue("Expected heading $heading formatted as '### ...'", heading.startsWith("### "))
            assertTrue("Prompt must contain heading $heading", prompt.contains(heading))
        }
    }
}
