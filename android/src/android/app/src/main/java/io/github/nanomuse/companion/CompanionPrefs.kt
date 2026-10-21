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

    // Defaults
    const val DEFAULT_PROACTIVE_ENABLED = true
    const val DEFAULT_QUIET_START_HOUR = 23
    const val DEFAULT_QUIET_END_HOUR = 8
    const val DEFAULT_MIN_INTERVAL_HOURS = 4
    const val DEFAULT_DAILY_MAX_COUNT = 3
    val DEFAULT_PREFERRED_SCENARIOS: Set<String> = setOf("morning", "evening", "curious", "longing")

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

    fun getPreferredScenarios(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_PREFERRED_SCENARIOS, DEFAULT_PREFERRED_SCENARIOS) ?: DEFAULT_PREFERRED_SCENARIOS

    fun setPreferredScenarios(prefs: SharedPreferences, scenarios: Set<String>) {
        prefs.edit().putStringSet(KEY_PREFERRED_SCENARIOS, scenarios).apply()
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
}
