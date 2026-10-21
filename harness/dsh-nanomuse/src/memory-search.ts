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

export const EQUIVALENT_SYNONYMS: readonly (readonly string[])[] = [
  // Devices & Hardware
  ['电脑', 'pc', '计算机', '台式机', '主机', '工作站'],
  ['笔记本', 'laptop', 'macbook', 'thinkpad'],
  ['平板', 'tablet', 'pad', 'ipad', '平板电脑'],
  ['手机', 'phone', '移动端', 'smartphone'],

  // Systems, Environments & Tools
  ['操作系统', 'os', '系统'],
  ['桌面环境', '窗口管理器', '合成器', 'wm', 'compositor'],
  ['包管理器', 'packagemanager', '包管理工具'],
  ['编辑器', '开发工具', 'ide', 'editor', '代码编辑器'],

  // Lifestyle & Habits
  ['热饮', '饮品', '饮料', '喝的'],
  ['饮食', '食物', '餐', '吃的'],
  ['偏好', '喜好', '偏爱', '倾向'],
  ['习惯', '日常习惯', '生活习惯'],
  ['住址', '所在地', '居住地', '现居'],
  ['姓名', '名字', '称呼', '叫我', '昵称', 'name'],
  ['代码', '编程', '开发', '写代码', 'coding'],
  ['工作', '职业', '身份', '岗位', '上班', '公司', 'work', 'job'],
]

export const HYPERNYM_EXPANSIONS: Record<string, readonly string[]> = {
  // Category -> Instances
  '热饮': ['拿铁', '咖啡', '美式', '茶', '红茶', '绿茶', '乌龙茶', '奶茶', '可乐', '水'],
  '饮品': ['拿铁', '咖啡', '美式', '茶', '红茶', '绿茶', '乌龙茶', '奶茶', '可乐', '水'],
  '饮料': ['拿铁', '咖啡', '美式', '茶', '红茶', '绿茶', '乌龙茶', '奶茶', '可乐', '水'],
  '喝': ['拿铁', '咖啡', '美式', '茶', '红茶', '绿茶', '乌龙茶', '奶茶', '可乐', '水'],
  '饮食': ['菜', '饭', '面', '早饭', '午饭', '晚饭', '早餐', '午餐', '晚餐', '零食', '甜品'],
  '食物': ['菜', '饭', '面', '早饭', '午饭', '晚饭', '早餐', '午餐', '晚餐', '零食', '甜品'],
  '吃': ['菜', '饭', '面', '早饭', '午饭', '晚饭', '早餐', '午餐', '晚餐', '零食', '甜品'],
  '操作系统': ['linux', 'arch', 'cachyos', 'windows', 'macos', 'android', 'coloros', 'hyperos', 'ios'],
  '系统': ['linux', 'arch', 'cachyos', 'windows', 'macos', 'android', 'coloros', 'hyperos', 'ios'],
  '窗口管理器': ['niri', 'hyprland', 'sway', 'wayland', 'gnome', 'kde', 'x11'],
  '合成器': ['niri', 'hyprland', 'sway', 'wayland'],
  '编辑器': ['neovim', 'nvim', 'vscode', 'cursor', 'zed', 'vim'],
  '包管理器': ['pacman', 'paru', 'aur', 'pnpm', 'npm', 'yarn', 'cargo', 'pip'],
}

const EQUIVALENTS_MAP = new Map<string, readonly string[]>()
for (const cluster of EQUIVALENT_SYNONYMS) {
  for (const term of cluster) {
    EQUIVALENTS_MAP.set(term.toLowerCase(), cluster)
  }
}

export function getSynonyms(term: string): readonly string[] {
  const lower = term.toLowerCase()
  const equivalents = EQUIVALENTS_MAP.get(lower) ?? []
  const hypernyms = HYPERNYM_EXPANSIONS[lower] ?? []
  if (!equivalents.length && !hypernyms.length) return []
  const combined = new Set<string>([...equivalents, ...hypernyms])
  return [...combined]
}

/**
 * Build safe FTS match query string from raw search query.
 * Quotes each token and joins with OR, expanding concept synonyms to enable semantic recall.
 */
export function buildFtsQuery(query: string): string {
  const tokenized = tokenizeForSearch(query)
  const tokens = tokenized.split(/\s+/).filter((t) => t.length > 0)
  if (!tokens.length) return ''

  const querySet = new Set(tokens)

  // Expand tokens with concept synonyms
  for (const token of tokens) {
    const syns = getSynonyms(token)
    for (const s of syns.slice(0, 12)) {
      const synTokenized = tokenizeForSearch(s)
      for (const st of synTokenized.split(/\s+/)) {
        if (st.length > 0) querySet.add(st)
      }
    }
  }

  // Also check raw query words (e.g. multi-character words like "热饮", "电脑")
  const rawWords = query.toLowerCase().split(/[\s,，.。!！?？]+/)
  for (const w of rawWords) {
    const syns = getSynonyms(w)
    for (const s of syns.slice(0, 12)) {
      const synTokenized = tokenizeForSearch(s)
      for (const st of synTokenized.split(/\s+/)) {
        if (st.length > 0) querySet.add(st)
      }
    }
  }

  return [...querySet].map((t) => `"${t.replace(/"/g, '""')}"`).join(' OR ')
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
 * Grants partial semantic bonus for concept synonyms to achieve semantic generalized ranking.
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

  let exactHits = 0
  let synonymHits = 0

  for (const token of queryTokens) {
    if (candidateTokenSet.has(token)) {
      exactHits++
    } else {
      const syns = getSynonyms(token)
      for (const s of syns) {
        const sTokens = tokenizeForSearch(s).split(/\s+/).filter(Boolean)
        if (sTokens.some((st) => candidateTokenSet.has(st)) || content.includes(s.toLowerCase())) {
          synonymHits++
          break
        }
      }
    }
  }

  const effectiveHits = exactHits + (synonymHits * 0.5)
  const tokenCoverage = queryTokens.length > 0 ? effectiveHits / queryTokens.length : 0

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
    hitCount: exactHits + synonymHits,
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
