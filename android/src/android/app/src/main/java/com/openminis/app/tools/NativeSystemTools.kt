package com.openminis.app.tools

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.R
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import io.github.nanomuse.identity.NanoMuseIdentity.withFace
import org.json.JSONObject
import java.io.File

object NativeSystemTools {

    const val TOOL_DEVICE_INFO = "device_info"
    const val TOOL_APP_LAUNCH = "app_launch"
    const val TOOL_SYSTEM_NOTIFY = "system_notify"

    const val CHANNEL_ID = "nanomuse_agent_notifications"
    private const val CHANNEL_NAME = "NyanoMuse Notifications"

    fun isReadOnly(name: String): Boolean = when (name) {
        TOOL_DEVICE_INFO, FileReadTool.NAME, "memory_get", ReadImageTool.NAME -> true
        else -> false
    }

    fun makeDefinitions(): List<AgentToolDefinition> = listOf(
        deviceInfoDefinition(),
        appLaunchDefinition(),
        systemNotifyDefinition(),
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

    fun executeDeviceInfo(argsJson: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", "Device Info") } catch (_: Exception) { "Device Info" }

        return try {
            val sb = StringBuilder()
            sb.append("### Android Device Status\n\n")

            // 1. Device Hardware & OS
            sb.append("**Device**: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")
            sb.append("**OS**: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.DISPLAY})\n")
            sb.append("**Architecture**: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n\n")

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

            // 3. Memory (RAM)
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val availRamMB = memInfo.availMem / (1024 * 1024)
            val totalRamMB = memInfo.totalMem / (1024 * 1024)
            sb.append("**Memory (RAM)**:\n")
            sb.append("- Available: $availRamMB MB / Total: $totalRamMB MB\n")
            sb.append("- Low Memory State: ${memInfo.lowMemory}\n\n")

            // 4. Storage
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalStorageGB = (totalBlocks * blockSize) / (1024 * 1024 * 1024)
            val availStorageGB = (availableBlocks * blockSize) / (1024 * 1024 * 1024)
            sb.append("**Internal Storage**:\n")
            sb.append("- Free: $availStorageGB GB / Total: $totalStorageGB GB\n\n")

            // 5. Connectivity
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
