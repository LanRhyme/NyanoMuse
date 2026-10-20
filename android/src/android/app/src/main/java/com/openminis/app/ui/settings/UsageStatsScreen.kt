package com.openminis.app.ui.settings

import com.openminis.app.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.chat.DeepSeekTracker
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * [T-token-attribution-snapshot] How trustworthy a row's model attribution is.
 */
internal enum class Attribution {
    MEASURED,
    ESTIMATED,
    UNKNOWN_SESSION,
    MEASURED_REMOVED,
}

private data class ModelStats(
    val modelId: String,
    val displayName: String,
    val provider: String,
    val attribution: Attribution,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheCreationTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    var estimatedCost: Double = 0.0,
    val distinctDays: MutableSet<String> = mutableSetOf(),
    val distinctSessions: MutableSet<String> = mutableSetOf(),
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
}

private data class ProviderGroup(val name: String, val models: List<ModelStats>)

private data class GrandTotal(
    val totalInput: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val estimatedCost: Double = 0.0,
) {
    val cacheHitRate: Double?
        get() = if (totalInput <= 0 || cacheReadTokens <= 0) null
        else (cacheReadTokens.toDouble() / totalInput) * 100
}

private data class DayUsage(
    val dateStr: String,
    val timestamp: Long,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    var cacheCreationTokens: Long = 0,
    var cost: Double = 0.0,
    val models: MutableSet<String> = mutableSetOf(),
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
}

private data class DayCell(
    val dateStr: String,
    val timestamp: Long,
    val usage: DayUsage?,
    val isFuture: Boolean,
    val isToday: Boolean,
    val dayOfWeek: Int, // 0=Mon, 6=Sun
    val monthLabel: String? = null,
)

private enum class TimeRange(val labelRes: Int) {
    TODAY(R.string.usage_tab_today),
    WEEK(R.string.usage_tab_week),
    MONTH(R.string.usage_tab_month),
}

private data class PeriodSummary(
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cost: Double = 0.0,
    val sessionsCount: Int = 0,
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
    val cacheHitRate: Double?
        get() = if (totalInput <= 0 || cacheReadTokens <= 0) null
        else (cacheReadTokens.toDouble() / totalInput) * 100
}

private const val UNKNOWN_MODEL_KEY = "(unknown model)"
private const val UNKNOWN_PROVIDER = "Unknown"

internal fun providerDisplayName(rawValue: String): String =
    runCatching { ProviderType.valueOf(rawValue).displayName }.getOrDefault(rawValue)

internal fun classifyAttribution(
    modelId: String?,
    hasSnapshot: Boolean,
    resolvesInConfig: Boolean,
): Attribution = when {
    modelId == null -> Attribution.UNKNOWN_SESSION
    !hasSnapshot -> Attribution.ESTIMATED
    !resolvesInConfig -> Attribution.MEASURED_REMOVED
    else -> Attribution.MEASURED
}

internal fun estimateCost(modelId: String?, input: Long, output: Long, cacheRead: Long): Double {
    if (modelId != null && DeepSeekTracker.isDeepSeekModel(modelId)) {
        return DeepSeekTracker.calculateCost(modelId, input, output, cacheRead)
    }
    val mid = (modelId ?: "").lowercase(Locale.US)
    val (inRate, outRate) = when {
        mid.contains("gpt-4o-mini") -> (1.08 / 1_000_000.0) to (4.32 / 1_000_000.0)
        mid.contains("gpt-4o") || mid.contains("claude-3-5-sonnet") -> (18.0 / 1_000_000.0) to (72.0 / 1_000_000.0)
        mid.contains("qwen") -> (1.0 / 1_000_000.0) to (2.0 / 1_000_000.0)
        else -> (1.0 / 1_000_000.0) to (2.0 / 1_000_000.0)
    }
    return (maxOf(0L, input) * inRate) + (maxOf(0L, output) * outRate)
}

