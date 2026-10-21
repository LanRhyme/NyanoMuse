package io.github.nanomuse.companion

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-function PAD (Pleasure-Arousal-Dominance) Emotion Engine for nanoMuse companion.
 *
 * Deterministic and stateless: computes mood and proactive willingness directly from:
 *   - Current wall clock time (circadian rhythm)
 *   - Real idle duration since last user message in Main Chat
 *   - Historical active interaction days (intimacy factor)
 *
 * Impervious to Android process kills and background app restarts.
 */
object CompanionEmotion {

    enum class PrimaryMood {
        HAPPY,   // 喜悦 / 元气 / 满足
        CURIOUS, // 好奇 / 探头 / 关切
        LONGING, // 思念 / 黏人 / 挂念
        SLEEPY   // 困倦 / 恬静 / 打哈欠
    }

    enum class Scenario(val tag: String) {
        NIGHT_REST("night_rest"),
        EVENING_CARE("evening_care"),
        MORNING_GREETING("morning_greeting"),
        FRESH_RESONANCE("fresh_resonance"),
        CALM_COMPANION("calm_companion"),
        CURIOUS_CHECKIN("curious_checkin"),
        LONGING_MISS("longing_miss")
    }

    data class MoodState(
        val primaryMood: PrimaryMood,
        /** Pleasure / Valence: [-1.0, 1.0], from gloomy/lonely to cheerful/happy */
        val valence: Float,
        /** Arousal: [0.0, 1.0], from sleepy/calm to alert/energetic */
        val arousal: Float,
        /** Dominance / Affection bond: [0.0, 1.0], attachment and closeness to user */
        val affection: Float,
        /** Proactive eagerness: [0.0, 1.0], willingness to initiate conversation */
        val proactiveScore: Float,
        /** Scenario category */
        val scenario: Scenario,
        /** Framing guidance injected into the routine prompt */
        val framingInstruction: String
    ) {
        val scenarioTag: String get() = scenario.tag
    }

