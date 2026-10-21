package io.github.nanomuse.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class MemoryEntity(
    val id: String,
    val content: String,
    val searchText: String,
    val category: String,
    val source: String,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val importance: Float,
    val useCount: Int,
    val lastUsedAt: Long,
    val confidence: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    /** The relay's seq of the change this row carries; 0 while it is only here (C15). */
    val seq: Long = 0
)

/** One memory change as it travels: the row's whole state, and what it said before (C15). */
data class MemoryOpPayload(
    val id: String,
    val content: String,
    val beforeContent: String,
    val category: String,
    val source: String,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val importance: Float,
    val updatedAt: Long,
    val deleted: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("content", content)
        .put("before_content", beforeContent)
        .put("category", category)
        .put("source", source)
        .put("is_pinned", isPinned)
        .put("is_archived", isArchived)
        .put("importance", importance.toDouble())
        .put("updated_at", updatedAt)
        .put("deleted", deleted)

    companion object {
        fun fromJson(o: JSONObject): MemoryOpPayload = MemoryOpPayload(
            id = o.optString("id"),
            content = o.optString("content"),
            beforeContent = o.optString("before_content"),
            category = o.optString("category", "preference"),
            source = o.optString("source", "agent"),
            isPinned = o.optBoolean("is_pinned", false),
            isArchived = o.optBoolean("is_archived", false),
            importance = o.optDouble("importance", 0.5).toFloat(),
            updatedAt = o.optLong("updated_at", 0L),
            deleted = o.optBoolean("deleted", false)
        )
    }
}

data class MemoryOp(
    val opId: String,
    val seq: Long,
    val opType: String,
    val payload: MemoryOpPayload,
    val clientTime: Long
)

