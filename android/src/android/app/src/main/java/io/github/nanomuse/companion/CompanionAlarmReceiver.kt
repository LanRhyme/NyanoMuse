package io.github.nanomuse.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.home.MainChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Periodic broadcast receiver evaluating companion proactive dialogue conditions.
 * Uses inexact alarm / setAndAllowWhileIdle to stay friendly to Android Doze mode.
 */
class CompanionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? MinisApp ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                evaluateAndTrigger(context, app)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Companion evaluation failed: ${t.message}")
            } finally {
                scheduleNext(context)
                pendingResult.finish()
            }
        }
    }

    private suspend fun evaluateAndTrigger(context: Context, app: MinisApp) {
        val prefs = CompanionPrefs.prefs(context)
        val now = System.currentTimeMillis()

        // 1. Fast check if proactive is enabled and allowed by cooldown / quiet hours / daily limit
        if (!CompanionPrefs.canTriggerProactive(prefs, now)) {
            return
        }

        // 2. Resolve main chat ID
        val mainChatId = MainChat.resolve(context, app.chatRepository)
        if (MainChat.isDraftId(mainChatId)) {
            return
        }

        // 3. Query real user message created_at in main chat (excluding routine/companion wakes)
        val stamps = app.chatRepository.dao.recentUserMessageStamps(mainChatId, 20)
        var lastUserTime = 0L
        for (stamp in stamps) {
            if (io.github.nanomuse.chat.RoutineWakes.wakeFor(stamp.id) == null) {
                lastUserTime = stamp.createdAt
                break
            }
        }
        if (lastUserTime == 0L) {
            lastUserTime = stamps.lastOrNull()?.createdAt ?: 0L
        }
        if (lastUserTime > 0L) {
            CompanionPrefs.recordLastRealUserMessageTime(prefs, lastUserTime)
        }

        // 4. Query active interaction days (cached once per day with local timezone offset)
        var activeDays = CompanionPrefs.getCachedActiveDays(prefs, now)
        if (activeDays == null) {
            val tzOffsetMs = java.util.TimeZone.getDefault().getOffset(now).toLong()
            val countedDays = app.chatRepository.dao.distinctUserMessageDays(mainChatId, tzOffsetMs)
            activeDays = maxOf(1, countedDays)
            CompanionPrefs.setCachedActiveDays(prefs, activeDays, now)
        }

        // 5. Evaluate mood and proactive willingness via pure function
        val quietStart = CompanionPrefs.getQuietStartHour(prefs)
        val quietEnd = CompanionPrefs.getQuietEndHour(prefs)
        val moodState = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = lastUserTime,
            activeDaysCount = activeDays,
            quietHoursStart = quietStart,
            quietHoursEnd = quietEnd
        )

        // 6. Check proactive score threshold and user preferred scenarios (flexible prefix/tag matching)
        val preferredScenarios = CompanionPrefs.getPreferredScenarios(prefs)
        if (moodState.proactiveScore >= 0.60f && CompanionPrefs.isScenarioAllowed(preferredScenarios, moodState.scenarioTag)) {
            AppLogger.info(TAG, "Triggering companion proactive dialogue: scenario=${moodState.scenarioTag} mood=${moodState.primaryMood}")
            val ok = CompanionWake.dispatch(context, mainChatId, moodState)
            if (ok) {
                CompanionPrefs.recordProactiveTriggered(prefs, now)
            }
        }
    }

    companion object {
        private const val TAG = "CompanionAlarm"
        private const val REQUEST_CODE = 0x50524F // "PRO"
        private const val INTERVAL_MS = 30 * 60_000L // 30 minutes

        fun scheduleNext(context: Context) {
            val prefs = CompanionPrefs.prefs(context)
            if (!CompanionPrefs.isEnabled(prefs)) {
                cancel(context)
                return
            }

            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, CompanionAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, CompanionAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
    }
}
