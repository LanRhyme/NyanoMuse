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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    const val TOOL_FOREGROUND_APP = "foreground_app"
    const val TOOL_APP_LAUNCH = "app_launch"
    const val TOOL_SYSTEM_NOTIFY = "system_notify"
    const val TOOL_SCREEN_CAPTURE = "screen_capture"
    const val TOOL_IMAGE_GENERATE = "image_generate"

    const val CHANNEL_ID = "nanomuse_agent_notifications"
    private const val CHANNEL_NAME = "NyanoMuse Notifications"

    fun isReadOnly(name: String): Boolean = when (name) {
        TOOL_DEVICE_INFO, TOOL_FOREGROUND_APP, TOOL_SCREEN_CAPTURE, FileReadTool.NAME, "memory_get", ReadImageTool.NAME -> true
        else -> false
    }

    fun makeDefinitions(): List<AgentToolDefinition> = listOf(
        deviceInfoDefinition(),
        foregroundAppDefinition(),
        appLaunchDefinition(),
        systemNotifyDefinition(),
        screenCaptureDefinition(),
        imageGenerateDefinition(),
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
