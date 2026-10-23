package io.github.nanomuse.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.companion.CompanionAlarmReceiver
import io.github.nanomuse.companion.CompanionPrefs
import io.github.nanomuse.companion.CompanionStatus
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRowDivider
import io.github.nanomuse.ui.muse.MuseSectionLabel

import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionSettingsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { CompanionPrefs.prefs(context) }
    val emotionVersion by CompanionStatus.emotionVersion.collectAsState()

    var enabled by remember { mutableStateOf(CompanionPrefs.isEnabled(prefs)) }
    var quietStart by remember { mutableIntStateOf(CompanionPrefs.getQuietStartHour(prefs)) }
    var quietEnd by remember { mutableIntStateOf(CompanionPrefs.getQuietEndHour(prefs)) }
    var minInterval by remember { mutableIntStateOf(CompanionPrefs.getMinIntervalHours(prefs)) }
    var dailyMax by remember { mutableIntStateOf(CompanionPrefs.getDailyMaxCount(prefs)) }
    var preferredScenarios by remember { mutableStateOf(CompanionPrefs.getPreferredScenarios(prefs)) }
    var intensity by remember { mutableStateOf(CompanionPrefs.getEmotionIntensity(prefs)) }
    var affection by remember(emotionVersion) { mutableStateOf(CompanionPrefs.getAffectionScore(prefs)) }
    var emotionEnabled by remember { mutableStateOf(CompanionPrefs.isEmotionEnabled(prefs)) }
    var showMoodDashboard by remember { mutableStateOf(false) }

    val currentMoodSubtitle = remember(emotionVersion) { CompanionStatus.currentMoodSubtitle(context) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.nm_companion_settings_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "当前伴侣心境状态: $currentMoodSubtitle",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            // Master Toggle
            MuseCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.nm_companion_proactive_toggle),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.nm_companion_proactive_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { next ->
                            enabled = next
                            CompanionPrefs.setEnabled(prefs, next)
                            if (next) {
                                CompanionAlarmReceiver.scheduleNext(context)
                            } else {
                                CompanionAlarmReceiver.cancel(context)
                            }
                        }
                    )
                }
            }

            if (enabled) {
                MuseGap()
                MuseSectionLabel("规则与时段")
                MuseCard {
                    // Quiet hours
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.nm_companion_quiet_hours),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.nm_companion_quiet_hours_desc, quietStart, quietEnd),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    MuseRowDivider()

                    // Min interval
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.nm_companion_cooldown),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.nm_companion_hours_val, minInterval),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    MuseRowDivider()

                    // Daily Max Count
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.nm_companion_daily_limit),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.nm_companion_daily_limit_val, dailyMax),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                MuseGap()
                MuseSectionLabel(stringResource(R.string.nm_companion_scenarios_title))
                MuseCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val scenarioItems = listOf(
                            "morning_greeting" to R.string.nm_companion_scenario_morning,
                            "evening_care" to R.string.nm_companion_scenario_evening,
                            "curious_checkin" to R.string.nm_companion_scenario_curious,
                            "longing_miss" to R.string.nm_companion_scenario_longing,
                            "calm_companion" to R.string.nm_companion_scenario_calm
                        )
                        scenarioItems.forEachIndexed { index, (tag, labelRes) ->
                            val isSelected = CompanionPrefs.isScenarioAllowed(preferredScenarios, tag)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    fontSize = 14.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Switch(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        val next = preferredScenarios.toMutableSet()
                                        if (checked) next.add(tag) else next.remove(tag)
                                        preferredScenarios = next
                                        CompanionPrefs.setPreferredScenarios(prefs, next)
                                    }
                                )
                            }
                            if (index < scenarioItems.lastIndex) {
                                MuseRowDivider()
                            }
                        }
                    }
                }
            }

            MuseGap()
            MuseSectionLabel(stringResource(R.string.nm_companion_emotion_toggle))
            MuseCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.nm_companion_emotion_toggle),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.nm_companion_emotion_toggle_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = emotionEnabled,
                        onCheckedChange = { next ->
                            emotionEnabled = next
                            CompanionPrefs.setEmotionEnabled(prefs, next)
                        }
                    )
                }

                if (emotionEnabled) {
                    MuseRowDivider()
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = stringResource(R.string.nm_companion_intensity_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "subtle" to R.string.nm_companion_intensity_subtle,
                                "normal" to R.string.nm_companion_intensity_normal,
                                "vivid" to R.string.nm_companion_intensity_vivid,
                            ).forEach { (id, labelRes) ->
                                val selected = intensity == id
                                androidx.compose.material3.FilterChip(
                                    selected = selected,
                                    onClick = {
                                        intensity = id
                                        CompanionPrefs.setEmotionIntensity(prefs, id)
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                    }

                    MuseRowDivider()
                    io.github.nanomuse.ui.muse.MuseRow(
                        title = stringResource(R.string.nm_companion_settings_compass_title),
                        value = currentMoodSubtitle,
                        onClick = { showMoodDashboard = true }
                    )

                    MuseRowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.2f", affection)} / 1.00",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.nm_companion_affection_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                CompanionPrefs.resetAffectionScore(prefs)
                                affection = CompanionPrefs.getAffectionScore(prefs)
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.nm_companion_reset_affection),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showMoodDashboard) {
        MoodDashboardSheet(onDismiss = { showMoodDashboard = false })
    }
}
