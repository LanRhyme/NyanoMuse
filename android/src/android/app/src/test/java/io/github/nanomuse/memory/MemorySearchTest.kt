package io.github.nanomuse.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemorySearchTest {

    @Test
    fun testTokenizeForSearchMatchesExpectedBigrams() {
        val cjk = "界面偏好低饱和度莫兰迪色系"
        val tokenized = MemoryTokenizer.tokenizeForSearch(cjk)
        assertEquals("界面 面偏 偏好 好低 低饱 饱和 和度 度莫 莫兰 兰迪 迪色 色系", tokenized)

        val mixed = "Arch Linux 与 Niri 合成器 (Wayland)"
        val tokenizedMixed = MemoryTokenizer.tokenizeForSearch(mixed)
        assertTrue(tokenizedMixed.contains("arch"))
        assertTrue(tokenizedMixed.contains("linux"))
        assertTrue(tokenizedMixed.contains("niri"))
        assertTrue(tokenizedMixed.contains("wayland"))
        assertTrue(tokenizedMixed.contains("合成 成器"))
    }

    @Test
    fun testBuildFtsQueryQuotesTokensSafely() {
        val query = "莫兰迪 UI"
        val fts = MemoryTokenizer.buildFtsQuery(query)
        assertEquals("\"莫兰\" OR \"兰迪\" OR \"ui\"", fts)
    }

    @Test
    fun testEvaluationAgainstGoldenDatasetMatchesTopExpectations() {
        // Find golden dataset dynamically from parent directories
        var curr: File? = File(".").canonicalFile
        var fixtureFile: File? = null
        for (step in 0..8) {
            if (curr == null) break
            val target = File(curr, "tests/fixtures/memory/memory_eval_dataset.json")
            if (target.exists()) {
                fixtureFile = target
                break
            }
            curr = curr.parentFile
        }
        requireNotNull(fixtureFile) { "Fixture dataset file not found from ${File(".").canonicalPath}" }

        val root = JSONObject(fixtureFile.readText())
        val factsJson = root.getJSONArray("facts")
        val queriesJson = root.getJSONArray("queries")

        val candidates = mutableListOf<MemoryCandidate>()
        for (i in 0 until factsJson.length()) {
            val f = factsJson.getJSONObject(i)
            candidates.add(
                MemoryCandidate(
                    id = f.getString("id"),
                    content = f.getString("content"),
                    searchText = MemoryTokenizer.tokenizeForSearch(f.getString("content")),
                    isPinned = f.optInt("is_pinned", 0) == 1,
                    importance = f.optDouble("importance", 0.5).toFloat(),
                    updatedAt = f.optLong("created_at", 0L)
                )
            )
        }

        for (i in 0 until queriesJson.length()) {
            val q = queriesJson.getJSONObject(i)
            val queryText = q.getString("query")
            val expectedArray = q.getJSONArray("expected_top")
            val expectedList = (0 until expectedArray.length()).map { expectedArray.getString(it) }

            val results = MemoryScorer.rankCandidates(queryText, candidates, now = 1740001000000L)
            assertTrue("Query '$queryText' should have matched candidates", results.isNotEmpty())

            val topId = results[0].item.id
            assertTrue(
                "Query '$queryText' expected top in $expectedList, got '$topId' (${results[0].item.content})",
                expectedList.contains(topId)
            )
        }
    }
}
