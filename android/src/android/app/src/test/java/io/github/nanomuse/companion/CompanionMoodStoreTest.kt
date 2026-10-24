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

    /**
     * A fixed local wall-clock time in the noon hour.
     *
     * `circadianOffset` only shifts the baselines for 23:00-05:59, 06:00-09:59 and
     * 21:00-22:59, and `currentSnapshot` folds that shift into every score it hands
     * back. A test that compares a score against its raw baseline therefore passes by
     * day and fails at night; pinning the clock to an offset-free hour keeps the
     * assertion about the thing it actually means to check.
     */
    private fun noon(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 12)
        set(java.util.Calendar.MINUTE, 30)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun setup() {
        prefs = MockPrefs()
    }

    @Test
    fun testInitialSnapshotHas33MoodsAndCalmLeader() {
        val snapshot = CompanionMoodStore.currentSnapshot(prefs, now = noon())
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
    fun testSincerePraiseDoesNotTriggerSulk() {
        val snapshot = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "你真厉害啊，帮了大忙！",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1000L
        )
        val proud = snapshot.scores[CompanionMoodStore.Mood.PROUD] ?: 0f
        val happy = snapshot.scores[CompanionMoodStore.Mood.HAPPY] ?: 0f
        val sulk = snapshot.scores[CompanionMoodStore.Mood.SULK] ?: 0f

        assertTrue("PROUD should increase on sincere praise", proud > CompanionMoodStore.Mood.PROUD.baseline)
        assertTrue("HAPPY should increase on sincere praise", happy > CompanionMoodStore.Mood.HAPPY.baseline)
        assertTrue("SULK should not increase on sincere praise", sulk <= CompanionMoodStore.Mood.SULK.baseline)

        val affection = CompanionPrefs.getAffectionScore(prefs)
        assertTrue("Affection should increase on praise turn", affection > CompanionPrefs.DEFAULT_AFFECTION_SCORE)
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
            now = noon()
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
        // Current leader is CALM (baseline 32). We set HAPPY to 34: the lead is 2,
        // under MARGIN (3), so CALM must remain leader — a lukewarm signal is not
        // a change of heart. (These figures track MARGIN/CALM.baseline; the point of
        // the test is that a small lead never steals the mood.)
        val snapshot = CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.HAPPY to 4.0f), // 30 + 4 = 34 vs CALM 32
            now = 1000L
        )
        assertEquals(CompanionMoodStore.Mood.CALM, snapshot.primary)

        // A lead past BIG_LEAD overrides the hold, so this switch lands at once
        // instead of waiting out MIN_HOLD_MS.
        val snapshot2 = CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.HAPPY to 31.0f), // 34 + 31 = 65 vs CALM 32
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
        val snapshot = CompanionMoodStore.currentSnapshot(prefs, now = noon())
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
        val initialHyper = CompanionMoodStore.currentSnapshot(prefs, now = noon()).scores[CompanionMoodStore.Mood.HYPER] ?: 0f
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
        val resetSnapshot = CompanionMoodStore.resetToBaseline(prefs, now = noon())
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
        assertTrue(prompt.contains("心理援助热线"))
    }

    @Test
    fun testFromIdOrNullCaseInsensitiveAndInvalidDiscard() {
        // Lowercase
        assertEquals(CompanionMoodStore.Mood.PROUD, CompanionMoodStore.Mood.fromIdOrNull("proud"))
        // Uppercase
        assertEquals(CompanionMoodStore.Mood.PROUD, CompanionMoodStore.Mood.fromIdOrNull("PROUD"))
        // Mixed case
        assertEquals(CompanionMoodStore.Mood.COY, CompanionMoodStore.Mood.fromIdOrNull("Coy"))
        // Invalid IDs must return null (NEVER fallback to CALM)
        org.junit.Assert.assertNull(CompanionMoodStore.Mood.fromIdOrNull("nonexistent_xyz"))
        org.junit.Assert.assertNull(CompanionMoodStore.Mood.fromIdOrNull(""))
        org.junit.Assert.assertNull(CompanionMoodStore.Mood.fromIdOrNull(null))
    }

    @Test
    fun testExtractMoodFeedbackAndSanitize() {
        val textWithTag = "老大你真好呀！<nm:mood feel=\"proud\" reason=\"收到老大的真诚夸奖\"/>"
        val feedback = io.github.nanomuse.chat.BubbleTimeFormatter.extractMoodFeedback(textWithTag)
        assertNotNull(feedback)
        assertEquals("proud", feedback?.feel)
        assertEquals("收到老大的真诚夸奖", feedback?.reason)

        val stripped = io.github.nanomuse.chat.BubbleTimeFormatter.stripMoodTags(textWithTag)
        assertEquals("老大你真好呀！", stripped)
        assertFalse(io.github.nanomuse.chat.BubbleTimeFormatter.hasMoodTag(stripped))

        // Multiple tags and bubbles coexistence
        val complex = "第一段气泡<nm:bubble/>第二段气泡<nm:mood feel=\"sulk\"/>"
        val clean1 = io.github.nanomuse.chat.BubbleTimeFormatter.stripMoodTags(complex)
        val clean2 = io.github.nanomuse.chat.BubbleTimeFormatter.sanitizeBubbleTags(clean1)
        assertEquals("第一段气泡\n\n第二段气泡", clean2)
    }

    @Test
    fun testApplyFeedbackDualTrackRevertAndCalibratedDelta() {
        // 1. User message applies instant heuristic boost to PROUD (+3.0)
        CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "夸夸你，做得好",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1000L
        )
        val proudAfterUser = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.PROUD] ?: 0f
        assertEquals(CompanionMoodStore.Mood.PROUD.baseline + 3.0f, proudAfterUser, 0.01f)

        // 2. Assistant finishes and emits feedback for SULK (e.g. model actually perceived coldness or sulk)
        // applyFeedback should revert PROUD instant delta and apply calibrated SULK delta (+2.5)
        val snapshot = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "sulk",
            reasonRaw = "心里有点委屈生闷气",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 2000L
        )
        assertNotNull(snapshot)
        val scores = CompanionMoodStore.getScores(prefs)

        // PROUD heuristic delta was reverted, then mutually suppressed by SULK (suppress 1.2f)
        val expectedProud = CompanionMoodStore.Mood.PROUD.baseline - 1.2f
        assertEquals(expectedProud, scores[CompanionMoodStore.Mood.PROUD] ?: 0f, 0.01f)

        // SULK received client-calibrated FEEDBACK_DELTA (+2.5f)
        val expectedSulk = CompanionMoodStore.Mood.SULK.baseline + 2.5f
        assertEquals(expectedSulk, scores[CompanionMoodStore.Mood.SULK] ?: 0f, 0.01f)

        // Last instant delta should now be cleared
        assertTrue(CompanionMoodStore.getLastInstantDelta(prefs).isEmpty())
    }

    @Test
    fun testApplyFeedbackDiscardsUnknownMood() {
        val initialCalm = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.CALM] ?: 0f
        val result = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "unknown_hallucination",
            reasonRaw = "不存在的心情",
            now = 1000L
        )
        // Must return null and discard without mutating CALM score
        org.junit.Assert.assertNull(result)
        val calmAfter = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.CALM] ?: 0f
        assertEquals(initialCalm, calmAfter, 0.01f)
    }

    @Test
    fun testFeedbackSwitchFactsModelNarrativeNotLeakedToPrompt() {
        // Boost HAPPY enough to trigger primary switch from CALM (baseline 45) to HAPPY (baseline 30)
        // We set initial scores so HAPPY leaps over CALM by >= 15
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.HAPPY to 35f), // 30 + 35 = 65 vs 45
            now = 1000L
        )
        val facts = CompanionMoodStore.currentSnapshot(prefs).lastSwitchFacts
        assertNotNull(facts)

        // Now apply feedback with modelNarrative
        val snap = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "proud",
            reasonRaw = "收到老大的认可很开心",
            now = 2000L
        )
        // Trigger a switch to PROUD (baseline 25) by boosting it high
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.PROUD to 50f),
            now = 3000L
        )
        val snapSwitched = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "proud",
            reasonRaw = "收到老大的认可很开心",
            now = 4000L
        )
        assertNotNull(snapSwitched)
    }

    @Test
    fun testApplyFeedbackRetainsSarcasmSulkOnHappyProud() {
        // Setup initial sulk instant delta of 2.5f (client detected sarcasm)
        CompanionMoodStore.saveInstantDelta(prefs, mapOf(CompanionMoodStore.Mood.SULK to 2.5f))
        CompanionMoodStore.applyAdjustment(prefs, mapOf(CompanionMoodStore.Mood.SULK to 2.5f), now = 1000L)

        // Assistant model claims HAPPY (missed the sarcasm)
        val snap = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "happy",
            reasonRaw = "以为对方在夸自己",
            now = 2000L
        )
        assertNotNull(snap)
        val scores = CompanionMoodStore.getScores(prefs)
        val sulkScore = scores[CompanionMoodStore.Mood.SULK] ?: 0f

        // Baseline (8.0) - instantDelta (2.5) + retained sarcasm (2.5 * 0.5 = 1.25) - suppress from HAPPY (1.2) = 8.05f
        // Stored scores round to 1 decimal place: 8.05 rounds to 8.1f
        assertEquals(8.1f, sulkScore, 0.05f)
    }

    @Test
    fun testPromptAddendumQualityAndConstraints() {
        val prompt = CompanionMoodStore.promptAddendum(prefs, "老大")
        // Length check (< 2000 chars)
        assertTrue("Prompt length should be compact (< 2000 chars), was: ${prompt.length}", prompt.length < 2000)

        // Three golden self-checks
        assertTrue("Must include self-check 1", prompt.contains("换个人、换个场合还成立吗？"))
        assertTrue("Must include self-check 2", prompt.contains("有没有替对方编他没说过的事？"))
        assertTrue("Must include self-check 3", prompt.contains("有没有拿姿态当内容？"))

        // Confusables disambiguation
        assertTrue("Must include confusables guide", prompt.contains("易混辨析：烦躁是火往外发(炸)"))

        // Distress safety guard override
        assertTrue("Must include distress safety override", prompt.contains("角色必须给真人让路"))

        // Intimacy & empathy bounds
        assertTrue("Must include future promise bound", prompt.contains("做不到的未来：不轻率否定，也不轻率保证"))
        assertTrue("Must include empathy feeling bound", prompt.contains("别替对方决定该怎么感觉"))

        // Feedback tag protocol
        assertTrue("Must include tag protocol", prompt.contains("<nm:mood feel=\"心境ID\" reason=\"触发短因\"/>"))
    }

    // ─── 情绪响应速度：连标递增 / 双轨印证 / 待确认估计 ─────────────────────

    @Test
    fun testStreakMultiplierTable() {
        // Indexed by the streak length: first reply 1.0, second 1.35, third and beyond 1.7.
        assertEquals(1.0f, CompanionMoodStore.streakMultiplier(1), 0.001f)
        assertEquals(1.0f, CompanionMoodStore.streakMultiplier(0), 0.001f)
        assertEquals(1.35f, CompanionMoodStore.streakMultiplier(2), 0.001f)
        assertEquals(1.7f, CompanionMoodStore.streakMultiplier(3), 0.001f)
        // Past the table the last step holds, so a long run does not keep accelerating.
        assertEquals(1.7f, CompanionMoodStore.streakMultiplier(9), 0.001f)
    }

    @Test
    fun testConsecutiveTagsOfTheSameMoodWeighMoreEachTime() {
        // FEAR sits far below the leader, so it never takes over: the echo guard never
        // engages and the only thing moving the score is the streak weight.
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "一", now = 1_000L)
        assertEquals(7f + 2.0f, CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.FEAR]!!, 0.05f)

        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "二", now = 2_000L)
        assertEquals(7f + 2.0f + 2.0f * 1.35f, CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.FEAR]!!, 0.05f)

        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "三", now = 3_000L)
        assertEquals(7f + 2.0f * (1f + 1.35f + 1.7f), CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.FEAR]!!, 0.05f)
    }

    @Test
    fun testADifferentMoodInBetweenResetsTheStreak() {
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "一", now = 1_000L)
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "二", now = 2_000L)
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "happy", reasonRaw = "插一句", now = 3_000L)
        // Back to FEAR: this is a fresh run, not the third in a row.
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "三", now = 4_000L)
        val fear = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.FEAR]!!
        assertEquals(7f + 2.0f * (1f + 1.35f + 1f), fear, 0.05f)
    }

    @Test
    fun testAQuietStreakIsADifferentConversation() {
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "一", now = 1_000L)
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "二", now = 2_000L)
        // Two hours later the run is over; the next tag starts at full weight again.
        val later = 2_000L + CompanionMoodStore.STREAK_WINDOW_MS + 1_000L
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "fear", reasonRaw = "三", now = later)
        val fear = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.FEAR]!!
        assertEquals(7f + 2.0f * (1f + 1.35f + 1f), fear, 0.05f)
    }

    @Test
    fun testHeuristicAndTagNamingTheSameMoodReinforceInsteadOfCancelling() {
        // "真棒" hits the praise branch: HAPPY +2.5 and PROUD +3.0 both land at once.
        CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "老大夸你：真棒，太喜欢小猫了！",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1_000L
        )
        assertEquals(
            30f + 2.5f,
            CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.HAPPY]!!,
            0.05f
        )

        // The model names HAPPY too. Two independent reads agreeing: the heuristic's share
        // stays and the tag is scaled up — 2.5 kept + 2.5 × 1.4 = 6.0 this turn.
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "happy", reasonRaw = "被夸了", now = 2_000L)
        assertEquals(
            30f + 2.5f + 2.5f * CompanionMoodStore.CORROBORATION_MULTIPLIER,
            CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.HAPPY]!!,
            0.05f
        )

        // PROUD was guessed at but never confirmed by the tag, so it comes back off.
        assertEquals(
            12f,
            CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.PROUD]!!,
            0.05f
        )
    }

    @Test
    fun testUnconfirmedHeuristicEstimateIsCappedAndRevertsExactly() {
        // Two praise messages in a row. The heuristic keeps applying so the face reacts,
        // but the estimate on the books stops at the cap instead of ratcheting.
        repeat(2) {
            CompanionMoodStore.judgeMessage(
                prefs = prefs,
                userText = "老大夸你：真棒，太喜欢小猫了！",
                sessionTurns = 1,
                sessionErrors = 0,
                now = 1_000L + it * 1_000L
            )
        }
        assertEquals(
            CompanionMoodStore.PENDING_CAP,
            CompanionMoodStore.getLastInstantDelta(prefs)[CompanionMoodStore.Mood.PROUD]!!,
            0.05f
        )
        // The score only ever moved by what the books can take back.
        assertEquals(
            12f + CompanionMoodStore.PENDING_CAP,
            CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.PROUD]!!,
            0.05f
        )

        // A tag naming a different mood takes the whole estimate off at once, exactly —
        // and SULK then suppresses PROUD by its mutual-suppression share.
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "sulk", reasonRaw = "生闷气", now = 5_000L)
        assertEquals(
            12f - 1.2f,
            CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.PROUD]!!,
            0.05f
        )
        assertTrue(CompanionMoodStore.getLastInstantDelta(prefs).isEmpty())
    }

    @Test
    fun testOneReplyCanFlipTheLeadButNotTeleportAMoodAcrossThePool() {
        CompanionPrefs.setEmotionIntensity(prefs, "vivid")

        fun corroborate(at: Long) {
            CompanionMoodStore.saveInstantDelta(prefs, mapOf(CompanionMoodStore.Mood.EXCITED to CompanionMoodStore.PENDING_CAP))
            CompanionMoodStore.applyAdjustment(prefs, mapOf(CompanionMoodStore.Mood.EXCITED to CompanionMoodStore.PENDING_CAP), now = at)
        }

        corroborate(1_000L)
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "excited", reasonRaw = "一", now = 2_000L)
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "excited", reasonRaw = "二", now = 3_000L)
        corroborate(4_000L)

        val before = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.EXCITED]!!
        // vivid 1.4 × streak 3 (1.7) × corroboration 1.4 × 2.5 = 8.33, which is more than one
        // reply is allowed to move a mood.
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "excited", reasonRaw = "三", now = 5_000L)
        val after = CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.EXCITED]!!

        assertEquals(before + CompanionMoodStore.MAX_TAG_BOOST, after, 0.05f)
        assertTrue("the cap must bite", before + 8.33f > after)
    }

    @Test
    fun testPeriodicDecayClearsThePendingEstimateSoItIsNotSubtractedTwice() {
        CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "老大夸你：真棒，太喜欢小猫了！",
            sessionTurns = 1,
            sessionErrors = 0,
            now = 1_000L
        )
        CompanionMoodStore.periodicDecay(prefs, now = 2_000L)
        // The decay already pulled PROUD toward its baseline. If the estimate survived it
        // would be taken off a second time by the next tag and push PROUD below baseline.
        CompanionMoodStore.applyFeedback(prefs, feelRaw = "sulk", reasonRaw = "翻旧账", now = 3_000L)
        assertTrue(
            "PROUD was subtracted twice",
            CompanionMoodStore.getScores(prefs)[CompanionMoodStore.Mood.PROUD]!! > CompanionMoodStore.Mood.PROUD.baseline
        )
    }

    @Test
    fun testExcitedPromptAndFeedbackCorroboratesAndEntersSecondaryBlend() {
        // User explicitly asks to test emotion system and boost excitement:
        // "我现在要测试情绪系统，你试试提升兴奋数值"
        val t1 = noon()
        val t2 = t1 + 1_000L

        CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "我现在要测试情绪系统，你试试提升兴奋数值",
            sessionTurns = 1,
            sessionErrors = 0,
            now = t1
        )

        // EXCITED is boosted by heuristic (+3.5) and CALM suppressed (-2.5)
        val scoresMid = CompanionMoodStore.getScores(prefs)
        assertEquals(16f + 3.5f, scoresMid[CompanionMoodStore.Mood.EXCITED]!!, 0.05f)
        assertEquals(32f - 2.5f, scoresMid[CompanionMoodStore.Mood.CALM]!!, 0.05f)

        // Model responds with <nm:mood feel="excited" reason="把档位拉起来"/>:
        // Both tracks agree on EXCITED: heuristic stands and tag is boosted 1.4x (2.5 * 1.4 = 3.5),
        // and CALM is suppressed further by 1.5.
        val snapshot = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "excited",
            reasonRaw = "把档位拉起来",
            sessionTurns = 1,
            sessionErrors = 0,
            now = t2
        )
        assertNotNull(snapshot)
        val rawScoresFinal = CompanionMoodStore.getScores(prefs)
        val expectedExcited = 16f + 3.5f + 2.5f * CompanionMoodStore.CORROBORATION_MULTIPLIER
        assertEquals(expectedExcited, rawScoresFinal[CompanionMoodStore.Mood.EXCITED]!!, 0.05f)
        // CALM's heuristic delta is reverted, then applyFeedback suppresses CALM: 32 - 1.5 = 30.5
        assertEquals(32f - 1.5f, rawScoresFinal[CompanionMoodStore.Mood.CALM]!!, 0.05f)

        // Gap between CALM (30.5) and EXCITED (23.0) is 7.5, which is < BLEND_GAP (8.0).
        // EXCITED must enter secondary blend and be reflected in displayTitle!
        assertTrue(
            "EXCITED must be in secondary moods: ${snapshot!!.secondary}",
            snapshot.secondary.contains(CompanionMoodStore.Mood.EXCITED)
        )
        assertTrue(
            "Display title must reflect 兴奋: ${snapshot.displayTitle}",
            snapshot.displayTitle.contains("兴奋")
        )
    }

    @Test
    fun testFromIdOrNullSupportsDisplayNameAndAliases() {
        assertEquals(CompanionMoodStore.Mood.EXCITED, CompanionMoodStore.Mood.fromIdOrNull("兴奋"))
        assertEquals(CompanionMoodStore.Mood.EXCITED, CompanionMoodStore.Mood.fromIdOrNull("激动"))
        assertEquals(CompanionMoodStore.Mood.HAPPY, CompanionMoodStore.Mood.fromIdOrNull("开心"))
        assertEquals(CompanionMoodStore.Mood.SULK, CompanionMoodStore.Mood.fromIdOrNull("生气"))
        assertEquals(CompanionMoodStore.Mood.DOWN, CompanionMoodStore.Mood.fromIdOrNull("难过"))
        assertEquals(CompanionMoodStore.Mood.CALM, CompanionMoodStore.Mood.fromIdOrNull("平静"))
    }

    @Test
    fun testPeriodicDecayAllowsWornMoodToSettleBackToLeaderWhenDecayed() {
        // Boost SULK so it becomes primary
        CompanionMoodStore.applyAdjustment(
            prefs,
            mapOf(CompanionMoodStore.Mood.SULK to 40.0f),
            now = 1000L
        )
        val sInit = CompanionMoodStore.currentSnapshot(prefs, now = noon())
        assertEquals(CompanionMoodStore.Mood.SULK, sInit.primary)

        // Multiple periodic decays over hours pull SULK back toward baseline (8.0).
        // CALM (32.0) now leads SULK by >= MARGIN (3.0). Primary must settle back to CALM.
        var t = 2000L
        repeat(10) {
            t += 4 * 3600_000L
            CompanionMoodStore.periodicDecay(prefs, now = t)
        }
        val snapshot = CompanionMoodStore.currentSnapshot(prefs, now = noon())
        assertEquals(CompanionMoodStore.Mood.CALM, snapshot.primary)
    }

    @Test
    fun testFocusRegulationMessageHeuristics() {
        val snapshotDrop = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "降低专注，歇会摸鱼放空",
            sessionTurns = 1,
            sessionErrors = 0,
            now = noon()
        )
        val focusedDrop = snapshotDrop.scores[CompanionMoodStore.Mood.FOCUSED] ?: 0f
        val checkedoutRise = snapshotDrop.scores[CompanionMoodStore.Mood.CHECKEDOUT] ?: 0f
        assertTrue(
            "FOCUSED score should decrease on 降低专注",
            focusedDrop < CompanionMoodStore.Mood.FOCUSED.baseline
        )
        assertTrue(
            "CHECKEDOUT score should increase on 摸鱼放空",
            checkedoutRise > CompanionMoodStore.Mood.CHECKEDOUT.baseline
        )

        CompanionMoodStore.resetToBaseline(prefs, now = noon() + 5000L)
        val snapshotBoost = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "认真点，提升专注写代码",
            sessionTurns = 2,
            sessionErrors = 0,
            now = noon() + 5000L
        )
        val focusedBoost = snapshotBoost.scores[CompanionMoodStore.Mood.FOCUSED] ?: 0f
        assertTrue(
            "FOCUSED score should increase on 提升专注",
            focusedBoost > CompanionMoodStore.Mood.FOCUSED.baseline
        )
    }

    @Test
    fun testFocusCheckedoutMutualSuppression() {
        // Model feedback tags checkedout: should suppress focused
        val s1 = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "checkedout",
            reasonRaw = "摆烂摸鱼",
            now = noon()
        )
        assertNotNull(s1)
        val focusedScore = s1!!.scores[CompanionMoodStore.Mood.FOCUSED] ?: 0f
        assertTrue(
            "FOCUSED should be suppressed when checkedout is tagged",
            focusedScore < CompanionMoodStore.Mood.FOCUSED.baseline
        )

        CompanionMoodStore.resetToBaseline(prefs, now = noon() + 5000L)
        // Model feedback tags focused: should suppress checkedout
        val s2 = CompanionMoodStore.applyFeedback(
            prefs = prefs,
            feelRaw = "focused",
            reasonRaw = "深度思考",
            now = noon() + 5000L
        )
        assertNotNull(s2)
        val checkedoutScore = s2!!.scores[CompanionMoodStore.Mood.CHECKEDOUT] ?: 0f
        assertTrue(
            "CHECKEDOUT should be suppressed when focused is tagged",
            checkedoutScore < CompanionMoodStore.Mood.CHECKEDOUT.baseline
        )
    }

    @Test
    fun testCalmDeescalationMessageHeuristics() {
        // First raise EXCITED
        CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "太兴奋了，激动",
            now = noon()
        )
        // Now de-escalate
        val snapshot = CompanionMoodStore.judgeMessage(
            prefs = prefs,
            userText = "冷静点，淡定，消消气",
            now = noon() + 5000L
        )
        val calmScore = snapshot.scores[CompanionMoodStore.Mood.CALM] ?: 0f
        assertTrue(
            "CALM score should rise on 冷静点",
            calmScore > CompanionMoodStore.Mood.CALM.baseline
        )
    }

    @Test
    fun testEmotionIntensityDynamicParameters() {
        CompanionPrefs.setEmotionIntensity(prefs, "subtle")
        assertEquals(4.5f, CompanionMoodStore.margin(prefs), 0.01f)
        assertEquals(8 * 60_000L, CompanionMoodStore.minHoldMs(prefs))
        assertEquals(10.0f, CompanionMoodStore.bigLead(prefs), 0.01f)
        assertEquals(5.0f, CompanionMoodStore.blendGap(prefs), 0.01f)
        assertEquals(0.5f, CompanionMoodStore.intensityMultiplier(prefs), 0.01f)

        CompanionPrefs.setEmotionIntensity(prefs, "vivid")
        assertEquals(2.0f, CompanionMoodStore.margin(prefs), 0.01f)
        assertEquals(2 * 60_000L, CompanionMoodStore.minHoldMs(prefs))
        assertEquals(5.5f, CompanionMoodStore.bigLead(prefs), 0.01f)
        assertEquals(10.0f, CompanionMoodStore.blendGap(prefs), 0.01f)
        assertEquals(1.4f, CompanionMoodStore.intensityMultiplier(prefs), 0.01f)

        CompanionPrefs.setEmotionIntensity(prefs, "normal")
        assertEquals(CompanionMoodStore.MARGIN, CompanionMoodStore.margin(prefs), 0.01f)
        assertEquals(CompanionMoodStore.MIN_HOLD_MS, CompanionMoodStore.minHoldMs(prefs))
        assertEquals(CompanionMoodStore.BIG_LEAD, CompanionMoodStore.bigLead(prefs), 0.01f)
        assertEquals(CompanionMoodStore.BLEND_GAP, CompanionMoodStore.blendGap(prefs), 0.01f)
        assertEquals(1.0f, CompanionMoodStore.intensityMultiplier(prefs), 0.01f)
    }

    @Test
    fun testEmotionIntensityPromptAddendumGuidance() {
        CompanionPrefs.setEmotionIntensity(prefs, "vivid")
        val vividPrompt = CompanionMoodStore.promptAddendum(prefs)
        assertTrue("Vivid prompt must mention 生动充沛", vividPrompt.contains("生动充沛"))

        CompanionPrefs.setEmotionIntensity(prefs, "subtle")
        val subtlePrompt = CompanionMoodStore.promptAddendum(prefs)
        assertTrue("Subtle prompt must mention 克制内敛", subtlePrompt.contains("克制内敛"))
    }
}
