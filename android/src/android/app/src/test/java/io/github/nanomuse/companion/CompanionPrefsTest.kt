package io.github.nanomuse.companion

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CompanionPrefsTest {

    private class MockPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class MockEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                removed.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }
    }

    private lateinit var prefs: MockPrefs
    private val utc = TimeZone.getTimeZone("UTC")

    @Before
    fun setup() {
        prefs = MockPrefs()
    }

    @Test
    fun testIsQuietHoursMidnightWrap() {
        // Quiet window: 23:00 to 08:00
        assertTrue(CompanionPrefs.isQuietHours(23, 23, 8))
        assertTrue(CompanionPrefs.isQuietHours(0, 23, 8))
        assertTrue(CompanionPrefs.isQuietHours(4, 23, 8))
        assertTrue(CompanionPrefs.isQuietHours(7, 23, 8))
        assertFalse(CompanionPrefs.isQuietHours(8, 23, 8))
        assertFalse(CompanionPrefs.isQuietHours(12, 23, 8))
        assertFalse(CompanionPrefs.isQuietHours(22, 23, 8))
    }

    @Test
    fun testIsQuietHoursSameDay() {
        // Quiet window: 01:00 to 06:00
        assertTrue(CompanionPrefs.isQuietHours(1, 1, 6))
        assertTrue(CompanionPrefs.isQuietHours(3, 1, 6))
        assertFalse(CompanionPrefs.isQuietHours(6, 1, 6))
        assertFalse(CompanionPrefs.isQuietHours(0, 1, 6))
        assertFalse(CompanionPrefs.isQuietHours(15, 1, 6))
    }

    @Test
    fun testNeverProactiveBeforeSentinelWorksEvenAtLowNow() {
        // Sentinel test: if lastProactiveTime is 0L, even if now is a small timestamp (e.g. 1000L),
        // it must allow triggering and NOT be blocked by cooldown.
        val smallNow = 1000L // 1 second past epoch
        assertTrue(
            CompanionPrefs.checkCanTrigger(
                enabled = true,
                now = smallNow,
                hourOfDay = 14,
                quietStart = 23,
                quietEnd = 8,
                todayCount = 0,
                dailyMaxCount = 3,
                lastProactiveTime = 0L,
                minIntervalHours = 4
            )
        )
    }

    @Test
    fun testTodayTriggerCountResetAcrossDays() {
        val day1 = 1700000000000L
        CompanionPrefs.recordProactiveTriggered(prefs, day1, utc)
        assertEquals(1, CompanionPrefs.getTodayTriggerCount(prefs, day1, utc))

        // Trigger again on day1
        CompanionPrefs.recordProactiveTriggered(prefs, day1 + 3600_000L, utc)
        assertEquals(2, CompanionPrefs.getTodayTriggerCount(prefs, day1, utc))

        // Move to next day (day1 + 24 hours)
        val day2 = day1 + 24 * 3600_000L
        assertEquals(0, CompanionPrefs.getTodayTriggerCount(prefs, day2, utc))
    }

    @Test
    fun testCanTriggerProactiveEnforcesCooldownAndLimit() {
        val cal = Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 21)
            set(Calendar.HOUR_OF_DAY, 14) // 14:00 daytime
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val t0 = cal.timeInMillis

        // Initially enabled with no prior triggers
        assertTrue(CompanionPrefs.canTriggerProactive(prefs, t0, utc))

        // Trigger 1 at 14:00
        CompanionPrefs.recordProactiveTriggered(prefs, t0, utc)
        assertEquals(1, CompanionPrefs.getTodayTriggerCount(prefs, t0, utc))

        // 2 hours later (16:00): cooldown is 4 hours by default -> should be false
        val t2 = t0 + 2 * 3600_000L
        assertFalse(CompanionPrefs.canTriggerProactive(prefs, t2, utc))

        // 4 hours later (18:00): cooldown satisfied -> should be true
        val t4 = t0 + 4 * 3600_000L
        assertTrue(CompanionPrefs.canTriggerProactive(prefs, t4, utc))

        // Trigger 2 at 18:00
        CompanionPrefs.recordProactiveTriggered(prefs, t4, utc)
        assertEquals(2, CompanionPrefs.getTodayTriggerCount(prefs, t4, utc))

        // 4 hours later (22:00) -> Trigger 3
        val t8 = t4 + 4 * 3600_000L
        assertTrue(CompanionPrefs.canTriggerProactive(prefs, t8, utc))
        CompanionPrefs.recordProactiveTriggered(prefs, t8, utc)
        assertEquals(3, CompanionPrefs.getTodayTriggerCount(prefs, t8, utc))

        // Now max count (3) reached: even after cooldown, should NOT trigger on same day
        assertFalse(CompanionPrefs.canTriggerProactive(prefs, t8 + 1000L, utc))
    }

    @Test
    fun testCanTriggerProactiveBlockedInQuietHours() {
        val cal = Calendar.getInstance(utc).apply {
            set(Calendar.HOUR_OF_DAY, 23) // Night 23:00
        }
        assertFalse(CompanionPrefs.canTriggerProactive(prefs, cal.timeInMillis, utc))

        cal.set(Calendar.HOUR_OF_DAY, 3) // Night 03:00
        assertFalse(CompanionPrefs.canTriggerProactive(prefs, cal.timeInMillis, utc))
    }

    @Test
    fun testActiveDaysCache() {
        val t0 = 1700000000000L
        assertNull(CompanionPrefs.getCachedActiveDays(prefs, t0, utc))

        CompanionPrefs.setCachedActiveDays(prefs, 15, t0, utc)
        assertEquals(15, CompanionPrefs.getCachedActiveDays(prefs, t0, utc))

        // Next day cache is stale
        val nextDay = t0 + 24 * 3600_000L
        assertNull(CompanionPrefs.getCachedActiveDays(prefs, nextDay, utc))
    }

    @Test
    fun testPreferredScenarios() {
        assertEquals(CompanionPrefs.DEFAULT_PREFERRED_SCENARIOS, CompanionPrefs.getPreferredScenarios(prefs))

        val custom = setOf("morning", "evening")
        CompanionPrefs.setPreferredScenarios(prefs, custom)
        assertEquals(custom, CompanionPrefs.getPreferredScenarios(prefs))

        assertTrue(CompanionPrefs.isScenarioAllowed(setOf("morning_greeting"), "morning_greeting"))
        assertTrue(CompanionPrefs.isScenarioAllowed(setOf("morning"), "morning_greeting"))
        assertFalse(CompanionPrefs.isScenarioAllowed(setOf("evening_care"), "morning_greeting"))
    }
}
