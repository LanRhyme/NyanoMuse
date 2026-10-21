package io.github.nanomuse.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * Dedicated high-priority notification channel and dispatcher for companion proactive dialogues.
 * Independent of background task completion notifications, allowing user to independently toggle
 * or silence companion proactive messages without affecting task completions.
 */
object CompanionNotifications {
    private const val TAG = "CompanionNotif"
    const val CHANNEL_ID = "nanomuse_companion_proactive"
    const val NOTIFICATION_ID = 0x50524F41 // "PROA"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val name = io.github.nanomuse.identity.NanoMuseIdentity.name(context)
        val channelName = context.getString(R.string.nm_companion_channel_name, name)
        val channelDesc = context.getString(R.string.nm_companion_channel_description)

        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = channelDesc
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 80, 150)
        }
        manager.createNotificationChannel(channel)
    }

    fun postProactiveNotification(
        context: Context,
        sessionId: String,
        mood: CompanionEmotion.PrimaryMood,
        text: String
    ) {
        ensureChannel(context)

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
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val agentName = io.github.nanomuse.identity.NanoMuseIdentity.name(context)
        val moodSubtitle = when (mood) {
            CompanionEmotion.PrimaryMood.HAPPY -> context.getString(R.string.nm_companion_mood_happy)
            CompanionEmotion.PrimaryMood.CURIOUS -> context.getString(R.string.nm_companion_mood_curious)
            CompanionEmotion.PrimaryMood.LONGING -> context.getString(R.string.nm_companion_mood_longing)
            CompanionEmotion.PrimaryMood.SLEEPY -> context.getString(R.string.nm_companion_mood_sleepy)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setLargeIcon(io.github.nanomuse.identity.NanoMuseIdentity.face(context))
            .setContentTitle(agentName)
            .setSubText(moodSubtitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "postProactiveNotification denied (POST_NOTIFICATIONS not granted)")
        }
    }

    fun cancel(context: Context) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        try {
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }
}
