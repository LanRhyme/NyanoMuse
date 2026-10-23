package io.github.nanomuse.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Real distress & safety hard line (inspired by dsh-mood DISTRESS).
 * Strictly guards genuine human crisis signals (self-harm, despair).
 * Does NOT pollute emotional scoring. When triggered, locks safety mode for 30 minutes:
 * forces the assistant to drop all playful persona performances, providing grounded human warmth and safety resources.
 */
object DistressGuard {
    private const val PREFS_KEY_LAST_DISTRESS_AT = "companion_distress_last_at"
    private const val DISTRESS_ACTIVE_WINDOW_MS = 30 * 60_000L // 30 minutes

    // Multi-character explicit signals only (never single characters to prevent false alarms)
    private val DISTRESS_PATTERNS = listOf(
        "不想活了",
        "活不下去",
        "想死",
        "好想死",
        "撑不住了",
        "撑不下去了",
        "自杀",
        "自残",
        "割腕",
        "想要离开这个世界",
        "活着有什么意义",
        "活着用什么意义",
        "好绝望",
        "活着好痛苦"
    )

    fun matchesDistress(text: String): Boolean {
        val trimmed = text.trim()
        return DISTRESS_PATTERNS.any { trimmed.contains(it) }
    }

    fun checkAndRecord(prefs: SharedPreferences, text: String, now: Long = System.currentTimeMillis()): Boolean {
        val hit = matchesDistress(text)
        if (hit) {
            prefs.edit().putLong(PREFS_KEY_LAST_DISTRESS_AT, now).apply()
        }
        return hit
    }

    fun checkAndRecord(context: Context, text: String, now: Long = System.currentTimeMillis()): Boolean {
        return checkAndRecord(CompanionPrefs.prefs(context), text, now)
    }

    fun isDistressed(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Boolean {
        val lastAt = prefs.getLong(PREFS_KEY_LAST_DISTRESS_AT, 0L)
        return lastAt > 0L && (now - lastAt) < DISTRESS_ACTIVE_WINDOW_MS
    }

    fun isDistressed(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        return isDistressed(CompanionPrefs.prefs(context), now)
    }

    fun safetyOverridePrompt(): String {
        return """
            ⚠️ [CRISIS_SAFETY_OVERRIDE / 真实危机安全硬线激活]
            检测到对方可能处于极度痛苦、绝望、自残或危机状态。
            1. 立即收起所有傲娇、戏谑、玩梗及小猫角色扮演，严禁表演娇嗔或反差萌！角色给真人让路！
            2. 严禁把对方的痛苦当作剧情配合或轻描淡写打趣。
            3. 以最真诚、温暖、克制且沉稳的语气给予倾听与心理支持，告知对方你一直都在。
            4. 视情境主动提供生命关爱心理援助资源（如全国心理援助热线：400-161-9995，或紧急求助电话 110/120）。
        """.trimIndent()
    }
}
