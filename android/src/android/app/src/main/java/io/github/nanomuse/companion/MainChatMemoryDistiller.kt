package io.github.nanomuse.companion

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import io.github.nanomuse.memory.StructuredMemory

/**
 * Asynchronously distills user facts, preferences and emotional bonds from dropped history
 * into structured memories, maintaining a persistent cursor to prevent duplicate distillation.
 */
object MainChatMemoryDistiller {
    private const val TAG = "MainChatDistiller"
    private const val PREFS_KEY_CURSOR = "companion_distilled_message_cursor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isDistilling = false

    fun getCursor(context: Context): Long =
        CompanionPrefs.prefs(context).getLong(PREFS_KEY_CURSOR, 0L)

    fun setCursor(context: Context, cursor: Long) {
        CompanionPrefs.prefs(context).edit().putLong(PREFS_KEY_CURSOR, cursor).apply()
    }

    fun distillAsync(
        context: Context,
        sessionId: String,
        messages: List<LLMMessage>,
        chatRepository: ChatRepository? = null
    ) {
        if (messages.isEmpty() || isDistilling) return

        val candidateUserTexts = messages.filter { it.role == LLMMessage.Role.USER }
            .map { it.content.trim() }
            .filter { it.isNotBlank() && !it.startsWith("（") && !it.startsWith("<") }

        if (candidateUserTexts.isEmpty()) return

        scope.launch {
            isDistilling = true
            try {
                for (text in candidateUserTexts) {
                    if (isFactCandidate(text)) {
                        AppLogger.info(TAG, "Distilling fact from Main Chat: ${text.take(30)}...")
                        StructuredMemory.writeWithConflict(
                            context = context,
                            content = text,
                            source = "distilled"
                        )
                    } else if (isBondCandidate(text)) {
                        AppLogger.info(TAG, "Distilling companion bond from Main Chat: ${text.take(30)}...")
                        StructuredMemory.upsert(
                            context = context,
                            content = text,
                            category = "bond",
                            source = "distilled"
                        )
                    }
                }
                setCursor(context, System.currentTimeMillis())
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Distillation failed: ${t.message}")
            } finally {
                isDistilling = false
            }
        }
    }

    private fun isFactCandidate(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("记住") ||
            lower.contains("我喜欢") ||
            lower.contains("我不喜欢") ||
            lower.contains("我住在") ||
            lower.contains("我的名字是") ||
            lower.contains("我的生日是") ||
            lower.contains("我平时") ||
            lower.contains("我是")
    }

    private fun isBondCandidate(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("小猫真乖") ||
            lower.contains("最喜欢小猫") ||
            lower.contains("约定") ||
            lower.contains("想你了") ||
            lower.contains("谢谢你陪我") ||
            lower.contains("我们要一直")
    }
}
