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
    fun testMorningHoursWithRecentInteractionYieldsFreshResonance() {
        // 08:59 in the morning, user just messaged 23 minutes ago: must yield FRESH_RESONANCE with low score
        val now = timeAt(8, 59)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 23 * 60_000L,
            activeDaysCount = 5,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.HAPPY, state.primaryMood)
        assertEquals(CompanionEmotion.Scenario.FRESH_RESONANCE, state.scenario)
        assertTrue(state.proactiveScore < 0.3f)
    }

    @Test
    fun testMorningHoursWithEarlierInteractionTodayYieldsCalmCompanion() {
        // 08:30 in the morning, but user already chatted 2 hours ago (at 06:30 today, after waking up)
        val now = timeAt(8, 30)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 2 * 3600_000L, // 06:30 today
            activeDaysCount = 5,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.Scenario.CALM_COMPANION, state.scenario)
    }

    @Test
    fun testMorningHoursWhenAlreadyGreetedTodayYieldsCuriousCheckinOnLongIdle() {
        // 08:30 in the morning, user messaged 8 hours ago, but morning greeting already fired today -> falls to CURIOUS_CHECKIN
        val now = timeAt(8, 30)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 8 * 3600_000L,
            activeDaysCount = 5,
            timeZone = utc,
            hasGreetedMorningToday = true
        )

        assertEquals(CompanionEmotion.Scenario.CURIOUS_CHECKIN, state.scenario)
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

    @Test
    fun testDialogueActAppraisalAllClasses() {
        assertEquals(CompanionEmotion.DialogueAct.PRAISE, CompanionEmotion.appraiseUserTurn("太棒了，做得好！"))
        assertEquals(CompanionEmotion.DialogueAct.PRAISE, CompanionEmotion.appraiseUserTurn("辛苦了"))
        assertEquals(CompanionEmotion.DialogueAct.PRAISE, CompanionEmotion.appraiseUserTurn("you are so cute and clever, thanks!"))
        assertEquals(CompanionEmotion.DialogueAct.PRAISE, CompanionEmotion.appraiseUserTurn("可以可以，推进一下吧\n[2026-10-22 周四 22:04]"))
        assertEquals(CompanionEmotion.DialogueAct.PRAISE, CompanionEmotion.appraiseUserTurn("好耶太棒了"))
        assertEquals(CompanionEmotion.DialogueAct.PLAYFUL_TEASE, CompanionEmotion.appraiseUserTurn("摸摸头，给你吃小鱼干"))
        assertEquals(CompanionEmotion.DialogueAct.PLAYFUL_TEASE, CompanionEmotion.appraiseUserTurn("吸吸猫猫，rua一下"))
        assertEquals(CompanionEmotion.DialogueAct.VENTING, CompanionEmotion.appraiseUserTurn("今天工作好累，好烦好难过"))
        assertEquals(CompanionEmotion.DialogueAct.VENTING, CompanionEmotion.appraiseUserTurn("头疼死了，好难受"))
        assertEquals(CompanionEmotion.DialogueAct.GREETING_NIGHT, CompanionEmotion.appraiseUserTurn("小猫晚安，准备睡觉了"))
        assertEquals(CompanionEmotion.DialogueAct.GREETING_NIGHT, CompanionEmotion.appraiseUserTurn("去睡了，明天见"))
        assertEquals(CompanionEmotion.DialogueAct.QUESTION_CURIOUS, CompanionEmotion.appraiseUserTurn("为什么天是蓝色的，帮我查查"))
        assertEquals(CompanionEmotion.DialogueAct.QUESTION_CURIOUS, CompanionEmotion.appraiseUserTurn("帮我看看这个代码"))
        assertEquals(CompanionEmotion.DialogueAct.CASUAL_CHAT, CompanionEmotion.appraiseUserTurn("今天天气晴朗"))
    }

    @Test
    fun testNightHoursActiveEngagementDoesNotForceSleepy() {
        // 02:00 at night, but user was actively chatting 5 minutes ago!
        val now = timeAt(2)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 5 * 60_000L,
            activeDaysCount = 10,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.HAPPY, state.primaryMood)
        assertEquals(CompanionEmotion.Scenario.FRESH_RESONANCE, state.scenario)
    }

    @Test
    fun testEveningCareActiveEngagementDoesNotForceSleepy() {
        // 22:15 at night, user actively chatting 3 minutes ago
        val now = timeAt(22, 15)
        val state = CompanionEmotion.evaluate(
            now = now,
            lastUserMessageTime = now - 3 * 60_000L,
            activeDaysCount = 10,
            timeZone = utc
        )

        assertEquals(CompanionEmotion.PrimaryMood.HAPPY, state.primaryMood)
        assertEquals(CompanionEmotion.Scenario.FRESH_RESONANCE, state.scenario)
    }

    @Test
    fun testDialogueActSarcasmDetection() {
        // High-frequency Chinese cynicism expressions should be intercepted and treated neutrally
        assertEquals(CompanionEmotion.DialogueAct.CASUAL_CHAT, CompanionEmotion.appraiseUserTurn("呵呵你真棒"))
        assertEquals(CompanionEmotion.DialogueAct.CASUAL_CHAT, CompanionEmotion.appraiseUserTurn("你可真行啊"))
        assertEquals(CompanionEmotion.DialogueAct.CASUAL_CHAT, CompanionEmotion.appraiseUserTurn("行吧你开心就好"))
        assertEquals(CompanionEmotion.DialogueAct.CASUAL_CHAT, CompanionEmotion.appraiseUserTurn("我可谢谢你了"))
        assertEquals(CompanionEmotion.DialogueAct.CASUAL_CHAT, CompanionEmotion.appraiseUserTurn("真棒呢"))
    }

    @Test
    fun testPromptAddendumFormattingAndBoundaries() {
        val state = CompanionEmotion.MoodState(
            primaryMood = CompanionEmotion.PrimaryMood.SHY,
            valence = 0.85f,
            arousal = 0.70f,
            affection = 0.75f,
            proactiveScore = 0.5f,
            scenario = CompanionEmotion.Scenario.FRESH_RESONANCE,
            framingInstruction = ""
        )

        // Test safe address normalization
        val longAddress = "SuperExtraLongCustomNickname123456789"
        val prompt = CompanionEmotion.promptAddendum(longAddress, state, intensity = "vivid")
        assertTrue(prompt.contains("[Companion Heart & Disposition]"))
        assertTrue(prompt.contains("SuperExtraLo")) // Capped to 12 chars
        assertTrue(prompt.contains("Vividly express"))

        val emptyPrompt = CompanionEmotion.promptAddendum("", state, intensity = "subtle")
        assertTrue(emptyPrompt.contains("老大"))
        assertTrue(emptyPrompt.contains("Reflect this disposition subtly"))
    }
}
