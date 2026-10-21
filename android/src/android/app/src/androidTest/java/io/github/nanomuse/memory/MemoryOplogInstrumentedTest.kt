package io.github.nanomuse.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The phone's half of C15 against a real SQLite file: every write queues one operation, the
 * relay's seq decides between two writes, an edit not yet pushed is not overwritten, and a
 * store written before the oplog upgrades in place.
 */
@RunWith(AndroidJUnit4::class)
class MemoryOplogInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "nanomuse_memory_test.db"
    private lateinit var db: LocalMemoryDb

    @Before
    fun setUp() {
        deleteFiles()
        db = LocalMemoryDb(context, name)
    }

    @After
    fun tearDown() {
        db.close()
        deleteFiles()
    }

    private fun deleteFiles() {
        for (suffix in listOf("", "-wal", "-shm")) {
            File(context.getDatabasePath(name).absolutePath + suffix).delete()
        }
    }

    @Test
    fun aWriteQueuesOneOpAndTheSameLineAgainQueuesNothing() {
        val item = db.upsert(content = "临时脚本放 ~/tmp")
        assertEquals(1, db.pendingOps().size)

        db.upsert(content = "临时脚本放 ~/tmp")
        assertEquals(1, db.pendingOps().size)
        assertEquals(item.id, db.pendingOps().first().payload.id)
    }

    @Test
    fun anAcceptedOpGivesTheRowTheRelaySeq() {
        val item = db.upsert(content = "电脑是 Arch Linux")
        val op = db.pendingOps().first()
        db.markAccepted(listOf(op.opId to 7L))

        assertTrue(db.pendingOps().isEmpty())
        assertEquals(7L, db.get(item.id)!!.seq)
        assertTrue(db.hasOp(op.opId))
    }

    @Test
    fun aRemoteChangeAppliesOnceAndAnOlderOneCannotTakeTheRowBack() {
        val item = db.upsert(content = "住在上海")
        db.markAccepted(listOf(db.pendingOps().first().opId to 1L))

        val remote = MemoryOp(
            opId = "remote-1",
            seq = 2L,
            opType = "upsert_memory",
            payload = payload(item.id, "住在北京"),
            clientTime = System.currentTimeMillis(),
        )
        assertTrue(db.applyRemote(remote))
        assertEquals("住在北京", db.get(item.id)!!.content)
        assertFalse(db.applyRemote(remote))

        assertFalse(db.applyRemote(remote.copy(opId = "remote-0", seq = 0L, payload = payload(item.id, "住在广州"))))
        assertEquals("住在北京", db.get(item.id)!!.content)
    }

    @Test
    fun anEditNotYetPushedIsNotOverwrittenByARemoteChange() {
        val item = db.upsert(content = "住在上海")
        db.markAccepted(listOf(db.pendingOps().first().opId to 1L))
        db.upsert(id = item.id, content = "住在深圳")

        assertFalse(db.applyRemote(MemoryOp("remote-2", 3L, "upsert_memory", payload(item.id, "住在杭州"), System.currentTimeMillis())))
        assertEquals("住在深圳", db.get(item.id)!!.content)
    }

    @Test
    fun aRemoteDeleteHidesTheMemoryFromSearch() {
        val item = db.upsert(content = "临时脚本放 ~/tmp")
        db.markAccepted(listOf(db.pendingOps().first().opId to 1L))

        val delete = MemoryOp(
            opId = "remote-del",
            seq = 2L,
            opType = "delete_memory",
            payload = payload(item.id, "").copy(deleted = true),
            clientTime = System.currentTimeMillis(),
        )
        assertTrue(db.applyRemote(delete))
        assertTrue(db.search("临时脚本").isEmpty())
        assertTrue(db.get(item.id)!!.deleted)
    }

    @Test
    fun aStoreWrittenBeforeTheOplogUpgradesInPlace() {
        db.close()
        deleteFiles()
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE memories (
                    id TEXT PRIMARY KEY NOT NULL, content TEXT NOT NULL, search_text TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'preference', source TEXT NOT NULL DEFAULT 'extracted',
                    is_pinned INTEGER NOT NULL DEFAULT 0, is_archived INTEGER NOT NULL DEFAULT 0,
                    importance REAL NOT NULL DEFAULT 0.5, use_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at INTEGER NOT NULL DEFAULT 0, confidence REAL NOT NULL DEFAULT 1.0,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0
                );
                """.trimIndent(),
            )
            old.execSQL("INSERT INTO memories (id, content, search_text, created_at, updated_at) VALUES ('mem_old', '旧的记忆', '旧 的 记忆', 1, 1);")
            old.version = 1
        }

        db = LocalMemoryDb(context, name)
        val columns = mutableListOf<String>()
        db.readableDatabase.rawQuery("PRAGMA table_info(memories)", null).use { c ->
            while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        assertTrue("seq column added", columns.contains("seq"))
        // the tables exist and the old row is still readable
        assertEquals(0, db.pendingOps().size)
        assertNotNull(db.get("mem_old"))
        assertEquals(0L, db.cursor())
    }

    private fun payload(id: String, content: String) = MemoryOpPayload(
        id = id,
        content = content,
        beforeContent = "",
        category = "profile",
        source = "agent",
        isPinned = false,
        isArchived = false,
        importance = 0.5f,
        updatedAt = System.currentTimeMillis(),
        deleted = false,
    )
}
