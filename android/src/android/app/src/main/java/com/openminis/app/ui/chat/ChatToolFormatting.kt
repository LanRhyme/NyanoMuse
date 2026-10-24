package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers extracted verbatim from ChatScreen.kt. `internal` so the rest of the
// chat package (still in ChatScreen.kt) can call them across the file boundary.
// No logic change — code moved as-is.

internal val stepTimestampFormatter: java.text.SimpleDateFormat =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

internal fun formatStepTimestamp(epochMs: Long): String =
    stepTimestampFormatter.format(java.util.Date(epochMs))

// [T-step-timestamp v2 aa8b1128] Short "elapsed-or-final duration"
// label for the tool detail header. Cross-platform format contract:
//   < 60s   → "3s"
//   < 1h    → "2m30s" (drops the seconds suffix when seconds == 0)
//   ≥ 1h    → "1h12m"
// When `stillRunning` is true the result is suffixed "…" so the
// header reads "12s…" while the tool is in flight.
// Negative / non-positive values clamp to 0.
internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

// Helper: tool accent color
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute" -> Color(0xFF34C759)
    "file_read" -> Color(0xFF32ADE6)
    "file_write" -> Color(0xFF015CFB)
    "file_edit" -> Color(0xFFFF9500)
    "browser_use" -> Color(0xFF015CFB)
    "read_image", "image_generate" -> Color(0xFFAF52DE)
    "memory_write", "memory_get" -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)    // iOS: .cyan for search
    "device_control" -> Color(0xFF0A84FF)
    "app_manage" -> Color(0xFF5E5CE6)
    "screen_interact" -> Color(0xFFFF375F)
    "device_info" -> Color(0xFF32ADE6)
    "foreground_app" -> Color(0xFF30D158)
    "app_launch" -> Color(0xFFBF5AF2)
    "system_notify" -> Color(0xFFFF9F0A)
    "screen_capture" -> Color(0xFF64D2FF)
    else -> Color(0xFF8E8E93)
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type)
internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description         // iOS: doc.text
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    "file_edit" -> Icons.Default.EditNote             // iOS: square.and.pencil
    "browser_use" -> Icons.Default.Language            // iOS: globe
    "read_image", "image_generate" -> Icons.Default.Image // iOS: photo
    "memory_write", "memory_get" -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    "device_control", "device_info" -> Icons.Default.PhoneAndroid
    "app_manage", "app_launch" -> Icons.Default.AppShortcut
    "screen_interact", "screen_capture" -> Icons.Default.AutoAwesome
    else -> Icons.Default.Build
}

// Helper: tool display name for "nanoMuse is using X"
internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "image_generate" -> "image generator"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "web_search" -> "search"
    "device_control" -> "device control"
    "app_manage" -> "app manager"
    "screen_interact" -> "screen operator"
    "device_info" -> "device info"
    "foreground_app" -> "app sensor"
    "app_launch" -> "app launcher"
    "system_notify" -> "notifier"
    "screen_capture" -> "screen capture"
    else -> toolName
}

/**
 * Full "nanoMuse is …" label shown in the tool detail sheet's bottom bar.
 * Mirrors iOS ToolLiveSheet.toolTitle so the wording matches per tool.
 */
internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "nanoMuse is using Shell"
    "file_read" -> "nanoMuse is reading File"
    "file_write" -> "nanoMuse is using Editor"
    "file_edit" -> "nanoMuse is editing File"
    "browser_use" -> "nanoMuse is using Browser"
    "read_image" -> "nanoMuse is reading Image"
    "image_generate" -> "nanoMuse is generating Image"
    "memory_write", "memory_get" -> "nanoMuse is using Memory"
    "web_search" -> "nanoMuse is using Search"
    "device_control" -> "nanoMuse is controlling Device"
    "app_manage" -> "nanoMuse is managing Apps"
    "screen_interact" -> "nanoMuse is interacting with Screen"
    "device_info" -> "nanoMuse is checking Device Info"
    "foreground_app" -> "nanoMuse is sensing Foreground App"
    "app_launch" -> "nanoMuse is launching App"
    "system_notify" -> "nanoMuse is sending Notification"
    "screen_capture" -> "nanoMuse is capturing Screen"
    else -> "nanoMuse is using ${toolDisplayName(toolName)}"
}

// nanoMuse: the same label once the step is over (issue #67 — the sheet's bottom bar
// kept saying "is using" for a step that had finished long ago). Present tense while it
// runs; what happened afterwards.
internal fun toolTitleLabel(toolName: String, status: ToolBlockStatus?): String = when (status) {
    null, ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> toolTitleLabel(toolName)
    ToolBlockStatus.SUCCESS -> when (toolName) {
        "shell_execute" -> "nanoMuse used Shell"
        "file_read" -> "nanoMuse read File"
        "file_write" -> "nanoMuse used Editor"
        "file_edit" -> "nanoMuse edited File"
        "browser_use" -> "nanoMuse used Browser"
        "read_image" -> "nanoMuse read Image"
        "image_generate" -> "nanoMuse generated Image"
        "memory_write", "memory_get" -> "nanoMuse used Memory"
        "web_search" -> "nanoMuse used Search"
        "device_control" -> "nanoMuse adjusted Device"
        "app_manage" -> "nanoMuse managed Apps"
        "screen_interact" -> "nanoMuse interacted with Screen"
        "device_info" -> "nanoMuse checked Device Info"
        "foreground_app" -> "nanoMuse sensed Foreground App"
        "app_launch" -> "nanoMuse launched App"
        "system_notify" -> "nanoMuse posted Notification"
        "screen_capture" -> "nanoMuse captured Screen"
        else -> "nanoMuse used ${toolDisplayName(toolName)}"
    }
    ToolBlockStatus.FAILED -> "${toolDisplayName(toolName).replaceFirstChar { it.uppercase() }} failed"
    ToolBlockStatus.CANCELLED -> "${toolDisplayName(toolName).replaceFirstChar { it.uppercase() }} stopped"
    ToolBlockStatus.TIMEOUT -> "${toolDisplayName(toolName).replaceFirstChar { it.uppercase() }} timed out"
}

// Helper: format duration (iOS: < 1s → "0.1s", < 60s → "45s", >= 60s → "2m 10s")
internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}
