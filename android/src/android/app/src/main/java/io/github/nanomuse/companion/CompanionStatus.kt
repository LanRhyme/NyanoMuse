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
        val snapshot = CompanionMoodStore.currentSnapshot(prefs)
        val primaryMood = when (snapshot.primary.family) {
            CompanionMoodStore.AvatarFamily.HAPPY -> CompanionEmotion.PrimaryMood.HAPPY
            CompanionMoodStore.AvatarFamily.CURIOUS -> CompanionEmotion.PrimaryMood.CURIOUS
            CompanionMoodStore.AvatarFamily.LONGING -> CompanionEmotion.PrimaryMood.LONGING
            CompanionMoodStore.AvatarFamily.SLEEPY -> CompanionEmotion.PrimaryMood.SLEEPY
            CompanionMoodStore.AvatarFamily.SHY -> CompanionEmotion.PrimaryMood.SHY
            CompanionMoodStore.AvatarFamily.CARING -> CompanionEmotion.PrimaryMood.CARING
            CompanionMoodStore.AvatarFamily.TOUCHED -> CompanionEmotion.PrimaryMood.TOUCHED
            CompanionMoodStore.AvatarFamily.EXCITED -> CompanionEmotion.PrimaryMood.EXCITED
            CompanionMoodStore.AvatarFamily.SULK_JEALOUS -> CompanionEmotion.PrimaryMood.SULK_JEALOUS
            CompanionMoodStore.AvatarFamily.CRACKED_HURT -> CompanionEmotion.PrimaryMood.CRACKED_HURT
            CompanionMoodStore.AvatarFamily.HYPER -> CompanionEmotion.PrimaryMood.HYPER
        }
        val accumulatedAffection = CompanionPrefs.getAffectionScore(prefs)
        val leaderScore = snapshot.scores[snapshot.primary] ?: snapshot.primary.baseline
        return CompanionEmotion.MoodState(
            primaryMood = primaryMood,
            valence = ((leaderScore - 50f) / 50f).coerceIn(-1f, 1f),
            arousal = (leaderScore / 100f).coerceIn(0f, 1f),
            affection = accumulatedAffection,
            proactiveScore = when (snapshot.primary) {
                CompanionMoodStore.Mood.HYPER, CompanionMoodStore.Mood.EXCITED -> 0.85f
                CompanionMoodStore.Mood.LONELY, CompanionMoodStore.Mood.FLUTTER -> 0.80f
                CompanionMoodStore.Mood.CURIOUS, CompanionMoodStore.Mood.COY -> 0.75f
                CompanionMoodStore.Mood.TIRED, CompanionMoodStore.Mood.CHECKEDOUT, CompanionMoodStore.Mood.CALM -> 0.20f
                else -> 0.50f
            },
            scenario = when (snapshot.primary) {
                CompanionMoodStore.Mood.LONELY -> CompanionEmotion.Scenario.LONGING_MISS
                CompanionMoodStore.Mood.CURIOUS -> CompanionEmotion.Scenario.CURIOUS_CHECKIN
                CompanionMoodStore.Mood.TIRED -> CompanionEmotion.Scenario.EVENING_CARE
                else -> CompanionEmotion.Scenario.FRESH_RESONANCE
            },
            framingInstruction = snapshot.primary.behaviorTendency
        )
    }

    fun currentMoodSubtitle(context: Context): String {
        val prefs = CompanionPrefs.prefs(context)
        val snapshot = CompanionMoodStore.currentSnapshot(prefs)
        return snapshot.displayTitle
    }

    fun currentMoodDescription(context: Context, moodState: CompanionEmotion.MoodState): String {
        val prefs = CompanionPrefs.prefs(context)
        val snapshot = CompanionMoodStore.currentSnapshot(prefs)
        return "${snapshot.displayTitle} · ${snapshot.primary.behaviorTendency}"
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
