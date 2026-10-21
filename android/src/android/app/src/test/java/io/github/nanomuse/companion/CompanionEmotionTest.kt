package io.github.nanomuse.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CompanionEmotionTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun timeAt(hour: Int, minute: Int = 0): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 21)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun testNightQuietHoursYieldsSleepyAndZeroProactive() {
        // 02:00 at night
        val now = timeAt(2)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 5 * 3600_000L,
            activeDaysCount = 10,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.SLEEPY, state.primaryMood)
        assertEquals(0.0f, state.proactiveScore, 0.001f)
        assertEquals(CompanionEmotion.Scenario.NIGHT_REST, state.scenario)
        assertEquals("night_rest", state.scenarioTag)
        assertTrue(state.framingInstruction.contains("深夜静默"))
    }

    @Test
    fun testMorningHoursYieldsPlayfulGreetingWhenIdleUnderTenHours() {
        // 08:30 in the morning, user last messaged 8 hours ago (slept normally)
        val now = timeAt(8, 30)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 8 * 3600_000L,
            activeDaysCount = 5,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.HAPPY, state.primaryMood)
        assertEquals(CompanionEmotion.Scenario.MORNING_GREETING, state.scenario)
        assertTrue(state.framingInstruction.contains("早安"))
    }

    @Test
    fun testPostMorningThresholdDoesNotTriggerMorningGreeting() {
        // 09:31 in the morning: morning window ends at 09:30
        val now = timeAt(9, 31)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 2 * 3600_000L, // 2h idle -> calm companion
            activeDaysCount = 5,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.Scenario.CALM_COMPANION, state.scenario)
    }

    @Test
    fun testEveningCareWindowBeforeQuietHours() {
        // 22:15 at night: evening care band
        val now = timeAt(22, 15)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 3 * 3600_000L,
            activeDaysCount = 10,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.SLEEPY, state.primaryMood)
        assertEquals(CompanionEmotion.Scenario.EVENING_CARE, state.scenario)
        assertTrue(state.framingInstruction.contains("提醒老大早点休息"))
        assertTrue(state.proactiveScore > 0.5f)
    }

    @Test
    fun testLongAbsenceOverridesMorningCheer() {
        // 08:30 in the morning, but user was absent for 3 days (72 hours)
        val now = timeAt(8, 30)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 72 * 3600_000L,
            activeDaysCount = 20,
            timeZone = utc
        )

        // Missing/Longing takes precedence over routine morning cheer!
        assertEquals(CompanionEmotion.PrimaryMood.LONGING, state.primaryMood)
        assertEquals(CompanionEmotion.Scenario.LONGING_MISS, state.scenario)
        assertTrue(state.framingInstruction.contains("好久没见到老大"))
    }

    @Test
    fun testIdleExactBoundariesOneHour() {
        val now = timeAt(14)

        // idleMs = 1 hour - 1 ms -> FRESH_RESONANCE
        val stateUnder1h = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - (3600_000L - 1),
            activeDaysCount = 10,
            timeZone = utc
        )
        assertEquals(CompanionEmotion.Scenario.FRESH_RESONANCE, stateUnder1h.scenario)

        // idleMs = 1 hour exactly -> CALM_COMPANION
        val stateAt1h = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 3600_000L,
            activeDaysCount = 10,
            timeZone = utc
        )
        assertEquals(CompanionEmotion.Scenario.CALM_COMPANION, stateAt1h.scenario)
    }

    @Test
    fun testIdleExactBoundariesFourHours() {
        val now = timeAt(14)

        // idleMs = 4 hours - 1 ms -> CALM_COMPANION
        val stateUnder4h = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - (4 * 3600_000L - 1),
            activeDaysCount = 10,
            timeZone = utc
        )
        assertEquals(CompanionEmotion.Scenario.CALM_COMPANION, stateUnder4h.scenario)

        // idleMs = 4 hours exactly -> CURIOUS_CHECKIN
        val stateAt4h = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 4 * 3600_000L,
            activeDaysCount = 10,
            timeZone = utc
        )
        assertEquals(CompanionEmotion.Scenario.CURIOUS_CHECKIN, stateAt4h.scenario)
    }

    @Test
    fun testIdleExactBoundariesTenHours() {
        val now = timeAt(16)

        // idleMs = 10 hours - 1 ms -> CURIOUS_CHECKIN
        val stateUnder10h = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - (10 * 3600_000L - 1),
            activeDaysCount = 10,
            timeZone = utc
        )
        assertEquals(CompanionEmotion.Scenario.CURIOUS_CHECKIN, stateUnder10h.scenario)

        // idleMs = 10 hours exactly -> LONGING_MISS
        val stateAt10h = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 10 * 3600_000L,
            activeDaysCount = 10,
            timeZone = utc
        )
        assertEquals(CompanionEmotion.Scenario.LONGING_MISS, stateAt10h.scenario)
    }

    @Test
    fun testIntimacyBoostsProactiveScore() {
        val now = timeAt(15)
        val lastUserTime = now - 5 * 3600_000L

        val state1 = CompanionEmotion.evaluate(now, lastUserTime, activeDaysCount = 1, timeZone = utc)
        val state30 = CompanionEmotion.evaluate(now, lastUserTime, activeDaysCount = 30, timeZone = utc)

        assertTrue(state30.affection > state1.affection)
        assertTrue(state30.proactiveScore > state1.proactiveScore)
        assertTrue(state30.affection <= 1.0f)
        assertTrue(state30.proactiveScore <= 1.0f)
    }
}
