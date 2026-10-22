package io.github.nanomuse.companion

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Preferences and rate-limiting storage for nanoMuse companion proactive dialogues and emotions.
 * Uses the existing "nanomuse" SharedPreferences to avoid multi-storage fragmentation.
 */
object CompanionPrefs {
    const val PREFS_NAME = "nanomuse"

    const val KEY_PROACTIVE_ENABLED = "companion_proactive_enabled"
    const val KEY_QUIET_START_HOUR = "companion_quiet_start_hour"
    const val KEY_QUIET_END_HOUR = "companion_quiet_end_hour"
    const val KEY_MIN_INTERVAL_HOURS = "companion_min_interval_hours"
    const val KEY_DAILY_MAX_COUNT = "companion_daily_max_count"
    const val KEY_PREFERRED_SCENARIOS = "companion_preferred_scenarios"
    const val KEY_PROACTIVE_MODEL_BINDING = "companion_proactive_model_binding"

    // Runtime rate-limiting & caching keys
    const val KEY_LAST_PROACTIVE_TIME = "companion_last_proactive_time"
    const val KEY_TODAY_DATE = "companion_today_date"
    const val KEY_TODAY_COUNT = "companion_today_count"
    const val KEY_CACHED_ACTIVE_DAYS = "companion_cached_active_days"
    const val KEY_CACHED_ACTIVE_DAYS_DATE = "companion_cached_active_days_date"
    const val KEY_LAST_REAL_USER_MESSAGE_TIME = "companion_last_real_user_message_time"

    // Dynamic emotion & affective computing keys
    const val KEY_DYNAMIC_BIAS_P = "companion_dynamic_bias_p"
    const val KEY_DYNAMIC_BIAS_A = "companion_dynamic_bias_a"
    const val KEY_MOOD_TIMESTAMP = "companion_mood_timestamp"
    const val KEY_LAST_DIALOGUE_ACT = "companion_last_dialogue_act"
    const val KEY_EMOTION_INTENSITY = "companion_emotion_intensity"
    const val KEY_AFFECTION_SCORE = "companion_affection_score"
    const val KEY_EMOTION_ENABLED = "companion_emotion_enabled"

    const val DEFAULT_EMOTION_INTENSITY = "normal" // "subtle", "normal", "vivid"
    const val DEFAULT_AFFECTION_SCORE = 0.70f
    const val DEFAULT_EMOTION_ENABLED = true

    // Defaults
    const val DEFAULT_PROACTIVE_ENABLED = true
    const val DEFAULT_QUIET_START_HOUR = 23
    const val DEFAULT_QUIET_END_HOUR = 8
    const val DEFAULT_MIN_INTERVAL_HOURS = 4
    const val DEFAULT_DAILY_MAX_COUNT = 3
    val DEFAULT_PREFERRED_SCENARIOS: Set<String> = setOf(
        "morning_greeting",
        "evening_care",
        "curious_checkin",
        "longing_miss",
        "calm_companion"
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PROACTIVE_ENABLED, DEFAULT_PROACTIVE_ENABLED)

