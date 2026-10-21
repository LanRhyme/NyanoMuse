package io.github.nanomuse.memory

import android.content.Context
import android.util.Log

/**
 * The structured memory store on the phone: what the agent remembers, searchable, hot and
 * cold, and shared with the account's other devices (C15).
 *
 * The upstream repository keeps the markdown files (`GLOBAL.md`, the daily logs); this is
 * the store beside them, and the screen and the `memory_write` tool both come through here.
 */
object StructuredMemory {

    private const val TAG = "StructuredMemory"

    private fun db(context: Context): LocalMemoryDb? = try {
        LocalMemoryDb.getInstance(context.applicationContext)
    } catch (e: Exception) {
        Log.w(TAG, "memory store unavailable", e)
        null
    }

    /** Every memory, the cold ones included, newest and pinned first. */
    fun all(context: Context, includeArchived: Boolean = true): List<MemoryEntity> = try {
        db(context)?.getAllMemories(includeArchived) ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "failed to read the memories", e)
        emptyList()
    }

    /** The memories that match the words, the cold ones included. */
    fun search(
        context: Context,
        query: String,
        limit: Int = 20,
        includeArchived: Boolean = true
    ): List<MemoryMatch> = try {
        db(context)?.search(query, limit, includeArchived) ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "failed to search the memories", e)
        emptyList()
    }

    /** The memories in the hot store: the ones the prompt carries. */
    fun activeCount(context: Context): Int = try {
        db(context)?.activeCount() ?: 0
    } catch (e: Exception) {
        Log.e(TAG, "failed to count the memories", e)
        0
    }

    /** The memories moved out of the list for age; they are still findable. */
    fun archivedCount(context: Context): Int = try {
        db(context)?.archivedCount() ?: 0
    } catch (e: Exception) {
        Log.e(TAG, "failed to count the archived memories", e)
        0
    }

    fun upsert(
        context: Context,
        id: String? = null,
        content: String,
        category: String = "preference",
        source: String = "manual",
        isPinned: Boolean = false,
        isArchived: Boolean = false
    ): MemoryEntity? = try {
        db(context)?.upsert(
            id = id,
            content = content,
            category = category,
            source = source,
            isPinned = isPinned,
            isArchived = isArchived
        )
    } catch (e: Exception) {
        Log.e(TAG, "failed to write the memory", e)
        null
    }

    fun togglePin(context: Context, id: String, isPinned: Boolean? = null): Boolean = try {
        db(context)?.togglePin(id, isPinned) ?: false
    } catch (e: Exception) {
        Log.e(TAG, "failed to pin the memory", e)
        false
    }

    fun toggleArchive(context: Context, id: String, isArchived: Boolean? = null): Boolean = try {
        db(context)?.toggleArchive(id, isArchived) ?: false
    } catch (e: Exception) {
        Log.e(TAG, "failed to archive the memory", e)
        false
    }

    fun delete(context: Context, id: String): Boolean = try {
        db(context)?.delete(id) ?: false
    } catch (e: Exception) {
        Log.e(TAG, "failed to delete the memory", e)
        false
    }

    /**
     * Write one fact, without letting two of them contradict each other.
     *
     * The person may say plainly which memory it replaces ([replacesId]); when they do not,
     * a fact that answers the same question (where they live, what they are called, which
     * device they use) replaces the old one instead of standing next to it. The old memory
     * is archived, not deleted: it is still findable, and the other devices see the same
     * change.
     */
    fun writeWithConflict(
        context: Context,
        content: String,
        replacesId: String? = null,
        source: String = "agent"
    ): Boolean {
        val store = db(context) ?: return false
        return try {
            if (!replacesId.isNullOrBlank()) {
                store.supersede(replacesId, content, source = source)
            } else {
                val conflict = store.findDirectConflict(content)
                if (conflict != null) {
                    store.supersede(conflict.id, content, source = source)
                } else {
                    store.upsert(content = content, source = source)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "memory store write failed", e)
            false
        }
    }
}
