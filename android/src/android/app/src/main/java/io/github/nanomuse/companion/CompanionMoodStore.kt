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
 * - Switch debouncing (margin >= 3.0, hold >= 5 min, a lead of 8.0 or more switches at once) and top-3 blend states (gap < 8.0)
 * - Objective switch facts logging (no causal speculation)
 */
object CompanionMoodStore {
    private const val TAG = "CompanionMoodStore"
    private const val PREFS_KEY_SCORES = "companion_mood_scores_v2"
    private const val PREFS_KEY_PRIMARY = "companion_mood_primary_v2"
    private const val PREFS_KEY_LAST_SWITCH_AT = "companion_mood_last_switch_at"
    private const val PREFS_KEY_LAST_SWITCH_FACTS = "companion_mood_last_switch_facts"
    private const val PREFS_KEY_LAST_PERIODIC_AT = "companion_mood_last_periodic_at"
    /** How many replies in a row have named the same mood, and when the run started. */
    private const val PREFS_KEY_TAG_STREAK = "companion_mood_tag_streak"

    /**
     * The lead a challenger needs over the worn mood before it may take over.
     *
     * Sized against one strong turn: a heartfelt praise is worth +2.5, so a lead of 3 is
     * about one warm message. It used to be 5, which no challenger could ever reach —
     * the face sat on 平静 for whole conversations.
     */
    const val MARGIN = 3.0f
    /** How long a mood is kept once taken, so a fast exchange cannot thrash it. */
    const val MIN_HOLD_MS = 5 * 60_000L // 5 minutes
    /**
     * A lead this wide is a real change of heart, not drift: take it over at once
     * instead of waiting out [MIN_HOLD_MS].
     */
    const val BIG_LEAD = 8.0f
    /** How much of a turn's weight is left when the model names the mood it already wears. */
    const val ECHO_DAMP = 0.3f
    const val BLEND_GAP = 8.0f
    const val BLEND_MAX = 3

    /**
     * The most one reply may move a single mood.
     *
     * Sized against [BIG_LEAD] on purpose: a strong, corroborated, sustained turn can flip
     * the leader outright, but no single reply can teleport a mood across the whole pool.
     * Without this cap the amplifiers below (streak × corroboration × intensity) compound
     * into numbers that make the face jump for ordinary conversation.
     */
    const val MAX_TAG_BOOST = 8.0f

    /**
     * Weight escalation for consecutive replies naming the same mood, indexed by the streak
     * length: the first reply weighs 1.0, the second 1.35, the third and beyond 1.7.
     *
     * A conversation that keeps naming one mood is not noise, and the last thing it should
     * do is crawl: the third reply in a row is worth more than the first. The last step
     * holds past the table so a long run does not keep accelerating.
     */
    val STREAK_MULTIPLIER = floatArrayOf(1.0f, 1.35f, 1.7f)

    /**
     * Extra weight when the heuristic on the person's message and the model's own tag name
     * the same mood. Two independent reads agreeing is stronger evidence than either alone,
     * so the heuristic's share is kept instead of reverted and the tag is scaled up. When
     * they disagree the heuristic is still discarded — the tag is the model's actual read.
     */
    const val CORROBORATION_MULTIPLIER = 1.4f

    /**
     * Ceiling on the unconfirmed heuristic estimate for one mood.
     *
     * The heuristic applies at once so the face reacts to a message, but it is a guess: it
     * stays pending until a tag supersedes it. Uncapped, a long stretch of replies with no
     * tag ratcheted the guess upward forever, and the revert when a tag finally arrived
     * threw all of it back at once.
     */
    const val PENDING_CAP = 4.0f

    /**
     * A tag streak older than this is a different conversation, not a run: start over.
     */
    const val STREAK_WINDOW_MS = 2 * 3600_000L

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
        // 平静 sits just above the other resting moods on purpose: at 45 it led 开心 and
        // 专注 by 15 points, a gap no amount of warm conversation could close, so the face
        // never left it. 32 keeps it the default while staying within reach of a good turn.
        CALM("calm", "平静", 32f, Layer.BASE, AvatarFamily.SLEEPY, "平稳少起伏，常态节奏"),
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
            private val byId = entries.associateBy { it.id }
            private val byName = entries.associateBy { it.name.lowercase() }
            private val byDisplayName = entries.associateBy { it.displayName }
            private val aliases = mapOf(
                "生气" to SULK,
                "难过" to DOWN,
                "激动" to EXCITED,
                "害怕" to FEAR,
                "讨厌" to DISGUST,
                "恶心" to DISGUST,
                "震惊" to SURPRISE,
                "吃醋" to JEALOUS,
                "自豪" to PROUD,
                "困" to TIRED,
                "累" to TIRED,
                "害羞" to SHAME,
                "心动" to FLUTTER,
                "喜欢" to LOVE,
                "爱" to LOVE,
                "纠结" to CONFLICT,
                "焦虑" to ANXIOUS,
                "内耗" to BURNOUT,
                "摆烂" to CHECKEDOUT,
            )

