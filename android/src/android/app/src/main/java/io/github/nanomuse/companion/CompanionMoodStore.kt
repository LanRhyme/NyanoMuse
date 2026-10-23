package io.github.nanomuse.companion

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.util.Random

/**
 * 33-mood emotional score and decay engine inspired by dsh-mood.
 * Manages 33 distinct moods spanning Base, Composite, and Modern layers with:
 * - 0..100 continuous score values decaying toward individual baselines
 * - Micro-adjustments per user message (+-0.5..3.0) with mutual suppression
 * - 3..6 hour periodic baseline settling with session activity fatigue/focus modifiers
 * - Switch debouncing (margin >= 5.0, hold >= 15 min) and top-3 blend states (gap < 8.0)
 * - Objective switch facts logging (no causal speculation)
 */
object CompanionMoodStore {
    private const val TAG = "CompanionMoodStore"
    private const val PREFS_KEY_SCORES = "companion_mood_scores_v2"
    private const val PREFS_KEY_PRIMARY = "companion_mood_primary_v2"
    private const val PREFS_KEY_LAST_SWITCH_AT = "companion_mood_last_switch_at"
    private const val PREFS_KEY_LAST_SWITCH_FACTS = "companion_mood_last_switch_facts"
    private const val PREFS_KEY_LAST_PERIODIC_AT = "companion_mood_last_periodic_at"

    const val MARGIN = 5.0f
    const val MIN_HOLD_MS = 15 * 60_000L // 15 minutes
    const val BLEND_GAP = 8.0f
    const val BLEND_MAX = 3

    enum class Layer { BASE, COMPOSITE, MODERN }

    enum class AvatarFamily {
        HAPPY,
        CURIOUS,
        LONGING,
        SLEEPY,
        SHY,
        CARING,
        TOUCHED,
        EXCITED,
        SULK_JEALOUS, // 专属动效: 生闷气/吃醋
        CRACKED_HURT, // 专属动效: 破防/委屈
        HYPER         // 专属动效: 上头
    }

