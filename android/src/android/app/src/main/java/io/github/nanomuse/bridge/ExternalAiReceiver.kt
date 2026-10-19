package io.github.nanomuse.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulStore
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.avatar.AvatarPackImporter
import io.github.nanomuse.cloud.ProfileSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Open external AI receiver: allows external AI agents (via ADB or root shell)
 * to update SOUL prompt, import avatars, configure relay, or reload state dynamically.
 */
class ExternalAiReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLogger.info(TAG, "onReceive action: $action")

        when {
            action.endsWith(".UPDATE_SOUL") -> {
                val text = intent.getStringExtra("text")
                    ?: intent.getStringExtra("soul")
                    ?: intent.getStringExtra("file")?.let { path ->
                        val f = File(path)
                        if (f.exists()) f.readText() else null
                    }
                if (!text.isNullOrBlank()) {
                    scope.launch {
                        try {
                            val parsed = SoulMDParser.parse(text)
                            SoulStore.save(context, parsed)
                            AppLogger.info(TAG, "SOUL.md updated successfully via ExternalAiReceiver")
                        } catch (e: Exception) {
                            AppLogger.error(TAG, "Failed to update SOUL.md: ${e.message}")
                        }
                    }
                }
            }

            action.endsWith(".IMPORT_AVATAR") -> {
                val path = intent.getStringExtra("path") ?: intent.getStringExtra("file")
                if (!path.isNullOrBlank()) {
                    scope.launch {
                        try {
                            val f = File(path)
                            val res = AvatarPackImporter.importFromFile(context, f)
                            AppLogger.info(TAG, "Avatar imported via ExternalAiReceiver: ${res.message}")
                        } catch (e: Exception) {
                            AppLogger.error(TAG, "Failed to import avatar: ${e.message}")
                        }
                    }
                }
            }

            action.endsWith(".UPDATE_RELAY") -> {
                val url = intent.getStringExtra("url")
                val token = intent.getStringExtra("token")
                if (!url.isNullOrBlank()) {
                    val prefs = context.getSharedPreferences("nanomuse", Context.MODE_PRIVATE)
                    prefs.edit().putString("cloud.base", url.trimEnd('/')).apply()
                    if (!token.isNullOrBlank()) {
                        prefs.edit().putString("cloud.api_key", token.trim()).apply()
                    }
                    ProfileSync.pullSoon(context)
                }
            }

            action.endsWith(".RELOAD_CONFIG") -> {
                ProfileSync.pullSoon(context)
                SoulStore.refreshCache(context)
            }
        }
    }

    companion object {
        private const val TAG = "ExternalAiReceiver"
        const val ACTION_UPDATE_SOUL = "io.github.nanomuse.UPDATE_SOUL"
        const val ACTION_IMPORT_AVATAR = "io.github.nanomuse.IMPORT_AVATAR"
        const val ACTION_UPDATE_RELAY = "io.github.nanomuse.UPDATE_RELAY"
        const val ACTION_RELOAD_CONFIG = "io.github.nanomuse.RELOAD_CONFIG"
    }
}
