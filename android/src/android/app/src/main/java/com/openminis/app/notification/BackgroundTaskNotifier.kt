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
 * Posts task-completion notifications when an agent session finishes.
 * Handles both background completion (high-priority heads-up notification with reply excerpt)
 * and foreground completion (haptic feedback, and notifications when on different screens/tabs).
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
     * session finishes (active → inactive transition).
     */
    fun notifyTaskCompleted(sessionId: String, isError: Boolean = false) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return

        // Always trigger a subtle haptic vibration pulse on completion
        vibrateCompletion(context)

        val isViewingThisSession = isAppForeground() &&
            (com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId == sessionId)

        // If the user is actively viewing this exact session in foreground, don't post a status-bar notification
        if (isViewingThisSession) return

        scope.launch {
            try {
                val session = chatRepository.getSession(sessionId)
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
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("minis://session/$sessionId")
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
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setLargeIcon(io.github.nanomuse.identity.NanoMuseIdentity.face(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        val notification = builder.build()

        try {
            nm.notify(sessionId.hashCode(), notification)
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    fun cancelNotification(sessionId: String) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        try {
            nm.cancel(sessionId.hashCode())
        } catch (_: Throwable) {}
    }

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
        const val CHANNEL_ID = "nanomuse_task_completed"

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
