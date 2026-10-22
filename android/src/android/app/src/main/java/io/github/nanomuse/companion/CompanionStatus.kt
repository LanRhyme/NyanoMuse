package io.github.nanomuse.companion

import android.content.Context
import com.openminis.app.R
import io.github.nanomuse.home.MainChat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Status helper providing live companion emotion subtitles for the Main Chat header.
 */
object CompanionStatus {

    private val _emotionVersion = MutableStateFlow(0L)
    val emotionVersion: StateFlow<Long> = _emotionVersion.asStateFlow()

    fun notifyEmotionChanged() {
        _emotionVersion.value++
    }

    fun currentMood(context: Context): CompanionEmotion.MoodState {
        val prefs = CompanionPrefs.prefs(context)
        val now = System.currentTimeMillis()
        val realUserTime = CompanionPrefs.getLastRealUserMessageTime(prefs)
        val lastTime = if (realUserTime > 0L) realUserTime else prefs.getLong(MainChat.KEY_LAST_ACTIVE_TIME, now)
        val activeDays = CompanionPrefs.getCachedActiveDays(prefs, now) ?: 1
        val baseState = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = lastTime,
            activeDaysCount = activeDays,
            quietHoursStart = CompanionPrefs.getQuietStartHour(prefs),
            quietHoursEnd = CompanionPrefs.getQuietEndHour(prefs)
        )

        val biasP = CompanionPrefs.getDynamicBiasP(prefs)
        val biasA = CompanionPrefs.getDynamicBiasA(prefs)
        val moodTime = CompanionPrefs.getMoodTimestamp(prefs)
        val lastAct = CompanionPrefs.getLastDialogueAct(prefs)
        val elapsed = if (moodTime > 0L) maxOf(0L, now - moodTime) else 0L

        val blended = CompanionEmotion.blendWithAsymmetricDecay(baseState, biasP, biasA, elapsed, lastAct)
        val accumulatedAffection = CompanionPrefs.getAffectionScore(prefs)
        return blended.copy(affection = accumulatedAffection)
    }

    fun currentMoodSubtitle(context: Context): String {
        val moodState = currentMood(context)
        return when (moodState.primaryMood) {
            CompanionEmotion.PrimaryMood.HAPPY -> context.getString(R.string.nm_companion_mood_happy)
            CompanionEmotion.PrimaryMood.CURIOUS -> context.getString(R.string.nm_companion_mood_curious)
            CompanionEmotion.PrimaryMood.LONGING -> context.getString(R.string.nm_companion_mood_longing)
            CompanionEmotion.PrimaryMood.SLEEPY -> context.getString(R.string.nm_companion_mood_sleepy)
            CompanionEmotion.PrimaryMood.SHY -> context.getString(R.string.nm_companion_mood_shy)
            CompanionEmotion.PrimaryMood.CARING -> context.getString(R.string.nm_companion_mood_caring)
            CompanionEmotion.PrimaryMood.TOUCHED -> context.getString(R.string.nm_companion_mood_touched)
            CompanionEmotion.PrimaryMood.EXCITED -> context.getString(R.string.nm_companion_mood_excited)
        }
    }

    fun currentMoodDescription(context: Context, moodState: CompanionEmotion.MoodState): String {
        val address = CompanionPrefs.getUserAddress(context)
        return when (moodState.primaryMood) {
            CompanionEmotion.PrimaryMood.HAPPY -> context.getString(R.string.nm_companion_desc_happy)
            CompanionEmotion.PrimaryMood.SHY -> context.getString(R.string.nm_companion_desc_shy)
            CompanionEmotion.PrimaryMood.CARING -> context.getString(R.string.nm_companion_desc_caring, address)
            CompanionEmotion.PrimaryMood.TOUCHED -> context.getString(R.string.nm_companion_desc_touched)
            CompanionEmotion.PrimaryMood.EXCITED -> context.getString(R.string.nm_companion_desc_excited)
            CompanionEmotion.PrimaryMood.CURIOUS -> context.getString(R.string.nm_companion_desc_curious)
            CompanionEmotion.PrimaryMood.SLEEPY -> context.getString(R.string.nm_companion_desc_sleepy)
            CompanionEmotion.PrimaryMood.LONGING -> context.getString(R.string.nm_companion_desc_longing)
        }
    }

    fun currentMoodDescription(context: Context): String =
        currentMoodDescription(context, currentMood(context))

    fun probeDetails(context: Context): String {
        val moodState = currentMood(context)
        val prefs = CompanionPrefs.prefs(context)
        val now = System.currentTimeMillis()
        val moodTime = CompanionPrefs.getMoodTimestamp(prefs)
        val elapsedMin = if (moodTime > 0L) (now - moodTime) / 60_000L else 0L
        val act = CompanionPrefs.getLastDialogueAct(prefs)?.name ?: "NONE"
        val intensity = CompanionPrefs.getEmotionIntensity(prefs)
        return "PAD: [P=${String.format(java.util.Locale.US, "%+.2f", moodState.valence)}, A=${String.format(java.util.Locale.US, "%.2f", moodState.arousal)}, D=${String.format(java.util.Locale.US, "%.2f", moodState.affection)}]\n" +
               "Mood: ${moodState.primaryMood.name} | Last Act: $act (${elapsedMin}m ago)\n" +
               "Affection: ${String.format(java.util.Locale.US, "%.2f", moodState.affection)} / 1.00 | Intensity: $intensity"
    }
}