    fun isEnabled(context: Context): Boolean =
        isEnabled(prefs(context))

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }

    fun getQuietStartHour(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_QUIET_START_HOUR, DEFAULT_QUIET_START_HOUR)

    fun getQuietEndHour(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_QUIET_END_HOUR, DEFAULT_QUIET_END_HOUR)

    fun getMinIntervalHours(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_MIN_INTERVAL_HOURS, DEFAULT_MIN_INTERVAL_HOURS)

    fun getDailyMaxCount(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DAILY_MAX_COUNT, DEFAULT_DAILY_MAX_COUNT)

    fun normalizeScenario(scenario: String): String {
        return when (scenario) {
            "morning", "morning_greeting" -> "morning_greeting"
            "evening", "evening_care" -> "evening_care"
            "curious", "curious_checkin" -> "curious_checkin"
            "longing", "longing_miss" -> "longing_miss"
            "calm", "calm_companion" -> "calm_companion"
            else -> scenario
        }
    }

    fun getPreferredScenarios(prefs: SharedPreferences): Set<String> {
        val raw = prefs.getStringSet(KEY_PREFERRED_SCENARIOS, null) ?: DEFAULT_PREFERRED_SCENARIOS
        return raw.map { normalizeScenario(it) }.toSet()
    }

    fun setPreferredScenarios(prefs: SharedPreferences, scenarios: Set<String>) {
        val normalized = scenarios.map { normalizeScenario(it) }.toSet()
        prefs.edit().putStringSet(KEY_PREFERRED_SCENARIOS, normalized).apply()
    }

    fun isScenarioAllowed(preferred: Set<String>, tag: String): Boolean {
        val normTag = normalizeScenario(tag)
        return preferred.map { normalizeScenario(it) }.contains(normTag)
    }

    fun getLastRealUserMessageTime(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_LAST_REAL_USER_MESSAGE_TIME, 0L)

    fun recordLastRealUserMessageTime(prefs: SharedPreferences, at: Long) {
        prefs.edit().putLong(KEY_LAST_REAL_USER_MESSAGE_TIME, at).apply()
    }

    fun recordLastRealUserMessageTime(context: Context, at: Long) {
        recordLastRealUserMessageTime(prefs(context), at)
    }

    fun getLastProactiveTime(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_LAST_PROACTIVE_TIME, 0L)

    fun getProactiveModelBinding(prefs: SharedPreferences): String? =
        prefs.getString(KEY_PROACTIVE_MODEL_BINDING, null)

    fun setProactiveModelBinding(prefs: SharedPreferences, binding: String?) {
        prefs.edit().putString(KEY_PROACTIVE_MODEL_BINDING, binding).apply()
    }

    /**
     * Format timestamp as "yyyy-MM-dd" using given timezone.
     */
    fun formatDate(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = timeZone
        return sdf.format(Date(timestamp))
    }

    /**
     * Pure function to determine whether a given hour falls into the quiet hours window.
     * Supports midnight wrap (e.g. 23:00 to 08:00).
     */
    fun isQuietHours(hourOfDay: Int, startHour: Int, endHour: Int): Boolean {
        if (startHour == endHour) return false
        return if (startHour < endHour) {
            hourOfDay in startHour until endHour
        } else {
            hourOfDay >= startHour || hourOfDay < endHour
        }
    }

    /**
     * Returns today's proactive trigger count, safely resetting if the recorded date differs from today.
     */
    fun getTodayTriggerCount(prefs: SharedPreferences, now: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val todayStr = formatDate(now, timeZone)
        val recordedDate = prefs.getString(KEY_TODAY_DATE, null)
        return if (recordedDate == todayStr) {
            prefs.getInt(KEY_TODAY_COUNT, 0)
        } else {
            0
        }
    }

    /**
     * Pure condition check on raw primitive values: can a proactive trigger fire?
     */
    fun checkCanTrigger(
        enabled: Boolean,
        now: Long,
        hourOfDay: Int,
        quietStart: Int,
        quietEnd: Int,
        todayCount: Int,
        dailyMaxCount: Int,
        lastProactiveTime: Long,
        minIntervalHours: Int
    ): Boolean {
        if (!enabled) return false
        if (isQuietHours(hourOfDay, quietStart, quietEnd)) return false
        if (todayCount >= dailyMaxCount) return false

        val minIntervalMs = minIntervalHours * 3600_000L
        if (lastProactiveTime > 0L && now - lastProactiveTime < minIntervalMs) return false

        return true
    }

    /**
     * Convenience wrapper that extracts state from SharedPreferences and delegates to [checkCanTrigger].
     */
    fun canTriggerProactive(
        prefs: SharedPreferences,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return checkCanTrigger(
            enabled = isEnabled(prefs),
            now = now,
            hourOfDay = hour,
            quietStart = getQuietStartHour(prefs),
            quietEnd = getQuietEndHour(prefs),
            todayCount = getTodayTriggerCount(prefs, now, timeZone),
            dailyMaxCount = getDailyMaxCount(prefs),
            lastProactiveTime = getLastProactiveTime(prefs),
            minIntervalHours = getMinIntervalHours(prefs)
        )
    }

    /**
     * Records a successful proactive trigger, incrementing today's count and updating last triggered timestamp.
     */
    fun recordProactiveTriggered(
        prefs: SharedPreferences,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ) {
        val todayStr = formatDate(now, timeZone)
        val currentCount = getTodayTriggerCount(prefs, now, timeZone)
        prefs.edit()
            .putLong(KEY_LAST_PROACTIVE_TIME, now)
            .putString(KEY_TODAY_DATE, todayStr)
            .putInt(KEY_TODAY_COUNT, currentCount + 1)
            .apply()
    }

    /**
     * Retrieves cached active interaction days count for the given day, or null if cache is stale.
     */
    fun getCachedActiveDays(
        prefs: SharedPreferences,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Int? {
        val todayStr = formatDate(now, timeZone)
        val cachedDate = prefs.getString(KEY_CACHED_ACTIVE_DAYS_DATE, null)
        return if (cachedDate == todayStr) {
            prefs.getInt(KEY_CACHED_ACTIVE_DAYS, 1)
        } else {
            null
        }
    }

    /**
     * Updates the once-a-day cache for active interaction days.
     */
    fun setCachedActiveDays(
        prefs: SharedPreferences,
        activeDays: Int,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ) {
        val todayStr = formatDate(now, timeZone)
        prefs.edit()
            .putString(KEY_CACHED_ACTIVE_DAYS_DATE, todayStr)
            .putInt(KEY_CACHED_ACTIVE_DAYS, activeDays)
            .apply()
    }

    fun isEmotionEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_EMOTION_ENABLED, DEFAULT_EMOTION_ENABLED)

    fun setEmotionEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EMOTION_ENABLED, enabled).apply()
    }

    fun getEmotionIntensity(prefs: SharedPreferences): String =
        prefs.getString(KEY_EMOTION_INTENSITY, DEFAULT_EMOTION_INTENSITY) ?: DEFAULT_EMOTION_INTENSITY

    fun setEmotionIntensity(prefs: SharedPreferences, intensity: String) {
        prefs.edit().putString(KEY_EMOTION_INTENSITY, intensity).apply()
    }

    fun getAffectionScore(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_AFFECTION_SCORE, DEFAULT_AFFECTION_SCORE).coerceIn(0.0f, 1.0f)

    fun addAffectionScore(prefs: SharedPreferences, delta: Float) {
        val cur = getAffectionScore(prefs)
        val next = (cur + delta).coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat(KEY_AFFECTION_SCORE, next).apply()
    }

    fun resetAffectionScore(prefs: SharedPreferences) {
        prefs.edit().putFloat(KEY_AFFECTION_SCORE, DEFAULT_AFFECTION_SCORE).apply()
    }

    fun getDynamicBiasP(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_DYNAMIC_BIAS_P, 0f)

    fun getDynamicBiasA(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_DYNAMIC_BIAS_A, 0f)

    fun getMoodTimestamp(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_MOOD_TIMESTAMP, 0L)

    fun getLastDialogueAct(prefs: SharedPreferences): CompanionEmotion.DialogueAct? {
        val raw = prefs.getString(KEY_LAST_DIALOGUE_ACT, null) ?: return null
        return runCatching { CompanionEmotion.DialogueAct.valueOf(raw) }.getOrNull()
    }

    fun recordEmotionImpulse(
        prefs: SharedPreferences,
        impulse: CompanionEmotion.EmotionImpulse,
        act: CompanionEmotion.DialogueAct,
        now: Long = System.currentTimeMillis()
    ) {
        val intensity = getEmotionIntensity(prefs)
        val multiplier = when (intensity) {
            "subtle" -> 0.5f
            "vivid" -> 1.4f
            else -> 1.0f
        }
        val curBiasP = getDynamicBiasP(prefs)
        val curBiasA = getDynamicBiasA(prefs)

        // Blend impulse into current bias with saturation bounds [-1.0, 1.0]
        val nextBiasP = (curBiasP * 0.4f + impulse.dP * multiplier).coerceIn(-1.0f, 1.0f)
        val nextBiasA = (curBiasA * 0.4f + impulse.dA * multiplier).coerceIn(-1.0f, 1.0f)

        addAffectionScore(prefs, impulse.dAffection * multiplier)

        prefs.edit()
            .putFloat(KEY_DYNAMIC_BIAS_P, nextBiasP)
            .putFloat(KEY_DYNAMIC_BIAS_A, nextBiasA)
            .putLong(KEY_MOOD_TIMESTAMP, now)
            .putString(KEY_LAST_DIALOGUE_ACT, act.name)
            .apply()
    }

    fun getUserAddress(context: Context): String {
        val p = prefs(context)
        val addr = p.getString("first_conversation_address", null)?.trim()
        if (!addr.isNullOrEmpty()) return addr
        return "老大"
    }
}
