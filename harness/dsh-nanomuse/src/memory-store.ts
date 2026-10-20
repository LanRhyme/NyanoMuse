/**
 * Persistent SQLite memory store using Node.js built-in `node:sqlite`.
 * Works out-of-the-box in Node 22+ and Electron 44+ with zero native compilation dependencies.
 */
import { DatabaseSync } from 'node:sqlite'
import { tokenizeForSearch, buildFtsQuery, rankCandidates, type CandidateMemory, type ScoredMatch } from './memory-search.ts'

export interface MemoryRecord extends CandidateMemory {
  id: string
  content: string
  search_text: string
  category: 'preference' | 'habit' | 'profile' | 'device' | 'custom'
  source: 'person' | 'agent' | 'import' | 'extracted' | 'tool'
  is_pinned: number // 0 or 1
  is_archived: number // 0 or 1
  importance: number // 0.0 ~ 1.0
  use_count: number
  last_used_at: number
  confidence: number
  created_at: number
  updated_at: number
  deleted: number // 0 or 1
}

export interface EpisodeRecord {
  id: string
  thread_id: string
  start_time: number
  end_time: number
  summary: string
  search_text: string
  updated_at: number
}

export class MemoryStore {
  readonly db: DatabaseSync

  constructor(location: string = ':memory:') {
    this.db = new DatabaseSync(location)
    this.initSchema()
  }

  private initSchema(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS memories (
        id TEXT PRIMARY KEY NOT NULL,
        content TEXT NOT NULL,
        search_text TEXT NOT NULL,
        category TEXT NOT NULL DEFAULT 'preference',
        source TEXT NOT NULL DEFAULT 'extracted',
        is_pinned INTEGER NOT NULL DEFAULT 0,
        is_archived INTEGER NOT NULL DEFAULT 0,
        importance REAL NOT NULL DEFAULT 0.5,
        use_count INTEGER NOT NULL DEFAULT 0,
        last_used_at INTEGER NOT NULL DEFAULT 0,
        confidence REAL NOT NULL DEFAULT 1.0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0
      );

      CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
        id UNINDEXED,
        search_text
      );

      CREATE TABLE IF NOT EXISTS episodes (
        id TEXT PRIMARY KEY NOT NULL,
        thread_id TEXT NOT NULL,
        start_time INTEGER NOT NULL,
        end_time INTEGER NOT NULL,
        summary TEXT NOT NULL,
        search_text TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );

      CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(
        id UNINDEXED,
        search_text
      );

