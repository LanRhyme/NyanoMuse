package io.github.nanomuse.companion

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.memory.MemoryExtractor
import io.github.nanomuse.memory.StructuredMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronously distills user facts, preferences and emotional bonds from dropped history
 * into structured memories, maintaining a persistent cursor to prevent duplicate distillation.
 * Also preserves dropped raw turns into a local JSONL archive so context is never permanently lost.
 */
object MainChatMemoryDistiller {
    private const val TAG = "MainChatDistiller"
    private const val PREFS_KEY_CURSOR = "companion_distilled_message_cursor"
    private const val PREFS_KEY_DISTILLED_IDS = "companion_distilled_msg_ids"
    private const val MAX_TRACKED_IDS = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDistilling = AtomicBoolean(false)

    fun getCursor(context: Context): Long =
        CompanionPrefs.prefs(context).getLong(PREFS_KEY_CURSOR, 0L)

    fun setCursor(context: Context, cursor: Long) {
        CompanionPrefs.prefs(context).edit().putLong(PREFS_KEY_CURSOR, cursor).apply()
    }

    private fun getDistilledIds(context: Context): Set<String> =
        CompanionPrefs.prefs(context).getStringSet(PREFS_KEY_DISTILLED_IDS, emptySet()) ?: emptySet()

    private fun addDistilledIds(context: Context, newIds: Set<String>) {
        if (newIds.isEmpty()) return
        val current = getDistilledIds(context).toMutableSet()
        current.addAll(newIds)
        if (current.size > MAX_TRACKED_IDS) {
            val trimmed = current.toList().takeLast(MAX_TRACKED_IDS).toSet()
            CompanionPrefs.prefs(context).edit().putStringSet(PREFS_KEY_DISTILLED_IDS, trimmed).apply()
        } else {
            CompanionPrefs.prefs(context).edit().putStringSet(PREFS_KEY_DISTILLED_IDS, current).apply()
        }
    }

    fun distillAsync(
        context: Context,
        sessionId: String,
        messages: List<LLMMessage>,
        chatRepository: ChatRepository? = null
    ) {
        if (messages.isEmpty()) return
        // Atomic CAS check to prevent concurrent distillation races (P2-5)
        if (!isDistilling.compareAndSet(false, true)) return

        scope.launch {
            try {
                // 1. Archive dropped messages locally to preserve full verbatim history (P2-3)
                archiveDroppedMessages(context, messages)

                // 2. Filter out already distilled messages using persistent cursor and ID set (P1-3)
                val processedIds = getDistilledIds(context)
                val unconsumedMessages = messages.filter { msg ->
                    msg.role == LLMMessage.Role.USER && (msg.dbMessageId == null || !processedIds.contains(msg.dbMessageId))
                }

                val newlyProcessedIds = mutableSetOf<String>()

                for (msg in unconsumedMessages) {
                    val text = msg.content.trim()
                    if (text.isBlank() || text.startsWith("（") || text.startsWith("<")) {
                        msg.dbMessageId?.let { newlyProcessedIds.add(it) }
                        continue
                    }

                    // 3. Extract facts & preferences via robust MemoryExtractor (11 patterns + privacy check)
                    val facts = MemoryExtractor.extractPassiveMemories(text)
                    for (fact in facts) {
                        AppLogger.info(TAG, "Distilling fact from Main Chat: ${fact.content} (${fact.category})")
                        StructuredMemory.writeWithConflict(
                            context = context,
                            content = fact.content,
                            source = "distilled"
                        )
                    }

                    // 4. Extract companion emotional bonds
                    if (isBondCandidate(text)) {
                        AppLogger.info(TAG, "Distilling companion bond: ${text.take(30)}...")
                        StructuredMemory.upsert(
                            context = context,
                            content = text,
                            category = "bond",
                            source = "distilled"
                        )
                    }

                    msg.dbMessageId?.let { newlyProcessedIds.add(it) }
                }

                // 5. Update cursor and ID tracker
                setCursor(context, System.currentTimeMillis())
                addDistilledIds(context, newlyProcessedIds)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Distillation failed: ${t.message}")
            } finally {
                isDistilling.set(false)
            }
        }
    }

    private fun archiveDroppedMessages(context: Context, messages: List<LLMMessage>) {
        try {
            val archiveDir = File(context.filesDir, "companion")
            if (!archiveDir.exists()) archiveDir.mkdirs()
            val file = File(archiveDir, "main_chat_dropped_archive.jsonl")
            val now = System.currentTimeMillis()
            val lines = messages.map { msg ->
                JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", msg.content)
                    put("dbId", msg.dbMessageId)
                    put("time", now)
                }.toString()
            }
            file.appendText(lines.joinToString("\n") + "\n")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Failed to archive dropped messages: ${t.message}")
        }
    }

    private fun isBondCandidate(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("小猫真乖") ||
            lower.contains("最喜欢小猫") ||
            lower.contains("约定") ||
            lower.contains("想你了") ||
            lower.contains("谢谢你陪我") ||
            lower.contains("好可爱") ||
            lower.contains("我们要一直")
    }
}
