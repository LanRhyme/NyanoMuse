package io.github.nanomuse.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
    val deleted: Boolean
)

class LocalMemoryDb(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "nanomuse_memory.db"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: LocalMemoryDb? = null

        fun getInstance(context: Context): LocalMemoryDb {
            return instance ?: synchronized(this) {
                instance ?: LocalMemoryDb(context.applicationContext).also { instance = it }
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
                deleted INTEGER NOT NULL DEFAULT 0
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations
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
        val memId = id ?: findIdByContent(content) ?: "mem_${System.currentTimeMillis().toString(36)}_${(1000..9999).random()}"
        val searchText = MemoryTokenizer.tokenizeForSearch(content)

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("id", memId)
                put("content", content.trim())
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
                db.insert("memories", null, cv)
            }

            db.delete("memories_fts", "id = ?", arrayOf(memId))
            val ftsCv = ContentValues().apply {
                put("id", memId)
                put("search_text", searchText)
            }
            db.insert("memories_fts", null, ftsCv)

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
                    deleted = it.getInt(it.getColumnIndexOrThrow("deleted")) == 1
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
        db.beginTransaction()
        try {
            if (hard) {
                db.delete("memories", "id = ?", arrayOf(id))
            } else {
                val cv = ContentValues().apply {
                    put("deleted", 1)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update("memories", cv, "id = ?", arrayOf(id))
            }
            db.delete("memories_fts", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun search(query: String, limit: Int = 5, includeArchived: Boolean = false): List<MemoryMatch> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()

        val ftsQuery = MemoryTokenizer.buildFtsQuery(clean)
        if (ftsQuery.isEmpty()) return emptyList()

        val db = readableDatabase
        val candidateIds = mutableListOf<String>()

        try {
            val cursor = db.rawQuery("SELECT id FROM memories_fts WHERE memories_fts MATCH ?", arrayOf(ftsQuery))
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
                        deleted = it.getInt(it.getColumnIndexOrThrow("deleted")) == 1
                    )
                )
            }
        }
        return list
    }
}
