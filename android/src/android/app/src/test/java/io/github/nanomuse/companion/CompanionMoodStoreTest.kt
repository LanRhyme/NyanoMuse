package io.github.nanomuse.companion

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompanionMoodStoreTest {

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

    @Before
    fun setup() {
        prefs = MockPrefs()
    }

    @Test
    fun testInitialSnapshotHas33MoodsAndCalmLeader() {
        val snapshot = CompanionMoodStore.currentSnapshot(prefs)
        assertEquals(33, CompanionMoodStore.Mood.entries.size)
        assertEquals(33, snapshot.scores.size)
        assertEquals(CompanionMoodStore.Mood.CALM, snapshot.primary)
        assertEquals(CompanionMoodStore.Mood.CALM.baseline, snapshot.scores[CompanionMoodStore.Mood.CALM])
    }

    @Test
    fun testScoreClampingAndAdjustment() {
        val map = mapOf(
            CompanionMoodStore.Mood.HAPPY to 80.0f,
            CompanionMoodStore.Mood.ANNOYED to -20.0f
        )
        val snapshot = CompanionMoodStore.applyAdjustment(prefs, map, now = 1000L)
        assertEquals(100.0f, snapshot.scores[CompanionMoodStore.Mood.HAPPY] ?: 0f, 0.01f)
        assertEquals(0.0f, snapshot.scores[CompanionMoodStore.Mood.ANNOYED] ?: 0f, 0.01f)
    }

    @Test
    fun testPraiseMessageIncreasesAffectionAndSuppressesAnnoyed() {
        // Set ANNOYED initially high
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.ANNOYED to 30.0f),
            now = 1000L
        )
        val before = CompanionMoodStore.currentSnapshot(prefs).scores[CompanionMoodStore.Mood.ANNOYED] ?: 0f
        assertTrue(before > 30f)

        // Praise message
        val snapshot = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "老大夸你：真棒，太喜欢小猫了！",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 2000L
        )

        val afterAnnoyed = snapshot.scores[CompanionMoodStore.Mood.ANNOYED] ?: 0f
        val love = snapshot.scores[CompanionMoodStore.Mood.LOVE] ?: 0f
        val coy = snapshot.scores[CompanionMoodStore.Mood.COY] ?: 0f

        // Love & Coy boost
        assertTrue("LOVE should be higher than baseline", love > CompanionMoodStore.Mood.LOVE.baseline)
        assertTrue("COY should be higher than baseline", coy > CompanionMoodStore.Mood.COY.baseline)
        // Annoyed suppressed
        assertTrue("ANNOYED should decrease on praise", afterAnnoyed < before)
    }

    @Test
    fun testSarcasticPraiseTriggersSulkNotProud() {
        val snapshot = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "你可真厉害啊，连这个都不会",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1000L
        )
        val proud = snapshot.scores[CompanionMoodStore.Mood.PROUD] ?: 0f
        val sulk = snapshot.scores[CompanionMoodStore.Mood.SULK] ?: 0f
        val hurt = snapshot.scores[CompanionMoodStore.Mood.HURT] ?: 0f

        assertTrue("PROUD should decrease on sarcastic praise", proud < CompanionMoodStore.Mood.PROUD.baseline)
        assertTrue("SULK should increase on sarcastic praise", sulk > CompanionMoodStore.Mood.SULK.baseline)
        assertTrue("HURT should increase on sarcastic praise", hurt > CompanionMoodStore.Mood.HURT.baseline)
    }

    @Test
    fun testSleepyMessageBoostsTiredAndCalm() {
        val snapshot = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "好困了，准备睡觉啦晚安",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1000L
        )
        val tired = snapshot.scores[CompanionMoodStore.Mood.TIRED] ?: 0f
        val calm = snapshot.scores[CompanionMoodStore.Mood.CALM] ?: 0f
        val hyper = snapshot.scores[CompanionMoodStore.Mood.HYPER] ?: 0f

        assertTrue("TIRED should increase on sleep message", tired > CompanionMoodStore.Mood.TIRED.baseline)
        assertTrue("CALM should increase on sleep message", calm > CompanionMoodStore.Mood.CALM.baseline)
        assertTrue("HYPER should decrease on sleep message", hyper < CompanionMoodStore.Mood.HYPER.baseline)
    }

    @Test
    fun testCircadianOffsetLateNightVsDay() {
        // 2 AM UTC
        val calNight = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.OCTOBER, 23, 2, 30, 0)
        }
        val nightOffsets = CompanionMoodStore.circadianOffset(
            now = calNight.timeInMillis,
            timeZone = java.util.TimeZone.getTimeZone("UTC"),
            lastRealUserTime = 0L // idle late at night
        )
        assertTrue("TIRED offset should be strong at 2 AM", (nightOffsets[CompanionMoodStore.Mood.TIRED] ?: 0f) >= 6.0f)
        assertTrue("CALM offset should be positive at 2 AM", (nightOffsets[CompanionMoodStore.Mood.CALM] ?: 0f) > 0f)
        assertTrue("HYPER offset should be suppressed at 2 AM", (nightOffsets[CompanionMoodStore.Mood.HYPER] ?: 0f) < 0f)

        // 2 PM UTC
        val calDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.OCTOBER, 23, 14, 0, 0)
        }
        val dayOffsets = CompanionMoodStore.circadianOffset(
            now = calDay.timeInMillis,
            timeZone = java.util.TimeZone.getTimeZone("UTC"),
            lastRealUserTime = 0L
        )
        assertEquals(0f, dayOffsets[CompanionMoodStore.Mood.TIRED] ?: 0f, 0.01f)
    }

    @Test
    fun testCheckPeriodicDecayInterval() {
        val baseTime = 1000_000L
        // 1st call: initializes last periodic timestamp, returns null
        val r1 = CompanionMoodStore.checkPeriodicDecay(prefs, now = baseTime)
        org.junit.Assert.assertNull(r1)

        // 2nd call: only 1 hour later, returns null
        val r2 = CompanionMoodStore.checkPeriodicDecay(prefs, now = baseTime + 3600_000L)
        org.junit.Assert.assertNull(r2)

        // 3rd call: 3.5 hours later, triggers periodic decay
        val r3 = CompanionMoodStore.checkPeriodicDecay(prefs, now = baseTime + 3500_000L * 4)
        assertNotNull(r3)
    }

    @Test
    fun testCrackedMessageBoostsCrackedAndSuppressesAnnoyed() {
        val snapshot = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "破防了，彻底委屈难过",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1000L
        )

        val cracked = snapshot.scores[CompanionMoodStore.Mood.CRACKED] ?: 0f
        assertTrue("CRACKED score should rise on 破防", cracked > CompanionMoodStore.Mood.CRACKED.baseline)
    }

    @Test
    fun testDebounceMarginPreventsSpuriousSwitch() {
        // Current leader is CALM (baseline 45)
        // We set HAPPY to 47. Gap is only 2 (< margin 5), so CALM must remain leader
        val snapshot = CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.HAPPY to 17.0f), // 30 + 17 = 47 vs CALM 45
            now = 1000L
        )
        assertEquals(CompanionMoodStore.Mood.CALM, snapshot.primary)

        // Now boost HAPPY to 65 (gap = 20 > margin 5 and strong leap >= 15)
        val snapshot2 = CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.HAPPY to 18.0f), // 47 + 18 = 65
            now = 2000L
        )
        assertEquals(CompanionMoodStore.Mood.HAPPY, snapshot2.primary)
    }

    @Test
    fun testHybridSecondaryMoodWhenWithin8Points() {
        // Set HAPPY to 70 and EXCITED to 65 (gap = 5 <= 8)
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(
                CompanionMoodStore.Mood.CALM to -20.0f, // 25
                CompanionMoodStore.Mood.HAPPY to 40.0f, // 70
                CompanionMoodStore.Mood.EXCITED to 49.0f // 65
            ),
            now = 1000L * 60 * 30 // 30 minutes in
        )
        val snapshot = CompanionMoodStore.currentSnapshot(prefs)
        assertEquals(CompanionMoodStore.Mood.HAPPY, snapshot.primary)
        assertTrue("EXCITED should be secondary aroma", snapshot.secondary.contains(CompanionMoodStore.Mood.EXCITED))
        assertTrue("Display title should indicate hybrid blend", snapshot.displayTitle.contains("开心"))
    }

    @Test
    fun testPeriodicDecayTowardBaseline() {
        // Boost HYPER to 90
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.HYPER to 76.0f), // 14 + 76 = 90
            now = 1000L
        )
        val initialHyper = CompanionMoodStore.currentSnapshot(prefs).scores[CompanionMoodStore.Mood.HYPER] ?: 0f
        assertEquals(90.0f, initialHyper, 0.1f)

        // 4 hours later, check periodic decay
        val fourHoursLater = 1000L + (4 * 3600 * 1000L)
        val decayed = CompanionMoodStore.periodicDecay(
            prefs = prefs,
            turnsSinceLast = 0,
            errorsSinceLast = 0,
            now = fourHoursLater
        )
        val decayedHyper = decayed.scores[CompanionMoodStore.Mood.HYPER] ?: 0f
        assertTrue("Score should decay toward baseline", decayedHyper < initialHyper)
        assertTrue("Score should still be above baseline", decayedHyper > CompanionMoodStore.Mood.HYPER.baseline)
    }

    @Test
    fun testResetToBaselineRestoresAllValues() {
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(
                CompanionMoodStore.Mood.HYPER to 50f,
                CompanionMoodStore.Mood.CALM to -40f
            ),
            now = 1000L
        )
        val resetSnapshot = CompanionMoodStore.resetToBaseline(prefs)
        assertEquals(CompanionMoodStore.Mood.CALM, resetSnapshot.primary)
        for (m in CompanionMoodStore.Mood.entries) {
            assertEquals(m.baseline, resetSnapshot.scores[m] ?: 0f, 0.01f)
        }
    }

    @Test
    fun testDistressGuardMatchesDistressKeywords() {
        assertTrue(DistressGuard.matchesDistress("活着好累，好想死"))
        assertTrue(DistressGuard.matchesDistress("我想自杀，再见了世界"))
        assertTrue(DistressGuard.matchesDistress("重度抑郁快撑不下去了"))
        assertTrue(DistressGuard.matchesDistress("拿刀割腕了"))
        assertFalse(DistressGuard.matchesDistress("这道题好难，难死了"))
        assertFalse(DistressGuard.matchesDistress("今天天气真好"))
    }

    @Test
    fun testDistressSafetyOverridePrompt() {
        val prompt = DistressGuard.safetyOverridePrompt()
        assertTrue(prompt.contains("CRISIS_SAFETY_OVERRIDE"))
        assertTrue(prompt.contains("严禁表演娇嗔"))
        assertTrue(prompt.contains("400-161-9995"))
    }
}
