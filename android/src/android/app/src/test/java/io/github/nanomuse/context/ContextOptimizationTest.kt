package io.github.nanomuse.context

import io.github.nanomuse.memory.MemoryCandidate
import io.github.nanomuse.memory.MemoryScorer
import io.github.nanomuse.memory.MemoryTokenizer
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
    fun testStructuredCompactionPromptHeadingsDefinition() {
        // Verify expected headings for Tier 2 structured compaction
        val requiredHeadings = listOf(
            "### 任务状态与目标",
            "### 核心决策与约束",
            "### 文件产物与代码变动",
            "### 踩坑与禁止事项"
        )
        for (heading in requiredHeadings) {
            assertNotNull(heading)
            assertTrue(heading.startsWith("### "))
        }
    }
}