@Composable
fun UsageStatsScreen(
    chatDao: ChatDao,
    providerConfig: ProviderConfig? = null,
    onBack: () -> Unit,
) {
    var grandTotal by remember { mutableStateOf(GrandTotal()) }
    var providerGroups by remember { mutableStateOf<List<ProviderGroup>>(emptyList()) }
    var weeks by remember { mutableStateOf<List<List<DayCell>>>(emptyList()) }
    var maxDailyTokens by remember { mutableStateOf(1L) }
    var activeDaysCount by remember { mutableStateOf(0) }
    var periodSummaries by remember { mutableStateOf<Map<TimeRange, PeriodSummary>>(emptyMap()) }
    var selectedRange by remember { mutableStateOf(TimeRange.TODAY) }
    var selectedDay by remember { mutableStateOf<DayUsage?>(null) }
    var isLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val records = chatDao.allUsageRecords()

        val modelLookup = mutableMapOf<String, Pair<String, String>>()
        for (m in LLMModel.allModels) modelLookup[m.id] = m.displayName to m.provider
        providerConfig?.let { config ->
            for (entry in config.modelEntries) {
                if (entry.model.id !in modelLookup) {
                    val instance = config.instances.find { it.id == entry.providerInstanceId }
                    val providerName = instance?.providerType?.displayName ?: entry.model.provider
                    modelLookup[entry.model.id] = entry.model.displayName to providerName
                }
            }
        }

        val statsMap = mutableMapOf<String, ModelStats>()
        val dayMap = mutableMapOf<String, DayUsage>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (record in records) {
            val usage = try { JSONObject(record.tokenUsage) } catch (_: Exception) { continue }
            val input = usage.optLong("inputTokens", 0)
            val output = usage.optLong("outputTokens", 0)
            val cacheCr = usage.optLong("cacheCreationTokens", usage.optLong("cacheCreationInputTokens", 0))
            val cacheRd = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))
            val cost = estimateCost(record.modelId, input, output, cacheRd)

            val modelKey = record.modelId ?: UNKNOWN_MODEL_KEY
            val resolved = modelLookup[modelKey]

            val attribution = classifyAttribution(
                modelId = record.modelId,
                hasSnapshot = record.hasSnapshot,
                resolvesInConfig = resolved != null,
            )

            val displayName = record.modelDisplayName?.takeIf { it.isNotBlank() }
                ?: resolved?.first
                ?: modelKey
            val provider = record.providerType?.takeIf { it.isNotBlank() }
                ?.let { raw -> providerDisplayName(raw) }
                ?: resolved?.second
                ?: UNKNOWN_PROVIDER

            val stats = statsMap.getOrPut("$modelKey#${attribution.name}") {
                ModelStats(modelKey, displayName, provider, attribution)
            }
            stats.inputTokens += input
            stats.outputTokens += output
            stats.cacheCreationTokens += cacheCr
            stats.cacheReadTokens += cacheRd
            stats.estimatedCost += cost
            val dayStr = dateFormat.format(Date(record.createdAt))
            stats.distinctDays.add(dayStr)
            stats.distinctSessions.add(record.sessionId)

            // Day usage
            val day = dayMap.getOrPut(dayStr) { DayUsage(dayStr, record.createdAt) }
            day.inputTokens += input
            day.outputTokens += output
            day.cacheCreationTokens += cacheCr
            day.cacheReadTokens += cacheRd
            day.cost += cost
            if (!record.modelId.isNullOrBlank()) {
                day.models.add(record.modelDisplayName?.ifBlank { null } ?: record.modelId)
            }
        }

        // Calendar calculations (26 weeks ending today)
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMs = cal.timeInMillis
        val todayDow = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7 // 0=Mon, 6=Sun

        // Start from Monday of 25 weeks ago (total 26 weeks)
        val startCal = Calendar.getInstance().apply {
            timeInMillis = todayMs
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.DAY_OF_YEAR, -todayDow)
            add(Calendar.WEEK_OF_YEAR, -25)
        }

        val monthFmt = SimpleDateFormat("M月", Locale.CHINA)
        val generatedWeeks = mutableListOf<List<DayCell>>()
        var lastMonth = -1

        for (w in 0 until 26) {
            val days = mutableListOf<DayCell>()
            var weekMonthLabel: String? = null
            for (d in 0 until 7) {
                val cellTime = startCal.timeInMillis
                val dateStr = dateFormat.format(Date(cellTime))
                val isFuture = cellTime > todayMs
                val isToday = cellTime == todayMs
                val usage = dayMap[dateStr]

                val currentMonth = startCal.get(Calendar.MONTH)
                if (currentMonth != lastMonth && d == 0) {
                    weekMonthLabel = monthFmt.format(startCal.time)
                    lastMonth = currentMonth
                }

                days.add(
                    DayCell(
                        dateStr = dateStr,
                        timestamp = cellTime,
                        usage = usage,
                        isFuture = isFuture,
                        isToday = isToday,
                        dayOfWeek = d,
                        monthLabel = weekMonthLabel,
                    )
                )
                startCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            generatedWeeks.add(days)
        }
        weeks = generatedWeeks

        maxDailyTokens = maxOf(1L, dayMap.values.maxOfOrNull { it.totalTokens } ?: 1L)
        activeDaysCount = dayMap.count { it.value.totalTokens > 0 }

        // Day / Week / Month summaries
        val startOfToday = todayMs
        val startOfWeek = Calendar.getInstance().apply {
            timeInMillis = todayMs
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.DAY_OF_YEAR, -todayDow)
        }.timeInMillis
        val startOfMonth = Calendar.getInstance().apply {
            timeInMillis = todayMs
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        fun computeSummary(startTime: Long): PeriodSummary {
            var inTok = 0L
            var outTok = 0L
            var cRd = 0L
            var cCr = 0L
            var cost = 0.0
            val sessions = mutableSetOf<String>()

            for (r in records) {
                if (r.createdAt < startTime) continue
                val u = try { JSONObject(r.tokenUsage) } catch (_: Exception) { continue }
                val i = u.optLong("inputTokens", 0)
                val o = u.optLong("outputTokens", 0)
                val rd = u.optLong("cacheReadTokens", u.optLong("cacheReadInputTokens", 0))
                val cr = u.optLong("cacheCreationTokens", u.optLong("cacheCreationInputTokens", 0))
                inTok += i
                outTok += o
                cRd += rd
                cCr += cr
                cost += estimateCost(r.modelId, i, o, rd)
                sessions.add(r.sessionId)
            }
            return PeriodSummary(
                totalTokens = inTok + outTok + cRd + cCr,
                inputTokens = inTok,
                outputTokens = outTok,
                cacheReadTokens = cRd,
                cacheCreationTokens = cCr,
                cost = cost,
                sessionsCount = sessions.size,
            )
        }

        periodSummaries = mapOf(
            TimeRange.TODAY to computeSummary(startOfToday),
            TimeRange.WEEK to computeSummary(startOfWeek),
            TimeRange.MONTH to computeSummary(startOfMonth),
        )

        val providerOrder = listOf("DeepSeek", "OpenAI", "Anthropic", "Google Gemini", "Google", "Antigravity", "Unknown")
        val grouped = statsMap.values.groupBy { it.provider }
        val sortedGroups = grouped.entries.sortedBy { (name, _) ->
            val idx = providerOrder.indexOf(name)
            if (idx >= 0) idx else providerOrder.size
        }.map { (name, models) ->
            ProviderGroup(name, models.sortedByDescending { it.totalInput })
        }

        val allStats = statsMap.values
        grandTotal = GrandTotal(
            totalInput = allStats.sumOf { it.totalInput },
            outputTokens = allStats.sumOf { it.outputTokens },
            cacheReadTokens = allStats.sumOf { it.cacheReadTokens },
            cacheCreationTokens = allStats.sumOf { it.cacheCreationTokens },
            estimatedCost = allStats.sumOf { it.estimatedCost },
        )

        providerGroups = sortedGroups
        isLoaded = true
    }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = onBack) {
        if (!isLoaded) return@SettingsScaffold

        // ── 1. Activity Heatmap (GitHub Style) ──────────────────────────────────
        ActivityHeatmapCard(
            weeks = weeks,
            maxDailyTokens = maxDailyTokens,
            activeDaysCount = activeDaysCount,
            selectedDay = selectedDay,
            onSelectDay = { day ->
                selectedDay = if (selectedDay?.dateStr == day?.dateStr) null else day
            },
        )

        Spacer(Modifier.height(16.dp))

        // ── 2. Time Range Selector (Today / Week / Month) ──────────────────────
        TimeRangeSummarySection(
            selectedRange = selectedRange,
            onSelectRange = { selectedRange = it },
            summaries = periodSummaries,
        )

        Spacer(Modifier.height(16.dp))

        // ── 3. Total Usage Summary ─────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.usage_section_total)) {
            val stats = listOfNotNull(
                stringResource(R.string.usage_label_total_tokens) to formatCount(grandTotal.totalInput + grandTotal.outputTokens),
                stringResource(R.string.usage_label_total_input) to formatCount(grandTotal.totalInput),
                stringResource(R.string.usage_label_output) to formatCount(grandTotal.outputTokens),
                if (grandTotal.cacheReadTokens > 0) stringResource(R.string.usage_label_cache_read) to formatCount(grandTotal.cacheReadTokens) else null,
                if (grandTotal.cacheCreationTokens > 0) stringResource(R.string.usage_label_cache_creation) to formatCount(grandTotal.cacheCreationTokens) else null,
                grandTotal.cacheHitRate?.let { rate -> stringResource(R.string.usage_label_cache_hit_rate) to String.format(Locale.US, "%.1f%%", rate) },
                stringResource(R.string.usage_label_cost) to String.format(Locale.US, "¥%.2f", grandTotal.estimatedCost),
            )
            stats.forEachIndexed { idx, (label, value) ->
                SettingsValueRow(
                    title = label,
                    value = value,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    showDivider = idx < stats.size - 1,
                )
            }
        }

        // ── 4. Provider & Model Breakdown ──────────────────────────────────────
        for (group in providerGroups) {
            SettingsSection(header = group.name) {
                group.models.forEachIndexed { idx, model ->
                    ExpandableModelRow(
                        model = model,
                        showDivider = idx < group.models.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ActivityHeatmapCard(
    weeks: List<List<DayCell>>,
    maxDailyTokens: Long,
    activeDaysCount: Int,
    selectedDay: DayUsage?,
    onSelectDay: (DayUsage?) -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(weeks.size) {
        if (weeks.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val primaryTone = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Insights,
                        contentDescription = null,
                        tint = primaryTone,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.usage_heatmap_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(primaryTone.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "${activeDaysCount}天活跃",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryTone,
                    )
                }
            }

            Text(
                text = stringResource(R.string.usage_heatmap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            // Grid: Weekdays labels + horizontal scrolling weeks
            Row(modifier = Modifier.fillMaxWidth()) {
                // Day-of-week labels on the left (一, 三, 五)
                Column(
                    modifier = Modifier.padding(top = 18.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf("一", "", "三", "", "五", "", "").forEach { label ->
                        Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                            if (label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }

                // Weeks scrollable container
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    weeks.forEach { week ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // Month label at the top of the column
                            val monthLabel = week.firstOrNull()?.monthLabel
                            Box(
                                modifier = Modifier
                                    .height(16.dp)
                                    .padding(bottom = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (monthLabel != null) {
                                    Text(
                                        text = monthLabel,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // 7 days in the week
                            week.forEach { cell ->
                                val usage = cell.usage
                                val tokens = usage?.totalTokens ?: 0L
                                val isSelected = selectedDay?.dateStr == cell.dateStr

                                val cellColor = when {
                                    cell.isFuture -> Color.Transparent
                                    tokens == 0L -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    tokens <= maxDailyTokens * 0.25 -> primaryTone.copy(alpha = 0.35f)
                                    tokens <= maxDailyTokens * 0.50 -> primaryTone.copy(alpha = 0.58f)
                                    tokens <= maxDailyTokens * 0.75 -> primaryTone.copy(alpha = 0.80f)
                                    else -> primaryTone
                                }

                                val borderModifier = when {
                                    isSelected -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.5.dp))
                                    cell.isToday -> Modifier.border(1.dp, primaryTone, RoundedCornerShape(2.5.dp))
                                    else -> Modifier
                                }

                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(cellColor)
                                        .then(borderModifier)
                                        .clickable(enabled = !cell.isFuture) {
                                            onSelectDay(usage ?: DayUsage(cell.dateStr, cell.timestamp))
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // Legend at the bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("少", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    primaryTone.copy(alpha = 0.35f),
                    primaryTone.copy(alpha = 0.58f),
                    primaryTone.copy(alpha = 0.80f),
                    primaryTone,
                ).forEach { col ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.5.dp)
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(col),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("多", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Day Detail Card (When a day is tapped) ──
            AnimatedVisibility(
                visible = selectedDay != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                selectedDay?.let { day ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = day.dateStr,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(
                                    onClick = { onSelectDay(null) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                MetricItem("总 Token", formatCount(day.totalTokens))
                                MetricItem("输入 / 输出", "${formatCount(day.inputTokens)} / ${formatCount(day.outputTokens)}")
                                MetricItem("缓存读取", formatCount(day.cacheReadTokens))
                                MetricItem("预估花费", String.format(Locale.US, "¥%.3f", day.cost))
                            }

                            if (day.models.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.usage_models_used) + ":",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    day.models.take(4).forEach { modelName ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(primaryTone.copy(alpha = 0.1f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = modelName,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = primaryTone,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default)
    }
}

@Composable
private fun TimeRangeSummarySection(
    selectedRange: TimeRange,
    onSelectRange: (TimeRange) -> Unit,
    summaries: Map<TimeRange, PeriodSummary>,
) {
    val summary = summaries[selectedRange] ?: PeriodSummary()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Pill segmented tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimeRange.values().forEach { range ->
                val selected = selectedRange == range
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { onSelectRange(range) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(range.labelRes),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 4 Key metrics cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.usage_label_total_tokens),
                value = formatCount(summary.totalTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = "输入 / 输出",
                value = "${formatCount(summary.totalInput)} / ${formatCount(summary.outputTokens)}",
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.usage_label_cache_hit_rate),
                value = summary.cacheHitRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--",
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.usage_label_cost),
                value = String.format(Locale.US, "¥%.2f", summary.cost),
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExpandableModelRow(model: ModelStats, showDivider: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val summary = "${formatCount(model.totalInput)} / ${formatCount(model.outputTokens)}"

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val caveat = when (model.attribution) {
                    Attribution.MEASURED, Attribution.ESTIMATED -> null
                    Attribution.UNKNOWN_SESSION -> stringResource(R.string.usage_attr_unknown_session)
                    Attribution.MEASURED_REMOVED -> stringResource(R.string.usage_attr_removed)
                }
                if (caveat != null) {
                    Text(
                        caveat,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                DetailRow(stringResource(R.string.usage_detail_input), formatCount(model.inputTokens))
                DetailRow(stringResource(R.string.usage_detail_output), formatCount(model.outputTokens))
                if (model.cacheReadTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_read), formatCount(model.cacheReadTokens))
                if (model.cacheCreationTokens > 0) DetailRow(stringResource(R.string.usage_label_cache_creation), formatCount(model.cacheCreationTokens))
                val modelTotalInput = model.totalInput
                if (modelTotalInput > 0 && model.cacheReadTokens > 0) {
                    val rate = (model.cacheReadTokens.toDouble() / modelTotalInput) * 100
                    DetailRow(stringResource(R.string.usage_label_cache_hit_rate), String.format(Locale.US, "%.1f%%", rate))
                }
                DetailRow(stringResource(R.string.usage_label_cost), String.format(Locale.US, "¥%.3f", model.estimatedCost))
                val days = model.distinctDays.size
                val sessions = model.distinctSessions.size
                if (days > 0) {
                    DetailRow(stringResource(R.string.usage_detail_daily_avg), formatCount((model.inputTokens + model.outputTokens) / days))
                }
                if (sessions > 0) {
                    DetailRow(stringResource(R.string.usage_detail_session_avg), formatCount((model.inputTokens + model.outputTokens) / sessions))
                }
                DetailRow(stringResource(R.string.usage_detail_sessions), sessions.toString())
                DetailRow(stringResource(R.string.usage_detail_active_days), days.toString())
            }
        }

        if (showDivider) {
            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Default)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> {
        val k = n / 1000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}k"
        else String.format(Locale.US, "%.1fk", k)
    }
    else -> n.toString()
}
