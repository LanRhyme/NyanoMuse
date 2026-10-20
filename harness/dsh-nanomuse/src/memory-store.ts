/**
 * Persistent SQLite memory store using Node.js built-in `node:sqlite`.
 * Works out-of-the-box in Node 22+ and Electron 44+ with zero native compilation dependencies.
 */
import { randomUUID } from 'node:crypto'
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
  /** The relay's sequence number of the change this row carries; 0 while it is only here. */
  seq: number
}

/** One memory change as it travels: the row's whole state, and what it said before (C15). */
export interface MemoryOpPayload {
  id: string
  content: string
  before_content: string
  category: string
  source: string
  is_pinned: boolean
  is_archived: boolean
  importance: number
  updated_at: number
  deleted: boolean
}

export interface MemoryOp {
  op_id: string
  seq: number
  op_type: 'upsert_memory' | 'delete_memory'
  payload: MemoryOpPayload
  client_time: number
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
        deleted INTEGER NOT NULL DEFAULT 0,
        seq INTEGER NOT NULL DEFAULT 0
      );

      CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
        id UNINDEXED,
        search_text
      );

      -- every change made here, until the relay assigns it a seq (C15)
      CREATE TABLE IF NOT EXISTS memory_ops (
        op_id TEXT PRIMARY KEY NOT NULL,
        seq INTEGER NOT NULL DEFAULT 0,
        op_type TEXT NOT NULL,
        memory_id TEXT NOT NULL,
        payload TEXT NOT NULL,
        client_time INTEGER NOT NULL,
        created_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS memory_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);

      CREATE INDEX IF NOT EXISTS idx_memories_sync ON memories(updated_at, deleted);
      CREATE INDEX IF NOT EXISTS idx_memories_hot ON memories(is_archived, is_pinned, updated_at);
      CREATE INDEX IF NOT EXISTS idx_memory_ops_pending ON memory_ops(seq, created_at);
    `)
    // a database written before the oplog: the row's own seq starts at 0 and takes the next push
    const columns = new Set((this.db.prepare('PRAGMA table_info(memories);').all() as Array<{ name: string }>).map((c) => c.name))
    if (!columns.has('seq')) {
      this.db.exec('ALTER TABLE memories ADD COLUMN seq INTEGER NOT NULL DEFAULT 0;')
    }
  }

  /**
   * Insert or update a memory item.
   * Atomically synchronizes the main table, the standalone FTS index and the oplog.
   * A write that changes nothing (the same line, imported again at boot) does nothing at
   * all, so it neither rewrites the index nor queues an operation for the relay.
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

    if (
      existing &&
      existing.deleted === 0 &&
      existing.content === content &&
      existing.category === category &&
      existing.source === source &&
      existing.is_pinned === is_pinned &&
      existing.is_archived === is_archived &&
      existing.importance === importance &&
      existing.confidence === confidence
    ) {
      return existing
    }

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
            confidence, created_at, updated_at, deleted, seq
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, 0, 0);
        `)
        stmt.run(memId, content, search_text, category, source, is_pinned, is_archived, importance, confidence, created_at, updated_at)

        // Insert into FTS
        this.db.prepare('INSERT INTO memories_fts (id, search_text) VALUES (?, ?);').run(memId, search_text)
      }
      this.recordOp('upsert_memory', {
        id: memId,
        content,
        before_content: existing?.content ?? '',
        category,
        source,
        is_pinned: is_pinned === 1,
        is_archived: is_archived === 1,
        importance,
        updated_at,
        deleted: false,
      })
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

    const now = Date.now()
    this.db.exec('BEGIN TRANSACTION;')
    try {
      if (hard) {
        this.db.prepare('DELETE FROM memories WHERE id = ?;').run(id)
      } else {
        this.db.prepare('UPDATE memories SET deleted = 1, updated_at = ? WHERE id = ?;').run(now, id)
      }
      this.db.prepare('DELETE FROM memories_fts WHERE id = ?;').run(id)
      this.recordOp('delete_memory', {
        id,
        content: '',
        before_content: existing.content,
        category: existing.category,
        source: existing.source,
        is_pinned: existing.is_pinned === 1,
        is_archived: existing.is_archived === 1,
        importance: existing.importance,
        updated_at: now,
        deleted: true,
      })
      this.db.exec('COMMIT;')
    } catch (err) {
      this.db.exec('ROLLBACK;')
      throw err
    }
    return true
  }

  // ------------------------------------------------------------------ oplog (C15)

  private recordOp(opType: MemoryOp['op_type'], payload: MemoryOpPayload): void {
    const now = Date.now()
    this.db
      .prepare('INSERT INTO memory_ops (op_id, seq, op_type, memory_id, payload, client_time, created_at) VALUES (?, 0, ?, ?, ?, ?, ?);')
      .run(randomUUID(), opType, payload.id, JSON.stringify(payload), now, now)
  }

  /** Changes this device made and the relay has not taken yet, oldest first. */
  pendingOps(limit = 100): MemoryOp[] {
    const rows = this.db
      .prepare('SELECT * FROM memory_ops WHERE seq = 0 ORDER BY created_at ASC, rowid ASC LIMIT ?;')
      .all(limit) as Array<Record<string, unknown>>
    return rows.map((r) => ({
      op_id: String(r.op_id),
      seq: 0,
      op_type: String(r.op_type) as MemoryOp['op_type'],
      payload: JSON.parse(String(r.payload)) as MemoryOpPayload,
      client_time: Number(r.client_time),
    }))
  }

  /** The relay took these: the op now has a seq, and so does the row it describes. */
  markAccepted(assigned: Array<{ op_id: string; seq: number }>): void {
    if (!assigned.length) return
    this.db.exec('BEGIN TRANSACTION;')
    try {
      const setSeq = this.db.prepare('UPDATE memory_ops SET seq = ? WHERE op_id = ?;')
      const setRow = this.db.prepare('UPDATE memories SET seq = ? WHERE id = ?;')
      for (const { op_id, seq } of assigned) {
        setSeq.run(seq, op_id)
        const row = this.db.prepare('SELECT memory_id FROM memory_ops WHERE op_id = ?;').get(op_id) as { memory_id: string } | undefined
        // a newer edit queued here keeps the row at 0 until it too has a seq
        if (row && !this.localPending(row.memory_id)) setRow.run(seq, row.memory_id)
      }
      // acknowledged operations only matter for a retry; a month is far longer than any
      this.db.prepare('DELETE FROM memory_ops WHERE seq > 0 AND created_at < ?;').run(Date.now() - 30 * 24 * 60 * 60 * 1000)
      this.db.exec('COMMIT;')
    } catch (err) {
      this.db.exec('ROLLBACK;')
      throw err
    }
  }

  hasOp(opId: string): boolean {
    return this.db.prepare('SELECT 1 FROM memory_ops WHERE op_id = ?;').get(opId) !== undefined
  }

  /** A change here that the relay has not answered yet: a remote write must not overwrite it. */
  private localPending(memoryId: string): boolean {
    return this.db.prepare('SELECT 1 FROM memory_ops WHERE memory_id = ? AND seq = 0 LIMIT 1;').get(memoryId) !== undefined
  }

  /**
   * Apply one change from another device; false when it was already here or loses to what
   * this device holds. The relay's `seq` decides: a row whose seq is newer stays.
   */
  applyRemote(op: MemoryOp): boolean {
    const seq = Math.max(0, Math.floor(op.seq))
    const payload = op.payload
    const id = String(payload?.id ?? '')
    if (!id || this.hasOp(op.op_id)) return false

    const keep = (): false => {
      this.db
        .prepare('INSERT OR IGNORE INTO memory_ops (op_id, seq, op_type, memory_id, payload, client_time, created_at) VALUES (?, ?, ?, ?, ?, ?, ?);')
        .run(op.op_id, seq, op.op_type, id, JSON.stringify(payload), op.client_time, Date.now())
      return false
    }

    const current = this.get(id)
    if (current && (this.localPending(id) || current.seq > seq)) return keep()

    const now = Date.now()
    const updated_at = Number(payload.updated_at) || now
    this.db.exec('BEGIN TRANSACTION;')
    try {
      if (payload.deleted) {
        if (!current) {
          this.db.exec('ROLLBACK;')
          return keep()
        }
        this.db.prepare('UPDATE memories SET deleted = 1, updated_at = ?, seq = ? WHERE id = ?;').run(updated_at, seq, id)
        this.db.prepare('DELETE FROM memories_fts WHERE id = ?;').run(id)
      } else {
        const content = String(payload.content ?? '').trim()
        if (!content) {
          this.db.exec('ROLLBACK;')
          return keep()
        }
        const searchText = tokenizeForSearch(content)
        const category = String(payload.category ?? 'preference')
        const source = String(payload.source ?? 'agent')
        const pinned = payload.is_pinned ? 1 : 0
        const archived = payload.is_archived ? 1 : 0
        const importance = typeof payload.importance === 'number' ? payload.importance : 0.5
        if (current) {
          this.db
            .prepare(
              'UPDATE memories SET content = ?, search_text = ?, category = ?, source = ?, is_pinned = ?, is_archived = ?, importance = ?, updated_at = ?, deleted = 0, seq = ? WHERE id = ?;',
            )
            .run(content, searchText, category, source, pinned, archived, importance, updated_at, seq, id)
        } else {
          this.db
            .prepare(
              'INSERT INTO memories (id, content, search_text, category, source, is_pinned, is_archived, importance, use_count, last_used_at, confidence, created_at, updated_at, deleted, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1.0, ?, ?, 0, ?);',
            )
            .run(id, content, searchText, category, source, pinned, archived, importance, updated_at, updated_at, seq)
        }
        this.db.prepare('DELETE FROM memories_fts WHERE id = ?;').run(id)
        this.db.prepare('INSERT INTO memories_fts (id, search_text) VALUES (?, ?);').run(id, searchText)
      }
      this.db
        .prepare('INSERT OR IGNORE INTO memory_ops (op_id, seq, op_type, memory_id, payload, client_time, created_at) VALUES (?, ?, ?, ?, ?, ?, ?);')
        .run(op.op_id, seq, op.op_type, id, JSON.stringify(payload), op.client_time, now)
      this.db.exec('COMMIT;')
    } catch (err) {
      this.db.exec('ROLLBACK;')
      throw err
    }
    return true
  }

  /** Where this device is in the account's memory changes. */
  cursor(): number {
    const row = this.db.prepare("SELECT value FROM memory_meta WHERE key = 'cursor';").get() as { value: string } | undefined
    return row ? Number(row.value) || 0 : 0
  }

  setCursor(seq: number): void {
    this.db
      .prepare("INSERT INTO memory_meta (key, value) VALUES ('cursor', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value;")
      .run(String(Math.max(0, Math.floor(seq))))
  }

  opCount(): number {
    return Number((this.db.prepare('SELECT COUNT(*) AS c FROM memory_ops;').get() as { c: number }).c)
  }

  archivedCount(): number {
    return Number((this.db.prepare('SELECT COUNT(*) AS c FROM memories WHERE deleted = 0 AND is_archived = 1;').get() as { c: number }).c)
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
      SELECT * FROM memories
      WHERE deleted = 0 AND is_archived = 0 AND is_pinned = 0
      ORDER BY importance ASC, use_count ASC, updated_at ASC
      LIMIT ?;
    `).all(excess) as unknown as MemoryRecord[]

    if (!toArchive.length) return 0

    const ids = toArchive.map((r) => r.id)
    const placeholders = ids.map(() => '?').join(',')
    this.db.exec('BEGIN TRANSACTION;')
    try {
      this.db.prepare(`UPDATE memories SET is_archived = 1 WHERE id IN (${placeholders});`).run(...ids)
      // moving a line out of the list is a change the other devices see too
      for (const row of toArchive) {
        this.recordOp('upsert_memory', {
          id: row.id,
          content: row.content,
          before_content: row.content,
          category: row.category,
          source: row.source,
          is_pinned: row.is_pinned === 1,
          is_archived: true,
          importance: row.importance,
          updated_at: row.updated_at,
          deleted: false,
        })
      }
      this.db.exec('COMMIT;')
    } catch (err) {
      this.db.exec('ROLLBACK;')
      throw err
    }
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
