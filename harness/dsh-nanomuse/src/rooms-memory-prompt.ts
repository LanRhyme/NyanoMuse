import type { MemoryItem } from './client/rooms.ts'

export const MEMORY_PROMPT_LIMITS = {
  MAX_PINNED_COUNT: 6,
  MAX_PINNED_CHARS: 800,
  MAX_TOTAL_COUNT: 8,
  MAX_TOTAL_CHARS: 2000,
} as const

export interface SelectedMemoriesResult {
  items: MemoryItem[]
  lines: string[]
  injectedCount: number
}

/**
 * Pure memory selector for system prompt injection.
 * Tier 3: Query-Aware Attention Injection
 * - Stable Pinned Passport (capped by count and chars to prevent prompt attention dilution)
 * - Intent-driven Top-K dynamic recall when query is active
 * - Fallback to recent hot memories up to MAX_TOTAL_COUNT
 * - Character budget ceiling (MAX_TOTAL_CHARS)
 */
/**
 * The person's latest message, read from the live session history at assembly time.
 *
 * The event-driven activity record is one turn stale (measured on the device: the
 * `user/message` session event lands after this turn's prompt assembly), so the truth
 * is the session log. The agent loop appends its own user-role snapshots after the
 * person's line (the runtime context, source kind "runtime-context"), so only a
 * message whose source is the human counts.
 */
export function latestUserQuery(history: readonly { role?: string; content?: unknown; source?: { kind?: string } }[] | undefined): string {
  if (!history?.length) return ''
  for (let i = history.length - 1; i >= 0 && i >= history.length - 20; i--) {
    const m = history[i]
    if (m?.role !== 'user' || m.source?.kind !== 'user') continue
    const text = typeof m.content === 'string' ? m.content : textOfContent(m.content)
    const line = text.replace(/\s+/g, ' ').trim()
    if (line) return line
  }
  return ''
}

/** Text of a content-block list, tolerant of the shapes the harness hands over. */
function textOfContent(content: unknown): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .map((block) => (block && typeof block === 'object' && 'type' in block && (block as { type?: string }).type === 'text' && 'text' in block && typeof (block as { text?: unknown }).text === 'string' ? (block as { text: string }).text : ''))
    .filter(Boolean)
    .join('\n')
    .trim()
}

export function selectMemoriesForPrompt(
  memory: readonly MemoryItem[],
  query?: string | null,
  searchFn?: (query: string, options?: { limit?: number; includeArchived?: boolean }) => Array<{ item: { id: string; updated_at: number; content: string; source: string; is_pinned: number | boolean } }>,
  limits = MEMORY_PROMPT_LIMITS,
): SelectedMemoriesResult {
  const cleanQuery = query?.trim() ?? ''
  const chosenItems: MemoryItem[] = []
  const chosenIds = new Set<string>()

  // 1. Stable Pinned Passport (capped by count and char budget)
  let pinnedChars = 0
  for (const m of memory.filter((item) => item.is_pinned)) {
    if (chosenItems.length >= limits.MAX_PINNED_COUNT || pinnedChars >= limits.MAX_PINNED_CHARS) break
    if (pinnedChars + m.text.length > limits.MAX_PINNED_CHARS && chosenItems.length > 0) continue
    chosenItems.push(m)
    chosenIds.add(m.id)
    pinnedChars += m.text.length
  }

  // 2. Query-Aware Dynamic Top-K: If query is available, search relevant memories
  if (cleanQuery && searchFn) {
    try {
      const matches = searchFn(cleanQuery, { limit: limits.MAX_TOTAL_COUNT, includeArchived: false })
      for (const match of matches) {
        if (chosenItems.length >= limits.MAX_TOTAL_COUNT) break
        if (!chosenIds.has(match.item.id)) {
          chosenIds.add(match.item.id)
          const existing = memory.find((m) => m.id === match.item.id)
          chosenItems.push(
            existing ?? {
              id: match.item.id,
              at: match.item.updated_at,
              text: match.item.content,
              source: (match.item.source === 'person' || match.item.source === 'import' || match.item.source === 'extracted' ? match.item.source : 'agent') as MemoryItem['source'],
              is_pinned: Boolean(match.item.is_pinned),
            },
          )
        }
      }
    } catch {
      // ignore search failures gracefully
    }
  }

  // 3. Fallback: If still under quota, fill with newest unpinned hot memories
  for (const item of memory) {
    if (chosenItems.length >= limits.MAX_TOTAL_COUNT) break
    if (!item.is_pinned && !chosenIds.has(item.id)) {
      chosenIds.add(item.id)
      chosenItems.push(item)
    }
  }

  // 4. Budget cap and format lines
  const lines: string[] = []
  let charCount = 0
  let injectedCount = 0
  for (const item of chosenItems) {
    if (charCount >= limits.MAX_TOTAL_CHARS) break
    if (charCount + item.text.length > limits.MAX_TOTAL_CHARS && injectedCount > 0) continue
    lines.push(`- [${item.id}] ${item.text}`)
    charCount += item.text.length + item.id.length + 4
    injectedCount++
  }

  if (memory.length > injectedCount) {
    lines.push(`(${memory.length - injectedCount} more memories in store, search with recall_memory)`)
  }

  return { items: chosenItems.slice(0, injectedCount), lines, injectedCount }
}
