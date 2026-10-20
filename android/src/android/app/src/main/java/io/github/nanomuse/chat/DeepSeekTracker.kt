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

    fun isDeepSeekModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        return modelId.contains("deepseek", ignoreCase = true)
    }

    /**
     * DeepSeek Official Pricing (in CNY ¥ per token):
     * Reasoner / R1:
     *   - Cache hit: ¥1.00 / 1M tokens
     *   - Cache miss (Input): ¥4.00 / 1M tokens
     *   - Output: ¥16.00 / 1M tokens
     *
     * V3 / Chat / V4 / Default:
     *   - Cache hit: ¥0.10 / 1M tokens
     *   - Cache miss (Input): ¥1.00 / 1M tokens
     *   - Output: ¥2.00 / 1M tokens
     */
    fun calculateCost(modelId: String?, inputTokens: Long, outputTokens: Long, cacheReadTokens: Long): Double {
        val mid = (modelId ?: "").lowercase(Locale.US)
        val isReasoner = mid.contains("reasoner") || mid.contains("r1")

        val (inputRate, cacheHitRate, outputRate) = if (isReasoner) {
            Triple(4.0 / 1_000_000.0, 1.0 / 1_000_000.0, 16.0 / 1_000_000.0)
        } else {
            Triple(1.0 / 1_000_000.0, 0.10 / 1_000_000.0, 2.0 / 1_000_000.0)
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
            if (!isDeepSeekModel(record.modelId)) continue

            val usage = try {
                JSONObject(record.tokenUsage)
            } catch (_: Exception) {
                continue
            }
            val input = usage.optLong("inputTokens", 0)
            val output = usage.optLong("outputTokens", 0)
            val cacheRead = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))

            totalCost += calculateCost(record.modelId, input, output, cacheRead)
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