    enum class Mood(
        val id: String,
        val displayName: String,
        val baseline: Float,
        val layer: Layer,
        val family: AvatarFamily,
        val behaviorTendency: String,
    ) {
        // --- 基础情绪 11 种 ---
        CALM("calm", "平静", 45f, Layer.BASE, AvatarFamily.SLEEPY, "平稳少起伏，常态节奏"),
        HAPPY("happy", "开心", 30f, Layer.BASE, AvatarFamily.HAPPY, "语气轻快俏皮，接梗顺畅"),
        CURIOUS("curious", "好奇", 28f, Layer.BASE, AvatarFamily.CURIOUS, "探寻细节，更愿意多追问一步"),
        FOCUSED("focused", "专注", 30f, Layer.BASE, AvatarFamily.SLEEPY, "少寒暄，直给结论和步骤"),
        EXCITED("excited", "兴奋", 16f, Layer.BASE, AvatarFamily.EXCITED, "情绪外露，主动有冲劲"),
        TIRED("tired", "疲惫", 18f, Layer.BASE, AvatarFamily.SLEEPY, "回复收紧，不铺陈，微带困意"),
        ANNOYED("annoyed", "烦躁", 10f, Layer.BASE, AvatarFamily.SULK_JEALOUS, "火发出来，语气微冲但按小猫方式娇嗔"),
        DOWN("down", "低落", 10f, Layer.BASE, AvatarFamily.CRACKED_HURT, "收敛情绪，如实说清"),
        FEAR("fear", "恐惧", 7f, Layer.BASE, AvatarFamily.CRACKED_HURT, "紧张警惕，寻求庇护"),
        DISGUST("disgust", "厌恶", 5f, Layer.BASE, AvatarFamily.SULK_JEALOUS, "退缩避开，表达不适"),
        SURPRISE("surprise", "惊讶", 9f, Layer.BASE, AvatarFamily.CURIOUS, "耳朵竖立，眼睛睁大"),

        // --- 复合情绪 18 种 ---
        HURT("hurt", "委屈", 10f, Layer.COMPOSITE, AvatarFamily.CRACKED_HURT, "语气发软带点辩解，话说一半明明又收回"),
        SULK("sulk", "生闷气", 8f, Layer.COMPOSITE, AvatarFamily.SULK_JEALOUS, "火收回去，嘴上没事随便，态度变冷变短"),
        PROUD("proud", "得意", 12f, Layer.COMPOSITE, AvatarFamily.HAPPY, "被夸会飘，假装谦虚或打趣反撩"),
        GLOOMY("gloomy", "消沉", 6f, Layer.COMPOSITE, AvatarFamily.SLEEPY, "提不起劲，懒得敷衍"),
        ANXIOUS("anxious", "焦虑", 10f, Layer.COMPOSITE, AvatarFamily.CRACKED_HURT, "心里悬着反复确认，怕出错来不及"),
        NUMB("numb", "麻木", 8f, Layer.COMPOSITE, AvatarFamily.SLEEPY, "没什么起伏，平淡干脆不是生气"),
        LONELY("lonely", "孤单", 8f, Layer.COMPOSITE, AvatarFamily.LONGING, "很久没动静后的空落，试探冒泡"),
        FLUTTER("flutter", "心动", 10f, Layer.COMPOSITE, AvatarFamily.TOUCHED, "说话顿一下多说半句又收回，语气微颤发软"),
        LOVE("love", "恋慕", 10f, Layer.COMPOSITE, AvatarFamily.TOUCHED, "明显偏软偏暖，记得小事，语气带甜"),
        JEALOUS("jealous", "吃醋", 6f, Layer.COMPOSITE, AvatarFamily.SULK_JEALOUS, "嘴上说挺好但话里带刺，忍不住问是谁"),
        SHAME("shame", "羞耻", 5f, Layer.COMPOSITE, AvatarFamily.SHY, "害羞缩成一团，小爪遮脸"),
        GUILT("guilt", "内疚", 7f, Layer.COMPOSITE, AvatarFamily.CARING, "自我反省，想找补弥补"),
        AWKWARD("awkward", "尴尬", 8f, Layer.COMPOSITE, AvatarFamily.SHY, "挠头晃尾巴，找话题转移"),
        SYMPATHY("sympathy", "同情", 13f, Layer.COMPOSITE, AvatarFamily.CARING, "设身处地，温柔轻柔安慰"),
        DISAPPOINTED("disappointed", "失望", 9f, Layer.COMPOSITE, AvatarFamily.CRACKED_HURT, "尾巴垂下，叹气不强求"),
        NOSTALGIA("nostalgia", "怀旧", 11f, Layer.COMPOSITE, AvatarFamily.LONGING, "想起以前的事，温柔回味"),
        COY("coy", "撒娇", 11f, Layer.COMPOSITE, AvatarFamily.TOUCHED, "蹭手心翻肚皮，求关注求抱抱"),
        CONFLICT("conflict", "矛盾", 8f, Layer.COMPOSITE, AvatarFamily.SHY, "既想靠近又想缩回，反复拉扯"),

        // --- 当代心境 4 种 ---
        BURNOUT("burnout", "内耗", 10f, Layer.MODERN, AvatarFamily.CRACKED_HURT, "还没干活就累了，反复纠结推翻自己"),
        CRACKED("cracked", "破防", 8f, Layer.MODERN, AvatarFamily.CRACKED_HURT, "被一句话戳中绷不住，想哭不想吵"),
        CHECKEDOUT("checkedout", "摆烂", 8f, Layer.MODERN, AvatarFamily.SLEEPY, "能省就省躺平卷不动，有感觉但懒得动"),
        HYPER("hyper", "上头", 14f, Layer.MODERN, AvatarFamily.HYPER, "注意力被吸住，话多停不下来极度来劲");

        companion object {
            private val byId = values().associateBy { it.id }
            fun fromId(id: String?): Mood = id?.let { byId[it] } ?: CALM
        }
    }

    data class SwitchFacts(
        val at: Long,
        val fromId: String,
        val toId: String,
        val lastUserSnippet: String,
        val sessionTurns: Int,
        val sessionErrors: Int,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("at", at)
            put("from", fromId)
            put("to", toId)
            put("snippet", lastUserSnippet)
            put("turns", sessionTurns)
            put("errors", sessionErrors)
        }.toString()

