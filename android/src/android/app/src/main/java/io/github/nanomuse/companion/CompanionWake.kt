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
            CompanionEmotion.Scenario.MORNING_GREETING -> "（早晨元气满满地伸个懒腰，可调用工具感知${address}的电量和状态，向${address}问候早安，若前文已有交流则自然承接）"
            CompanionEmotion.Scenario.EVENING_CARE -> "（夜深了，打个哈欠，感知${address}是否还在用手机，提醒${address}早点休息）"
            CompanionEmotion.Scenario.CURIOUS_CHECKIN -> "（探出小脑袋，可调用工具看看${address}当前正在忙什么，轻声关心${address}）"
            CompanionEmotion.Scenario.LONGING_MISS -> "（好久没见${address}了，感知${address}的动态，软乎乎地蹭蹭求抱抱，表达想念）"
            else -> "（亲昵地蹭蹭${address}，关心${address}当前状态）"
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

    /**
     * Triggered when the user enters the Main Chat in the foreground after a period of absence (>30m).
     * Proactively calls sensory tools to inspect current user activity and delivers a lively welcome.
     */
    suspend fun dispatchForegroundGreeting(
        context: Context,
        sessionId: String,
    ): Boolean = withContext(Dispatchers.Main) {
        val app = context.applicationContext as? MinisApp ?: return@withContext false
        val session = app.chatRepository.getSession(sessionId) ?: return@withContext false

        val moodState = CompanionStatus.currentMood(context)
        val address = CompanionPrefs.getUserAddress(context)

        val label = context.getString(R.string.nm_status_waiting)
        val promptText = "（注意到${address}打开了应用，先调用感知工具看看${address}刚才或正在干嘛，然后欢快软萌地打个招呼）"
        val framing = "The user just returned and opened nanoMuse. Sense their context with tools (e.g. foreground_app, device_info, screen_capture) and give a warm, lively companion greeting."

        RoutineWakes.armCompanion(
            context = context,
            sessionId = sessionId,
            label = label,
            framingInstruction = framing
        )

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
                AppLogger.warning(TAG, "Companion foreground greeting failed: ${t.message}")
                return@withContext false
            }

            if (result.status != "Completed" || result.responseText.isNullOrBlank()) {
                RoutineWakes.disarm(sessionId)
                return@withContext false
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