      CREATE INDEX IF NOT EXISTS idx_memories_sync ON memories(updated_at, deleted);
      CREATE INDEX IF NOT EXISTS idx_memories_hot ON memories(is_archived, is_pinned, updated_at);
      CREATE INDEX IF NOT EXISTS idx_episodes_thread ON episodes(thread_id, end_time);
    `)
  }

  /**
   * Insert or update a memory item.
   * Atomically synchronizes the main table and standalone FTS index.
   */
  upsert(input: {
    id?: string | undefined
    content: string
    category?: MemoryRecord['category'] | undefined
    source?: MemoryRecord['source'] | undefined
    is_pinned?: number | boolean | undefined
    is_archived?: number | boolean | undefined
    importance?: number | undefined
    confidence?: number | undefined
    created_at?: number | undefined
    updated_at?: number | undefined
  }): MemoryRecord {
    const now = Date.now()
    const content = input.content.trim()
    let existing: MemoryRecord | null = null
    let memId: string

    if (input.id) {
      memId = input.id
      existing = this.get(input.id)
    } else {
      existing = this.findByContent(content)
      memId = existing ? existing.id : `mem_${Math.random().toString(36).slice(2, 10)}_${now.toString(36)}`
    }

    const search_text = tokenizeForSearch(content)
    const category = input.category ?? existing?.category ?? 'preference'
    const source = input.source ?? existing?.source ?? 'agent'
    const is_pinned = typeof input.is_pinned !== 'undefined' ? (input.is_pinned ? 1 : 0) : (existing?.is_pinned ?? 0)
    const is_archived = typeof input.is_archived !== 'undefined' ? (input.is_archived ? 1 : 0) : (existing?.is_archived ?? 0)
    const importance = typeof input.importance === 'number' ? input.importance : (existing?.importance ?? 0.5)
    const confidence = typeof input.confidence === 'number' ? input.confidence : (existing?.confidence ?? 1.0)
    const created_at = input.created_at ?? existing?.created_at ?? now
    const updated_at = input.updated_at ?? now

    this.db.exec('BEGIN TRANSACTION;')
    try {
      if (existing) {
        const stmt = this.db.prepare(`
          UPDATE memories SET
            content = ?, search_text = ?, category = ?, source = ?,
            is_pinned = ?, is_archived = ?, importance = ?, confidence = ?,
            updated_at = ?, deleted = 0
          WHERE id = ?;
        `)
        stmt.run(content, search_text, category, source, is_pinned, is_archived, importance, confidence, updated_at, memId)

        // Update FTS
        this.db.prepare('DELETE FROM memories_fts WHERE id = ?;').run(memId)
        this.db.prepare('INSERT INTO memories_fts (id, search_text) VALUES (?, ?);').run(memId, search_text)
      } else {
        const stmt = this.db.prepare(`
          INSERT INTO memories (
            id, content, search_text, category, source,
            is_pinned, is_archived, importance, use_count, last_used_at,
            confidence, created_at, updated_at, deleted
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, 0);
        `)
        stmt.run(memId, content, search_text, category, source, is_pinned, is_archived, importance, confidence, created_at, updated_at)

        // Insert into FTS
        this.db.prepare('INSERT INTO memories_fts (id, search_text) VALUES (?, ?);').run(memId, search_text)
      }
      this.db.exec('COMMIT;')
    } catch (err) {
      this.db.exec('ROLLBACK;')
      throw err
    }

    return this.get(memId)!
  }

  findByContent(content: string): MemoryRecord | null {
    const row = this.db.prepare('SELECT * FROM memories WHERE content = ? AND deleted = 0 LIMIT 1;').get(content.trim()) as Record<string, unknown> | undefined
    if (!row) return null
    return row as unknown as MemoryRecord
  }

  getAll(includeArchived = true): MemoryRecord[] {
    const sql = includeArchived
      ? 'SELECT * FROM memories WHERE deleted = 0 ORDER BY is_pinned DESC, updated_at DESC;'
      : 'SELECT * FROM memories WHERE deleted = 0 AND is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC;'
    return this.db.prepare(sql).all() as unknown as MemoryRecord[]
  }

  get(id: string): MemoryRecord | null {
    const row = this.db.prepare('SELECT * FROM memories WHERE id = ?;').get(id) as Record<string, unknown> | undefined
    if (!row) return null
    return row as unknown as MemoryRecord
  }

  /**
   * Delete a memory item.
   * By default performs a soft delete (marking deleted=1 and removing from FTS)
   * so that the deletion can be synchronized over OpLog.
   */
  delete(id: string, hard = false): boolean {
    const existing = this.get(id)
    if (!existing) return false

    this.db.exec('BEGIN TRANSACTION;')
    try {
      if (hard) {
        this.db.prepare('DELETE FROM memories WHERE id = ?;').run(id)
      } else {
        this.db.prepare('UPDATE memories SET deleted = 1, updated_at = ? WHERE id = ?;').run(Date.now(), id)
      }
      this.db.prepare('DELETE FROM memories_fts WHERE id = ?;').run(id)
      this.db.exec('COMMIT;')
    } catch (err) {
      this.db.exec('ROLLBACK;')
      throw err
    }
    return true
  }

  /**
   * Search memories via FTS candidate retrieval + deterministic application ranking.
   */
  search(query: string, options?: { limit?: number; includeArchived?: boolean }): ScoredMatch<MemoryRecord>[] {
    const clean = query.trim()
    const limit = options?.limit ?? 5
    if (!clean) {
      // Empty query: return hot memories ordered by pinned and recency
      const hot = this.getHotMemories(limit)
      return hot.map((item) => ({ item, score: item.is_pinned ? 1.0 : 0.5, hitCount: 1 }))
    }

    const ftsQuery = buildFtsQuery(clean)
    let candidateIds: string[] = []

    if (ftsQuery) {
      try {
        const rows = this.db.prepare('SELECT id FROM memories_fts WHERE memories_fts MATCH ? LIMIT 500;').all(ftsQuery) as Array<{ id: string }>
        candidateIds = rows.map((r) => r.id)
      } catch {
        // FTS syntax fallback: query returned nothing or syntax issue
        candidateIds = []
      }
    }

    if (!candidateIds.length) {
      return []
    }

    // Fetch candidate records
    const placeholders = candidateIds.map(() => '?').join(',')
    const sql = `
      SELECT * FROM memories
      WHERE id IN (${placeholders})
        AND deleted = 0
        ${options?.includeArchived ? '' : 'AND is_archived = 0'}
    `
    const candidates = this.db.prepare(sql).all(...candidateIds) as unknown as MemoryRecord[]

    // Rank candidates deterministically
    const scored = rankCandidates(clean, candidates)
    const topMatches = scored.slice(0, limit)

    // Bump use_count and last_used_at for returned items
    if (topMatches.length > 0) {
      const now = Date.now()
      const updateStmt = this.db.prepare('UPDATE memories SET use_count = use_count + 1, last_used_at = ? WHERE id = ?;')
      for (const m of topMatches) {
        updateStmt.run(now, m.item.id)
        m.item.use_count = (m.item.use_count ?? 0) + 1
        m.item.last_used_at = now
      }
    }

    return topMatches
  }

  /**
   * Retrieve active hot memories for core prompt context injection.
   */
  getHotMemories(limit = 100): MemoryRecord[] {
    const rows = this.db.prepare(`
      SELECT * FROM memories
      WHERE deleted = 0 AND is_archived = 0
      ORDER BY is_pinned DESC, updated_at DESC
      LIMIT ?;
    `).all(limit) as unknown as MemoryRecord[]
    return rows
  }

  /**
   * Count total active memories.
   */
  activeCount(): number {
    const res = this.db.prepare('SELECT COUNT(*) as c FROM memories WHERE deleted = 0 AND is_archived = 0;').get() as { c: number }
    return res.c
  }

  /**
   * Migrate and demote excess memories to Cold (is_archived = 1) if active exceeds limit.
   */
  archiveStale(activeThreshold = 100): number {
    const count = this.activeCount()
    if (count <= activeThreshold) return 0

    const excess = count - activeThreshold
    // Select unpinned memories with lowest use_count and oldest updated_at
    const toArchive = this.db.prepare(`
      SELECT id FROM memories
      WHERE deleted = 0 AND is_archived = 0 AND is_pinned = 0
      ORDER BY importance ASC, use_count ASC, updated_at ASC
      LIMIT ?;
    `).all(excess) as Array<{ id: string }>

    if (!toArchive.length) return 0

    const ids = toArchive.map((r) => r.id)
    const placeholders = ids.map(() => '?').join(',')
    this.db.prepare(`UPDATE memories SET is_archived = 1 WHERE id IN (${placeholders});`).run(...ids)
    return ids.length
  }

  /**
   * Import legacy memory array from rooms.json.
   */
  importLegacy(items: Array<{ id?: string; text: string; at?: number; source?: string }>): number {
    let imported = 0
    for (const item of items) {
      if (!item.text || !item.text.trim()) continue
      this.upsert({
        id: item.id,
        content: item.text,
        source: (item.source as MemoryRecord['source']) || 'import',
        created_at: item.at,
        updated_at: item.at,
      })
      imported++
    }
    return imported
  }

  /**
   * Close the underlying database connection.
   */
  close(): void {
    this.db.close()
  }
}
