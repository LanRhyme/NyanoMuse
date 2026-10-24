package com.openminis.app.tools

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.R
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import io.github.nanomuse.identity.NanoMuseIdentity.withFace
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object NativeSystemTools {

    const val TOOL_DEVICE_INFO = "device_info"
    const val TOOL_FOREGROUND_APP = "foreground_app"
    const val TOOL_DEVICE_CONTROL = "device_control"
    const val TOOL_APP_MANAGE = "app_manage"
    const val TOOL_SCREEN_INTERACT = "screen_interact"
    const val TOOL_APP_LAUNCH = "app_launch"
    const val TOOL_SYSTEM_NOTIFY = "system_notify"
    const val TOOL_SCREEN_CAPTURE = "screen_capture"
    const val TOOL_IMAGE_GENERATE = "image_generate"

    const val CHANNEL_ID = "nanomuse_agent_notifications"
    private const val CHANNEL_NAME = "NyanoMuse Notifications"

    @Volatile
    private var isTorchOn: Boolean = false

    fun isReadOnly(name: String): Boolean = when (name) {
        TOOL_DEVICE_INFO, TOOL_FOREGROUND_APP, TOOL_SCREEN_CAPTURE, FileReadTool.NAME, "memory_get", ReadImageTool.NAME -> true
        else -> false
    }

    fun makeDefinitions(): List<AgentToolDefinition> = listOf(
        deviceInfoDefinition(),
        foregroundAppDefinition(),
        deviceControlDefinition(),
        appManageDefinition(),
        screenInteractDefinition(),
        appLaunchDefinition(),
        systemNotifyDefinition(),
        screenCaptureDefinition(),
        imageGenerateDefinition(),
    )

    fun deviceControlDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_DEVICE_CONTROL,
        description = "Inspect and control Android hardware settings and state, including audio volume (media, ringtone, alarm, notification), flashlight/torch, system clipboard (read/write), media playback (play/pause/next/prev), navigation key actions (back, home, recents, notifications, quick settings, lock screen), vibration feedback, system settings pages, and system intents (open URL in browser, dial phone number, share text).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
            "action" to AgentToolParam("string", "The device control action to execute", enumValues = listOf("volume", "flashlight", "clipboard", "media", "navigation", "open_settings", "vibrate", "intent")),
            "sub_action" to AgentToolParam("string", "Specific command for the action. For volume: 'get', 'set', 'raise', 'lower', 'mute', 'unmute'. For flashlight: 'on', 'off', 'toggle', 'status'. For clipboard: 'get', 'set'. For media: 'play_pause', 'play', 'pause', 'next', 'previous', 'stop'. For navigation: 'back', 'home', 'recents', 'notifications', 'quick_settings', 'lock_screen', 'power_dialog'. For intent: 'open_url', 'dial', 'share'."),
            "stream" to AgentToolParam("string", "Audio stream for volume action ('media', 'ring', 'notification', 'alarm', 'call'). Default is 'media'."),
            "value" to AgentToolParam("integer", "Numeric parameter. Volume level (0-100 percentage or raw level) for volume 'set'; or duration in milliseconds for 'vibrate'."),
            "text" to AgentToolParam("string", "Text string to copy into system clipboard for clipboard 'set', or text to share for intent 'share'."),
            "settings_page" to AgentToolParam("string", "System settings page to open for open_settings action ('wifi', 'bluetooth', 'display', 'sound', 'battery', 'accessibility', 'apps', 'date', 'location', 'airplane_mode')."),
            "url" to AgentToolParam("string", "Web URL to open in browser for intent 'open_url' action."),
            "phone_number" to AgentToolParam("string", "Phone number to open dialer for intent 'dial' action."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "sub_action", "stream", "value", "text", "settings_page", "url", "phone_number"),
    )

    fun appManageDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_APP_MANAGE,
        description = "Inspect and manage installed applications on the Android device. Supports listing installed apps with optional search keywords and user vs system apps filtering, inspecting detailed app package metadata (version, target SDK, install dates, permissions, data directory), force-stopping apps (via Root or system settings), and opening app system info settings.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
            "action" to AgentToolParam("string", "The application management action to execute", enumValues = listOf("list", "info", "stop", "open_settings")),
            "query" to AgentToolParam("string", "Optional search keyword to filter apps by name or package (for 'list')."),
            "package_name" to AgentToolParam("string", "Exact Android package name (required for 'info', 'stop', and 'open_settings')."),
            "user_apps_only" to AgentToolParam("boolean", "If true, shows only user-installed apps and excludes system apps. Default is true."),
            "limit" to AgentToolParam("integer", "Maximum number of apps to return in 'list'. Default is 40."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "query", "package_name", "user_apps_only", "limit"),
    )

    fun screenInteractDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_SCREEN_INTERACT,
        description = "Directly interact with and control the Android screen via touch, gestures, text input, key presses, or UI element clicking. Works via MinisAccessibilityService or Root mode. Supports normalized 0-999 coordinates (or raw screen pixels), text typing with automatic focus tap and non-ASCII clipboard paste fallback, key events, and locating/clicking UI elements by visible text or resource ID.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
            "action" to AgentToolParam("string", "The interaction action to perform", enumValues = listOf("tap", "double_tap", "long_press", "swipe", "scroll", "type", "key", "click_element")),
            "coordinate_x" to AgentToolParam("integer", "X coordinate (0-999 normalized grid or raw screen pixel) for tap, double_tap, long_press, or focus tap for type."),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate (0-999 normalized grid or raw screen pixel) for tap, double_tap, long_press, or focus tap for type."),
            "start_x" to AgentToolParam("integer", "Starting X coordinate for swipe (0-999 grid or raw pixel)."),
            "start_y" to AgentToolParam("integer", "Starting Y coordinate for swipe (0-999 grid or raw pixel)."),
            "end_x" to AgentToolParam("integer", "Ending X coordinate for swipe (0-999 grid or raw pixel)."),
            "end_y" to AgentToolParam("integer", "Ending Y coordinate for swipe (0-999 grid or raw pixel)."),
            "direction" to AgentToolParam("string", "Scroll direction ('up', 'down', 'left', 'right') for scroll action. Default is 'down'."),
            "amount" to AgentToolParam("integer", "Scroll distance in pixels for scroll action. Default is 600."),
            "text" to AgentToolParam("string", "Text string to type into the focused input field for 'type' action. Automatically handles Chinese and Emoji via clipboard paste fallback."),
            "element_text" to AgentToolParam("string", "Visible text or button label on screen to locate and click for 'click_element' action."),
            "resource_id" to AgentToolParam("string", "Optional view resource ID (e.g. 'com.tencent.mm:id/action_search') to locate and click for 'click_element' action."),
            "key" to AgentToolParam("string", "Key name for 'key' action ('back', 'home', 'enter', 'tab', 'delete', 'volume_up', 'volume_down', 'power')."),
            "duration_ms" to AgentToolParam("integer", "Optional gesture duration in milliseconds (e.g. for long_press or custom swipe)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "coordinate_x", "coordinate_y", "start_x", "start_y", "end_x", "end_y", "direction", "amount", "text", "element_text", "resource_id", "key", "duration_ms"),
    )

    fun imageGenerateDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_IMAGE_GENERATE,
        description = "Generate an image via Antigravity CLI on the user's connected desktop computer. " +
            "Supports text prompt, aspect ratio ('1:1', '16:9', '9:16', '4:3', '3:4', '3:2', '2:3'), and image name. " +
            "The generated image is returned and rendered in chat for the user to view, save, or set as wallpaper.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise summary like 'Generating Morandi cat wallpaper'."),
            "prompt" to AgentToolParam("string", "The detailed prompt describing what image to generate."),
            "aspect_ratio" to AgentToolParam("string", "Optional aspect ratio: '1:1' (default), '16:9', '9:16', '4:3', '3:4', '3:2', '2:3'."),
            "image_name" to AgentToolParam("string", "Optional short name for the generated image file (lowercase with underscores)."),
        ),
        required = listOf("tool_title", "prompt"),
        propertyOrdering = listOf("tool_title", "prompt", "aspect_ratio", "image_name"),
    )

    fun foregroundAppDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_FOREGROUND_APP,
        description = "Inspect what Android application is currently in the foreground / focused on the user's screen (e.g. Bilibili, WeChat, Game, Terminal, YouTube), along with its package name and window title. Read-only, ultra-fast, and zero vision token overhead. Call this first to sense what the user is doing.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    fun deviceInfoDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_DEVICE_INFO,
        description = "Inspect the Android device's hardware, battery state, RAM, storage, network connectivity, and OS environment. Read-only and side-effect free.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    fun appLaunchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_APP_LAUNCH,
        description = "Launch an installed Android application either by exact package name (e.g. 'com.tencent.mm', 'org.videolan.vlc') or by searching app name / title keywords (e.g. '微信', '设置', 'Camera', 'Gallery').",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
            "package_name" to AgentToolParam("string", "Optional exact Android package name to launch."),
            "query" to AgentToolParam("string", "Optional application name or keyword to search across installed apps."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "package_name", "query"),
    )

    fun systemNotifyDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_SYSTEM_NOTIFY,
        description = "Post a system status bar notification or show an instant Toast message on the Android device screen.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
            "title" to AgentToolParam("string", "The notification title or heading."),
            "message" to AgentToolParam("string", "The notification body or toast message."),
            "as_toast" to AgentToolParam("boolean", "If true, displays an immediate on-screen Toast message instead of a status bar notification. Default is false."),
        ),
        required = listOf("tool_title", "title", "message"),
        propertyOrdering = listOf("tool_title", "title", "message", "as_toast"),
    )

    fun screenCaptureDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_SCREEN_CAPTURE,
        description = "Capture a real-time screenshot of the current Android screen for visual inspection and analysis. Automatically captures via native root screencap or AccessibilityService. Returns image data directly for vision models.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does (e.g. 'Capture current screen state', 'Inspect error on screen'). Use the same language as the user."),
            "prompt" to AgentToolParam("string", "Optional. A custom instruction describing what you want to understand from the screen (e.g. 'what app is open', 'transcribe the visible error message', 'inspect UI state')."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "prompt"),
    )

    fun executeForegroundApp(argsJson: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", "Foreground App") } catch (_: Exception) { "Foreground App" }
        val isRootMode = (com.openminis.app.sandbox.ExecutionMode.getMode(context) == com.openminis.app.sandbox.ExecutionMode.ROOT)
        val pm = context.packageManager

        var packageName: String? = null
        var activityName: String? = null

        // 1. Root mode: query dumpsys window for mCurrentFocus
        if (isRootMode) {
            try {
                val p = ProcessBuilder("su", "-c", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                // e.g. mCurrentFocus=Window{3949423 u0 com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity}
                val match = Regex("""u\d+\s+([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""").find(text)
                if (match != null) {
                    packageName = match.groupValues[1]
                    activityName = match.groupValues[2]
                }
            } catch (_: Exception) {}
        }

        // 2. Accessibility fallback
        if (packageName == null) {
            try {
                val svc = com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
                val rootNode = svc?.rootInActiveWindow
                if (rootNode != null) {
                    packageName = rootNode.packageName?.toString()
                }
            } catch (_: Exception) {}
        }

        // 3. ActivityManager fallback
        if (packageName == null) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val running = am?.runningAppProcesses
                val top = running?.firstOrNull { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
                if (top != null) {
                    packageName = top.processName
                }
            } catch (_: Exception) {}
        }

        if (packageName == null) {
            return ToolExecutionResult(
                output = "Unable to determine current foreground application. The screen might be locked or permission not granted.",
                success = false,
                toolTitle = toolTitle
            )
        }

        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }

        val isSelf = (packageName == context.packageName)
        val sb = StringBuilder()
        sb.append("### Current Foreground Status\n\n")
        if (isSelf) {
            sb.append("**User is currently inside nanoMuse (this app)**\n")
            sb.append("- Package: `$packageName`\n")
        } else {
            sb.append("**Active Foreground App**: **$appLabel** (`$packageName`)\n")
            if (!activityName.isNullOrBlank()) {
                sb.append("- Activity: `$activityName`\n")
            }
        }

        // 4. If accessibility is available, extract prominent visible text elements
        val svc = com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
        if (svc != null) {
            val visibleTexts = mutableListOf<String>()
            fun collectTexts(node: AccessibilityNodeInfo?) {
                if (node == null || visibleTexts.size >= 12) return
                if (node.isVisibleToUser) {
                    val t = node.text?.toString()?.trim()
                    val c = node.contentDescription?.toString()?.trim()
                    if (!t.isNullOrBlank() && t.length in 1..80 && !visibleTexts.contains(t)) {
                        visibleTexts.add(t)
                    } else if (!c.isNullOrBlank() && c.length in 1..80 && !visibleTexts.contains(c)) {
                        visibleTexts.add(c)
                    }
                }
                for (i in 0 until node.childCount) {
                    collectTexts(node.getChild(i))
                }
            }
            try {
                for (r in svc.rootNodes()) {
                    collectTexts(r)
                }
            } catch (_: Exception) {}
            if (visibleTexts.isNotEmpty()) {
                sb.append("\n**Visible Screen Elements**:\n")
                visibleTexts.take(8).forEach { sb.append("- \"$it\"\n") }
            }
        }

        // Include battery and time context for instant situational awareness
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        sb.append("\n**Situational Context**:\n")
        sb.append("- Battery: $pct% ${if (isCharging) "(Charging)" else ""}\n")
        sb.append("- Current Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
        sb.append("*Tip: If you need to deeply inspect what is displayed on the screen (e.g. video contents, chat context, in-game stats, code/error details), you can call `screen_capture`.*")

        return ToolExecutionResult(
            output = sb.toString(),
            success = true,
            toolTitle = toolTitle
        )
    }

    fun executeDeviceInfo(argsJson: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", "Device Info") } catch (_: Exception) { "Device Info" }

        return try {
            val sb = StringBuilder()
            sb.append("### Android Device Status\n\n")

            // 1. Device Hardware & OS
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val dpi = dm.densityDpi
            sb.append("**Device**: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")
            sb.append("**OS**: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.DISPLAY})\n")
            sb.append("**Architecture**: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            sb.append("**Display**: ${screenW}x${screenH} (${dpi} dpi)\n\n")

            // 2. Battery
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val plugType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "None"
            }
            sb.append("**Battery**:\n")
            sb.append("- Level: $pct%\n")
            sb.append("- Charging: $isCharging (Source: $plugType)\n\n")

            // 3. Audio & Hardware Status
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val mediaVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxMediaVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val mediaPct = if (maxMediaVol > 0) (mediaVol * 100 / maxMediaVol) else 0
                val ringVol = am.getStreamVolume(AudioManager.STREAM_RING)
                val maxRingVol = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                val ringPct = if (maxRingVol > 0) (ringVol * 100 / maxRingVol) else 0
                sb.append("**Audio Volume**:\n")
                sb.append("- Media: $mediaVol/$maxMediaVol ($mediaPct%)\n")
                sb.append("- Ringtone: $ringVol/$maxRingVol ($ringPct%)\n")
            }
            sb.append("**Flashlight**: ${if (isTorchOn) "ON" else "OFF"}\n\n")

            // 4. Memory (RAM)
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val availRamMB = memInfo.availMem / (1024 * 1024)
            val totalRamMB = memInfo.totalMem / (1024 * 1024)
            sb.append("**Memory (RAM)**:\n")
            sb.append("- Available: $availRamMB MB / Total: $totalRamMB MB\n")
            sb.append("- Low Memory State: ${memInfo.lowMemory}\n\n")

            // 5. Storage
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalStorageGB = (totalBlocks * blockSize) / (1024 * 1024 * 1024)
            val availStorageGB = (availableBlocks * blockSize) / (1024 * 1024 * 1024)
            sb.append("**Internal Storage**:\n")
            sb.append("- Free: $availStorageGB GB / Total: $totalStorageGB GB\n\n")

            // 6. Connectivity
            val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connManager?.activeNetwork
            val capabilities = connManager?.getNetworkCapabilities(activeNetwork)
            val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val transport = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                else -> "Unknown/None"
            }
            sb.append("**Network**:\n")
            sb.append("- Connected: $isConnected\n")
            sb.append("- Active Transport: $transport\n")

            ToolExecutionResult(
                output = sb.toString(),
                success = true,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Failed to query device info: ${e.message}",
                success = false,
                toolTitle = toolTitle
            )
        }
    }

    fun executeDeviceControl(argsJson: String, context: Context): ToolExecutionResult {
        val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = json.optString("tool_title", "Device Control")
        val action = json.optString("action", "").lowercase().trim()
        val subAction = json.optString("sub_action", "").lowercase().trim()
        val stream = json.optString("stream", "media").lowercase().trim()
        val value = if (json.has("value")) json.optInt("value") else null
        val text = json.optString("text", "")
        val settingsPage = json.optString("settings_page", "wifi").lowercase().trim()

        return when (action) {
            "volume" -> {
                val effectiveSub = if (subAction.isNotBlank()) subAction else if (value != null) "set" else "get"
                val res = executeVolumeControl(context, effectiveSub, stream, value)
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            "flashlight" -> {
                val effectiveSub = if (subAction.isNotBlank()) subAction else "toggle"
                val res = setFlashlight(context, effectiveSub)
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            "clipboard" -> {
                val effectiveSub = if (subAction.isNotBlank()) subAction else if (text.isNotBlank()) "set" else "get"
                if (effectiveSub == "set") {
                    val ok = setClipboardText(context, text)
                    if (ok) {
                        ToolExecutionResult("Copied text to system clipboard: \"$text\"", true, toolTitle = toolTitle)
                    } else {
                        ToolExecutionResult("Failed to write to system clipboard.", false, toolTitle = toolTitle)
                    }
                } else {
                    val content = getClipboardText(context)
                    if (content != null) {
                        ToolExecutionResult("Clipboard Content:\n```\n$content\n```", true, toolTitle = toolTitle)
                    } else {
                        ToolExecutionResult("Clipboard is empty or access was restricted.", true, toolTitle = toolTitle)
                    }
                }
            }
            "media" -> {
                val effectiveSub = if (subAction.isNotBlank()) subAction else "play_pause"
                val res = executeMediaControl(context, effectiveSub)
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            "navigation" -> {
                val effectiveSub = if (subAction.isNotBlank()) subAction else "back"
                val res = executeNavigationAction(context, effectiveSub)
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            "open_settings" -> {
                val res = executeOpenSettings(context, settingsPage)
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            "vibrate" -> {
                val duration = value?.toLong() ?: 200L
                val res = executeVibrate(context, duration)
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            "intent" -> {
                val effectiveSub = if (subAction.isNotBlank()) subAction else "open_url"
                val url = json.optString("url", text).trim()
                val phone = json.optString("phone_number", text).trim()
                val res = when (effectiveSub.lowercase()) {
                    "open_url", "url", "browser" -> {
                        if (url.isEmpty()) {
                            false to "Error: 'url' parameter is required for open_url intent."
                        } else {
                            val parsedUri = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                Uri.parse("https://$url")
                            } else {
                                Uri.parse(url)
                            }
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                io.github.nanomuse.hands.HandsOverlayManager.show(context, "Open URL: $url")
                                io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss(3500L)
                                true to "Successfully opened URL in browser: $parsedUri"
                            } catch (e: Exception) {
                                false to "Failed to open URL $url: ${e.message}"
                            }
                        }
                    }
                    "dial", "phone", "call" -> {
                        if (phone.isEmpty()) {
                            false to "Error: 'phone_number' parameter is required for dial intent."
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                io.github.nanomuse.hands.HandsOverlayManager.show(context, "Dial: $phone")
                                io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss(3500L)
                                true to "Opened phone dialer for number: $phone"
                            } catch (e: Exception) {
                                false to "Failed to open dialer: ${e.message}"
                            }
                        }
                    }
                    "share" -> {
                        val shareText = if (text.isNotBlank()) text else url
                        if (shareText.isEmpty()) {
                            false to "Error: 'text' parameter is required for share intent."
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val chooser = Intent.createChooser(intent, "Share via").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooser)
                                io.github.nanomuse.hands.HandsOverlayManager.show(context, "Share Text")
                                io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss(3500L)
                                true to "Successfully invoked system share sheet with provided text."
                            } catch (e: Exception) {
                                false to "Failed to invoke share sheet: ${e.message}"
                            }
                        }
                    }
                    else -> false to "Unknown intent sub_action '$effectiveSub'. Supported: 'open_url', 'dial', 'share'."
                }
                ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
            }
            else -> ToolExecutionResult("Unknown device_control action '$action'. Supported actions: volume, flashlight, clipboard, media, navigation, open_settings, vibrate, intent.", false, toolTitle = toolTitle)
        }
    }

    fun executeAppManage(argsJson: String, context: Context): ToolExecutionResult {
        val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = json.optString("tool_title", "App Manage")
        val action = json.optString("action", "list").lowercase().trim()
        val query = json.optString("query", "").trim()
        val pkgName = json.optString("package_name", "").trim()
        val userAppsOnly = json.optBoolean("user_apps_only", true)
        val limit = json.optInt("limit", 40).coerceIn(1, 100)
        val pm = context.packageManager

        return when (action) {
            "list" -> {
                try {
                    val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    val filtered = installed.filter { app ->
                        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        if (userAppsOnly && isSystem) return@filter false
                        if (query.isNotEmpty()) {
                            val label = pm.getApplicationLabel(app).toString()
                            label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)
                        } else true
                    }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

                    val sb = StringBuilder()
                    sb.append("### Installed Applications (${filtered.size} found)\n\n")
                    if (filtered.isEmpty()) {
                        sb.append("No applications matched query '$query'.\n")
                    } else {
                        sb.append("| App Name | Package Name | Launchable |\n")
                        sb.append("|---|---|---|\n")
                        for (app in filtered.take(limit)) {
                            val label = pm.getApplicationLabel(app).toString().replace("|", "/")
                            val pkg = app.packageName
                            val launchable = pm.getLaunchIntentForPackage(pkg) != null
                            sb.append("| **$label** | `$pkg` | ${if (launchable) "Yes" else "No"} |\n")
                        }
                        if (filtered.size > limit) {
                            sb.append("\n*Showing first $limit of ${filtered.size} apps. Specify 'query' to filter.*")
                        }
                    }
                    ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
                } catch (e: Exception) {
                    ToolExecutionResult("Failed to list installed applications: ${e.message}", false, toolTitle = toolTitle)
                }
            }
            "info" -> {
                if (pkgName.isEmpty()) {
                    return ToolExecutionResult("Error: 'package_name' parameter is required for action 'info'.", false, toolTitle = toolTitle)
                }
                try {
                    val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
                    val appInfo = pkgInfo.applicationInfo ?: pm.getApplicationInfo(pkgName, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val firstInstall = sdf.format(Date(pkgInfo.firstInstallTime))
                    val lastUpdate = sdf.format(Date(pkgInfo.lastUpdateTime))
                    val launchable = pm.getLaunchIntentForPackage(pkgName) != null
                    val permissions = pkgInfo.requestedPermissions ?: emptyArray()

                    val sb = StringBuilder()
                    sb.append("### Application Details: $label\n\n")
                    sb.append("- **Package**: `$pkgName`\n")
                    sb.append("- **Version**: ${pkgInfo.versionName ?: "N/A"} (code ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode})\n")
                    sb.append("- **Target SDK**: ${appInfo.targetSdkVersion}\n")
                    sb.append("- **Type**: ${if (isSystem) "System App" else "User / Installed App"}\n")
                    sb.append("- **Launchable**: $launchable\n")
                    sb.append("- **Installed**: $firstInstall\n")
                    sb.append("- **Updated**: $lastUpdate\n")
                    sb.append("- **Data Dir**: `${appInfo.dataDir}`\n")
                    sb.append("- **Permissions Count**: ${permissions.size}\n")
                    if (permissions.isNotEmpty()) {
                        val samplePerms = permissions.map { it.substringAfterLast(".") }.distinct().take(10)
                        sb.append("- **Key Permissions**: ${samplePerms.joinToString(", ")}${if (permissions.size > 10) "..." else ""}\n")
                    }
                    ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
                } catch (e: Exception) {
                    ToolExecutionResult("Failed to get info for '$pkgName': ${e.message}", false, toolTitle = toolTitle)
                }
            }
            "stop" -> {
                if (pkgName.isEmpty()) {
                    return ToolExecutionResult("Error: 'package_name' parameter is required for action 'stop'.", false, toolTitle = toolTitle)
                }
                val isRootMode = (com.openminis.app.sandbox.ExecutionMode.getMode(context) == com.openminis.app.sandbox.ExecutionMode.ROOT)
                if (isRootMode) {
                    try {
                        val p = ProcessBuilder("su", "-c", "am force-stop $pkgName").start()
                        p.waitFor()
                        if (p.exitValue() == 0) {
                            ToolExecutionResult("Successfully force-stopped application: $pkgName", true, toolTitle = toolTitle)
                        } else {
                            ToolExecutionResult("Force stop returned exit code ${p.exitValue()}", false, toolTitle = toolTitle)
                        }
                    } catch (e: Exception) {
                        ToolExecutionResult("Failed to force stop app: ${e.message}", false, toolTitle = toolTitle)
                    }
                } else {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$pkgName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        ToolExecutionResult("Force-stopping without Root mode requires user confirmation. Opened App Info page for '$pkgName' so user can confirm force stop.", true, toolTitle = toolTitle)
                    } catch (e: Exception) {
                        ToolExecutionResult("Failed to open app info: ${e.message}", false, toolTitle = toolTitle)
                    }
                }
            }
            "open_settings" -> {
                if (pkgName.isEmpty()) {
                    return ToolExecutionResult("Error: 'package_name' parameter is required for action 'open_settings'.", false, toolTitle = toolTitle)
                }
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$pkgName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutionResult("Opened App Info settings page for '$pkgName'.", true, toolTitle = toolTitle)
                } catch (e: Exception) {
                    ToolExecutionResult("Failed to open app settings: ${e.message}", false, toolTitle = toolTitle)
                }
            }
            else -> ToolExecutionResult("Unknown action '$action' for app_manage. Supported actions: list, info, stop, open_settings.", false, toolTitle = toolTitle)
        }
    }

    fun executeScreenInteract(argsJson: String, context: Context): ToolExecutionResult {
        val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = json.optString("tool_title", "Screen Action")
        val action = json.optString("action", "tap").lowercase().trim()
        val isRootMode = (com.openminis.app.sandbox.ExecutionMode.getMode(context) == com.openminis.app.sandbox.ExecutionMode.ROOT)
        val svc = com.openminis.app.accessibility.MinisAccessibilityService.getInstance()

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        fun toPxX(x: Int): Int = if (x in 0..1000) (x * screenW / 1000.0).roundToInt().coerceIn(0, screenW - 1) else x.coerceIn(0, screenW - 1)
        fun toPxY(y: Int): Int = if (y in 0..1000) (y * screenH / 1000.0).roundToInt().coerceIn(0, screenH - 1) else y.coerceIn(0, screenH - 1)

        if (io.github.nanomuse.hands.HandsOverlayManager.stopRequested.get()) {
            io.github.nanomuse.hands.HandsOverlayManager.stopRequested.set(false)
            return ToolExecutionResult("Operation stopped by user via overlay Stop button.", false, toolTitle = toolTitle)
        }

        return when (action) {
            "tap", "click" -> {
                val gx = json.optInt("coordinate_x", -1)
                val gy = json.optInt("coordinate_y", -1)
                if (gx < 0 || gy < 0) {
                    return ToolExecutionResult("Error: 'coordinate_x' and 'coordinate_y' are required for tap (0-999 grid or raw pixels).", false, toolTitle = toolTitle)
                }
                val px = toPxX(gx)
                val py = toPxY(gy)
                val duration = json.optLong("duration_ms", 50L).coerceIn(10L, 500L)

                io.github.nanomuse.hands.HandsOverlayManager.withAimAndTap(context, px, py, "Tap ($px, $py)") {
                    if (svc != null) {
                        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()); lineTo(px + 0.5f, py + 0.5f) }
                        val ok = svc.dispatchSimpleGesture(path, 0L, duration)
                        if (ok) return@withAimAndTap ToolExecutionResult("Tapped screen at ($px, $py) [grid: $gx, $gy]", true, toolTitle = toolTitle)
                    }
                    if (isRootMode) {
                        try {
                            val p = ProcessBuilder("su", "-c", "input tap $px $py").start()
                            p.waitFor()
                            return@withAimAndTap ToolExecutionResult("Tapped screen via Root at ($px, $py)", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            return@withAimAndTap ToolExecutionResult("Root tap failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    }
                    ToolExecutionResult("Tap failed: MinisAccessibilityService is not running and Root execution mode is not available.", false, toolTitle = toolTitle)
                }
            }
            "double_tap" -> {
                val gx = json.optInt("coordinate_x", -1)
                val gy = json.optInt("coordinate_y", -1)
                if (gx < 0 || gy < 0) return ToolExecutionResult("Error: 'coordinate_x' and 'coordinate_y' are required.", false, toolTitle = toolTitle)
                val px = toPxX(gx)
                val py = toPxY(gy)

                io.github.nanomuse.hands.HandsOverlayManager.withAimAndTap(context, px, py, "Double-tap ($px, $py)") {
                    if (svc != null) {
                        val path1 = Path().apply { moveTo(px.toFloat(), py.toFloat()); lineTo(px + 0.5f, py + 0.5f) }
                        val ok1 = svc.dispatchSimpleGesture(path1, 0L, 50L)
                        Thread.sleep(100L)
                        val path2 = Path().apply { moveTo(px.toFloat(), py.toFloat()); lineTo(px + 0.5f, py + 0.5f) }
                        val ok2 = svc.dispatchSimpleGesture(path2, 0L, 50L)
                        if (ok1 && ok2) return@withAimAndTap ToolExecutionResult("Double-tapped screen at ($px, $py)", true, toolTitle = toolTitle)
                    }
                    if (isRootMode) {
                        try {
                            ProcessBuilder("su", "-c", "input tap $px $py && sleep 0.1 && input tap $px $py").start().waitFor()
                            return@withAimAndTap ToolExecutionResult("Double-tapped screen via Root at ($px, $py)", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            return@withAimAndTap ToolExecutionResult("Root double tap failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    }
                    ToolExecutionResult("Double-tap failed: AccessibilityService or Root required.", false, toolTitle = toolTitle)
                }
            }
            "long_press" -> {
                val gx = json.optInt("coordinate_x", -1)
                val gy = json.optInt("coordinate_y", -1)
                if (gx < 0 || gy < 0) return ToolExecutionResult("Error: 'coordinate_x' and 'coordinate_y' are required.", false, toolTitle = toolTitle)
                val px = toPxX(gx)
                val py = toPxY(gy)
                val duration = json.optLong("duration_ms", 650L).coerceIn(400L, 3000L)

                io.github.nanomuse.hands.HandsOverlayManager.withAimAndTap(context, px, py, "Long press ($px, $py)") {
                    if (svc != null) {
                        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()); lineTo(px + 0.5f, py + 0.5f) }
                        val ok = svc.dispatchSimpleGesture(path, 0L, duration)
                        if (ok) return@withAimAndTap ToolExecutionResult("Long pressed screen at ($px, $py) for ${duration}ms", true, toolTitle = toolTitle)
                    }
                    if (isRootMode) {
                        try {
                            ProcessBuilder("su", "-c", "input swipe $px $py $px $py $duration").start().waitFor()
                            return@withAimAndTap ToolExecutionResult("Long pressed screen via Root at ($px, $py)", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            return@withAimAndTap ToolExecutionResult("Root long press failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    }
                    ToolExecutionResult("Long press failed: AccessibilityService or Root required.", false, toolTitle = toolTitle)
                }
            }
            "swipe" -> {
                val gx1 = json.optInt("start_x", -1)
                val gy1 = json.optInt("start_y", -1)
                val gx2 = json.optInt("end_x", -1)
                val gy2 = json.optInt("end_y", -1)
                if (gx1 < 0 || gy1 < 0 || gx2 < 0 || gy2 < 0) {
                    return ToolExecutionResult("Error: 'start_x', 'start_y', 'end_x', 'end_y' are required for swipe.", false, toolTitle = toolTitle)
                }
                val px1 = toPxX(gx1)
                val py1 = toPxY(gy1)
                val px2 = toPxX(gx2)
                val py2 = toPxY(gy2)
                val duration = json.optLong("duration_ms", 350L).coerceIn(50L, 2000L)

                io.github.nanomuse.hands.HandsOverlayManager.withSweepAndSwipe(context, px1, py1, px2, py2, duration, "Swipe") {
                    if (svc != null) {
                        val path = Path().apply { moveTo(px1.toFloat(), py1.toFloat()); lineTo(px2.toFloat(), py2.toFloat()) }
                        val ok = svc.dispatchSimpleGesture(path, 0L, duration)
                        if (ok) return@withSweepAndSwipe ToolExecutionResult("Swiped from ($px1, $py1) to ($px2, $py2)", true, toolTitle = toolTitle)
                    }
                    if (isRootMode) {
                        try {
                            ProcessBuilder("su", "-c", "input swipe $px1 $py1 $px2 $py2 $duration").start().waitFor()
                            return@withSweepAndSwipe ToolExecutionResult("Swiped via Root from ($px1, $py1) to ($px2, $py2)", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            return@withSweepAndSwipe ToolExecutionResult("Root swipe failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    }
                    ToolExecutionResult("Swipe failed: AccessibilityService or Root required.", false, toolTitle = toolTitle)
                }
            }
            "scroll" -> {
                val direction = json.optString("direction", "down").lowercase()
                val amount = json.optInt("amount", 600)
                val cx = screenW / 2
                val cy = screenH / 2
                val (px1, py1, px2, py2) = when (direction) {
                    "down" -> listOf(cx, cy + amount / 2, cx, cy - amount / 2)
                    "up" -> listOf(cx, cy - amount / 2, cx, cy + amount / 2)
                    "left" -> listOf(cx + amount / 2, cy, cx - amount / 2, cy)
                    "right" -> listOf(cx - amount / 2, cy, cx + amount / 2, cy)
                    else -> listOf(cx, cy + amount / 2, cx, cy - amount / 2)
                }
                val duration = 300L

                io.github.nanomuse.hands.HandsOverlayManager.withSweepAndSwipe(context, px1, py1, px2, py2, duration, "Scroll $direction") {
                    if (svc != null) {
                        val path = Path().apply { moveTo(px1.toFloat(), py1.toFloat()); lineTo(px2.toFloat(), py2.toFloat()) }
                        val ok = svc.dispatchSimpleGesture(path, 0L, duration)
                        if (ok) return@withSweepAndSwipe ToolExecutionResult("Scrolled $direction (from ($px1, $py1) to ($px2, $py2))", true, toolTitle = toolTitle)
                    }
                    if (isRootMode) {
                        try {
                            ProcessBuilder("su", "-c", "input swipe $px1 $py1 $px2 $py2 $duration").start().waitFor()
                            return@withSweepAndSwipe ToolExecutionResult("Scrolled $direction via Root", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            return@withSweepAndSwipe ToolExecutionResult("Root scroll failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    }
                    ToolExecutionResult("Scroll failed: AccessibilityService or Root required.", false, toolTitle = toolTitle)
                }
            }
            "type" -> {
                val text = json.optString("text", "")
                if (text.isEmpty()) return ToolExecutionResult("Error: 'text' parameter is required for type action.", false, toolTitle = toolTitle)
                val targetX = if (json.has("coordinate_x")) json.optInt("coordinate_x") else if (json.has("x")) json.optInt("x") else null
                val targetY = if (json.has("coordinate_y")) json.optInt("coordinate_y") else if (json.has("y")) json.optInt("y") else null

                // If coordinates are provided, tap first to focus the input field
                if (targetX != null && targetY != null) {
                    val pxX = toPxX(targetX)
                    val pxY = toPxY(targetY)
                    io.github.nanomuse.hands.HandsOverlayManager.withAimAndTap(context, pxX, pxY, "Focus: \"${text.take(16)}\"") {
                        if (svc != null) {
                            val path = Path().apply { moveTo(pxX.toFloat(), pxY.toFloat()); lineTo(pxX + 0.5f, pxY + 0.5f) }
                            svc.dispatchSimpleGesture(path, 0L, 50L)
                        } else if (isRootMode) {
                            runCatching { ProcessBuilder("su", "-c", "input tap $pxX $pxY").start().waitFor() }
                        }
                    }
                    try { Thread.sleep(200L) } catch (_: InterruptedException) {}
                }

                io.github.nanomuse.hands.HandsOverlayManager.show(context, "Typing: \"${text.take(24)}\"")
                io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss()

                val hasNonAscii = text.any { it.code > 127 }

                // 1. Accessibility Service input
                if (svc != null) {
                    val roots = svc.rootNodes()
                    val focused = roots.firstNotNullOfOrNull { root ->
                        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                    }
                    if (focused != null) {
                        if (svc.setNodeText(focused, text)) {
                            return ToolExecutionResult("Typed text into focused input field: \"$text\"", true, toolTitle = toolTitle)
                        }
                        // Fallback: clipboard paste on focused node
                        setClipboardText(context, text)
                        if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                            return ToolExecutionResult("Pasted text into focused input field via clipboard: \"$text\"", true, toolTitle = toolTitle)
                        }
                    }
                }

                // 2. Root input
                if (isRootMode) {
                    if (hasNonAscii) {
                        // Android 'input text' cannot handle non-ASCII; use clipboard + KEYCODE_PASTE (279)
                        setClipboardText(context, text)
                        return try {
                            ProcessBuilder("su", "-c", "input keyevent 279").start().waitFor()
                            ToolExecutionResult("Pasted text via Root clipboard paste: \"$text\"", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            ToolExecutionResult("Root clipboard paste failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    } else {
                        val escaped = text.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace(" ", "%s")
                            .replace("&", "\\&")
                            .replace("<", "\\<")
                            .replace(">", "\\>")
                            .replace(";", "\\;")
                            .replace("$", "\\$")
                        try {
                            ProcessBuilder("su", "-c", "input text \"$escaped\"").start().waitFor()
                            return ToolExecutionResult("Typed text via Root input: \"$text\"", true, toolTitle = toolTitle)
                        } catch (e: Exception) {
                            return ToolExecutionResult("Root text input failed: ${e.message}", false, toolTitle = toolTitle)
                        }
                    }
                }

                // 3. Fallback when neither worked directly: set clipboard so user/model can paste
                setClipboardText(context, text)
                return ToolExecutionResult("Type completed: copied text \"$text\" to clipboard (no active input field had focus for direct entry).", true, toolTitle = toolTitle)
            }
            "key" -> {
                val keyName = json.optString("key", "").lowercase().trim()
                if (keyName.isEmpty()) return ToolExecutionResult("Error: 'key' parameter is required.", false, toolTitle = toolTitle)
                io.github.nanomuse.hands.HandsOverlayManager.show(context, "Key: $keyName")
                io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss()
                if (keyName == "back" || keyName == "home") {
                    val res = executeNavigationAction(context, keyName)
                    return ToolExecutionResult(res.second, res.first, toolTitle = toolTitle)
                }
                val isEnter = (keyName == "enter")
                if (isEnter && svc != null) {
                    val focused = svc.rootNodes().firstNotNullOfOrNull { root ->
                        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                    }
                    if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val imeAction = if (focused.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }) {
                            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        } else false
                        if (imeAction) return ToolExecutionResult("Pressed Enter (IME action)", true, toolTitle = toolTitle)
                    }
                }
                val keycode = when (keyName) {
                    "enter" -> 66
                    "tab" -> 61
                    "delete", "backspace" -> 67
                    "volume_up" -> 24
                    "volume_down" -> 25
                    "power" -> 26
                    "menu" -> 82
                    "escape", "esc" -> 111
                    else -> keyName.toIntOrNull()
                }
                if (keycode != null && isRootMode) {
                    try {
                        ProcessBuilder("su", "-c", "input keyevent $keycode").start().waitFor()
                        return ToolExecutionResult("Pressed key '$keyName' (keycode $keycode) via Root", true, toolTitle = toolTitle)
                    } catch (e: Exception) {
                        return ToolExecutionResult("Root keyevent failed: ${e.message}", false, toolTitle = toolTitle)
                    }
                }
                return ToolExecutionResult("Key event '$keyName' could not be executed without Root mode.", false, toolTitle = toolTitle)
            }
            "click_element" -> {
                val targetText = json.optString("element_text", "").trim()
                val resourceId = json.optString("resource_id", json.optString("element_id", "")).trim()
                if (targetText.isEmpty() && resourceId.isEmpty()) {
                    return ToolExecutionResult("Error: Either 'element_text' or 'resource_id' parameter is required.", false, toolTitle = toolTitle)
                }
                if (svc == null) {
                    return ToolExecutionResult("click_element requires MinisAccessibilityService to be enabled in Android Settings.", false, toolTitle = toolTitle)
                }
                val displayLabel = if (targetText.isNotEmpty()) targetText else resourceId
                io.github.nanomuse.hands.HandsOverlayManager.show(context, "Click “${displayLabel.take(24)}”")
                val roots = svc.rootNodes()
                var matchedNode: AccessibilityNodeInfo? = null

                // 1. Direct match by View ID if provided
                if (resourceId.isNotEmpty()) {
                    for (r in roots) {
                        val list = r.findAccessibilityNodeInfosByViewId(resourceId)
                        if (!list.isNullOrEmpty()) {
                            matchedNode = list.firstOrNull { it.isVisibleToUser } ?: list.first()
                            break
                        }
                    }
                }

                // 2. Direct text match
                if (matchedNode == null && targetText.isNotEmpty()) {
                    for (r in roots) {
                        val list = r.findAccessibilityNodeInfosByText(targetText)
                        if (!list.isNullOrEmpty()) {
                            matchedNode = list.firstOrNull { it.isVisibleToUser } ?: list.first()
                            break
                        }
                    }
                }

                // 3. Recursive fuzzy match
                if (matchedNode == null && targetText.isNotEmpty()) {
                    fun search(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (n == null) return null
                        val t = n.text?.toString() ?: ""
                        val c = n.contentDescription?.toString() ?: ""
                        if (t.contains(targetText, ignoreCase = true) || c.contains(targetText, ignoreCase = true)) {
                            return n
                        }
                        for (i in 0 until n.childCount) {
                            val found = search(n.getChild(i))
                            if (found != null) return found
                        }
                        return null
                    }
                    for (r in roots) {
                        matchedNode = search(r)
                        if (matchedNode != null) break
                    }
                }

                if (matchedNode == null) {
                    io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss(1000L)
                    return ToolExecutionResult("Could not find any visible element matching '${displayLabel}' on current screen.", false, toolTitle = toolTitle)
                }

                // Perform click on node or its ancestor
                var curr: AccessibilityNodeInfo? = matchedNode
                var clicked = false
                while (curr != null) {
                    if (curr.isClickable && curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true
                        break
                    }
                    curr = curr.parent
                }
                // If action click failed, tap coordinates from bounds with visual aim
                if (!clicked) {
                    val bounds = Rect()
                    matchedNode.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        val cx = bounds.centerX()
                        val cy = bounds.centerY()
                        clicked = io.github.nanomuse.hands.HandsOverlayManager.withAimAndTap(context, cx, cy, "Click “${displayLabel.take(20)}”") {
                            val path = Path().apply { moveTo(cx.toFloat(), cy.toFloat()); lineTo(cx + 0.5f, cy + 0.5f) }
                            svc.dispatchSimpleGesture(path, 0L, 50L)
                        }
                    }
                } else {
                    io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss()
                }
                val matchedLabel = matchedNode.text?.toString() ?: matchedNode.contentDescription?.toString() ?: matchedNode.viewIdResourceName ?: displayLabel
                return if (clicked) {
                    ToolExecutionResult("Successfully clicked element \"$matchedLabel\" on screen.", true, toolTitle = toolTitle)
                } else {
                    ToolExecutionResult("Found element \"$matchedLabel\", but click gesture could not be dispatched.", false, toolTitle = toolTitle)
                }
            }
            else -> ToolExecutionResult("Unknown action '$action' for screen_interact. Supported actions: tap, double_tap, long_press, swipe, scroll, type, key, click_element.", false, toolTitle = toolTitle)
        }
    }

    private fun executeVolumeControl(context: Context, subAction: String, streamName: String, value: Int?): Pair<Boolean, String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false to "AudioManager not available"
        val stream = when (streamName.lowercase()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "call_ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "call", "voice_call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val streamLabel = when (stream) {
            AudioManager.STREAM_MUSIC -> "Media"
            AudioManager.STREAM_RING -> "Ringtone"
            AudioManager.STREAM_NOTIFICATION -> "Notification"
            AudioManager.STREAM_ALARM -> "Alarm"
            AudioManager.STREAM_VOICE_CALL -> "Voice Call"
            else -> "Media"
        }
        val maxVol = am.getStreamMaxVolume(stream)
        val curVol = am.getStreamVolume(stream)
        return try {
            when (subAction.lowercase()) {
                "get", "status", "query" -> {
                    val allInfo = buildString {
                        append("### Audio Volume Status\n")
                        val streams = listOf(
                            "Media" to AudioManager.STREAM_MUSIC,
                            "Ringtone" to AudioManager.STREAM_RING,
                            "Notification" to AudioManager.STREAM_NOTIFICATION,
                            "Alarm" to AudioManager.STREAM_ALARM,
                        )
                        for ((lbl, s) in streams) {
                            val c = am.getStreamVolume(s)
                            val m = am.getStreamMaxVolume(s)
                            val p = if (m > 0) (c * 100 / m) else 0
                            append("- **$lbl**: $c / $m ($p%)\n")
                        }
                    }
                    true to allInfo
                }
                "set" -> {
                    if (value == null) return false to "Missing 'value' parameter (volume level 0-100 percentage or 0-$maxVol)"
                    val targetVol = if (value in 0..100 && maxVol > 0 && value > maxVol) {
                        (value * maxVol / 100.0).roundToInt().coerceIn(0, maxVol)
                    } else {
                        value.coerceIn(0, maxVol)
                    }
                    am.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
                    val newPct = if (maxVol > 0) (targetVol * 100 / maxVol) else 0
                    true to "Set $streamLabel volume to $targetVol / $maxVol ($newPct%)"
                }
                "raise", "up", "increase" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    val newVol = am.getStreamVolume(stream)
                    true to "Increased $streamLabel volume to $newVol / $maxVol"
                }
                "lower", "down", "decrease" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    val newVol = am.getStreamVolume(stream)
                    true to "Decreased $streamLabel volume to $newVol / $maxVol"
                }
                "mute" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    true to "Muted $streamLabel volume"
                }
                "unmute" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    val newVol = am.getStreamVolume(stream)
                    true to "Unmuted $streamLabel volume (now $newVol / $maxVol)"
                }
                else -> false to "Unknown volume action: $subAction"
            }
        } catch (e: Exception) {
            false to "Volume control error: ${e.message}"
        }
    }

    private fun setFlashlight(context: Context, state: String): Pair<Boolean, String> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false to "CameraManager not available"
        return try {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                flashAvailable && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                return false to "No camera with flashlight/torch found on device."
            }
            val targetOn = when (state.lowercase()) {
                "on", "true", "enable", "start" -> true
                "off", "false", "disable", "stop" -> false
                "toggle" -> !isTorchOn
                "status" -> return true to "Flashlight is currently ${if (isTorchOn) "ON" else "OFF"}"
                else -> !isTorchOn
            }
            cm.setTorchMode(cameraId, targetOn)
            isTorchOn = targetOn
            true to "Flashlight turned ${if (targetOn) "ON" else "OFF"}"
        } catch (e: Exception) {
            false to "Failed to control flashlight: ${e.message}"
        }
    }

    private fun getClipboardText(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val clip = cm.primaryClip
            return if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else null
        }
        val latch = CountDownLatch(1)
        var result: String? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    result = clip.getItemAt(0).coerceToText(context).toString()
                }
            } catch (_: Exception) {}
            finally {
                latch.countDown()
            }
        }
        latch.await(1000, TimeUnit.MILLISECONDS)
        return result
    }

    private fun setClipboardText(context: Context, text: String): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val latch = CountDownLatch(1)
        var success = false
        Handler(Looper.getMainLooper()).post {
            try {
                val clip = ClipData.newPlainText("nanoMuse", text)
                cm.setPrimaryClip(clip)
                success = true
            } catch (_: Exception) {}
            finally {
                latch.countDown()
            }
        }
        latch.await(1000, TimeUnit.MILLISECONDS)
        return success
    }

    private fun executeMediaControl(context: Context, subAction: String): Pair<Boolean, String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val keyCode = when (subAction.lowercase()) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am?.dispatchMediaKeyEvent(down)
            am?.dispatchMediaKeyEvent(up)
            val isRootMode = (com.openminis.app.sandbox.ExecutionMode.getMode(context) == com.openminis.app.sandbox.ExecutionMode.ROOT)
            if (isRootMode) {
                try {
                    ProcessBuilder("su", "-c", "input keyevent $keyCode").start().waitFor()
                } catch (_: Exception) {}
            }
            true to "Sent media playback command: $subAction"
        } catch (e: Exception) {
            false to "Failed to dispatch media key event: ${e.message}"
        }
    }

    private fun executeNavigationAction(context: Context, target: String): Pair<Boolean, String> {
        val svc = com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
        val isRootMode = (com.openminis.app.sandbox.ExecutionMode.getMode(context) == com.openminis.app.sandbox.ExecutionMode.ROOT)

        val globalAction = when (target.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen", "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else null
            else -> null
        }

        if (svc != null && globalAction != null) {
            val ok = svc.performGlobalAction(globalAction)
            if (ok) return true to "Successfully executed navigation action: $target"
        }

        if (isRootMode) {
            val keycode = when (target.lowercase()) {
                "back" -> 4
                "home" -> 3
                "recents" -> 187
                "power", "lock", "lock_screen" -> 26
                else -> null
            }
            if (keycode != null) {
                return try {
                    ProcessBuilder("su", "-c", "input keyevent $keycode").start().waitFor()
                    true to "Executed navigation action via Root: $target (keycode $keycode)"
                } catch (e: Exception) {
                    false to "Failed root keyevent: ${e.message}"
                }
            }
        }

        return if (svc == null) {
            false to "Navigation action requires MinisAccessibilityService enabled in Android Settings or Root execution mode."
        } else {
            false to "Failed to perform global action $target."
        }
    }

    private fun executeOpenSettings(context: Context, page: String): Pair<Boolean, String> {
        val intent = when (page.lowercase()) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY).takeIf { it.resolveActivity(context.packageManager) != null }
                ?: Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "apps", "applications" -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            "date", "time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane_mode", "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true to "Opened Android system settings page: $page"
        } catch (e: Exception) {
            false to "Failed to open settings page $page: ${e.message}"
        }
    }

    private fun executeVibrate(context: Context, durationMs: Long): Pair<Boolean, String> {
        val safeDuration = durationMs.coerceIn(10L, 5000L)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(safeDuration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(safeDuration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(safeDuration)
                }
            }
            true to "Device vibrated for ${safeDuration}ms"
        } catch (e: Exception) {
            false to "Vibration error: ${e.message}"
        }
    }

    fun executeAppLaunch(argsJson: String, context: Context): ToolExecutionResult {
        val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = json.optString("tool_title", "Launch App")
        val pkgName = json.optString("package_name", "").trim()
        val query = json.optString("query", "").trim()

        if (pkgName.isEmpty() && query.isEmpty()) {
            return ToolExecutionResult(
                output = "Error: Either 'package_name' or 'query' must be provided to launch an application.",
                success = false,
                toolTitle = toolTitle
            )
        }

        val pm = context.packageManager

        // 1. Try direct package name if provided
        if (pkgName.isNotEmpty()) {
            val intent = pm.getLaunchIntentForPackage(pkgName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(intent)
                    val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)) } catch (_: Exception) { pkgName }
                    io.github.nanomuse.hands.HandsOverlayManager.show(context, "Opened $label")
                    io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss(4000L)
                    ToolExecutionResult(
                        output = "Successfully launched application: $label ($pkgName)",
                        success = true,
                        toolTitle = toolTitle
                    )
                } catch (e: Exception) {
                    ToolExecutionResult(
                        output = "Failed to launch app $pkgName: ${e.message}",
                        success = false,
                        toolTitle = toolTitle
                    )
                }
            } else if (query.isEmpty()) {
                return ToolExecutionResult(
                    output = "Error: No launchable activity found for package '$pkgName'.",
                    success = false,
                    toolTitle = toolTitle
                )
            }
        }

        // 2. Search installed applications by query
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        data class AppCandidate(val label: String, val pkg: String, val intent: Intent)
        val candidates = mutableListOf<AppCandidate>()

        for (app in installedApps) {
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName) ?: continue
            val label = pm.getApplicationLabel(app).toString()
            if (label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true)) {
                candidates.add(AppCandidate(label, app.packageName, launchIntent))
            }
        }

        if (candidates.isEmpty()) {
            return ToolExecutionResult(
                output = "Error: No installed application found matching query '$query'.",
                success = false,
                toolTitle = toolTitle
            )
        }

        // Pick exact match first, otherwise the first candidate
        val chosen = candidates.firstOrNull { it.label.equals(query, ignoreCase = true) } ?: candidates.first()
        chosen.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(chosen.intent)
            io.github.nanomuse.hands.HandsOverlayManager.show(context, "Opened ${chosen.label}")
            io.github.nanomuse.hands.HandsOverlayManager.scheduleAutoDismiss(4000L)
            ToolExecutionResult(
                output = "Successfully launched application: ${chosen.label} (${chosen.pkg})",
                success = true,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Failed to start activity for ${chosen.label}: ${e.message}",
                success = false,
                toolTitle = toolTitle
            )
        }
    }

    fun executeSystemNotify(argsJson: String, context: Context): ToolExecutionResult {
        val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = json.optString("tool_title", "Notify")
        val title = json.optString("title", "NyanoMuse")
        val message = json.optString("message", "")
        val asToast = json.optBoolean("as_toast", false)

        if (message.isBlank()) {
            return ToolExecutionResult(
                output = "Error: 'message' cannot be blank.",
                success = false,
                toolTitle = toolTitle
            )
        }

        if (asToast) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, if (title.isNotBlank()) "$title: $message" else message, Toast.LENGTH_LONG).show()
            }
            return ToolExecutionResult(
                output = "Toast message displayed successfully: $message",
                success = true,
                toolTitle = toolTitle
            )
        }

        // Post Status Bar Notification
        return try {
            ensureNotificationChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_nanomuse)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .withFace(context)
                .build()

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
            ToolExecutionResult(
                output = "Notification posted successfully: $title - $message",
                success = true,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            // Graceful fallback to toast if notification posting fails or permission is denied
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "$title: $message", Toast.LENGTH_LONG).show()
            }
            ToolExecutionResult(
                output = "Notification delivery fell back to Toast due to: ${e.message}",
                success = true,
                toolTitle = toolTitle
            )
        }
    }

    fun executeScreenCapture(argsJson: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", "Capture Screen") } catch (_: Exception) { "Capture Screen" }
        val isRootMode = (com.openminis.app.sandbox.ExecutionMode.getMode(context) == com.openminis.app.sandbox.ExecutionMode.ROOT)

        // 1. Try Root mode native screencap first
        if (isRootMode) {
            try {
                val p = ProcessBuilder("su", "-c", "/system/bin/screencap -p").start()
                val bytes = p.inputStream.readBytes()
                p.waitFor()
                if (p.exitValue() == 0 && bytes.size > 100 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val w = opts.outWidth
                    val h = opts.outHeight
                    return ToolExecutionResult(
                        output = "[Screen captured | ${w}x${h} | ${bytes.size} bytes]",
                        success = true,
                        imageData = bytes,
                        imageMimeType = "image/png",
                        toolTitle = toolTitle
                    )
                }
            } catch (_: Exception) {}
        }

        // 2. Fall back to AccessibilityService if available
        try {
            val svc = com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val shot = svc.captureScreenshot(android.view.Display.DEFAULT_DISPLAY)
                val raw = shot.bitmap
                if (raw != null) {
                    val baos = java.io.ByteArrayOutputStream()
                    raw.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    val w = raw.width
                    val h = raw.height
                    raw.recycle()
                    val bytes = baos.toByteArray()
                    return ToolExecutionResult(
                        output = "[Screen captured via Accessibility | ${w}x${h} | ${bytes.size} bytes]",
                        success = true,
                        imageData = bytes,
                        imageMimeType = "image/png",
                        toolTitle = toolTitle
                    )
                }
            }
        } catch (_: Exception) {}

        return ToolExecutionResult(
            output = "Error: Screen capture requires either Root execution mode or AccessibilityService enabled. Please enable Root mode in Settings or grant Accessibility in Android Settings.",
            success = false,
            toolTitle = toolTitle
        )
    }

    suspend fun executeImageGenerate(argsJson: String, context: Context): ToolExecutionResult {
        val json = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: Invalid JSON arguments: ${e.message}", false)
        }
        val prompt = json.optString("prompt", "").trim()
        val toolTitle = json.optString("tool_title", "Antigravity 生图").ifBlank { "Antigravity 生图" }
        if (prompt.isEmpty()) {
            return ToolExecutionResult("Error: 'prompt' parameter is required for image_generate", false, toolTitle = toolTitle)
        }
        val aspectRatio = json.optString("aspect_ratio", "1:1").ifBlank { "1:1" }
        val imageName = json.optString("image_name", "").trim()

        val allDevices = io.github.nanomuse.reach.Computers.all(context)
        val target = allDevices.firstOrNull { it.online && it.kind == "computer" }
            ?: allDevices.firstOrNull { it.online }

        if (target == null) {
            return ToolExecutionResult(
                "生图功能需要连接运行 Antigravity CLI 的电脑设备，但当前 Hub 暂无在线的电脑设备。请确保电脑端已启动 NyanoMuse Desktop 并连接到 Relay Hub。",
                false,
                toolTitle = toolTitle
            )
        }

        return try {
            val resp = io.github.nanomuse.reach.Computers.generateImage(target, prompt, aspectRatio, imageName)
            val b64 = resp.optString("data")
            val filename = resp.optString("filename", "generated_${System.currentTimeMillis()}.jpg")
            val mimeType = resp.optString("mime_type", "image/jpeg")
            val desc = resp.optString("description", "")

            if (b64.isEmpty()) {
                return ToolExecutionResult("生图失败: 电脑端未返回图片数据", false, toolTitle = toolTitle)
            }

            val imageBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val imagesDir = File(context.filesDir, "generated_images").apply { mkdirs() }
            val localFile = File(imagesDir, filename)
            localFile.writeBytes(imageBytes)

            val markdownLink = "![${filename}](file://${localFile.absolutePath})"
            val output = buildString {
                append("使用 Antigravity 成功生成图片：\n")
                append("- 提示词: $prompt\n")
                append("- 画面比例: $aspectRatio\n")
                if (desc.isNotEmpty()) append("- 描述: $desc\n")
                append("- 本地存储: ${localFile.absolutePath}\n\n")
                append(markdownLink)
            }

            ToolExecutionResult(
                output = output,
                success = true,
                imageData = imageBytes,
                imageMimeType = mimeType,
                imageFilePath = localFile.absolutePath,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult("在设备 ${target.name} 上生成图片失败: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    fun saveImageToGallery(context: Context, file: File): Boolean {
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NyanoMuse")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications posted by NyanoMuse Agent"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}

object ToolSelfHealing {
    fun enrichFailure(name: String, errorOutput: String, argsJson: String): String {
        val lower = errorOutput.lowercase()
        val suggestions = mutableListOf<String>()

        when (name) {
            FileReadTool.NAME, FileEditTool.NAME -> {
                if (lower.contains("no such file") || lower.contains("not found") || lower.contains("file does not exist")) {
                    suggestions.add("The requested file was not found. Consider listing the directory with shell_execute ('ls' or 'find') to discover the correct path, or create the file with file_write.")
                }
            }
            FileWriteTool.NAME -> {
                if (lower.contains("permission denied") || lower.contains("read-only")) {
                    suggestions.add("Write permission was denied. Verify parent directory permissions or check if path is under a read-only partition.")
                }
            }
            ReadImageTool.NAME -> {
                if (lower.contains("cannot decode") || lower.contains("not found")) {
                    suggestions.add("Image file could not be decoded or found. Verify the file path and that it is a valid image format (PNG, JPEG, WEBP).")
                }
            }
            NativeSystemTools.TOOL_SCREEN_CAPTURE -> {
                suggestions.add("Screen capture failed. Ensure Root mode is active or grant Accessibility permission to NyanoMuse in system settings.")
            }
            "shell_execute" -> {
                if (lower.contains("command not found") || lower.contains("not found")) {
                    suggestions.add("The requested command was not found in PATH. In Root/Host mode, inspect installed tools in /system/bin or toybox/toolbox applets, or verify tool installation.")
                }
                if (lower.contains("permission denied")) {
                    suggestions.add("Execution permission denied. In Root execution mode, verify file permissions with 'chmod' or run via su.")
                }
            }
            NativeSystemTools.TOOL_APP_LAUNCH -> {
                suggestions.add("If the target app could not be found, try checking installed package names with shell_execute ('pm list packages') or broadening your query.")
            }
            NativeSystemTools.TOOL_DEVICE_CONTROL -> {
                suggestions.add("Ensure the target sub_action and arguments are valid. For volume, check stream ('media', 'ring', 'alarm') and value (0-100). Flashlight requires camera hardware.")
            }
            NativeSystemTools.TOOL_APP_MANAGE -> {
                suggestions.add("Use 'list' action with 'query' to search for the exact package name. Force-stopping third-party apps is supported in Root execution mode or via settings.")
            }
            NativeSystemTools.TOOL_SCREEN_INTERACT -> {
                suggestions.add("Screen gestures require MinisAccessibilityService enabled in Android Settings or Root execution mode. For element clicking, verify the element_text is currently visible on screen.")
            }
            NativeSystemTools.TOOL_IMAGE_GENERATE -> {
                if (lower.contains("暂无在线") || lower.contains("offline") || lower.contains("no computer")) {
                    suggestions.add("Ensure your computer is connected to the Relay Hub and NyanoMuse Desktop is running.")
                }
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    suggestions.add("Image generation timed out. Try simplifying the prompt or check the computer's network connectivity.")
                }
            }
        }

        return if (suggestions.isNotEmpty()) {
            buildString {
                append(errorOutput)
                append("\n\n[Diagnostic Self-Healing Hint]:\n")
                suggestions.forEach { append("- ").append(it).append("\n") }
            }
        } else {
            errorOutput
        }
    }
}
