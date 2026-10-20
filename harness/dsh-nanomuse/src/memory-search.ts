/**
 * Pure search tokenizer and ranking engine for NyanoMuse long-term memory.
 * Shared algorithm between Desktop (TS) and Android (Kotlin) to guarantee
 * 100% identical cross-platform matching and ranking.
 */

// CJK Unicode ranges (Unified Ideographs, Extension A, Compatibility Ideographs)
const CJK_REGEX = /[\u4e00-\u9fa5\u3400-\u4dbf\uf900-\ufaff]/

/**
 * Tokenize text for full-text search indexing.
 * Generates CJK Bigrams and lowercase Latin words separated by spaces.
 */
export function tokenizeForSearch(text: string): string {
  if (!text) return ''
  const clean = text.toLowerCase()
  const tokens: string[] = []

  let i = 0
  while (i < clean.length) {
    const char = clean.charAt(i)
    if (!char) {
      i++
      continue
    }

    // CJK character processing -> generate bigrams
    if (CJK_REGEX.test(char)) {
      const nextChar = clean.charAt(i + 1)
      if (nextChar && CJK_REGEX.test(nextChar)) {
        tokens.push(char + nextChar)
      } else if (i === 0 || !CJK_REGEX.test(clean.charAt(i - 1))) {
        // Isolated single CJK character
        tokens.push(char)
      }
      i++
      continue
    }

    // Unicode Letter / Alphanumeric word processing (excluding CJK)
    if (!CJK_REGEX.test(char) && /[\p{L}\p{N}_]/u.test(char)) {
      let word = ''
      while (i < clean.length) {
        const c = clean.charAt(i)
        if (!c || CJK_REGEX.test(c) || !/[\p{L}\p{N}_\-.]/u.test(c)) break
        word += c
        i++
      }
      const trimmed = word.replace(/^[.\-]+|[.\-]+$/g, '')
      if (trimmed) tokens.push(trimmed)
      continue
    }

    // Whitespace and punctuation
    i++
  }

  return tokens.join(' ')
}

/**
 * Build safe FTS match query string from raw search query.
 * Quotes each token and joins with OR so that any matching token retrieves the candidate,
 * which is then accurately scored and filtered by rankCandidates.
 */
export function buildFtsQuery(query: string): string {
  const tokenized = tokenizeForSearch(query)
  const tokens = tokenized.split(/\s+/).filter((t) => t.length > 0)
  if (!tokens.length) return ''
  // Quote each token to prevent SQLite FTS syntax errors, join with OR
  return tokens.map((t) => `"${t.replace(/"/g, '""')}"`).join(' OR ')
}

export interface CandidateMemory {
  id: string
  content: string
  search_text?: string
  is_pinned?: number
  importance?: number
  updated_at?: number
}

export interface ScoredMatch<T extends CandidateMemory = CandidateMemory> {
  item: T
  score: number
  hitCount: number
}

/**
 * Application-level scoring function for memory retrieval candidates.
 * Operates independently of underlying database FTS engine (FTS4 / FTS5).
 */
export function scoreCandidate<T extends CandidateMemory>(
  query: string,
  queryTokens: string[],
  candidate: T,
  now = Date.now(),
): ScoredMatch<T> {
  const content = candidate.content.toLowerCase()
  const candidateTokenStr = candidate.search_text || tokenizeForSearch(candidate.content)
  const candidateTokenSet = new Set(candidateTokenStr.split(/\s+/))

  let hitCount = 0
  for (const token of queryTokens) {
    if (candidateTokenSet.has(token)) {
      hitCount++
    }
  }

  const tokenCoverage = queryTokens.length > 0 ? hitCount / queryTokens.length : 0

  // Exact substring match bonus
  const cleanQuery = query.toLowerCase().trim()
  const exactBonus = cleanQuery.length > 1 && content.includes(cleanQuery) ? 0.3 : 0

  // Relevance base score
  const baseRelevance = tokenCoverage * 0.7 + exactBonus

  // Quality multiplier (pinned / importance provide up to 30% boost to relevance, never swamping unrelated queries)
  const importance = candidate.importance ?? 0.5
  const qualityMultiplier = 1 + (candidate.is_pinned ? 0.2 : 0) + (importance * 0.1)

  // Recency decay (half life: ~30 days, tiny tie-breaker)
  const ageMs = Math.max(0, now - (candidate.updated_at ?? now))
  const ageDays = ageMs / (1000 * 60 * 60 * 24)
  const recencyBoost = Math.max(0, 0.05 * (1 / (1 + ageDays / 30)))

  const score = (baseRelevance * qualityMultiplier) + recencyBoost

  return {
    item: candidate,
    score,
    hitCount,
  }
}

/**
 * Ranks candidates by application score.
 */
export function rankCandidates<T extends CandidateMemory>(
  query: string,
  candidates: T[],
  now = Date.now(),
): ScoredMatch<T>[] {
  const queryTokenStr = tokenizeForSearch(query)
  const queryTokens = queryTokenStr.split(/\s+/).filter((t) => t.length > 0)
  if (!queryTokens.length) return []

  const scored = candidates
    .map((c) => scoreCandidate(query, queryTokens, c, now))
    .filter((s) => s.hitCount > 0)

  // Sort descending by score, tiebreak by updated_at, then id
  scored.sort((a, b) => {
    if (Math.abs(b.score - a.score) > 0.0001) {
      return b.score - a.score
    }
    const timeDiff = (b.item.updated_at ?? 0) - (a.item.updated_at ?? 0)
    if (timeDiff !== 0) return timeDiff
    return a.item.id.localeCompare(b.item.id)
  })

  return scored
}