class LocalMemoryDb(context: Context, name: String = DATABASE_NAME) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "nanomuse_memory.db"
        /** 2: the oplog and the per-row seq (C15); 1: the first store. */
        const val DATABASE_VERSION = 2

        @Volatile
        private var instance: LocalMemoryDb? = null

        fun getInstance(context: Context): LocalMemoryDb {
            return instance ?: synchronized(this) {
                instance ?: LocalMemoryDb(context.applicationContext).also { instance = it }
            }
        }

        /** Before the file moves with its account: a held handle would follow the old inode. */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
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
        """.trimIndent())

        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(
                id,
                search_text
            );
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_sync ON memories(updated_at, deleted);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_hot ON memories(is_archived, is_pinned, updated_at);")
        createOplog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // a store written before the oplog: the row's own seq starts at 0 and takes the next push
            if (!hasColumn(db, "memories", "seq")) {
                db.execSQL("ALTER TABLE memories ADD COLUMN seq INTEGER NOT NULL DEFAULT 0;")
            }
            createOplog(db)
        }
    }

    private fun createOplog(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS memory_ops (
                op_id TEXT PRIMARY KEY NOT NULL,
                seq INTEGER NOT NULL DEFAULT 0,
                op_type TEXT NOT NULL,
                memory_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                client_time INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_ops_pending ON memory_ops(seq, created_at);")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndex("name")
            while (c.moveToNext()) if (name >= 0 && c.getString(name) == column) return true
        }
        return false
    }

    fun upsert(
        id: String? = null,
        content: String,
        category: String = "preference",
        source: String = "agent",
        isPinned: Boolean = false,
        isArchived: Boolean = false,
        importance: Float = 0.5f,
        confidence: Float = 1.0f
    ): MemoryEntity {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val trimmed = content.trim()
        val existing = if (id != null) get(id) else findIdByContent(trimmed)?.let { get(it) }
        val memId = id ?: existing?.id ?: "mem_${System.currentTimeMillis().toString(36)}_${(1000..9999).random()}"
        val searchText = MemoryTokenizer.tokenizeForSearch(trimmed)

        // the same line written again (an import at boot, a repeated tool call) changes nothing
        if (existing != null && !existing.deleted &&
            existing.content == trimmed && existing.category == category && existing.source == source &&
            existing.isPinned == isPinned && existing.isArchived == isArchived &&
            existing.importance == importance && existing.confidence == confidence
        ) {
            return existing
        }

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("id", memId)
                put("content", trimmed)
                put("search_text", searchText)
                put("category", category)
                put("source", source)
                put("is_pinned", if (isPinned) 1 else 0)
                put("is_archived", if (isArchived) 1 else 0)
                put("importance", importance)
                put("confidence", confidence)
                put("updated_at", now)
                put("deleted", 0)
            }

            val updated = db.update("memories", cv, "id = ?", arrayOf(memId))
            if (updated == 0) {
                cv.put("created_at", now)
                cv.put("use_count", 0)
                cv.put("last_used_at", 0L)
                cv.put("seq", 0L)
                db.insert("memories", null, cv)
            }

            db.delete("memories_fts", "id = ?", arrayOf(memId))
            val ftsCv = ContentValues().apply {
                put("id", memId)
                put("search_text", searchText)
            }
            db.insert("memories_fts", null, ftsCv)

            recordOp(
                db,
                "upsert_memory",
                MemoryOpPayload(
                    id = memId,
                    content = trimmed,
                    beforeContent = existing?.content ?: "",
                    category = category,
                    source = source,
                    isPinned = isPinned,
                    isArchived = isArchived,
                    importance = importance,
                    updatedAt = now,
                    deleted = false,
                ),
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return get(memId)!!
    }

    fun get(id: String): MemoryEntity? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM memories WHERE id = ?", arrayOf(id))
        return cursor.use {
            if (it.moveToFirst()) {
                MemoryEntity(
                    id = it.getString(it.getColumnIndexOrThrow("id")),
                    content = it.getString(it.getColumnIndexOrThrow("content")),
                    searchText = it.getString(it.getColumnIndexOrThrow("search_text")),
                    category = it.getString(it.getColumnIndexOrThrow("category")),
                    source = it.getString(it.getColumnIndexOrThrow("source")),
                    isPinned = it.getInt(it.getColumnIndexOrThrow("is_pinned")) == 1,
                    isArchived = it.getInt(it.getColumnIndexOrThrow("is_archived")) == 1,
                    importance = it.getFloat(it.getColumnIndexOrThrow("importance")),
                    useCount = it.getInt(it.getColumnIndexOrThrow("use_count")),
                    lastUsedAt = it.getLong(it.getColumnIndexOrThrow("last_used_at")),
                    confidence = it.getFloat(it.getColumnIndexOrThrow("confidence")),
                    createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at")),
                    deleted = it.getInt(it.getColumnIndexOrThrow("deleted")) == 1,
                    seq = it.getLong(it.getColumnIndexOrThrow("seq"))
                )
            } else null
        }
    }

    fun findIdByContent(content: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id FROM memories WHERE content = ? AND deleted = 0 LIMIT 1", arrayOf(content.trim()))
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun delete(id: String, hard: Boolean = false): Boolean {
        val db = writableDatabase
        val existing = get(id) ?: return false
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (hard) {
                db.delete("memories", "id = ?", arrayOf(id))
            } else {
                val cv = ContentValues().apply {
                    put("deleted", 1)
                    put("updated_at", now)
                }
                db.update("memories", cv, "id = ?", arrayOf(id))
            }
            db.delete("memories_fts", "id = ?", arrayOf(id))
            recordOp(
                db,
                "delete_memory",
                MemoryOpPayload(
                    id = id,
                    content = "",
                    beforeContent = existing.content,
                    category = existing.category,
                    source = existing.source,
                    isPinned = existing.isPinned,
                    isArchived = existing.isArchived,
                    importance = existing.importance,
                    updatedAt = now,
                    deleted = true,
                ),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    // ── the oplog (C15) ───────────────────────────────────────────────────

    private fun recordOp(db: SQLiteDatabase, opType: String, payload: MemoryOpPayload) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("op_id", java.util.UUID.randomUUID().toString())
            put("seq", 0L)
            put("op_type", opType)
            put("memory_id", payload.id)
            put("payload", payload.toJson().toString())
            put("client_time", now)
            put("created_at", now)
        }
        db.insert("memory_ops", null, cv)
    }

    /** Changes this phone made and the relay has not taken yet, oldest first. */
    fun pendingOps(limit: Int = 100): List<MemoryOp> {
        val out = mutableListOf<MemoryOp>()
        readableDatabase.rawQuery(
            "SELECT op_id, op_type, payload, client_time FROM memory_ops WHERE seq = 0 ORDER BY created_at ASC, rowid ASC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MemoryOp(
                        opId = c.getString(0),
                        seq = 0L,
                        opType = c.getString(1),
                        payload = MemoryOpPayload.fromJson(org.json.JSONObject(c.getString(2))),
                        clientTime = c.getLong(3),
                    ),
                )
            }
        }
        return out
    }

    /** The relay took these: the op has a seq, and so does the row it describes. */
    fun markAccepted(assigned: List<Pair<String, Long>>) {
        if (assigned.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((opId, seq) in assigned) {
                db.execSQL("UPDATE memory_ops SET seq = ? WHERE op_id = ?", arrayOf<Any>(seq, opId))
                val memoryId = db.rawQuery("SELECT memory_id FROM memory_ops WHERE op_id = ?", arrayOf(opId)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: continue
                // a newer edit queued here keeps the row at 0 until it too has a seq
                val stillPending = db.rawQuery(
                    "SELECT 1 FROM memory_ops WHERE memory_id = ? AND seq = 0 LIMIT 1",
                    arrayOf(memoryId),
                ).use { it.moveToFirst() }
                if (!stillPending) db.execSQL("UPDATE memories SET seq = ? WHERE id = ?", arrayOf<Any>(seq, memoryId))
            }
            db.execSQL("DELETE FROM memory_ops WHERE seq > 0 AND created_at < ?", arrayOf<Any>(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun hasOp(opId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM memory_ops WHERE op_id = ?", arrayOf(opId)).use { it.moveToFirst() }

    private fun localPending(db: SQLiteDatabase, memoryId: String): Boolean =
        db.rawQuery("SELECT 1 FROM memory_ops WHERE memory_id = ? AND seq = 0 LIMIT 1", arrayOf(memoryId)).use { it.moveToFirst() }

    /**
     * Apply one change from another device. The relay's seq decides: a row whose seq is
     * newer stays, and a change this phone has not pushed yet outranks anything arriving.
     */
    fun applyRemote(op: MemoryOp): Boolean {
        val seq = op.seq.coerceAtLeast(0)
        val p = op.payload
        if (p.id.isEmpty() || hasOp(op.opId)) return false
        val db = writableDatabase
        val current = get(p.id)

        fun keep(): Boolean {
            val cv = ContentValues().apply {
                put("op_id", op.opId)
                put("seq", seq)
                put("op_type", op.opType)
                put("memory_id", p.id)
                put("payload", p.toJson().toString())
                put("client_time", op.clientTime)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("memory_ops", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            return false
        }

        if (current != null && (localPending(db, p.id) || current.seq > seq)) return keep()
        if (current != null && p.deleted && current.seq >= seq) return keep()

        db.beginTransaction()
        try {
            if (p.deleted) {
                if (current == null) {
                    db.setTransactionSuccessful()
                    return keep()
                }
                db.execSQL("UPDATE memories SET deleted = 1, updated_at = ?, seq = ? WHERE id = ?", arrayOf<Any>(p.updatedAt, seq, p.id))
                db.delete("memories_fts", "id = ?", arrayOf(p.id))
            } else {
                val content = p.content.trim()
                if (content.isEmpty()) {
                    db.setTransactionSuccessful()
                    return keep()
                }
                val searchText = MemoryTokenizer.tokenizeForSearch(content)
                if (current != null) {
                    db.execSQL(
                        "UPDATE memories SET content = ?, search_text = ?, category = ?, source = ?, is_pinned = ?, is_archived = ?, importance = ?, updated_at = ?, deleted = 0, seq = ? WHERE id = ?",
                        arrayOf<Any>(content, searchText, p.category, p.source, if (p.isPinned) 1 else 0, if (p.isArchived) 1 else 0, p.importance, p.updatedAt, seq, p.id),
                    )
                } else {
                    db.execSQL(
                        "INSERT INTO memories (id, content, search_text, category, source, is_pinned, is_archived, importance, use_count, last_used_at, confidence, created_at, updated_at, deleted, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1.0, ?, ?, 0, ?)",
                        arrayOf<Any>(p.id, content, searchText, p.category, p.source, if (p.isPinned) 1 else 0, if (p.isArchived) 1 else 0, p.importance, p.updatedAt, p.updatedAt, seq),
                    )
                }
                db.delete("memories_fts", "id = ?", arrayOf(p.id))
                val ftsCv = ContentValues().apply {
                    put("id", p.id)
                    put("search_text", searchText)
                }
                db.insert("memories_fts", null, ftsCv)
            }
            keep()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /** Where this phone is in the account's memory changes. */
    fun cursor(): Long = readableDatabase.rawQuery("SELECT value FROM memory_meta WHERE key = 'cursor'", null).use {
        if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L
    }

    fun setCursor(seq: Long) {
        writableDatabase.execSQL(
            "INSERT INTO memory_meta (key, value) VALUES ('cursor', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf<Any>(seq.coerceAtLeast(0).toString()),
        )
    }

    fun archivedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memories WHERE deleted = 0 AND is_archived = 1", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun search(query: String, limit: Int = 5, includeArchived: Boolean = false): List<MemoryMatch> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()

        val ftsQuery = MemoryTokenizer.buildFtsQuery(clean)
        if (ftsQuery.isEmpty()) return emptyList()

        val db = readableDatabase
        val candidateIds = mutableListOf<String>()

        try {
            val cursor = db.rawQuery("SELECT id FROM memories_fts WHERE memories_fts MATCH ? LIMIT 500", arrayOf(ftsQuery))
            cursor.use {
                while (it.moveToNext()) {
                    candidateIds.add(it.getString(0))
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        if (candidateIds.isEmpty()) return emptyList()

        val placeholders = candidateIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM memories WHERE id IN ($placeholders) AND deleted = 0" +
                if (includeArchived) "" else " AND is_archived = 0"

        val candidates = mutableListOf<MemoryCandidate>()
        val recordsCursor = db.rawQuery(sql, candidateIds.toTypedArray())
        recordsCursor.use {
            while (it.moveToNext()) {
                candidates.add(
                    MemoryCandidate(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        searchText = it.getString(it.getColumnIndexOrThrow("search_text")),
                        isPinned = it.getInt(it.getColumnIndexOrThrow("is_pinned")) == 1,
                        importance = it.getFloat(it.getColumnIndexOrThrow("importance")),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }

        val ranked = MemoryScorer.rankCandidates(clean, candidates)
        val topMatches = ranked.take(limit)

        // Bump use count
        if (topMatches.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val writableDb = writableDatabase
            for (m in topMatches) {
                writableDb.execSQL(
                    "UPDATE memories SET use_count = use_count + 1, last_used_at = ? WHERE id = ?",
                    arrayOf<Any>(now, m.item.id)
                )
            }
        }

        return topMatches
    }

    fun getHotMemories(limit: Int = 100): List<MemoryEntity> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM memories WHERE deleted = 0 AND is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val list = mutableListOf<MemoryEntity>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    MemoryEntity(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        searchText = it.getString(it.getColumnIndexOrThrow("search_text")),
                        category = it.getString(it.getColumnIndexOrThrow("category")),
                        source = it.getString(it.getColumnIndexOrThrow("source")),
                        isPinned = it.getInt(it.getColumnIndexOrThrow("is_pinned")) == 1,
                        isArchived = it.getInt(it.getColumnIndexOrThrow("is_archived")) == 1,
                        importance = it.getFloat(it.getColumnIndexOrThrow("importance")),
                        useCount = it.getInt(it.getColumnIndexOrThrow("use_count")),
                        lastUsedAt = it.getLong(it.getColumnIndexOrThrow("last_used_at")),
                        confidence = it.getFloat(it.getColumnIndexOrThrow("confidence")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at")),
                        deleted = it.getInt(it.getColumnIndexOrThrow("deleted")) == 1,
                    seq = it.getLong(it.getColumnIndexOrThrow("seq"))
                    )
                )
            }
        }
        return list
    }
}
