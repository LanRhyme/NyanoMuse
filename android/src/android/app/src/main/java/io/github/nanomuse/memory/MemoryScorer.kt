package io.github.nanomuse.memory

import kotlin.math.abs
import kotlin.math.max

data class MemoryCandidate(
    val id: String,
    val content: String,
    val searchText: String? = null,
    val isPinned: Boolean = false,
    val importance: Float = 0.5f,
    val updatedAt: Long = 0L
)

data class MemoryMatch(
    val item: MemoryCandidate,
    val score: Float,
    val hitCount: Int
)

object MemoryScorer {
    fun scoreCandidate(
        query: String,
        queryTokens: List<String>,
        candidate: MemoryCandidate,
        now: Long = System.currentTimeMillis()
    ): MemoryMatch {
        val content = candidate.content.lowercase()
        val candidateTokenStr = candidate.searchText ?: MemoryTokenizer.tokenizeForSearch(candidate.content)
        val candidateTokenSet = candidateTokenStr.split(Regex("\\s+")).toSet()

        var exactHits = 0
        var synonymHits = 0
        for (token in queryTokens) {
            if (candidateTokenSet.contains(token)) {
                exactHits++
            } else {
                val syns = MemoryTokenizer.getSynonyms(token)
                for (s in syns) {
                    val sTokens = MemoryTokenizer.tokenizeForSearch(s).split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (sTokens.any { candidateTokenSet.contains(it) } || content.contains(s.lowercase())) {
                        synonymHits++
                        break
                    }
                }
            }
        }

        val effectiveHits = exactHits.toFloat() + (synonymHits.toFloat() * 0.5f)
        val tokenCoverage = if (queryTokens.isNotEmpty()) effectiveHits / queryTokens.size.toFloat() else 0f

        // Exact substring match bonus
        val cleanQuery = query.lowercase().trim()
        val exactBonus = if (cleanQuery.length > 1 && content.contains(cleanQuery)) 0.3f else 0f

        // Relevance base score
        val baseRelevance = tokenCoverage * 0.7f + exactBonus

        // Quality multiplier
        val pinBonus = if (candidate.isPinned) 0.2f else 0f
        val qualityMultiplier = 1f + pinBonus + (candidate.importance * 0.1f)

        // Recency decay (half life: ~30 days)
        val ageMs = max(0L, now - candidate.updatedAt)
        val ageDays = ageMs.toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
        val recencyBoost = max(0.0, 0.05 * (1.0 / (1.0 + ageDays / 30.0))).toFloat()

        val score = (baseRelevance * qualityMultiplier) + recencyBoost

        return MemoryMatch(
            item = candidate,
            score = score,
            hitCount = exactHits + synonymHits
        )
    }

    fun rankCandidates(
        query: String,
        candidates: List<MemoryCandidate>,
        now: Long = System.currentTimeMillis()
    ): List<MemoryMatch> {
        val queryTokenStr = MemoryTokenizer.tokenizeForSearch(query)
        val queryTokens = queryTokenStr.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (queryTokens.isEmpty()) return emptyList()

        val scored = candidates
            .map { scoreCandidate(query, queryTokens, it, now) }
            .filter { it.hitCount > 0 }
            .toMutableList()

        scored.sortWith { a, b ->
            if (abs(b.score - a.score) > 0.0001f) {
                b.score.compareTo(a.score)
            } else {
                val timeDiff = b.item.updatedAt.compareTo(a.item.updatedAt)
                if (timeDiff != 0) timeDiff else a.item.id.compareTo(b.item.id)
            }
        }

        return scored
    }
}
