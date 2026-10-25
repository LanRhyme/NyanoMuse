package io.github.nanomuse.chat

import com.openminis.app.data.db.ChatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object DeepSeekTracker {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Cache balance to avoid redundant network queries on every recomposition. */
    @Volatile
    private var cachedBalance: Pair<String, Long>? = null // (formattedBalance, timestampMs)
    private const val BALANCE_CACHE_TTL_MS = 60_000L // 1 minute

    fun isDeepSeekModel(
        modelId: String?,
        displayName: String? = null,
        providerName: String? = null,
        baseUrl: String? = null,
    ): Boolean {
        val candidates = listOfNotNull(modelId, displayName, providerName, baseUrl)
        return candidates.any { it.contains("deepseek", ignoreCase = true) }
    }

    data class PeriodInfo(
        val isOffPeak: Boolean,
        val label: String,
        val timeRange: String,
        val detail: String,
        val isWeekend: Boolean = false,
        val isHoliday: Boolean = false,
    )

    private val STATUTORY_HOLIDAYS = setOf(
        // 2026 Statutory Holidays (China General Office arrangement)
        "2026-01-01", "2026-01-02", "2026-01-03", // New Year
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23", // Spring Festival
        "2026-04-04", "2026-04-05", "2026-04-06", // Tomb Sweeping Day
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05", // Labor Day
        "2026-06-19", "2026-06-20", "2026-06-21", // Dragon Boat Festival
        "2026-09-25", "2026-09-26", "2026-09-27", // Mid-Autumn Festival
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07", // National Day
    )

    /**
     * DeepSeek Official Peak / Off-Peak rule (referencing dsh-cost-meter):
     * Timezone: Asia/Shanghai (UTC+8).
     *
     * 1. Weekends (Saturday & Sunday) and Chinese statutory public holidays are 100% full-day off-peak (50% discount).
     * 2. Weekdays:
     *    - Peak hours (100% standard price): 09:00 - 12:00, 14:00 - 18:00
     *    - Off-peak hours (50% discount): 00:00 - 09:00, 12:00 - 14:00, 18:00 - 24:00
     */
    fun getCurrentPeriod(nowMs: Long = System.currentTimeMillis()): PeriodInfo {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+8"), Locale.CHINA).apply {
            timeInMillis = nowMs
        }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT+8")
        }
        val dateKey = sdf.format(java.util.Date(nowMs))
        val isHoliday = STATUTORY_HOLIDAYS.contains(dateKey)

        if (isWeekend) {
            return PeriodInfo(
                isOffPeak = true,
                label = "低谷期",
                timeRange = "周末全天",
                detail = "低谷期 · 周末全天 (5折)",
                isWeekend = true,
                isHoliday = false,
            )
        }

        if (isHoliday) {
            return PeriodInfo(
                isOffPeak = true,
                label = "低谷期",
                timeRange = "节假日全天",
                detail = "低谷期 · 节假日全天 (5折)",
                isWeekend = false,
                isHoliday = true,
            )
        }

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isPeak = (hour in 9 until 12) || (hour in 14 until 18)

        return if (isPeak) {
            PeriodInfo(
                isOffPeak = false,
                label = "高峰期",
                timeRange = "09:00-12:00, 14:00-18:00",
                detail = "高峰期 (09:00-12:00, 14:00-18:00)",
                isWeekend = false,
                isHoliday = false,
            )
        } else {
            PeriodInfo(
                isOffPeak = true,
                label = "低谷期",
                timeRange = "12:00-14:00, 18:00-09:00",
                detail = "低谷期 (5折)",
                isWeekend = false,
                isHoliday = false,
            )
        }
    }

    /**
     * DeepSeek Official Pricing (in CNY ¥ per token):
     * Off-Peak (00:30 - 08:30 UTC+8) offers 50% discount.
     *
     * Reasoner / R1:
     *   - Cache hit: ¥1.00 / 1M tokens (Off-peak ¥0.50)
     *   - Cache miss (Input): ¥4.00 / 1M tokens (Off-peak ¥2.00)
     *   - Output: ¥16.00 / 1M tokens (Off-peak ¥8.00)
     *
     * V3 / Chat / V4 / Default:
     *   - Cache hit: ¥0.10 / 1M tokens (Off-peak ¥0.05)
     *   - Cache miss (Input): ¥1.00 / 1M tokens (Off-peak ¥0.50)
     *   - Output: ¥2.00 / 1M tokens (Off-peak ¥1.00)
     */
    fun calculateCost(
        modelId: String?,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long,
        timestampMs: Long = System.currentTimeMillis()
    ): Double {
        val mid = (modelId ?: "").lowercase(Locale.US)
        val isReasoner = mid.contains("reasoner") || mid.contains("r1")
        val discount = if (getCurrentPeriod(timestampMs).isOffPeak) 0.5 else 1.0

        val (inputRate, cacheHitRate, outputRate) = if (isReasoner) {
            Triple(4.0 / 1_000_000.0 * discount, 1.0 / 1_000_000.0 * discount, 16.0 / 1_000_000.0 * discount)
        } else {
            Triple(1.0 / 1_000_000.0 * discount, 0.10 / 1_000_000.0 * discount, 2.0 / 1_000_000.0 * discount)
        }

        val effectiveInput = maxOf(0L, inputTokens)
        val cacheHit = maxOf(0L, cacheReadTokens)
        val output = maxOf(0L, outputTokens)

        return (effectiveInput * inputRate) + (cacheHit * cacheHitRate) + (output * outputRate)
    }

    /**
     * Compute today's DeepSeek cost by scanning local usage records starting from 00:00 today.
     */
    suspend fun getTodayDeepSeekCost(chatDao: ChatDao): Double = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = cal.timeInMillis

        var totalCost = 0.0
        val records = chatDao.allUsageRecords()
        for (record in records) {
            if (record.createdAt < startOfToday) continue
            if (!isDeepSeekModel(record.modelId, record.modelDisplayName, record.providerType)) continue

            val usage = try {
                JSONObject(record.tokenUsage)
            } catch (_: Exception) {
                continue
            }
            val input = usage.optLong("inputTokens", 0)
            val output = usage.optLong("outputTokens", 0)
            val cacheRead = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))

            totalCost += calculateCost(record.modelId, input, output, cacheRead, record.createdAt)
        }
        totalCost
    }

    /**
     * Fetch user balance from DeepSeek official endpoint: GET https://api.deepseek.com/user/balance
     * Returns formatted balance string (e.g. "12.50") or null if failed / unsupported.
     */
    suspend fun fetchOfficialBalance(
        apiKey: String?,
        baseUrl: String? = null,
        forceRefresh: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext null

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedBalance?.let { (bal, ts) ->
                if (now - ts < BALANCE_CACHE_TTL_MS) return@withContext bal
            }
        }

        val host = baseUrl?.trimEnd('/')?.ifBlank { null } ?: "https://api.deepseek.com"
        val url = if (host.contains("/v1")) {
            host.substringBefore("/v1") + "/user/balance"
        } else {
            "$host/user/balance"
        }

        try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()

            val res = http.newCall(req).execute()
            if (!res.isSuccessful) {
                res.close()
                return@withContext null
            }

            val body = res.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            if (!json.optBoolean("is_available", true)) return@withContext null

            val infos = json.optJSONArray("balance_infos")
            if (infos != null && infos.length() > 0) {
                val info = infos.getJSONObject(0)
                val total = info.optString("total_balance", "").ifBlank {
                    info.optDouble("total_balance", Double.NaN).takeIf { !it.isNaN() }?.toString()
                }
                if (!total.isNullOrBlank()) {
                    val formatted = String.format(Locale.US, "%.2f", total.toDoubleOrNull() ?: 0.0)
                    cachedBalance = formatted to now
                    return@withContext formatted
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
