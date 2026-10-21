package io.github.nanomuse.companion

import android.content.Context
import com.openminis.app.R
import io.github.nanomuse.home.MainChat

/**
 * Status helper providing live companion emotion subtitles for the Main Chat header.
 */
object CompanionStatus {

    fun currentMood(context: Context): CompanionEmotion.MoodState {
        val prefs = CompanionPrefs.prefs(context)
        val now = System.currentTimeMillis()
        val lastTime = prefs.getLong(MainChat.KEY_LAST_ACTIVE_TIME, now)
        val activeDays = CompanionPrefs.getCachedActiveDays(prefs, now) ?: 1
        return CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = lastTime,
            activeDaysCount = activeDays,
            quietHoursStart = CompanionPrefs.getQuietStartHour(prefs),
            quietHoursEnd = CompanionPrefs.getQuietEndHour(prefs)
        )
    }

    fun currentMoodSubtitle(context: Context): String {
        val moodState = currentMood(context)
        return when (moodState.primaryMood) {
            CompanionEmotion.PrimaryMood.HAPPY -> context.getString(R.string.nm_companion_mood_happy)
            CompanionEmotion.PrimaryMood.CURIOUS -> context.getString(R.string.nm_companion_mood_curious)
            CompanionEmotion.PrimaryMood.LONGING -> context.getString(R.string.nm_companion_mood_longing)
            CompanionEmotion.PrimaryMood.SLEEPY -> context.getString(R.string.nm_companion_mood_sleepy)
        }
    }
}
