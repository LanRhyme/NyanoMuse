package io.github.nanomuse.feed

import android.content.Context
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The feed on disk: `minis-global/nanomuse/feed/<day>/<NN>.md` posts and the one-paragraph
 * `feed-preferences.md` that steers them. Lives for the process; every write refreshes [posts].
 */
object FeedStore {
    private const val TAG = "Feed"
    private const val MAX_POSTS_PER_DAY = 12
    private const val KEEP_DAYS = 30

    private lateinit var root: File
    private lateinit var prefsFile: File
    private lateinit var appContext: Context

    private val _posts = MutableStateFlow<List<FeedPost>>(emptyList())
    /** Newest day first, then by index. */
    val posts: StateFlow<List<FeedPost>> = _posts

    private val _preferences = MutableStateFlow("")
    val preferences: StateFlow<String> = _preferences

    fun init(context: Context) {
        appContext = context.applicationContext
        val base = File(context.filesDir, "minis-global/nanomuse")
        root = File(base, "feed").apply { mkdirs() }
        prefsFile = File(base, "feed-preferences.md")
        reload()
    }

    fun reload() {
        val all = root.listFiles()?.filter(FeedPost::isDayDir)
            ?.sortedByDescending { it.name }
            ?.flatMap { day ->
                day.listFiles()?.mapNotNull(FeedPost::parse)?.sortedBy { it.index }.orEmpty()
            }.orEmpty()
        _posts.value = all
        _preferences.value = runCatching { prefsFile.takeIf { it.exists() }?.readText() }.getOrNull()
            ?.trim().orEmpty().ifEmpty { defaultPreferences() }
    }

    fun defaultPreferences(): String = appContext.getString(R.string.nm_feed_default_preferences)

    fun savePreferences(text: String) {
        runCatching {
            prefsFile.parentFile?.mkdirs()
            prefsFile.writeText(text.trim() + "\n")
        }.onFailure { AppLogger.warning(TAG, "preferences not saved: ${it.message}") }
        _preferences.value = text.trim().ifEmpty { defaultPreferences() }
    }

    fun setLiked(post: FeedPost, liked: Boolean) {
        val updated = post.copy(liked = liked)
        runCatching { post.file.writeText(updated.serialize()) }
            .onFailure { AppLogger.warning(TAG, "like not saved: ${it.message}") }
        _posts.value = _posts.value.map { if (it.file == post.file) updated else it }
    }

    fun delete(post: FeedPost) {
        runCatching { post.file.delete() }
        _posts.value = _posts.value.filterNot { it.file == post.file }
    }

    /** Writes a batch of posts for today, continuing today's numbering. Returns what was written. */
    fun append(drafts: List<Draft>, now: Long = System.currentTimeMillis()): List<FeedPost> {
        if (drafts.isEmpty()) return emptyList()
        val day = DAY.format(Date(now))
        val dir = File(root, day).apply { mkdirs() }
        var next = (dir.listFiles()?.mapNotNull { FeedPost.parse(it)?.index }?.maxOrNull() ?: 0) + 1
        val written = mutableListOf<FeedPost>()
        for (d in drafts) {
            if (next > MAX_POSTS_PER_DAY) break
            val file = File(dir, "%02d.md".format(Locale.ROOT, next))
            val post = FeedPost(
                day = day, index = next, title = d.title.trim().take(80), type = d.type, emoji = d.emoji,
                body = d.body.trim(), source = d.source, createdAt = now, liked = false, file = file,
            )
            runCatching { file.writeText(post.serialize()) }
                .onSuccess { written += post; next++ }
                .onFailure { AppLogger.warning(TAG, "post not written: ${it.message}") }
        }
        prune()
        reload()
        AppLogger.info(TAG, "wrote ${written.size} post(s) for $day")
        return written
    }

    private fun prune() {
        val days = root.listFiles()?.filter(FeedPost::isDayDir)?.sortedByDescending { it.name }.orEmpty()
        days.drop(KEEP_DAYS).forEach { it.deleteRecursively() }
    }

    fun hasAnyPost(): Boolean = _posts.value.isNotEmpty()

    fun latestDay(): String? = _posts.value.firstOrNull()?.day

    data class RemotePost(
        val day: String,
        val title: String,
        val type: String = "note",
        val emoji: String = "",
        val body: String = "",
        val source: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val liked: Boolean = false,
    )

    /**
     * Imports posts written by another device (e.g. computer), deduplicating by title per day.
     * Returns the count of newly written posts.
     */
    fun importRemotePosts(remotes: List<RemotePost>): Int {
        if (remotes.isEmpty()) return 0
        var imported = 0
        val byDay = remotes.groupBy { it.day.ifBlank { DAY.format(Date(it.createdAt)) } }
        for ((day, list) in byDay) {
            val dir = File(root, day).apply { mkdirs() }
            val existingPosts = dir.listFiles()?.mapNotNull(FeedPost::parse).orEmpty()
            var nextIndex = (existingPosts.maxOfOrNull { it.index } ?: 0) + 1
            for (r in list) {
                val cleanTitle = r.title.trim().take(80)
                if (existingPosts.any { it.title.trim().equals(cleanTitle, ignoreCase = true) }) {
                    continue
                }
                if (nextIndex > MAX_POSTS_PER_DAY) break
                val file = File(dir, "%02d.md".format(Locale.ROOT, nextIndex))
                val post = FeedPost(
                    day = day,
                    index = nextIndex,
                    title = cleanTitle,
                    type = r.type.ifBlank { "note" },
                    emoji = r.emoji,
                    body = r.body.trim(),
                    source = r.source,
                    createdAt = if (r.createdAt > 0L) r.createdAt else System.currentTimeMillis(),
                    liked = r.liked,
                    file = file,
                )
                runCatching { file.writeText(post.serialize()) }
                    .onSuccess {
                        imported++
                        nextIndex++
                    }
                    .onFailure { AppLogger.warning(TAG, "remote post import failed: ${it.message}") }
            }
        }
        if (imported > 0) {
            prune()
            reload()
            AppLogger.info(TAG, "imported $imported remote post(s)")
        }
        return imported
    }

    data class Draft(
        val title: String,
        val type: String,
        val emoji: String,
        val body: String,
        val source: List<String>,
    )

    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
}