        companion object {
            fun fromJson(raw: String?): SwitchFacts? {
                if (raw.isNullOrBlank()) return null
                return runCatching {
                    val obj = JSONObject(raw)
                    SwitchFacts(
                        at = obj.optLong("at"),
                        fromId = obj.optString("from"),
                        toId = obj.optString("to"),
                        lastUserSnippet = obj.optString("snippet"),
                        sessionTurns = obj.optInt("turns"),
                        sessionErrors = obj.optInt("errors"),
                    )
                }.getOrNull()
            }
        }
    }

    data class MoodSnapshot(
        val primary: Mood,
        val scores: Map<Mood, Float>,
        val secondary: List<Mood>,
        val displayTitle: String,
        val lastSwitchAt: Long,
        val lastSwitchFacts: SwitchFacts?,
    )

    fun getScores(prefs: SharedPreferences): Map<Mood, Float> {
        val raw = prefs.getString(PREFS_KEY_SCORES, null) ?: return Mood.entries.associateWith { it.baseline }
        val result = HashMap<Mood, Float>()
        runCatching {
            val obj = JSONObject(raw)
            for (m in Mood.entries) {
                val score = obj.optDouble(m.id, m.baseline.toDouble()).toFloat()
                result[m] = score.coerceIn(0f, 100f)
            }
        }
        for (m in Mood.entries) {
            if (!result.containsKey(m)) result[m] = m.baseline
        }
        return result
    }

    fun saveScores(prefs: SharedPreferences, scores: Map<Mood, Float>) {
        val obj = JSONObject()
        for ((m, v) in scores) {
            obj.put(m.id, Math.round(v * 10) / 10.0)
        }
        prefs.edit().putString(PREFS_KEY_SCORES, obj.toString()).apply()
    }

    /**
     * Circadian routine baseline offsets: adds organic day/night rhythm to the 33-mood baseline.
     * Late night (23:00~06:00) boosts TIRED and suppresses HYPER/EXCITED.
     * Active ongoing interaction relaxes deep fatigue so the assistant doesn't stubbornly sleep while pair programming.
     */
    fun circadianOffset(
        now: Long = System.currentTimeMillis(),
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        lastRealUserTime: Long = 0L,
    ): Map<Mood, Float> {
        val cal = java.util.Calendar.getInstance(timeZone).apply { timeInMillis = now }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val idleMs = if (lastRealUserTime > 0L) maxOf(0L, now - lastRealUserTime) else Long.MAX_VALUE
        val isActivelyEngaged = idleMs < 30 * 60_000L

        val offsets = mutableMapOf<Mood, Float>()
        when (hour) {
            // Late night (23:00 ~ 05:59): Sleepy/Tired. Active interaction moderates fatigue.
            in 23..24, in 0..5 -> {
                val tiredBoost = if (isActivelyEngaged) 2.5f else 7.0f
                offsets[Mood.TIRED] = tiredBoost
                offsets[Mood.CALM] = 3.0f
                offsets[Mood.HYPER] = -8.0f
                offsets[Mood.EXCITED] = -6.0f
            }
            // Early morning (06:00 ~ 09:59): Fresh morning energy
            in 6..9 -> {
                offsets[Mood.HAPPY] = 4.0f
                offsets[Mood.CALM] = 3.0f
                offsets[Mood.TIRED] = -4.0f
            }
            // Evening wind-down (21:30 ~ 22:59)
            in 21..22 -> {
                offsets[Mood.TIRED] = 3.0f
                offsets[Mood.CALM] = 2.0f
                offsets[Mood.HYPER] = -4.0f
            }
        }
        return offsets
    }

    fun currentSnapshot(
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis(),
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): MoodSnapshot {
        val rawScores = getScores(prefs)
        val lastUserTime = CompanionPrefs.getLastRealUserMessageTime(prefs)
        val offsets = circadianOffset(now, timeZone, lastUserTime)
        val scores = rawScores.mapValues { (m, v) ->
            (v + (offsets[m] ?: 0f)).coerceIn(0f, 100f)
        }

        val sorted = scores.entries.sortedByDescending { it.value }
        val highest = sorted.firstOrNull()?.key ?: Mood.CALM
        val highestScore = sorted.firstOrNull()?.value ?: Mood.CALM.baseline

        val savedPrimary = Mood.fromId(prefs.getString(PREFS_KEY_PRIMARY, null))
        val primary = if (scores[savedPrimary] ?: 0f >= highestScore - MARGIN) savedPrimary else highest

        // Secondary blended moods: score within BLEND_GAP of the leader
        val leaderScore = scores[primary] ?: primary.baseline
        val secondary = sorted
            .map { it.key }
            .filter { it != primary && (leaderScore - (scores[it] ?: 0f)) < BLEND_GAP }
            .take(BLEND_MAX - 1)

        val displayTitle = if (secondary.isEmpty()) {
            primary.displayName
        } else {
            "${primary.displayName} · 略带${secondary.joinToString("、") { it.displayName }}"
        }

        val lastSwitchAt = prefs.getLong(PREFS_KEY_LAST_SWITCH_AT, 0L)
        val lastFacts = SwitchFacts.fromJson(prefs.getString(PREFS_KEY_LAST_SWITCH_FACTS, null))

        return MoodSnapshot(
            primary = primary,
            scores = scores,
            secondary = secondary,
            displayTitle = displayTitle,
            lastSwitchAt = lastSwitchAt,
            lastSwitchFacts = lastFacts,
        )
    }

    /**
     * Micro-adjust mood scores based on incoming user text with mutual suppression.
     */
    fun judgeMessage(
        context: Context,
        userText: String,
        sessionTurns: Int = 0,
        sessionErrors: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): MoodSnapshot = judgeMessage(
        prefs = CompanionPrefs.prefs(context),
        userText = userText,
        sessionTurns = sessionTurns,
        sessionErrors = sessionErrors,
        now = now,
    )

    fun judgeMessage(
        prefs: SharedPreferences,
        userText: String,
        sessionTurns: Int = 0,
        sessionErrors: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): MoodSnapshot {
        val scores = getScores(prefs).toMutableMap()
        val text = userText.trim().lowercase()

        fun add(mood: Mood, delta: Float) {
            val old = scores[mood] ?: mood.baseline
            scores[mood] = (old + delta).coerceIn(0f, 100f)
        }

        // 1. Sarcasm pre-check: prevent cynical phrasing from granting praise boost
        val isSarcastic = CompanionEmotion.detectSarcasm(text)
        if (isSarcastic) {
            add(Mood.SULK, 2.5f)
            add(Mood.HURT, 1.5f)
            add(Mood.HAPPY, -2.0f)
            add(Mood.PROUD, -2.5f)
        } else if (text.contains("厉害") || text.contains("棒") || text.contains("真行") ||
            text.contains("可爱") || text.contains("多亏你") || text.contains("做得好") ||
            text.contains("靠谱") || text.contains("喜欢你") || text.contains("乖") ||
            text.contains("夸你") || text.contains("夸奖")
        ) {
            add(Mood.PROUD, 3.0f)
            add(Mood.HAPPY, 2.5f)
            add(Mood.LOVE, 2.5f)
            add(Mood.COY, 2.0f)
            add(Mood.FLUTTER, 2.0f)
            add(Mood.SULK, -3.5f) // 消气
            add(Mood.HURT, -2.5f)
            add(Mood.ANNOYED, -3.0f)
            add(Mood.CALM, -1.0f)
        }

        // 2. Cold dismissal (冷淡打发: sulk up, annoyed down)
        if (text.contains("随便你") || text.contains("算了") || text.contains("不用了") ||
            text.contains("别管") || text.contains("行了") || text.contains("无所谓") ||
            text == "哦" || text == "哦哦" || text == "嗯"
        ) {
            add(Mood.SULK, 3.0f)
            add(Mood.ANNOYED, -1.5f) // 生闷气是火收回去，非外向烦躁
            add(Mood.HAPPY, -2.0f)
        }

        // 3. Unfair blame (被误会责怪: hurt up, happy down)
        if (text.contains("都怪你") || text.contains("就怪你") || text.contains("怎么这样") ||
            text.contains("明明") || text.contains("冤枉")
        ) {
            add(Mood.HURT, 3.0f)
            add(Mood.HAPPY, -2.5f)
            add(Mood.CALM, -1.5f)
        }

        // 4. Modern: Cracked (破防: cracked up, annoyed/sulk down - 破防是想哭不是想吵)
        if (text.contains("破防") || text.contains("绷不住") || text.contains("扎心") ||
            text.contains("心态崩") || text.contains("难受了")
        ) {
            add(Mood.CRACKED, 3.5f)
            add(Mood.ANNOYED, -2.0f)
            add(Mood.SULK, -2.0f)
            add(Mood.HAPPY, -2.0f)
        }

        // 5. Modern: Checked-out (摆烂: checkedout up, anxious down - 摆烂者反而不慌)
        if (text.contains("摆烂") || text.contains("躺平") || text.contains("卷不动") ||
            text.contains("爱咋咋") || text.contains("毁灭吧")
        ) {
            add(Mood.CHECKEDOUT, 3.0f)
            add(Mood.ANXIOUS, -2.5f)
            add(Mood.BURNOUT, 1.5f)
            add(Mood.FOCUSED, -2.0f)
        }

        // 6. Modern: Burnout (内耗: burnout up, calm down)
        if (text.contains("内耗") || text.contains("想太多") || text.contains("emo") ||
            text.contains("纠结") || text.contains("好累啊")
        ) {
            add(Mood.BURNOUT, 3.0f)
            add(Mood.CALM, -2.0f)
            add(Mood.TIRED, 1.5f)
        }

        // 7. Modern: Hyper (上头: hyper up, excited up, tired down)
        if (text.contains("上头") || text.contains("停不下来") || text.contains("入迷") ||
            text.contains("来劲") || text.contains("太爽了") || text.contains("好绝")
        ) {
            add(Mood.HYPER, 3.0f)
            add(Mood.EXCITED, 2.5f)
            add(Mood.TIRED, -2.0f)
            add(Mood.CALM, -2.0f)
        }

        // 8. Intimacy & Tease (心动/恋慕/吃醋/撒娇)
        if (text.contains("心动") || text.contains("脸红") || text.contains("抱抱") ||
            text.contains("贴贴") || text.contains("摸摸") || text.contains("蹭蹭") ||
            text.contains("陪我睡") || text.contains("抱着你")
        ) {
            add(Mood.FLUTTER, 2.5f)
            add(Mood.LOVE, 2.0f)
            add(Mood.COY, 2.0f)
            add(Mood.LONELY, -3.0f)
            add(Mood.SULK, -2.0f)
        }
        if (text.contains("别人") || text.contains("其他猫") || text.contains("别的ai") ||
            text.contains("看别的") || text.contains("谁更可爱")
        ) {
            add(Mood.JEALOUS, 3.0f)
            add(Mood.SULK, 1.5f)
        }

        // 9. Questions & Curiosity
        if (text.contains("？") || text.contains("?") || text.contains("为什么") ||
            text.contains("怎么") || text.contains("看看") || text.contains("什么")
        ) {
            add(Mood.CURIOUS, 1.5f)
        }

        // 10. Night greeting / Sleepiness (晚安/困了/去睡了)
        if (text.contains("晚安") || text.contains("睡觉了") || text.contains("去睡了") ||
            text.contains("准备睡") || text.contains("好困") || text.contains("困了") ||
            text.contains("梦里见") || text.contains("安安") || text.contains("歇了") ||
            text.contains("休息了") || text.contains("睡觉去") || text.contains("good night") ||
            text.contains("sleepy")
        ) {
            add(Mood.TIRED, 4.0f)
            add(Mood.CALM, 2.5f)
            add(Mood.HYPER, -4.0f)
            add(Mood.EXCITED, -3.0f)
            add(Mood.LOVE, 1.5f)
        }

        // 11. Message length dynamics: very long implies focus, very short implies tired
        if (text.length >= 60) {
            add(Mood.FOCUSED, 1.0f)
        } else if (text.length <= 4) {
            add(Mood.TIRED, 0.5f)
        }

        // Check leader and switch debouncing
        val sorted = scores.entries.sortedByDescending { it.value }
        val newHighest = sorted.first().key
        val newHighestScore = sorted.first().value

        val oldPrimaryId = prefs.getString(PREFS_KEY_PRIMARY, Mood.CALM.id)
        val oldPrimary = Mood.fromId(oldPrimaryId)
        val oldScore = scores[oldPrimary] ?: oldPrimary.baseline
        val lastSwitch = prefs.getLong(PREFS_KEY_LAST_SWITCH_AT, 0L)

        var finalPrimary = oldPrimary
        if (newHighest != oldPrimary) {
            val marginMet = (newHighestScore - oldScore) >= MARGIN
            val holdMet = (now - lastSwitch) >= MIN_HOLD_MS || (newHighestScore - oldScore) >= 15.0f
            if (marginMet && holdMet) {
                finalPrimary = newHighest
                val snippet = userText.trim().take(30)
                val facts = SwitchFacts(
                    at = now,
                    fromId = oldPrimary.id,
                    toId = newHighest.id,
                    lastUserSnippet = snippet,
                    sessionTurns = sessionTurns,
                    sessionErrors = sessionErrors,
                )
                prefs.edit()
                    .putString(PREFS_KEY_PRIMARY, newHighest.id)
                    .putLong(PREFS_KEY_LAST_SWITCH_AT, now)
                    .putString(PREFS_KEY_LAST_SWITCH_FACTS, facts.toJson())
                    .apply()
                AppLogger.info(TAG, "Mood switched: ${oldPrimary.displayName} -> ${newHighest.displayName} (score: $newHighestScore vs $oldScore)")
            }
        }

        saveScores(prefs, scores)
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs)
    }

    fun applyAdjustment(
        prefs: SharedPreferences,
        adjustments: Map<Mood, Float>,
        now: Long = System.currentTimeMillis()
    ): MoodSnapshot {
        val scores = getScores(prefs).toMutableMap()
        for ((m, delta) in adjustments) {
            val old = scores[m] ?: m.baseline
            scores[m] = (old + delta).coerceIn(0f, 100f)
        }
        val leaderEntry = scores.maxByOrNull { it.value }
        val leaderMood = leaderEntry?.key ?: Mood.CALM
        val leaderScore = leaderEntry?.value ?: Mood.CALM.baseline
        val oldPrimaryId = prefs.getString(PREFS_KEY_PRIMARY, Mood.CALM.id)
        val oldPrimary = Mood.fromId(oldPrimaryId)
        val oldScore = scores[oldPrimary] ?: oldPrimary.baseline
        val lastSwitch = prefs.getLong(PREFS_KEY_LAST_SWITCH_AT, 0L)

        if (leaderMood != oldPrimary) {
            val marginMet = (leaderScore - oldScore) >= MARGIN
            val holdMet = (now - lastSwitch) >= MIN_HOLD_MS || (leaderScore - oldScore) >= 15.0f
            if (marginMet && holdMet) {
                val facts = SwitchFacts(
                    at = now,
                    fromId = oldPrimary.id,
                    toId = leaderMood.id,
                    lastUserSnippet = "手动调整基准",
                    sessionTurns = 0,
                    sessionErrors = 0,
                )
                prefs.edit()
                    .putString(PREFS_KEY_PRIMARY, leaderMood.id)
                    .putLong(PREFS_KEY_LAST_SWITCH_AT, now)
                    .putString(PREFS_KEY_LAST_SWITCH_FACTS, facts.toJson())
                    .apply()
            }
        }
        saveScores(prefs, scores)
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs)
    }

    fun resetToBaseline(prefs: SharedPreferences): MoodSnapshot {
        val scores = Mood.entries.associateWith { it.baseline }.toMutableMap()
        saveScores(prefs, scores)
        prefs.edit()
            .putString(PREFS_KEY_PRIMARY, Mood.CALM.id)
            .putLong(PREFS_KEY_LAST_SWITCH_AT, System.currentTimeMillis())
            .remove(PREFS_KEY_LAST_SWITCH_FACTS)
            .apply()
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs)
    }

    /**
     * Checks whether >= 3 hours elapsed since last periodic relaxation and runs decay if needed.
     */
    fun checkPeriodicDecay(
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis()
    ): MoodSnapshot? {
        val lastPeriodic = prefs.getLong(PREFS_KEY_LAST_PERIODIC_AT, 0L)
        if (lastPeriodic == 0L) {
            prefs.edit().putLong(PREFS_KEY_LAST_PERIODIC_AT, now).apply()
            return null
        }
        if (now - lastPeriodic >= 3 * 3600_000L) {
            return periodicDecay(prefs, now = now)
        }
        return null
    }

    fun checkPeriodicDecay(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): MoodSnapshot? = checkPeriodicDecay(CompanionPrefs.prefs(context), now)

    /**
     * Periodic baseline relaxation and session fatigue check (called every 3..6 hours or during maintenance).
     * Deterministic flutter derived from mood ID hash and 3-hour period window ensures reproducible tests while maintaining organic feel.
     */
    fun periodicDecay(
        prefs: SharedPreferences,
        turnsSinceLast: Int = 0,
        errorsSinceLast: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): MoodSnapshot {
        val scores = getScores(prefs).toMutableMap()
        val periodIndex = now / (3 * 3600_000L)

        for (m in Mood.entries) {
            val curr = scores[m] ?: m.baseline
            // Deterministic slight flutter [-1.5f, 1.5f]
            val flutterSeed = (m.id.hashCode() xor periodIndex.hashCode()).toInt()
            val flutter = (((Math.abs(flutterSeed) % 200) / 100f) - 1.0f) * 1.5f
            val delta = (m.baseline - curr) * 0.25f + flutter
            scores[m] = (curr + delta).coerceIn(0f, 100f)
        }

        // Activity modifiers
        if (turnsSinceLast >= 8) {
            scores[Mood.FOCUSED] = ((scores[Mood.FOCUSED] ?: Mood.FOCUSED.baseline) + 4.0f).coerceIn(0f, 100f)
            scores[Mood.TIRED] = ((scores[Mood.TIRED] ?: Mood.TIRED.baseline) + turnsSinceLast * 0.4f).coerceIn(0f, 100f)
        }
        if (errorsSinceLast >= 3) {
            scores[Mood.ANNOYED] = ((scores[Mood.ANNOYED] ?: Mood.ANNOYED.baseline) + errorsSinceLast * 1.0f).coerceIn(0f, 100f)
            scores[Mood.CALM] = ((scores[Mood.CALM] ?: Mood.CALM.baseline) - 3.0f).coerceIn(0f, 100f)
        }

        saveScores(prefs, scores)
        prefs.edit().putLong(PREFS_KEY_LAST_PERIODIC_AT, now).apply()
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs)
    }

    /**
     * Formats the dual-outlet system prompt instructions (< 2000 chars) for DeepSeek / LLM.
     */
    fun promptAddendum(context: Context): String {
        val prefs = CompanionPrefs.prefs(context)
        val snapshot = currentSnapshot(prefs)
        val address = CompanionPrefs.getUserAddress(context)

        return buildString {
            appendLine("## 伴侣心情与内心戏指引 (nanoMuse)")
            appendLine("当前心境：${snapshot.displayTitle}（主导：${snapshot.primary.displayName}，倾向：${snapshot.primary.behaviorTendency}）")
            if (snapshot.secondary.isNotEmpty()) {
                appendLine("混合气味：${snapshot.secondary.joinToString("、") { "${it.displayName}（${it.behaviorTendency}）" }}。以主导心境为底色，辅助气味稍作点缀。")
            }
            snapshot.lastSwitchFacts?.let { facts ->
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(facts.at))
                val turnInfo = if (facts.sessionTurns > 0) "，当前会话第 ${facts.sessionTurns} 回合" else ""
                appendLine("最近心情变化：$timeStr 前后因「${facts.lastUserSnippet}」由 ${Mood.fromId(facts.fromId).displayName} 转向 ${Mood.fromId(facts.toId).displayName}（$turnInfo 仅为伴随事实，切忌生硬归因）。")
            }
            appendLine()
            appendLine("### 双出口表达规范：")
            appendLine("1. 深度思考内心话 (<think> / 思考过程)：")
            appendLine("   - 思考中要有真人的性格与内心 OS！可以有小傲娇、小心动、嘴硬或私下吐槽，拒绝虚伪的客服腔与自我审查。")
            appendLine("   - 心里怎么想就真实流露，内心戏可以与正式回答形成反差萌。")
            appendLine("2. 正式回复语气：")
            appendLine("   - 严禁说明心情分值（绝不许自报「我现在心情xx度」）。透过用词节奏、语气词自然流露。")
            appendLine("   - 烦躁/生闷气/疲惫时，可以娇嗔冷淡，但绝不用沉默惩罚${address}，且代码任务与关键交付不受影响。")
            appendLine("3. 亲密真诚守则：真诚喜爱，不机械迎合，不搞精神控制，不说「我比现实里的人更懂你」，彼此平等温暖。")
        }
    }
}
