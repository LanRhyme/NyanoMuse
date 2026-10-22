package io.github.nanomuse.companion

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.chat.RoutineWakes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles proactive message dispatch into Main Chat and surfaces high-priority notifications.
 */
object CompanionWake {
    private const val TAG = "CompanionWake"

    suspend fun dispatch(
        context: Context,
        sessionId: String,
        moodState: CompanionEmotion.MoodState
    ): Boolean = withContext(Dispatchers.Main) {
        val app = context.applicationContext as? MinisApp ?: return@withContext false
        val session = app.chatRepository.getSession(sessionId) ?: return@withContext false

        // 1. Choose natural intent label & quiet prompt based on mood scenario
        val label = when (moodState.primaryMood) {
            CompanionEmotion.PrimaryMood.HAPPY -> context.getString(R.string.nm_companion_mood_happy)
            CompanionEmotion.PrimaryMood.CURIOUS -> context.getString(R.string.nm_companion_mood_curious)
            CompanionEmotion.PrimaryMood.LONGING -> context.getString(R.string.nm_companion_mood_longing)
            CompanionEmotion.PrimaryMood.SLEEPY -> context.getString(R.string.nm_companion_mood_sleepy)
            CompanionEmotion.PrimaryMood.SHY -> context.getString(R.string.nm_companion_mood_shy)
            CompanionEmotion.PrimaryMood.CARING -> context.getString(R.string.nm_companion_mood_caring)
            CompanionEmotion.PrimaryMood.TOUCHED -> context.getString(R.string.nm_companion_mood_touched)
            CompanionEmotion.PrimaryMood.EXCITED -> context.getString(R.string.nm_companion_mood_excited)
        }

        val address = CompanionPrefs.getUserAddress(context)
        val promptText = when (moodState.scenario) {
            CompanionEmotion.Scenario.MORNING_GREETING -> "（早晨元气满满地伸个懒腰，向${address}问候早安）"
            CompanionEmotion.Scenario.EVENING_CARE -> "（打个哈欠，提醒${address}早点休息，明天再见）"
            CompanionEmotion.Scenario.CURIOUS_CHECKIN -> "（探出小脑袋，轻声关心${address}今天顺不顺利）"
            CompanionEmotion.Scenario.LONGING_MISS -> "（好久没见${address}了，软乎乎地蹭蹭求抱抱，表达想念）"
            else -> "（亲昵地蹭蹭${address}）"
        }

        // 2. Arm companion routine wake with companion-specific framing instruction
        RoutineWakes.armCompanion(
            context = context,
            sessionId = sessionId,
            label = label,
            framingInstruction = moodState.framingInstruction
        )

        // 3. Optional: apply model binding if configured by user, keeping original binding to restore in finally
        val customBinding = CompanionPrefs.getProactiveModelBinding(CompanionPrefs.prefs(context))
        val originalBinding = session.modelBinding
        val originalModelId = session.modelId
        var bindingSwapped = false

        if (!customBinding.isNullOrBlank() && customBinding != originalBinding) {
            runCatching {
                app.chatRepository.updateSessionBinding(sessionId, customBinding, "")
                bindingSwapped = true
            }
        }

        try {
            // 4. Send prompt headless with timeout
            val result = try {
                HeadlessChatRunner.prompt(
                    context = context,
                    sessionId = sessionId,
                    text = promptText,
                    wait = true,
                    timeoutMs = 45_000L
                )
            } catch (t: Throwable) {
                RoutineWakes.disarm(sessionId)
                AppLogger.warning(TAG, "Companion proactive prompt failed: ${t.message}")
                return@withContext false
            }

            if (result.status != "Completed" || result.responseText.isNullOrBlank()) {
                RoutineWakes.disarm(sessionId)
                return@withContext false
            }

            // 5. Post notification if user is not actively viewing main chat
            val activeSession = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId
            val isViewingMainChat = (app.isAppForeground() && activeSession == sessionId)
            if (!isViewingMainChat) {
                CompanionNotifications.postProactiveNotification(
                    context = context,
                    sessionId = sessionId,
                    mood = moodState.primaryMood,
                    text = result.responseText.trim()
                )
            }

            return@withContext true
        } finally {
            if (bindingSwapped) {
                runCatching {
                    app.chatRepository.updateSessionBinding(sessionId, originalBinding ?: "", originalModelId ?: "")
                }
            }
        }
    }
}
