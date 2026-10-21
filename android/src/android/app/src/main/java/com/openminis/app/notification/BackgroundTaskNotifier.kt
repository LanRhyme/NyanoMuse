package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * T180-bg-notif: posts task-completion notifications when an agent
 * session finishes while the app is backgrounded. Mirrors iOS
 * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
 *
 * Trigger contract: this class is hooked into [com.openminis.app.service.SessionActivityTracker]
 * so a session transitioning from active → inactive (i.e. its agent
 * loop completed) deterministically reaches `notifyTaskCompleted`. The
 * tracker itself is the single source of truth for "is this session
 * still streaming" — using its callback avoids invading
 * `ChatViewModel`, whose 4 stream-finally blocks would all need the
 * same hook.
 *
 * Behaviour rules:
 * - Skip silently if the user has disabled Task Notifications
 *   ([BackgroundSettingsRepository.taskNotificationsEnabled] = false).
 * - Skip silently if the app is currently in foreground — the user is
 *   already looking at the chat, no need to interrupt.
 * - Tap on the notification deep-links into the originating chat via
 *   `minis://session/<sessionId>` (existing
 *   `DeepLinkHandler.OpenSession` path).
 *
 * On Android the absence of `responseSummary` from the spec is
 * intentional: extracting plain text from a `parts_json` blob is
 * fragile; the notification's deep-link opens the chat where the
 * full response is rendered, matching the user's likely intent.
 */
class BackgroundTaskNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /**
     * Called by [com.openminis.app.service.SessionActivityTracker] when a
     * session finishes (active → inactive transition). Looks up the
     * session title via [chatRepository] and posts the notification, off
     * the main thread. No-ops silently if the user has disabled
     * notifications or the app is in foreground.
     */
    fun notifyTaskCompleted(sessionId: String, isError: Boolean = false) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return

        // nanoMuse: If the user is currently actively viewing this specific session, skip notification.
        // If the user switched to another session or tab (even while in foreground), notify them.
        val currentActiveSession = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId
        val isViewingThisSession = isAppForeground() && (currentActiveSession == sessionId)
        if (isViewingThisSession) return

        // nanoMuse: Provide gentle haptic feedback on completion
        vibrateCompletion(context)

        scope.launch {
            try {
                val session = chatRepository.getSession(sessionId)
                // nanoMuse: Extract agent name, session title, and dynamic excerpt from reply
                val agentName = io.github.nanomuse.identity.NanoMuseIdentity.name(context)
                val sessionTitle = session?.title?.takeIf { it.isNotBlank() }
                val title = if (isError) "❌ $agentName" else agentName
                val replyExcerpt = com.openminis.app.service.SessionActivityTracker.lastReplyExcerpt.value?.takeIf { it.isNotBlank() }
                val body = when {
                    isError -> context.getString(R.string.notif_task_failed_body)
                    replyExcerpt != null -> replyExcerpt
                    else -> context.getString(R.string.notif_task_completed_body)
                }
                postNotification(sessionId, title, body, sessionTitle)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyTaskCompleted failed: ${t.message}")
            }
        }
    }

    private fun postNotification(sessionId: String, title: String, body: String, subText: String? = null) {
        // Pre-Tiramisu: we don't need the runtime permission, just post.
        // Tiramisu+: NotificationManagerCompat.areNotificationsEnabled
        // is the right gate — POST_NOTIFICATIONS is requested at toggle-on
        // time in Settings (separate flow), and if it was denied we silently
        // skip rather than crash.
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("minis://session/$sessionId")
        // nanoMuse: to MainActivity by name, never through the system's choice of an app for the scheme.
        // FLAG_ACTIVITY_NEW_TASK because we're posting from a background scope without an Activity
        // context; FLAG_ACTIVITY_CLEAR_TOP so MainActivity (singleTask) reuses the existing instance
        // and routes the deep-link via onNewIntent rather than spawning a duplicate.
        val launchIntent: Intent = io.github.nanomuse.deeplink.OwnScheme.intent(
            context, deepLink, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nanomuse) // nanoMuse: flat status-bar mark
            .setLargeIcon(io.github.nanomuse.identity.NanoMuseIdentity.face(context)) // nanoMuse: the agent is the sender
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // nanoMuse: Heads-up priority
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        val notification = builder.build()

        try {
            nm.notify(sessionId.hashCode(), notification)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently no-op rather
            // than crashing the agent-completion path.
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    /**
     * nanoMuse: cancel notification for a specific session once opened.
     */
    fun cancelNotification(sessionId: String) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        try {
            nm.cancel(sessionId.hashCode())
        } catch (_: Throwable) {}
    }

    /**
     * T298: cancel every notification posted on the
     * [CHANNEL_ID] channel. Called from MinisApp's foreground transition
     * (Activity start count 0 → 1) so the user never finds a stale "task
     * completed" entry waiting in the tray when they open the app — they
     * just saw the result, the notification has served its purpose.
     *
     * Implementation: walk [NotificationManager.activeNotifications] (API
     * 23+, lower bound is API 26 for our app) and cancel any whose
     * channelId matches ours. We can't filter by channel directly because
     * each notification id is the session hash — there's no single id to
     * cancel — and `cancelAll()` would also nuke the FG service banner.
     */
    fun cancelAllCompletedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        try {
            val active = nm.activeNotifications ?: return
            for (sb in active) {
                if (sb.notification?.channelId == CHANNEL_ID || sb.notification?.channelId == "minis_task_completed") {
                    nm.cancel(sb.tag, sb.id)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cancelAllCompletedNotifications failed: ${t.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        // Clean up legacy low-importance channel
        try {
            manager.deleteNotificationChannel("minis_task_completed")
        } catch (_: Throwable) {}

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_task_completed_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_task_completed_channel_description)
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskNotifier"
        // nanoMuse: High importance channel replacing legacy minis_task_completed
        const val CHANNEL_ID = "nanomuse_task_completed"

        /**
         * nanoMuse: light haptic vibration feedback on AI output completion.
         */
        fun vibrateCompletion(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    v?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    @Suppress("DEPRECATION")
                    v?.vibrate(50)
                }
            } catch (_: Throwable) {}
        }
    }
}
