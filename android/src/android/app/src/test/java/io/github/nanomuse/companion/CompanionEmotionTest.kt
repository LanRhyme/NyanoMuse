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
        assertEquals("night_rest", state.scenarioTag)
        assertTrue(state.framingInstruction.contains("深夜静默"))
    }

    @Test
    fun testMorningHoursYieldsPlayfulGreeting() {
        // 08:30 in the morning
        val now = timeAt(8, 30)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 9 * 3600_000L, // user slept 9 hours
            activeDaysCount = 5,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.HAPPY, state.primaryMood)
        assertEquals(0.85f, state.proactiveScore, 0.001f)
        assertEquals("morning_greeting", state.scenarioTag)
        assertTrue(state.framingInstruction.contains("早安"))
    }

    @Test
    fun testRecentInteractionYieldsHighWarmthLowProactive() {
        // 14:00 daytime, user just chatted 20 minutes ago
        val now = timeAt(14)
        val lastUserTime = now - 20 * 60_000L
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = lastUserTime,
            activeDaysCount = 10,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.HAPPY, state.primaryMood)
        assertEquals(0.90f, state.valence, 0.001f)
        assertEquals(0.15f, state.proactiveScore, 0.001f)
        assertEquals("fresh_resonance", state.scenarioTag)
    }

    @Test
    fun testCuriousCheckinAfterModerateAbsence() {
        // 15:00 daytime, user last messaged 5 hours ago
        val now = timeAt(15)
        val lastUserTime = now - 5 * 3600_000L
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = lastUserTime,
            activeDaysCount = 10,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.CURIOUS, state.primaryMood)
        assertEquals(0.75f, state.proactiveScore, 0.001f)
        assertEquals("curious_checkin", state.scenarioTag)
        assertTrue(state.framingInstruction.contains("好奇地探出小脑袋"))
    }

    @Test
    fun testLongingWhenAbsentMoreThanTenHours() {
        // 20:00 evening, user last messaged 12 hours ago
        val now = timeAt(20)
        val lastUserTime = now - 12 * 3600_000L
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = lastUserTime,
            activeDaysCount = 20,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.LONGING, state.primaryMood)
        assertEquals(0.90f, state.proactiveScore, 0.001f)
        assertEquals("longing_miss", state.scenarioTag)
        assertTrue(state.framingInstruction.contains("好久没见到老大"))
        assertTrue(state.affection >= 0.9f)
    }

    @Test
    fun testIntimacyScalesWithActiveDays() {
        val now = timeAt(15)
        val lastUserTime = now - 5 * 3600_000L

        val state1 = CompanionEmotion.evaluate(now, lastUserTime, activeDaysCount = 1, timeZone = utc)
        val state30 = CompanionEmotion.evaluate(now, lastUserTime, activeDaysCount = 30, timeZone = utc)

        assertTrue(state30.affection > state1.affection)
        assertTrue(state30.affection <= 1.0f)
    }
}
