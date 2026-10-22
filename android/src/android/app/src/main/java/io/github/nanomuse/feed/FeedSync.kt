package io.github.nanomuse.feed

import android.content.Context
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.hub.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device feed synchronization:
 * Connects to online computers of the account via Hub to fetch:
 * 1. Feed posts (daily briefs/notes written by LLM) -> saved locally to [FeedStore]
 * 2. Activity feed (While you were away background passes, file artifacts, check-ins)
 */
object FeedSync {
    private const val TAG = "FeedSync"
    private const val CALL_TIMEOUT_MS = 15_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class FeedActivityItem(
        val id: String,
        val ts: Long,
        val kind: String,
        val title: String,
        val text: String,
        val thread: String,
        val threadTitle: String,
        val path: String? = null,
        val quiet: Boolean = false,
    )

    private val _activities = MutableStateFlow<List<FeedActivityItem>>(emptyList())
    val activities: StateFlow<List<FeedActivityItem>> = _activities.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    fun triggerSync(context: Context, userInitiated: Boolean = false) {
        scope.launch { sync(context, userInitiated) }
    }

    suspend fun sync(context: Context, userInitiated: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!Hub.isConnected) {
            AppLogger.debug(TAG, "sync skipped: Hub not connected")
            if (userInitiated) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(app, "未连接到 Hub，正在尝试连接...", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return@withContext false
        }

        // Find an online computer or device that can serve feed
        val others = Hub.others(app).filter { it.online }
        if (others.isEmpty()) {
            AppLogger.debug(TAG, "sync skipped: no online devices found")
            if (userInitiated) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(app, "未发现其他在线设备", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return@withContext false
        }

        val target = others.firstOrNull { it.isComputer && it.actions.contains("feed.posts") }
            ?: others.firstOrNull { it.actions.contains("feed.posts") }
            ?: others.firstOrNull { it.isComputer }
            ?: others.first()

        _isSyncing.value = true
        _lastSyncError.value = null

        var anySuccess = false
        var totalImported = 0
        try {
            // 1. Fetch feed posts
            runCatching {
                val res = Hub.call(target.id, "feed.posts", JSONObject(), CALL_TIMEOUT_MS)
                val postsJson = res.optJSONArray("posts") ?: JSONArray()
                val remotes = mutableListOf<FeedStore.RemotePost>()
                val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val dayRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

                for (i in 0 until postsJson.length()) {
                    val p = postsJson.optJSONObject(i) ?: continue
                    val sources = mutableListOf<String>()
                    p.optJSONArray("source")?.let { arr ->
                        for (j in 0 until arr.length()) arr.optString(j)?.let { if (it.isNotBlank()) sources += it }
                    }
                    val ts = when {
                        p.has("at") && p.optLong("at") > 0L -> p.optLong("at")
                        p.has("created_at") && p.optLong("created_at") > 0L -> p.optLong("created_at")
                        p.has("ts") && p.optLong("ts") > 0L -> p.optLong("ts")
                        else -> System.currentTimeMillis()
                    }
                    var day = p.optString("day")
                    if (!dayRegex.matches(day)) {
                        day = dayFormat.format(java.util.Date(ts))
                    }
                    remotes += FeedStore.RemotePost(
                        day = day,
                        title = p.optString("title"),
                        type = p.optString("area").ifBlank { p.optString("type", "note") },
                        emoji = p.optString("emoji"),
                        body = p.optString("body"),
                        source = sources,
                        createdAt = ts,
                        liked = p.optBoolean("liked", false),
                    )
                }
                if (remotes.isNotEmpty()) {
                    val imported = FeedStore.importRemotePosts(remotes)
                    totalImported = imported
                    AppLogger.info(TAG, "feed.posts sync from ${target.name}: imported $imported / ${remotes.size} posts")
                    anySuccess = true
                }
            }.onFailure {
                AppLogger.warning(TAG, "feed.posts sync failed: ${it.message}")
            }

            // 2. Fetch activity feed ("While you were away")
            runCatching {
                val res = Hub.call(target.id, "feed.activity", JSONObject().put("limit", 60), CALL_TIMEOUT_MS)
                val itemsJson = res.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<FeedActivityItem>()
                for (i in 0 until itemsJson.length()) {
                    val itm = itemsJson.optJSONObject(i) ?: continue
                    items += FeedActivityItem(
                        id = itm.optString("id", "${itm.optLong("ts")}-$i"),
                        ts = itm.optLong("ts", System.currentTimeMillis()),
                        kind = itm.optString("kind", "background"),
                        title = itm.optString("title"),
                        text = itm.optString("text"),
                        thread = itm.optString("thread"),
                        threadTitle = itm.optString("thread_title"),
                        path = itm.optString("path").takeIf { it.isNotBlank() },
                        quiet = itm.optBoolean("quiet", false),
                    )
                }
                _activities.value = items
                if (items.isNotEmpty()) {
                    AppLogger.info(TAG, "feed.activity sync from ${target.name}: got ${items.size} activities")
                    anySuccess = true
                }
            }.onFailure {
                AppLogger.warning(TAG, "feed.activity sync failed: ${it.message}")
            }
        } catch (e: Exception) {
            _lastSyncError.value = e.message
            AppLogger.warning(TAG, "feed sync general error: ${e.message}")
        } finally {
            _isSyncing.value = false
        }

        if (userInitiated) {
            withContext(Dispatchers.Main) {
                if (anySuccess) {
                    val count = FeedStore.posts.value.size
                    android.widget.Toast.makeText(app, "动态同步完成 (当前共 $count 条)", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val msg = _lastSyncError.value ?: "未获取到新动态"
                    android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        anySuccess
    }

    /**
     * Responds to incoming feed.posts call from another device
     */
    fun handleLocalPostsCall(): JSONObject {
        val root = JSONObject()
        val arr = JSONArray()
        for (post in FeedStore.posts.value) {
            val o = JSONObject()
                .put("day", post.day)
                .put("title", post.title)
                .put("type", post.type)
                .put("emoji", post.emoji)
                .put("body", post.body)
                .put("created_at", post.createdAt)
                .put("liked", post.liked)
            val src = JSONArray()
            post.source.forEach { src.put(it) }
            o.put("source", src)
            arr.put(o)
        }
        root.put("posts", arr)
        root.put("instructions", FeedStore.preferences.value)
        return root
    }
}
