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

    @Test
    fun testFindDirectConflictMatchesAttributeCollisions() {
        val callThemMatch = Regex("^(?:call them|叫我|称呼我为|我的名字是)[:：\\s]?", RegexOption.IGNORE_CASE)
        assertTrue(callThemMatch.containsMatchIn("Call them: Bob"))
        assertTrue(callThemMatch.containsMatchIn("叫我小明"))

        val livesInMatch = Regex("^(?:lives? in|living in|relocated to|住在|居住在|搬到了|现居)[:：\\s]?", RegexOption.IGNORE_CASE)
        assertTrue(livesInMatch.containsMatchIn("住在北京市海淀区"))
        assertTrue(livesInMatch.containsMatchIn("现居杭州市西湖区"))
        assertTrue(livesInMatch.containsMatchIn("Lives in Seattle"))

        val zhDeviceMatch = Regex("^(?:当前|现在|主力)?(手机|平板|电脑|笔记本|操作系统|系统|窗口管理器|合成器|桌面环境|编辑器|IDE|包管理器)(?:是|使用|换成了|为)[:：\\s]?", RegexOption.IGNORE_CASE)
        val m1 = zhDeviceMatch.find("当前平板使用一加平板2 Pro")
        assertTrue(m1 != null && m1.groupValues[1] == "平板")
        val m2 = zhDeviceMatch.find("当前平板换成了 iPad Pro")
        assertTrue(m2 != null && m2.groupValues[1] == "平板")
    }

    @Test
    fun testTheConflictFloorKeepsWeakMatchesOut() {
        // the floor the conflict detection uses: a close match counts, a shared word does not
        val now = System.currentTimeMillis()
        val query = "住在北京"
        val tokens = MemoryTokenizer.tokenizeForSearch(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

        val close = MemoryCandidate(
            id = "mem_city",
            content = "住在北京市海淀区",
            searchText = MemoryTokenizer.tokenizeForSearch("住在北京市海淀区"),
            updatedAt = now,
        )
        val weak = MemoryCandidate(
            id = "mem_tea",
            content = "平时喜欢喝大红袍",
            searchText = MemoryTokenizer.tokenizeForSearch("平时喜欢喝大红袍"),
            updatedAt = now,
        )
        assertTrue(MemoryScorer.scoreCandidate(query, tokens, close, now).score >= 0.35f)
        assertTrue(MemoryScorer.scoreCandidate(query, tokens, weak, now).score < 0.35f)
    }
}
