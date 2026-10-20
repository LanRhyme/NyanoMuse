package io.github.nanomuse.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import com.openminis.app.R

/**
 * nanoMuse's navigation rail for wide displays (tablets, foldables, freeform windows).
 *
 * In Muse's visual language: a slender vertical frosted glass rail on the left,
 * housing the 5 tab glyphs (Chat, Feed, Ideas, Goals, Library) with the same spring
 * indicator pill as MuseBottomBar, plus sidebar toggle and settings at top/bottom.
 */
@Composable
fun MuseNavRail(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onToggleSidebar: (() -> Unit)? = null,
    sidebarCollapsed: Boolean = false,
    onNewChat: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val barBackground = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val nmDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val nmSelectedBg = if (nmDark) MaterialTheme.colorScheme.primaryContainer else Color.Black
    val nmSelectedFg = if (nmDark) MaterialTheme.colorScheme.onPrimaryContainer else Color.White

    Column(
        modifier = modifier
            .width(76.dp)
            .fillMaxHeight()
            .hazeEffect(state = hazeState) {
                style = HazeStyle(
                    backgroundColor = barBackground,
                    blurRadius = 22.dp,
                    noiseFactor = 0.05f,
                    tints = emptyList(),
                )
            }
            .drawBehind {
                // Trailing vertical hairline border
                drawLine(
                    color = outlineColor,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top: Sidebar toggle / Menu
        if (onToggleSidebar != null) {
            IconButton(
                onClick = onToggleSidebar,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = if (sidebarCollapsed) Icons.Filled.Menu else Icons.AutoMirrored.Filled.MenuOpen,
                    contentDescription = stringResource(R.string.nm_open_drawer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Center: 5 navigation tabs
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val indicator by animateColorAsState(
                    targetValue = if (isSelected) nmSelectedBg else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "nmNavRailIndicator",
                )

                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 44.dp)
                        .semantics { role = Role.Tab; this.selected = isSelected }
                        .clip(RoundedCornerShape(16.dp))
                        .background(indicator)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(tab) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                        contentDescription = stringResource(tab.label),
                        tint = if (isSelected) nmSelectedFg else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Bottom actions: New chat + Settings
        if (onNewChat != null) {
            IconButton(
                onClick = onNewChat,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .size(40.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        if (onSettings != null) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
