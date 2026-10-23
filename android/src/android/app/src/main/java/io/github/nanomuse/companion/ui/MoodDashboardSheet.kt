package io.github.nanomuse.companion.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.companion.CompanionMoodStore
import io.github.nanomuse.companion.CompanionPrefs
import io.github.nanomuse.companion.CompanionStatus
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseSectionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodDashboardSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { CompanionPrefs.prefs(context) }
    val emotionVersion by CompanionStatus.emotionVersion.collectAsState()

    var snapshot by remember(emotionVersion) {
        mutableStateOf(CompanionMoodStore.currentSnapshot(prefs))
    }

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = listOf(
        stringResource(R.string.nm_companion_layer_all, 33) to null,
        stringResource(R.string.nm_companion_layer_base, 11) to CompanionMoodStore.Layer.BASE,
        stringResource(R.string.nm_companion_layer_composite, 18) to CompanionMoodStore.Layer.COMPOSITE,
        stringResource(R.string.nm_companion_layer_modern, 4) to CompanionMoodStore.Layer.MODERN
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize()
        ) {
            // Header: Title and Reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.nm_companion_dashboard_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.nm_companion_dashboard_subtitle),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        val resetSnapshot = CompanionMoodStore.resetToBaseline(prefs)
                        snapshot = resetSnapshot
                        CompanionStatus.notifyEmotionChanged()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.nm_companion_reset_baseline),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Dominant Mood Card
            MuseCard(inset = 0.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.nm_companion_dominant_mood),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val leaderScore = snapshot.scores[snapshot.primary] ?: snapshot.primary.baseline
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", leaderScore)} / 100",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = snapshot.displayTitle,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.nm_companion_tendency_fmt, snapshot.primary.behaviorTendency),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    // Secondary Hybrid Aromas
                    if (snapshot.secondary.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(8.dp)
                        ) {
                            val blendContent = snapshot.secondary.joinToString("、") {
                                "${it.displayName} (${String.format(Locale.getDefault(), "%.1f", snapshot.scores[it] ?: it.baseline)})"
                            }
                            Text(
                                text = stringResource(R.string.nm_companion_blend_fmt, blendContent),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Recent Transition Fact Note
            snapshot.lastSwitchFacts?.let { facts ->
                Spacer(modifier = Modifier.height(12.dp))
                MuseCard(inset = 0.dp) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(facts.at))
                        val turnInfo = if (facts.sessionTurns > 0) " · " + stringResource(R.string.nm_companion_facts_turn_fmt, facts.sessionTurns) else ""
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.nm_companion_facts_title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$timeStr$turnInfo",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.nm_companion_facts_trans_fmt,
                                CompanionMoodStore.Mood.fromId(facts.fromId).displayName,
                                CompanionMoodStore.Mood.fromId(facts.toId).displayName
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (facts.lastUserSnippet.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.nm_companion_facts_snippet_fmt, facts.lastUserSnippet),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!facts.modelNarrative.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "AI: \"${facts.modelNarrative}\"",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Category Tab Filter
            SecondaryTabRow(
                selectedTabIndex = selectedCategoryIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                categories.forEachIndexed { index, (label, _) ->
                    Tab(
                        selected = selectedCategoryIndex == index,
                        onClick = { selectedCategoryIndex = index },
                        text = {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selectedCategoryIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 33 Moods List
            val currentCategory = categories[selectedCategoryIndex].second
            val displayedMoods = remember(selectedCategoryIndex, snapshot) {
                val list = if (currentCategory == null) {
                    CompanionMoodStore.Mood.entries
                } else {
                    CompanionMoodStore.Mood.entries.filter { it.layer == currentCategory }
                }
                list.sortedByDescending { snapshot.scores[it] ?: it.baseline }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displayedMoods.forEach { mood ->
                    val score = snapshot.scores[mood] ?: mood.baseline
                    val isLeader = mood == snapshot.primary
                    val isSecondary = snapshot.secondary.contains(mood)

                    MoodScoreRow(
                        mood = mood,
                        score = score,
                        isLeader = isLeader,
                        isSecondary = isSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun MoodScoreRow(
    mood: CompanionMoodStore.Mood,
    score: Float,
    isLeader: Boolean,
    isSecondary: Boolean
) {
    val containerBg = when {
        isLeader -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        isSecondary -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mood.displayName,
                        fontSize = 14.sp,
                        fontWeight = if (isLeader) FontWeight.Bold else FontWeight.Medium,
                        color = if (isLeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (mood.layer) {
                            CompanionMoodStore.Layer.BASE -> "基础"
                            CompanionMoodStore.Layer.COMPOSITE -> "复合"
                            CompanionMoodStore.Layer.MODERN -> "当代"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (isLeader) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "主导",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (isSecondary) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondary)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "共存",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f", score),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isLeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " / 基线 ${mood.baseline.toInt()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Progress bar with baseline indicator
            LinearProgressIndicator(
                progress = { (score / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    isLeader -> MaterialTheme.colorScheme.primary
                    isSecondary -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = mood.behaviorTendency,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
