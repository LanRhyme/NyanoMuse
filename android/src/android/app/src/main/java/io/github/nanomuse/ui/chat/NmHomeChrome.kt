package io.github.nanomuse.ui.chat

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.settings.KEY_NM_HEADER_MODEL
import com.openminis.app.ui.settings.KEY_NM_SHOW_STEPS
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.settings.headerModelEnabled
import com.openminis.app.ui.settings.showStepsEnabled
import io.github.nanomuse.ui.home.MuseTones

/**
 * Passed to `ChatScreen` when it is hosted inside the home shell (`io.github.nanomuse.ui.home`).
 * It swaps the back arrow for Muse's hamburger, the kebab for a round button, and — for the
 * main chat — grows the header into Muse's big-face layout; side chats get their title instead.
 * It also switches the conversation to Muse's dress: grey bubbles for the agent, the one-row
 * composer pill, no per-message name.
 */
data class NmHomeChrome(
    val isMainChat: Boolean,
    val onOpenDrawer: () -> Unit,
    val isTwoPane: Boolean = false,
    val onToggleSidebar: (() -> Unit)? = null,
    val sidebarCollapsed: Boolean = false,
    val onArchiveMainChat: ((String) -> Unit)? = null,
)

/**
 * Whether the header shows the model rows under the name (Settings → Appearance). Observed, so
 * flipping the switch and coming back to the home applies without a relaunch.
 */
@Composable
fun rememberHeaderModelShown(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(headerModelEnabled(context)) }
    DisposableEffect(Unit) {
        val prefs = getAppearancePrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_NM_HEADER_MODEL) state.value = p.getBoolean(KEY_NM_HEADER_MODEL, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/**
 * Whether the chat shows the agent's steps — tool pills, reasoning, the bar over the composer
 * (Settings → Appearance → Conversation; on by default, a stored false wins). Observed like the switch above.
 */
@Composable
fun rememberShowSteps(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(showStepsEnabled(context)) }
    DisposableEffect(Unit) {
        val prefs = getAppearancePrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_NM_SHOW_STEPS) state.value = p.getBoolean(KEY_NM_SHOW_STEPS, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/**
 * Muse's grey bubble around one assistant text block. Headings, lists, code blocks, and tables
 * render inside this single bubble (matching iOS); only standalone `nanomuse-*` protocol fences
 * carry their own card frame and are left bare.
 */
@Composable
fun NmAssistantBubble(
    enabled: Boolean,
    rawText: String,
    clusterIndex: Int = 0,
    clusterSize: Int = 1,
    content: @Composable () -> Unit,
) {
    val bare = !enabled || isBareBlock(rawText)
    if (bare) {
        content()
        return
    }
    val topPad = if (clusterSize > 1 && clusterIndex > 0) 1.dp else 2.dp
    val bottomPad = if (clusterSize > 1 && clusterIndex < clusterSize - 1) 1.dp else 3.dp
    val bubbleShape = if (clusterSize > 1) {
        when {
            clusterIndex == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
            clusterIndex == clusterSize - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
            else -> RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        }
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    }

    val borderColor = if (ChatColors.isDark) {
        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
    } else {
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.05f)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPad, bottom = bottomPad),
        contentAlignment = Alignment.TopStart
    ) {
        val maxBubbleWidth = minOf(760.dp, this.maxWidth * 0.86f)
        androidx.compose.material3.Surface(
            shape = bubbleShape,
            color = ChatColors.assistantBubble,
            contentColor = ChatColors.primaryText,
            shadowElevation = 0.8.dp,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, borderColor),
            modifier = Modifier.widthIn(max = maxBubbleWidth),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 2.dp)) {
                content()
            }
        }
    }
}

internal fun isBareBlock(raw: String): Boolean {
    val t = raw.trim()
    if (!t.startsWith("```nanomuse-")) return false
    val closeIdx = t.indexOf("\n```")
    return closeIdx < 0 || closeIdx + 4 == t.length
}