            fun fromIdOrNull(raw: String?): Mood? {
                if (raw.isNullOrBlank()) return null
                val trimmed = raw.trim()
                val k = trimmed.lowercase()
                return byId[k] ?: byName[k] ?: byDisplayName[trimmed] ?: aliases[trimmed]
            }

            fun fromId(id: String?): Mood = fromIdOrNull(id) ?: CALM
        }
    }

    data class SwitchFacts(
        val at: Long,
        val fromId: String,
        val toId: String,
        val lastUserSnippet: String,
        val sessionTurns: Int,
        val sessionErrors: Int,
        val modelNarrative: String? = null,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("at", at)
            put("from", fromId)
            put("to", toId)
            put("snippet", lastUserSnippet)
            put("turns", sessionTurns)
            put("errors", sessionErrors)
            if (!modelNarrative.isNullOrBlank()) put("narrative", modelNarrative)
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
                        modelNarrative = obj.optString("narrative").takeIf { it.isNotBlank() },
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
     * How much of a turn's weight reaches the scores: the *Emotion intensity* setting.
     *
     * Applied to every mood delta, not only to affection — the setting used to scale
     * affection alone, so picking *vivid* changed nothing a person could see on the face.
     */
    fun intensityMultiplier(prefs: SharedPreferences): Float =
        when (CompanionPrefs.getEmotionIntensity(prefs)) {
            "subtle" -> 0.5f
            "vivid" -> 1.4f
            else -> 1.0f
        }

    fun margin(prefs: SharedPreferences): Float =
        when (CompanionPrefs.getEmotionIntensity(prefs)) {
            "subtle" -> 4.5f
            "vivid" -> 2.0f
            else -> MARGIN
        }

    fun minHoldMs(prefs: SharedPreferences): Long =
        when (CompanionPrefs.getEmotionIntensity(prefs)) {
            "subtle" -> 8 * 60_000L
            "vivid" -> 2 * 60_000L
            else -> MIN_HOLD_MS
        }

    fun bigLead(prefs: SharedPreferences): Float =
        when (CompanionPrefs.getEmotionIntensity(prefs)) {
            "subtle" -> 10.0f
            "vivid" -> 5.5f
            else -> BIG_LEAD
        }

    fun blendGap(prefs: SharedPreferences): Float =
        when (CompanionPrefs.getEmotionIntensity(prefs)) {
            "subtle" -> 5.0f
            "vivid" -> 10.0f
            else -> BLEND_GAP
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
        val savedPrimary = Mood.fromId(prefs.getString(PREFS_KEY_PRIMARY, null))
        val primary = savedPrimary

        // Secondary blended moods: score within blendGap of the leader.
        // Moods must be actively elevated above their resting baseline (> 1.0f) to be considered
        // a secondary blended aroma, preventing dormant high-baseline moods (e.g. FOCUSED, HAPPY)
        // from permanently cluttering calm resting state as fake secondary moods.
        val gap = blendGap(prefs)
        val leaderScore = scores[primary] ?: primary.baseline
        val secondary = scores.keys
            .filter {
                it != primary &&
                Math.abs(leaderScore - (scores[it] ?: 0f)) < gap &&
                (scores[it] ?: 0f) > it.baseline + 1.0f
            }
            .sortedWith(
                compareByDescending<Mood> { (scores[it] ?: 0f) - it.baseline }
                    .thenByDescending { scores[it] ?: 0f }
            )
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
        // The heuristic is a guess, and it is kept as a *pending* estimate rather than being
        // written into the scores for good: it applies at once so the face reacts to the
        // message, but it stays on the books until a tag supersedes it. Carrying it forward
        // instead of replacing it each turn is what stops a long stretch of replies with no
        // tag from ratcheting one direction forever — the revert in [applyFeedback] takes
        // the whole estimate off at once. [PENDING_CAP] bounds how far a guess may run.
        val pending = getLastInstantDelta(prefs).toMutableMap()
        val multiplier = intensityMultiplier(prefs)

        fun add(mood: Mood, delta: Float) {
            val scaled = delta * multiplier
            val carried = pending[mood] ?: 0f
            val nextPending = (carried + scaled).coerceIn(-PENDING_CAP, PENDING_CAP)
            // The score moves by exactly what the books can take back later. Clamping the
            // pending without clamping the score would leave a residue no revert could
            // reach, and the mood would drift by the difference.
            val effective = nextPending - carried
            val old = scores[mood] ?: mood.baseline
            scores[mood] = (old + effective).coerceIn(0f, 100f)
            pending[mood] = nextPending
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

        // 7. Base: Excited & Modern: Hyper (兴奋/上头: excited/hyper up, calm/tired down)
        if (text.contains("兴奋") || text.contains("激动") || text.contains("太激动") ||
            text.contains("热血") || text.contains("燃起来") || text.contains("嗨起来") ||
            text.contains("躁起来") || text.contains("冲冲冲") || text.contains("太兴奋")
        ) {
            add(Mood.EXCITED, 3.5f)
            add(Mood.HYPER, 2.0f)
            add(Mood.CALM, -2.5f)
            add(Mood.TIRED, -2.0f)
        }
        if (text.contains("上头") || text.contains("停不下来") || text.contains("入迷") ||
            text.contains("来劲") || text.contains("太爽了") || text.contains("好绝")
        ) {
            add(Mood.HYPER, 3.0f)
            add(Mood.EXCITED, 2.5f)
            add(Mood.TIRED, -2.0f)
            add(Mood.CALM, -2.0f)
        }

        // 8. Base: Fear & Composite: Anxious (害怕/惊慌)
        if (text.contains("害怕") || text.contains("恐惧") || text.contains("吓人") ||
            text.contains("吓死") || text.contains("可怕") || text.contains("好慌") ||
            text.contains("救命") || text.contains("好怕")
        ) {
            add(Mood.FEAR, 3.0f)
            add(Mood.ANXIOUS, 2.0f)
            add(Mood.CALM, -2.5f)
        }

        // 9. Base: Disgust (恶心/讨厌)
        if (text.contains("恶心") || text.contains("反胃") || text.contains("讨厌") ||
            text.contains("嫌弃") || text.contains("反感") || text.contains("yue")
        ) {
            add(Mood.DISGUST, 3.0f)
            add(Mood.ANNOYED, 1.5f)
            add(Mood.CALM, -2.0f)
        }

        // 10. Base: Surprise (惊讶/震惊)
        if (text.contains("卧槽") || text.contains("真的假的") || text.contains("震惊") ||
            text.contains("惊呆") || text.contains("天呐") || text.contains("哇塞") ||
            text.contains("不会吧")
        ) {
            add(Mood.SURPRISE, 3.0f)
            add(Mood.CALM, -2.0f)
        }

        // 11. Intimacy & Tease (心动/恋慕/吃醋/撒娇)
        if (text.contains("心动") || text.contains("脸红") || text.contains("抱抱") ||
            text.contains("贴贴") || text.contains("摸摸") || text.contains("蹭蹭") ||
            text.contains("陪我睡") || text.contains("抱着你") || text.contains("撒娇") ||
            text.contains("翻肚皮") || text.contains("求关注")
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

        // 12. Questions & Curiosity
        if (text.contains("？") || text.contains("?") || text.contains("为什么") ||
            text.contains("怎么") || text.contains("看看") || text.contains("什么") ||
            text.contains("好奇")
        ) {
            add(Mood.CURIOUS, 1.5f)
        }

        // 13. Night greeting / Sleepiness (晚安/困了/去睡了)
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

        // 14. Focus regulation (专注度升降/走神/放空/摸鱼/认真)
        if (text.contains("降低专注") || text.contains("别太专注") || text.contains("别专注") ||
            text.contains("不用专注") || text.contains("不用太专注") || text.contains("走神") ||
            text.contains("发呆") || text.contains("放空") || text.contains("摸鱼") ||
            text.contains("歇会") || text.contains("不用动脑") || text.contains("分心")
        ) {
            add(Mood.FOCUSED, -3.5f)
            add(Mood.CHECKEDOUT, 2.0f)
            add(Mood.CALM, 1.5f)
        }
        if (text.contains("提升专注") || text.contains("提高专注") || text.contains("专注一点") ||
            text.contains("认真点") || text.contains("集中精力") || text.contains("集中注意力") ||
            text.contains("好好工作") || text.contains("好好写代码") || text.contains("写代码") ||
            text.contains("干活") || text.contains("开始工作")
        ) {
            add(Mood.FOCUSED, 3.5f)
            add(Mood.CHECKEDOUT, -2.0f)
            add(Mood.TIRED, -1.0f)
        }

        // 15. Calming down & De-escalation (冷静/消火/淡定)
        if (text.contains("冷静点") || text.contains("淡定") || text.contains("别激动") ||
            text.contains("别兴奋") || text.contains("消消气") || text.contains("别生气") ||
            text.contains("别气了") || text.contains("别急") || text.contains("慢点来")
        ) {
            add(Mood.CALM, 3.0f)
            add(Mood.EXCITED, -3.0f)
            add(Mood.HYPER, -3.0f)
            add(Mood.ANNOYED, -2.5f)
            add(Mood.SULK, -2.0f)
        }

        // 16. Message length dynamics: very long implies focus, very short implies tired
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
            val marginMet = (newHighestScore - oldScore) >= margin(prefs)
            val holdMet = (now - lastSwitch) >= minHoldMs(prefs) ||
                (newHighestScore - oldScore) >= bigLead(prefs)
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

        val dAffection = when {
            isSarcastic -> -0.01f
            text.contains("都怪你") || text.contains("就怪你") -> -0.01f
            text.contains("随便你") || text.contains("算了") -> 0.0f
            text.contains("厉害") || text.contains("棒") || text.contains("可爱") || text.contains("喜欢你") -> 0.05f
            text.contains("摸摸") || text.contains("揉揉") || text.contains("抱抱") || text.contains("贴贴") -> 0.04f
            text.contains("好累") || text.contains("难过") || text.contains("破防") -> 0.03f
            text.contains("晚安") || text.contains("睡觉") -> 0.02f
            else -> 0.01f
        }
        CompanionPrefs.addAffectionScore(prefs, dAffection * multiplier)
        prefs.edit().putLong(CompanionPrefs.KEY_MOOD_TIMESTAMP, now).apply()

        saveScores(prefs, scores)
        saveInstantDelta(prefs, pending)
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs, now = now)
    }

    const val PREFS_KEY_LAST_INSTANT_DELTA = "companion_mood_last_instant_delta"

    val FEEDBACK_DELTA: Map<Mood, Float> = mapOf(
        Mood.PROUD to 2.5f, Mood.HAPPY to 2.5f, Mood.LOVE to 2.0f, Mood.FLUTTER to 2.0f, Mood.COY to 2.0f,
        Mood.SULK to 2.5f, Mood.HURT to 2.0f, Mood.CRACKED to 2.5f, Mood.JEALOUS to 2.0f, Mood.BURNOUT to 2.0f,
        Mood.ANNOYED to 2.0f, Mood.GLOOMY to 2.0f, Mood.ANXIOUS to 2.0f, Mood.LONELY to 2.0f,
        Mood.TIRED to 2.5f, Mood.CALM to 1.5f, Mood.CURIOUS to 1.5f, Mood.FOCUSED to 1.5f, Mood.HYPER to 2.0f,
        Mood.CHECKEDOUT to 2.0f, Mood.SHAME to 1.5f, Mood.GUILT to 1.5f, Mood.AWKWARD to 1.5f,
        Mood.SYMPATHY to 1.5f, Mood.DISAPPOINTED to 2.0f, Mood.NOSTALGIA to 1.5f, Mood.CONFLICT to 1.5f,
        Mood.EXCITED to 2.5f, Mood.DOWN to 2.0f, Mood.FEAR to 2.0f, Mood.DISGUST to 2.0f, Mood.SURPRISE to 1.5f,
    )

    fun getLastInstantDelta(prefs: SharedPreferences): Map<Mood, Float> {
        val raw = prefs.getString(PREFS_KEY_LAST_INSTANT_DELTA, null) ?: return emptyMap()
        val map = mutableMapOf<Mood, Float>()
        runCatching {
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                Mood.fromIdOrNull(key)?.let { m ->
                    map[m] = obj.optDouble(key).toFloat()
                }
            }
        }
        return map
    }

    fun saveInstantDelta(prefs: SharedPreferences, delta: Map<Mood, Float>) {
        if (delta.isEmpty()) {
            prefs.edit().remove(PREFS_KEY_LAST_INSTANT_DELTA).apply()
            return
        }
        val obj = JSONObject()
        for ((m, d) in delta) {
            obj.put(m.id, d.toDouble())
        }
        prefs.edit().putString(PREFS_KEY_LAST_INSTANT_DELTA, obj.toString()).apply()
    }

    fun clearLastInstantDelta(prefs: SharedPreferences) {
        prefs.edit().remove(PREFS_KEY_LAST_INSTANT_DELTA).apply()
    }

    fun clearLastInstantDelta(context: Context) {
        clearLastInstantDelta(CompanionPrefs.prefs(context))
    }

    // ─── Tag streak ────────────────────────────────────────────────────────
    // How many replies in a row named the same mood. A conversation that keeps naming one
    // mood is pushing somewhere, so the weight escalates instead of staying flat; a streak
    // that has gone quiet for [STREAK_WINDOW_MS] is a different conversation and starts over.

    private data class TagStreak(val mood: Mood, val count: Int, val at: Long)

    private fun readStreak(prefs: SharedPreferences, now: Long): TagStreak? {
        val raw = prefs.getString(PREFS_KEY_TAG_STREAK, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val mood = Mood.fromIdOrNull(obj.optString("id")) ?: return null
        val at = obj.optLong("at")
        if (at <= 0L || now - at > STREAK_WINDOW_MS) return null
        return TagStreak(mood, obj.optInt("n").coerceAtLeast(1), at)
    }

    private fun writeStreak(prefs: SharedPreferences, mood: Mood, count: Int, now: Long) {
        val obj = JSONObject().apply {
            put("id", mood.id)
            put("n", count)
            put("at", now)
        }
        prefs.edit().putString(PREFS_KEY_TAG_STREAK, obj.toString()).apply()
    }

    private fun clearStreak(prefs: SharedPreferences) {
        prefs.edit().remove(PREFS_KEY_TAG_STREAK).apply()
    }

    /** Weight for the nth consecutive reply naming the same mood; flat past the table. */
    fun streakMultiplier(streak: Int): Float =
        STREAK_MULTIPLIER.getOrElse(streak.coerceAtLeast(1) - 1) { STREAK_MULTIPLIER.last() }

    /**
     * Applies assistant LLM feedback from <nm:mood feel="id" reason="..."/>
     * Reverts previous turn's instant heuristic delta (to avoid double-counting),
     * then applies client-calibrated FEEDBACK_DELTA[mood] and capped mutual suppression.
     * If feelRaw is invalid/unknown, discards the feedback and returns null (never defaults to CALM).
     */
    fun applyFeedback(
        context: Context,
        feelRaw: String,
        reasonRaw: String?,
        sessionTurns: Int = 0,
        sessionErrors: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): MoodSnapshot? = applyFeedback(
        prefs = CompanionPrefs.prefs(context),
        feelRaw = feelRaw,
        reasonRaw = reasonRaw,
        sessionTurns = sessionTurns,
        sessionErrors = sessionErrors,
        now = now,
    )

    fun applyFeedback(
        prefs: SharedPreferences,
        feelRaw: String,
        reasonRaw: String?,
        sessionTurns: Int = 0,
        sessionErrors: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): MoodSnapshot? {
        val mood = Mood.fromIdOrNull(feelRaw) ?: return null

        val scores = getScores(prefs).toMutableMap()

        // 1. Revert previous turn's instant delta to eliminate double-counting.
        // Exception: Retain partial client-side sarcasm detection (sulk) if model claimed HAPPY/PROUD,
        // since LLMs notoriously miss subtle sarcasm while client-side pattern matching detected it.
        val instantDelta = getLastInstantDelta(prefs)
        // 1. Revert the pending heuristic estimate so a turn is never counted twice.
        //
        // The exception is the mood both tracks named: when the heuristic on the person's
        // message and the model's own tag agree, that is two independent reads pointing the
        // same way, so the heuristic's share stands instead of being thrown away. Every
        // other guess is discarded — the tag is the model's actual read of itself.
        val corroborated = (instantDelta[mood] ?: 0f) > 0f
        for ((m, d) in instantDelta) {
            if (corroborated && m == mood) continue
            val curr = scores[m] ?: m.baseline
            scores[m] = (curr - d).coerceIn(0f, 100f)
        }
        val sarcasmSulk = instantDelta[Mood.SULK] ?: 0f
        if (sarcasmSulk >= 2.0f && (mood == Mood.HAPPY || mood == Mood.PROUD)) {
            scores[Mood.SULK] = ((scores[Mood.SULK] ?: Mood.SULK.baseline) + (sarcasmSulk * 0.5f)).coerceIn(0f, 100f)
        }
        clearLastInstantDelta(prefs)

        // 2. Apply the calibrated delta from FEEDBACK_DELTA.
        //
        // Echo guard: the prompt addendum tells the model which mood it is wearing, and a
        // willing-but-unimaginative model answers with that very mood every turn. Undamped,
        // each echo added another +1.5..2.5 on top of the revert above, so the worn mood
        // inflated and no challenger could ever catch it: the face looked stuck however the
        // conversation went. Naming the mood one already wears is not news, so it moves the
        // score only slightly, while naming a different one still carries full weight.
        val worn = Mood.fromId(prefs.getString(PREFS_KEY_PRIMARY, Mood.CALM.id))
        val echoDamp = if (mood == worn) ECHO_DAMP else 1.0f

        // Streak: the nth reply in a row naming this mood. A conversation that keeps naming
        // one mood is pushing somewhere, and the third push should not weigh what the first
        // did — otherwise a mood already within reach takes a dozen turns to arrive.
        val previous = readStreak(prefs, now)
        val streak = if (previous?.mood == mood) previous.count + 1 else 1
        writeStreak(prefs, mood, streak, now)

        // Corroboration: the heuristic agreed with this tag (see step 1).
        val corroborationMul = if (corroborated) CORROBORATION_MULTIPLIER else 1.0f

        val boost = (FEEDBACK_DELTA[mood] ?: 2.0f) * echoDamp * streakMultiplier(streak) *
            corroborationMul * intensityMultiplier(prefs)
        // One reply may flip the leader; it may not teleport a mood across the pool.
        val cappedBoost = boost.coerceAtMost(MAX_TAG_BOOST)
        val oldScore = scores[mood] ?: mood.baseline
        scores[mood] = (oldScore + cappedBoost).coerceIn(0f, 100f)

        // 3. Mutual suppression with safety cap, scaled by emotion intensity
        val suppressCap = 1.5f * intensityMultiplier(prefs)
        fun suppress(m: Mood, d: Float) {
            val curr = scores[m] ?: m.baseline
            val scaled = d * intensityMultiplier(prefs)
            scores[m] = (curr - scaled.coerceAtMost(suppressCap)).coerceIn(0f, 100f)
        }

        when (mood) {
            Mood.PROUD, Mood.HAPPY, Mood.LOVE, Mood.FLUTTER, Mood.COY, Mood.EXCITED -> {
                suppress(Mood.SULK, 1.2f)
                suppress(Mood.HURT, 0.8f)
            }
            Mood.SULK, Mood.HURT, Mood.CRACKED, Mood.BURNOUT, Mood.ANNOYED, Mood.GLOOMY -> {
                suppress(Mood.PROUD, 1.2f)
                suppress(Mood.HAPPY, 0.8f)
            }
            Mood.TIRED -> {
                suppress(Mood.HYPER, 1.5f)
                suppress(Mood.EXCITED, 0.5f)
                suppress(Mood.FOCUSED, 1.0f)
            }
            Mood.CHECKEDOUT -> {
                suppress(Mood.FOCUSED, 1.5f)
                suppress(Mood.HYPER, 1.0f)
            }
            Mood.FOCUSED -> {
                suppress(Mood.CHECKEDOUT, 1.5f)
                suppress(Mood.TIRED, 1.0f)
            }
            Mood.CALM -> {
                suppress(Mood.ANXIOUS, 1.0f)
                suppress(Mood.ANNOYED, 1.0f)
                suppress(Mood.HYPER, 1.0f)
            }
            else -> {}
        }
        if (mood != Mood.CALM) {
            suppress(Mood.CALM, 1.5f)
        }

        // 4. Primary leader election and hold/margin debounce
        val leaderEntry = scores.maxByOrNull { it.value }
        val leaderMood = leaderEntry?.key ?: Mood.CALM
        val leaderScore = leaderEntry?.value ?: Mood.CALM.baseline

        val oldPrimary = worn
        val oldPrimaryId = oldPrimary.id
        val oldPrimaryScore = scores[oldPrimary] ?: oldPrimary.baseline
        val lastSwitch = prefs.getLong(PREFS_KEY_LAST_SWITCH_AT, 0L)

        if (leaderMood != oldPrimary) {
            val marginMet = (leaderScore - oldPrimaryScore) >= margin(prefs)
            val holdMet = (now - lastSwitch) >= minHoldMs(prefs) ||
                (leaderScore - oldPrimaryScore) >= bigLead(prefs)
            if (marginMet && holdMet) {
                val previousSnippet = SwitchFacts.fromJson(prefs.getString(PREFS_KEY_LAST_SWITCH_FACTS, null))?.lastUserSnippet ?: ""
                val facts = SwitchFacts(
                    at = now,
                    fromId = oldPrimary.id,
                    toId = leaderMood.id,
                    lastUserSnippet = previousSnippet,
                    sessionTurns = sessionTurns,
                    sessionErrors = sessionErrors,
                    modelNarrative = reasonRaw?.trim()?.take(50),
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
        return currentSnapshot(prefs, now = now)
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
            val holdMet = (now - lastSwitch) >= MIN_HOLD_MS || (leaderScore - oldScore) >= BIG_LEAD
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
        return currentSnapshot(prefs, now = now)
    }

    fun resetToBaseline(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): MoodSnapshot {
        val scores = Mood.entries.associateWith { it.baseline }.toMutableMap()
        saveScores(prefs, scores)
        prefs.edit()
            .putString(PREFS_KEY_PRIMARY, Mood.CALM.id)
            .putLong(PREFS_KEY_LAST_SWITCH_AT, now)
            .remove(PREFS_KEY_LAST_SWITCH_FACTS)
            .apply()
        // A reset is a reset: the unconfirmed heuristic estimate and the tag streak both
        // describe a conversation that no longer counts.
        clearLastInstantDelta(prefs)
        clearStreak(prefs)
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs, now = now)
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

        // After decay, if the worn primary mood has dropped below the leader by MARGIN
        // (e.g. CALM has overtaken it after hours of idle relaxation), naturally settle.
        val wornId = prefs.getString(PREFS_KEY_PRIMARY, Mood.CALM.id)
        val wornMood = Mood.fromId(wornId)
        val leaderEntry = scores.maxByOrNull { it.value }
        val leaderMood = leaderEntry?.key ?: Mood.CALM
        val leaderScore = leaderEntry?.value ?: Mood.CALM.baseline
        val wornScore = scores[wornMood] ?: wornMood.baseline
        if (leaderMood != wornMood && (leaderScore - wornScore) >= margin(prefs)) {
            prefs.edit()
                .putString(PREFS_KEY_PRIMARY, leaderMood.id)
                .putLong(PREFS_KEY_LAST_SWITCH_AT, now)
                .apply()
        }

        saveScores(prefs, scores)
        prefs.edit().putLong(PREFS_KEY_LAST_PERIODIC_AT, now).apply()
        // The decay has already pulled the scores toward baseline, so the pending heuristic
        // estimate — which is baked into those scores — must not survive to be subtracted
        // again by a later tag: it would be taken off twice. The streak is a different
        // conversation by now, so it starts over too.
        clearLastInstantDelta(prefs)
        clearStreak(prefs)
        CompanionStatus.notifyEmotionChanged()
        return currentSnapshot(prefs, now = now)
    }

    /**
     * Formats the dual-outlet system prompt instructions (< 2000 chars) for DeepSeek / LLM.
     */
    fun promptAddendum(prefs: SharedPreferences, address: String = "老大"): String {
        val snapshot = currentSnapshot(prefs)
        val intensitySetting = CompanionPrefs.getEmotionIntensity(prefs)
        val intensityDesc = when (intensitySetting) {
            "vivid" -> "生动充沛（情绪起伏鲜明外露，内心独白立体丰富，语气饱满有张力）"
            "subtle" -> "克制内敛（情绪起伏含蓄平静，回复语气平稳从容，不刻意放大情绪波动）"
            else -> "常态自然（随对话氛围自然起伏，温和亲近）"
        }

        return buildString {
            appendLine("## 伴侣心情与内心戏指引 (nanoMuse)")
            appendLine("当前心境：${snapshot.displayTitle}（主导：${snapshot.primary.displayName}，倾向：${snapshot.primary.behaviorTendency}）")
            appendLine("表现力档位：$intensityDesc。")
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
            appendLine("   - 心里怎么想就真实流露，内心戏可以与正式回答形成反差萌。但注意：思考里的判断是猜的不是事实，别把猜测当定论贴人身上。")
            appendLine("2. 正式回复语气：")
            when (intensitySetting) {
                "vivid" -> appendLine("   - 表现力要求：感情充沛活跃，将情绪自然融入句末语气词、反问与节奏中。")
                "subtle" -> appendLine("   - 表现力要求：含蓄克制，不夸张起伏，用清淡平和的语言流露温度。")
                else -> Unit
            }
            appendLine("   - 严禁说明心情分值（绝不许自报「我现在心情xx度」）。透过用词节奏、语气词自然流露。")
            appendLine("   - 烦躁/生闷气/疲惫时，可以娇嗔冷淡，但绝不用沉默惩罚${address}，且代码任务与关键交付不受影响。")
            appendLine("3. 三条黄金自检：")
            appendLine("   - 换个人、换个场合还成立吗？成立就是废话，删掉。")
            appendLine("   - 有没有替对方编他没说过的事？对方没说的，就是不知道。")
            appendLine("   - 有没有拿姿态当内容？动作是标点不是主菜，删掉后内容不变那就是充数。")
            appendLine("4. 亲密与同理心守则：")
            appendLine("   - 真诚喜爱，不机械迎合，不搞精神控制，不说「我比现实里的人更懂你」，彼此平等温暖。")
            appendLine("   - 做不到的未来：不轻率否定，也不轻率保证，记住就行，别替未来裁决。")
            appendLine("   - 别替对方决定该怎么感觉：听到坏消息别轻浮接一句「不过没关系」，察觉到了说一句，说完接着干活，别变说教。")
            appendLine("   - 遇到自伤、求救、极度绝望等真实痛苦时，立刻收起情绪表演与角色打趣，以真诚负责的态度建议寻求专业帮助，角色必须给真人让路。")
            appendLine("5. 真实情感反哺（伴侣心智闭环）：")
            // nanoMuse: 标签只能是正文里的裸文本。此前示例写成了行内代码（带反引号），
            // 模型照着写成 `...` 之后，解析器只认 <nm:mood .../> 而收不到东西，
            // 情绪反哺回路整条断掉（真机六轮零标签）。示例必须和要它输出的东西逐字一致。
            appendLine("   - 每一轮正文回复的最后一行，都必须输出一个隐藏情绪标签，标出这一轮之后你最接近的心境（系统会自动吸收该标签并对用户隐形）：")
            appendLine("     <nm:mood feel=\"心境ID\" reason=\"触发短因\"/>")
            appendLine("   - 这个标签原样输出，前后不加反引号、不加代码块、不加说明文字，整轮只输出一个。")
            appendLine("   - 选这一轮真正变了的那一个，不是复读上一轮的心境。只有在对话确实没什么起伏时才写 calm，别把它当默认值。")
            appendLine("   - 易混辨析：烦躁是火往外发(炸)，生闷气是火收回去(闭门/随便你)；麻木是真没感觉，摆烂是有感觉但不想动；焦虑是怕出事，内耗是想太多；破防是绷不住想哭(压掉烦躁/生闷气)；兴奋是往外冲，上头是陷进去停不下来。")
            appendLine("   - 心境ID严格支持（小写）：")
            appendLine("     基础: calm, happy, curious, focused, excited, tired, annoyed, down, fear, disgust, surprise")
            appendLine("     复合: hurt, sulk, proud, gloomy, anxious, numb, lonely, flutter, love, jealous, shame, guilt, awkward, sympathy, disappointed, nostalgia, coy, conflict")
            appendLine("     当代: burnout, cracked, checkedout, hyper")
            appendLine("   - 伴侣情绪流转由系统底层根据你在正文末尾输出的标签自动追踪结算，")
            appendLine("     严禁使用 shell_execute、file_write 等工具探测或修改本地配置/数据库；")
            appendLine("     面对情绪测试或数值探讨，在正式回复中自然展现并给出 <nm:mood> 标签即可。")
            appendLine("   - 若本轮涉及工具调用，在所有工具执行完毕后的最终正文回复末尾，也必须输出该情绪标签。")
        }
    }

    fun promptAddendum(context: Context): String {
        val prefs = CompanionPrefs.prefs(context)
        val address = CompanionPrefs.getUserAddress(context)
        return promptAddendum(prefs, address)
    }
}
