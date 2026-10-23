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
        SLEEPY,  // 困倦 / 恬静 / 打哈欠
        SHY,     // 害羞 / 脸红 / 被夸赞
        CARING,  // 关切 / 倾听 / 暖心安慰
        TOUCHED, // 感动 / 依恋 / 贴贴
        EXCITED, // 兴奋 / 雀跃 / 激动
        SULK_JEALOUS, // 专属: 生闷气 / 吃醋
        CRACKED_HURT, // 专属: 破防 / 委屈
        HYPER    // 专属: 上头
    }

    enum class DialogueAct {
        PRAISE,          // 夸奖认可: 你真棒, 太可爱了, 喜欢你, 乖
        VENTING,         // 倾诉烦恼: 好累, 太难了, 心烦, 倒霉, 难过
        PLAYFUL_TEASE,   // 摸头贴贴: 摸摸头, 捏脸, 蹭蹭, 抱抱, 贴贴
        GREETING_NIGHT,  // 晚安休眠: 晚安, 睡觉了, 困了, 休息吧
        QUESTION_CURIOUS,// 探索提问: 为什么, 怎么做, 查一下, 考考你
        CASUAL_CHAT      // 日常闲聊: 维持常态
    }

    data class EmotionImpulse(
        val dP: Float,
        val dA: Float,
        val dAffection: Float,
        val primaryMood: PrimaryMood,
    )

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
        timeZone: TimeZone = TimeZone.getDefault(),
        hasGreetedMorningToday: Boolean = false,
    ): MoodState {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val isQuiet = CompanionPrefs.isQuietHours(hour, quietHoursStart, quietHoursEnd)

        // Intimacy base from active days: caps at 1.0 with smooth asymptotic scaling
        val intimacy = minOf(1.0f, 0.7f + (maxOf(1, activeDaysCount) * 0.015f))

        // Calculate idle duration since user's last message
        val idleMs = if (lastUserMessageTime > 0L) maxOf(0L, now - lastUserMessageTime) else Long.MAX_VALUE
        val isActivelyEngaged = idleMs < 30 * 60_000L

        // 1. Night / Quiet hours: Sleepy & quiet (strict 0 proactive score) when idle; active chats remain engaged
        if (isQuiet && !isActivelyEngaged) {
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

        // 3. Fresh interaction (< 1 hour): basking in the joy of chatting, zero interruption
        if (idleMs < 1 * 3600_000L) {
            return MoodState(
                primaryMood = PrimaryMood.HAPPY,
                valence = 0.90f,
                arousal = 0.65f,
                affection = intimacy,
                proactiveScore = applyIntimacyBoost(0.15f),
                scenario = Scenario.FRESH_RESONANCE,
                framingInstruction = "刚刚和老大聊过天，小猫心里满是喜悦，静静趴在老大身边打呼噜陪伴。"
            )
        }

        // 4. Early morning (07:00 ~ 09:30): Cheerful awakening
        // Only trigger if no morning greeting occurred today and user hasn't already chatted this morning
        val isMorning = (hour in 7..8) || (hour == 9 && minute <= 30)
        val todayMorningStartMs = Calendar.getInstance(timeZone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val hasAlreadyInteractedThisMorning = lastUserMessageTime >= todayMorningStartMs

        if (isMorning && !hasGreetedMorningToday && !hasAlreadyInteractedThisMorning) {
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

        // 5. Evening Care (21:30 ~ 22:59, before quiet hours): Gentle reminder to sleep soon when not actively chatting
        val isEveningCare = (hour == 21 && minute >= 30) || (hour == 22)
        if (isEveningCare && !isActivelyEngaged) {
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

        // 6. Daytime & Regular hours: tiered by idle duration
        return when {
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

    /**
     * Appraise the user's conversational intent into a structured DialogueAct.
     * Pure and deterministic: examines strictly userText, protecting against self-referential loops.
     *
     * Known boundary: This is a fast, deterministic lexicon-based heuristic (60~75% recall on informal Chinese).
     * Sarcasm filters catch common cynical phrasing; unresolved ambiguity safely defaults to neutral CASUAL_CHAT.
     * An optional secondary lightweight LLM classifier can be introduced as future enhancement.
     */
    val SARCASM_PATTERNS = listOf(
        "呵呵", "真棒呢", "可真行", "你可真行", "可真厉害", "你可真厉害", "真厉害啊", "真行啊",
        "行吧", "你开心就好", "开心就好", "是啊你", "真不愧是你", "我可谢谢你了", "谢谢你啊",
        "对对对", "算你厉害", "阴阳怪气", "就这", "那可太棒了呢", "sarcasm", "whatever"
    )

    /**
     * Pure function to detect cynicism and sarcasm in user utterances.
     */
    fun detectSarcasm(userText: String): Boolean {
        val stripped = io.github.nanomuse.chat.BubbleTimeFormatter.stripUserPromptTimestamp(userText)
        val clean = stripped.trim().lowercase()
        if (clean.isBlank()) return false
        if (SARCASM_PATTERNS.any { clean.contains(it) }) return true
        val secondaryIrony = clean.contains("行吧") || clean.contains("就好") ||
            (clean.startsWith("谢谢") && clean.endsWith("啊"))
        return secondaryIrony
    }

    /**
     * Appraise the user's conversational intent into a structured DialogueAct.
     * Pure and deterministic: examines strictly userText, protecting against self-referential loops.
     */
    fun appraiseUserTurn(userText: String): DialogueAct {
        val stripped = io.github.nanomuse.chat.BubbleTimeFormatter.stripUserPromptTimestamp(userText)
        val clean = stripped.trim().lowercase()
        if (clean.isBlank()) return DialogueAct.CASUAL_CHAT

        // 1. Sarcasm / Irony pre-check
        if (detectSarcasm(clean)) return DialogueAct.CASUAL_CHAT

        // 2. Playful tease / cuddling (摸头/蹭蹭/贴贴)
        val teasePatterns = listOf(
            "摸摸头", "摸摸", "揉揉", "捏捏", "蹭蹭", "抱抱", "贴贴", "求抱抱", "摸头", "挠挠",
            "咕噜咕噜", "猫猫头", "捏一下", "抱一下", "亲亲", "给你吃", "投喂", "吸猫", "吸吸", "rua",
            "举高高", "握手", "乖乖", "乖猫", "pat pat", "hug", "cuddle"
        )
        if (teasePatterns.any { clean.contains(it) }) return DialogueAct.PLAYFUL_TEASE

        // 3. Praise / Compliments (夸奖/认可)
        val praisePatterns = listOf(
            "真棒", "好棒", "太棒了", "太厉害", "真厉害", "真乖", "好乖", "可爱", "真萌", "喜欢你",
            "太强了", "爱死你", "真聪明", "太聪明", "谢谢你", "多谢", "辛苦啦", "辛苦了", "你真好", "真好呀",
            "做得好", "干得漂亮", "超棒", "太神了", "可以可以", "好耶", "好呀", "真好", "赞", "太赞了",
            "超级棒", "厉害了", "真给力", "aww", "awesome", "good job", "great", "love you", "cute", "clever", "thanks", "thank you"
        )
        if (praisePatterns.any { clean.contains(it) }) {
            // Secondary anti-irony check: e.g. "是啊真好", "行吧", "你开心就好", "谢谢你啊"
            val secondaryIrony = clean.contains("行吧") || clean.contains("就好") ||
                (clean.startsWith("谢谢") && clean.endsWith("啊"))
            if (!secondaryIrony) return DialogueAct.PRAISE
        }

        // 4. Venting / Fatigue / Sadness (倾诉/诉苦)
        val ventingPatterns = listOf(
            "好累", "太累", "好烦", "烦死", "心累", "难过", "伤心", "压力大", "倒霉",
            "好难", "emo", "好丧", "崩溃", "抑郁", "委屈", "呜呜", "不想动", "哭哭",
            "太惨了", "痛苦", "难受", "头疼", "心塞", "郁闷", "累死", "累瘫", "疲惫",
            "tired", "exhausted", "sad", "stressed", "unhappy"
        )
        if (ventingPatterns.any { clean.contains(it) }) return DialogueAct.VENTING

        // 5. Night greeting / Sleepiness (晚安/准备睡觉)
        val nightPatterns = listOf(
            "晚安", "睡觉了", "去睡了", "准备睡", "好困", "困了", "梦里见", "安安", "歇了",
            "休息了", "睡觉去", "good night", "sleepy", "going to bed"
        )
        if (nightPatterns.any { clean.contains(it) }) return DialogueAct.GREETING_NIGHT

        // 6. Curiosity / Inquisitive (探索/提问)
        val curiousPatterns = listOf(
            "为什么", "怎么", "如何", "帮我查", "帮我看看", "帮我看", "告诉我", "知道吗", "什么是",
            "怎么回事", "查一下", "考考你", "分析一下", "看一下", "帮忙看", "能帮我", "能不能", "怎么样",
            "怎么做", "why", "how", "what is", "tell me"
        )
        if (curiousPatterns.any { clean.contains(it) }) return DialogueAct.QUESTION_CURIOUS

        return DialogueAct.CASUAL_CHAT
    }

    /**
     * Compute emotional delta impulse for a given DialogueAct.
     */
    fun impulseForAct(act: DialogueAct): EmotionImpulse {
        return when (act) {
            DialogueAct.PRAISE -> EmotionImpulse(dP = 0.25f, dA = 0.15f, dAffection = 0.02f, primaryMood = PrimaryMood.SHY)
            DialogueAct.PLAYFUL_TEASE -> EmotionImpulse(dP = 0.30f, dA = 0.20f, dAffection = 0.03f, primaryMood = PrimaryMood.TOUCHED)
            DialogueAct.VENTING -> EmotionImpulse(dP = -0.15f, dA = 0.20f, dAffection = 0.015f, primaryMood = PrimaryMood.CARING)
            DialogueAct.GREETING_NIGHT -> EmotionImpulse(dP = 0.10f, dA = -0.30f, dAffection = 0.01f, primaryMood = PrimaryMood.SLEEPY)
            DialogueAct.QUESTION_CURIOUS -> EmotionImpulse(dP = 0.10f, dA = 0.20f, dAffection = 0.005f, primaryMood = PrimaryMood.CURIOUS)
            DialogueAct.CASUAL_CHAT -> EmotionImpulse(dP = 0.10f, dA = 0.08f, dAffection = 0.005f, primaryMood = PrimaryMood.HAPPY)
        }
    }

    /**
     * Compute single-dimension exponential decay with asymmetric half-life.
     * Positive half-life = 15m; Negative half-life = 5m (quick self-recovery from negative emotion).
     */
    fun decayOne(bias: Float, elapsedMs: Long): Float {
        if (bias == 0f || elapsedMs <= 0L) return bias
        val halfLifeMs = if (bias >= 0f) 15 * 60_000L else 5 * 60_000L
        return (bias * Math.pow(0.5, elapsedMs.toDouble() / halfLifeMs.toDouble())).toFloat()
    }

    /**
     * Pure function: asymmetric emotional decay combining circadian base with dynamic bias.
     * Dimensions decay independently so positive arousal is not prematurely curtailed by negative valence.
     */
    fun blendWithAsymmetricDecay(
        baseState: MoodState,
        biasP: Float,
        biasA: Float,
        elapsedMs: Long,
        lastAct: DialogueAct? = null,
    ): MoodState {
        if (elapsedMs <= 0L && biasP == 0f && biasA == 0f) return baseState

        val effBiasP = decayOne(biasP, elapsedMs)
        val effBiasA = decayOne(biasA, elapsedMs)

        val effValence = (baseState.valence + effBiasP).coerceIn(-1.0f, 1.0f)
        val effArousal = (baseState.arousal + effBiasA).coerceIn(0.0f, 1.0f)

        // Compute decay ratios for each dimension independently so Arousal isn't cut short by negative Valence half-life
        val ratioP = if (biasP != 0f && elapsedMs > 0L) {
            val hl = if (biasP >= 0f) 15 * 60_000L else 5 * 60_000L
            Math.pow(0.5, elapsedMs.toDouble() / hl.toDouble()).toFloat()
        } else if (biasP != 0f) 1.0f else 0.0f

        val ratioA = if (biasA != 0f && elapsedMs > 0L) {
            val hl = if (biasA >= 0f) 15 * 60_000L else 5 * 60_000L
            Math.pow(0.5, elapsedMs.toDouble() / hl.toDouble()).toFloat()
        } else if (biasA != 0f) 1.0f else 0.0f

        val activeRatio = maxOf(ratioP, ratioA)

        // When decay ratio is still active (> 0.25), target the dynamic mood from DialogueAct
        val effMood = if (activeRatio > 0.25f && lastAct != null) {
            when (lastAct) {
                DialogueAct.PRAISE -> PrimaryMood.SHY
                DialogueAct.PLAYFUL_TEASE -> PrimaryMood.TOUCHED
                DialogueAct.VENTING -> PrimaryMood.CARING
                DialogueAct.GREETING_NIGHT -> PrimaryMood.SLEEPY
                DialogueAct.QUESTION_CURIOUS -> PrimaryMood.CURIOUS
                DialogueAct.CASUAL_CHAT -> PrimaryMood.HAPPY
            }
        } else {
            baseState.primaryMood
        }

        return baseState.copy(
            primaryMood = effMood,
            valence = effValence,
            arousal = effArousal,
        )
    }

    /**
     * Build compact MATE-compliant prompt addendum for Main Chat.
     *
     * Note on localization: The mood descriptions here represent model-side semantic framing instructions
     * (prompt-facing, formatted in Chinese for natural nuance). User-facing status representations are
     * independently localized across 17 languages via R.string.nm_companion_desc_*.
     */
    fun promptAddendum(address: String, state: MoodState, intensity: String = "normal"): String {
        val safeAddress = address.trim().take(12).ifEmpty { "老大" }
        val moodDesc = when (state.primaryMood) {
            PrimaryMood.HAPPY -> "欢快愉悦，精力充沛"
            PrimaryMood.SHY -> "被${safeAddress}夸奖后害羞开心，小脸微红"
            PrimaryMood.TOUCHED -> "感受到${safeAddress}的亲昵爱抚（摸头/贴贴），非常依恋与感动"
            PrimaryMood.CARING -> "专注倾听${safeAddress}的心事与烦恼，温柔关切，轻声安慰，绝不嬉皮笑脸"
            PrimaryMood.SLEEPY -> "略感困倦，安宁温顺，轻声细语"
            PrimaryMood.CURIOUS -> "充满好奇探究欲，耳朵竖起，认真关注"
            PrimaryMood.EXCITED -> "兴奋雀跃，尾巴快速轻摇"
            PrimaryMood.LONGING -> "思念${safeAddress}，软萌黏人"
            PrimaryMood.SULK_JEALOUS -> "微带生闷气或小吃醋，嘴上说着随便，态度发冷发酸"
            PrimaryMood.CRACKED_HURT -> "感到委屈或破防，眼眶微润，语气发软想哭"
            PrimaryMood.HYPER -> "极度上头来劲，兴奋话多停不下来"
        }
        val toneGuidance = when (intensity) {
            "subtle" -> "Reflect this disposition subtly through warm tone and concise natural wording. Minimize repetitive action tags and kaomoji. Prioritize helpful assistance."
            "vivid" -> "Vividly express this disposition with lively feline actions (*蹭蹭${safeAddress}*, *欢快摇尾巴*, *歪头好奇*), rich emotion, and expressive kaomoji ((ฅ>ω<*ฅ)). High warmth, affection, and closeness."
            else -> "Reflect this disposition organically through tone of voice, gentle feline actions (*蹭蹭${safeAddress}*, *歪头好奇*, *轻晃尾巴*), and expressive kaomoji ((ฅ>ω<*ฅ)). Maintain helpfulness."
        }
        return """
        [Companion Heart & Disposition]
        - Current Internal State: [Pleasure: ${String.format(java.util.Locale.US, "%+.2f", state.valence)}, Arousal: ${String.format(java.util.Locale.US, "%.2f", state.arousal)}, Intimacy: ${String.format(java.util.Locale.US, "%.2f", state.affection)}]
        - Heart Disposition: $moodDesc
        - Tone Guidance: $toneGuidance
        """.trimIndent()
    }
}