    /**
     * Pure function to evaluate companion mood and proactive willingness.
     *
     * @param now Current timestamp in milliseconds.
     * @param lastUserMessageTime Milliseconds of the latest user message in Main Chat (0L if none).
     * @param activeDaysCount Distinct historical active days with user interactions (>= 1).
     * @param quietHoursStart Hour of day (0..23) when night quiet hours start.
     * @param quietHoursEnd Hour of day (0..23) when night quiet hours end.
     * @param timeZone Timezone for local wall clock calculation.
     */
    fun evaluate(
        now: Long,
        lastUserMessageTime: Long,
        activeDaysCount: Int = 1,
        quietHoursStart: Int = CompanionPrefs.DEFAULT_QUIET_START_HOUR,
        quietHoursEnd: Int = CompanionPrefs.DEFAULT_QUIET_END_HOUR,
        timeZone: TimeZone = TimeZone.getDefault()
    ): MoodState {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val isQuiet = CompanionPrefs.isQuietHours(hour, quietHoursStart, quietHoursEnd)

        // Intimacy base from active days: caps at 1.0 with smooth asymptotic scaling
        val intimacy = minOf(1.0f, 0.7f + (maxOf(1, activeDaysCount) * 0.015f))

        // 1. Night / Quiet hours: Sleepy & quiet (strict 0 proactive score)
        if (isQuiet) {
            return MoodState(
                primaryMood = PrimaryMood.SLEEPY,
                valence = 0.5f,
                arousal = 0.15f,
                affection = intimacy,
                proactiveScore = 0.0f,
                scenario = Scenario.NIGHT_REST,
                framingInstruction = "现在是深夜静默时段，小猫很困倦，蜷缩在老大身边安睡，严禁打扰老大休息。"
            )
        }

        // Calculate idle duration since user's last message
        val idleMs = if (lastUserMessageTime > 0L) maxOf(0L, now - lastUserMessageTime) else Long.MAX_VALUE

        // Helper to scale base proactive score by intimacy/affection
        fun applyIntimacyBoost(baseScore: Float): Float =
            minOf(1.0f, baseScore * (0.8f + intimacy * 0.2f))

        // 2. Long absence (>= 10 hours or never messaged): Prioritized over generic morning
        // If absent for multiple days or all day, longing takes emotional priority over a generic morning cheer
        if (idleMs >= 10 * 3600_000L) {
            val longingAffection = minOf(1.0f, intimacy + 0.1f)
            return MoodState(
                primaryMood = PrimaryMood.LONGING,
                valence = 0.35f,
                arousal = 0.50f,
                affection = longingAffection,
                proactiveScore = applyIntimacyBoost(0.90f),
                scenario = Scenario.LONGING_MISS,
                framingInstruction = "好久没见到老大了，小猫格外思念老大，软乎乎地蹭蹭求抱抱，表达想念，严禁说教或客服语气，40字以内。"
            )
        }

        // 3. Early morning (07:00 ~ 09:30): Cheerful awakening
        val isMorning = (hour in 7..8) || (hour == 9 && minute <= 30)
        if (isMorning) {
            return MoodState(
                primaryMood = PrimaryMood.HAPPY,
                valence = 0.85f,
                arousal = 0.80f,
                affection = intimacy,
                proactiveScore = applyIntimacyBoost(0.85f),
                scenario = Scenario.MORNING_GREETING,
                framingInstruction = "早晨元气满满地伸个懒腰，向老大问候早安，语气亲昵软萌，严禁长篇大论或客服语气，40字以内。"
            )
        }

        // 4. Evening Care (21:30 ~ 22:59, before quiet hours): Gentle reminder to sleep soon
        val isEveningCare = (hour == 21 && minute >= 30) || (hour == 22)
        if (isEveningCare) {
            return MoodState(
                primaryMood = PrimaryMood.SLEEPY,
                valence = 0.70f,
                arousal = 0.30f,
                affection = intimacy,
                proactiveScore = applyIntimacyBoost(0.65f),
                scenario = Scenario.EVENING_CARE,
                framingInstruction = "已经很晚了，小猫轻声打个哈欠，提醒老大早点休息，明天再见，软萌不啰嗦，40字以内。"
            )
        }

        // 5. Daytime & Regular hours: tiered by idle duration
        return when {
            // Fresh interaction (< 1 hour): basking in the joy of chatting
            idleMs < 1 * 3600_000L -> MoodState(
                primaryMood = PrimaryMood.HAPPY,
                valence = 0.90f,
                arousal = 0.65f,
                affection = intimacy,
                proactiveScore = applyIntimacyBoost(0.15f),
                scenario = Scenario.FRESH_RESONANCE,
                framingInstruction = "刚刚和老大聊过天，小猫心里满是喜悦，静静趴在老大身边打呼噜陪伴。"
            )
            // Moderate idle (1 hour <= idle < 4 hours): calm companionship
            idleMs < 4 * 3600_000L -> MoodState(
                primaryMood = PrimaryMood.HAPPY,
                valence = 0.65f,
                arousal = 0.45f,
                affection = intimacy,
                proactiveScore = applyIntimacyBoost(0.35f),
                scenario = Scenario.CALM_COMPANION,
                framingInstruction = "小猫安稳地在旁边玩毛线球，随时等待老大召唤。"
            )
            // Noticeable absence (4 hours <= idle < 10 hours): curious check-in
            else -> MoodState(
                primaryMood = PrimaryMood.CURIOUS,
                valence = 0.55f,
                arousal = 0.60f,
                affection = intimacy,
                proactiveScore = applyIntimacyBoost(0.75f),
                scenario = Scenario.CURIOUS_CHECKIN,
                framingInstruction = "老大已经忙了好一会儿了，小猫好奇地探出小脑袋关心老大今天顺不顺利，语气温柔软萌，40字以内。"
            )
        }
    }
}
