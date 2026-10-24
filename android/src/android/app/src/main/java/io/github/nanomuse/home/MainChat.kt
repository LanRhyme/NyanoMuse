package io.github.nanomuse.home

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * The one conversation that is the home screen — Muse's "主要聊天". Every other session is a
 * side chat. The id lives in the `nanomuse` prefs; while nothing is persisted yet (fresh install,
 * or every session deleted) a process-lifetime draft id stands in, and the moment that draft is
 * promoted to a database row [onPromoted] pins the real id.
 */
object MainChat {
    private const val PREFS = "nanomuse"
    private const val KEY_SESSION = "main_chat.session"
    private const val KEY_FIRST_CONVERSATION_SESSION = "first_conversation.session"
    const val KEY_LAST_ACTIVE_SESSION = "last_active.session"
    const val KEY_LAST_ACTIVE_TIME = "last_active.time"

    @Volatile private var draft: String? = null

    /** Draft id → real id, for the shell that keeps showing the chat under its draft key. */
    @Volatile private var promotedDraft: Pair<String, String>? = null

    private val _lastPromotedDraft = MutableStateFlow<Pair<String, String>?>(null)
    val lastPromotedDraft: StateFlow<Pair<String, String>?> = _lastPromotedDraft

    private val _sessionId = MutableStateFlow<String?>(null)

    /** The current main chat id (real or draft) once [resolve] has run; null before that. */
    val sessionId: StateFlow<String?> = _sessionId

    fun persisted(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSION, null)

    fun setLastActive(context: Context, id: String) {
        if (isDraftId(id)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ACTIVE_SESSION, id)
            .putLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getLastActive(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_ACTIVE_SESSION, null)

    /**
     * Resolves which session should be displayed when the app launches or restarts:
     * - Mode 2 (New Chat): fresh draft
     * - Mode 3 (Home): designated main chat
     * - Mode 0 (Auto) / Mode 1 (Last Session): latest active conversation across local activity and multi-device sync
     */
    suspend fun resolveLaunchSession(context: Context, chatRepository: ChatRepository): String {
        val mode = com.openminis.app.ui.settings.getAppearancePrefs(context)
            .getInt(com.openminis.app.ui.settings.KEY_LAUNCH_SESSION, 0)

        if (mode == 2) {
            return "__new__${UUID.randomUUID()}"
        }

        if (mode == 3) {
            return resolve(context, chatRepository)
        }

        val hidden = io.github.nanomuse.sync.ConversationSync.hidden.value + io.github.nanomuse.feed.FeedFlow.hidden.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastActiveId = prefs.getString(KEY_LAST_ACTIVE_SESSION, null)
        val lastActiveTime = prefs.getLong(KEY_LAST_ACTIVE_TIME, 0L)
        val lastActiveSession = if (lastActiveId != null && lastActiveId !in hidden) {
            chatRepository.getSession(lastActiveId)
        } else null

        // In Mode 0 (Auto), if user was active within 15 minutes, resume last active session.
        // Otherwise, land on the designated Main Chat.
        val autoThresholdMs = 15L * 60 * 1000
        val isFresh = (System.currentTimeMillis() - lastActiveTime) < autoThresholdMs

        val targetId = when {
            mode == 1 && lastActiveSession != null -> lastActiveSession.id
            mode == 0 && lastActiveSession != null && isFresh -> lastActiveSession.id
            else -> resolve(context, chatRepository)
        }

        setLastActive(context, targetId)
        return targetId
    }

    /**
     * Picks the main chat:
     * 1. The persisted main chat if valid and not a known side chat in [io.github.nanomuse.sync.SyncDatabase].
     * 2. An authentic main conversation synced in [io.github.nanomuse.sync.SyncDatabase].
     * 3. The first-conversation session (if not a side chat).
     * 4. Else an independent fresh draft that promotes to real on first send.
     *
     * It NEVER falls back to hijacking existing side chat sessions!
     */
    suspend fun resolve(context: Context, chatRepository: ChatRepository): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hidden = io.github.nanomuse.sync.ConversationSync.hidden.value + io.github.nanomuse.feed.FeedFlow.hidden.value

        val persistedId = prefs.getString(KEY_SESSION, null)
        if (persistedId != null && persistedId !in hidden) {
            val session = chatRepository.getSession(persistedId)
            if (session != null) {
                // Sanity check: Ensure this session is not a known side chat in SyncDatabase
                val isSideChat = runCatching {
                    io.github.nanomuse.sync.SyncDatabase.get(context).dao().conversations()
                        .any { it.sessionId == persistedId && it.kind == "side" }
                }.getOrDefault(false)

                val currentAccount = io.github.nanomuse.account.AccountData.key(context)
                val realMain = runCatching {
                    io.github.nanomuse.sync.SyncDatabase.get(context).dao().conversations()
                        .firstOrNull { it.kind == "main" && (it.owner == null || it.owner == currentAccount) && it.sessionId !in hidden }
                }.getOrNull()

                if (isSideChat || (realMain != null && realMain.sessionId != persistedId)) {
                    prefs.edit().remove(KEY_SESSION).apply()
                    if (realMain != null && chatRepository.getSession(realMain.sessionId) != null) {
                        prefs.edit().putString(KEY_SESSION, realMain.sessionId).apply()
                        return publish(realMain.sessionId)
                    }
                } else {
                    return publish(persistedId)
                }
            }
        }

        // Check if SyncDatabase already has an authentic main conversation for this account
        val currentAccount = io.github.nanomuse.account.AccountData.key(context)
        val syncedMain = runCatching {
            io.github.nanomuse.sync.SyncDatabase.get(context).dao().conversations()
                .firstOrNull { it.kind == "main" && (it.owner == null || it.owner == currentAccount) && it.sessionId !in hidden }
        }.getOrNull()
        if (syncedMain != null && chatRepository.getSession(syncedMain.sessionId) != null) {
            prefs.edit().putString(KEY_SESSION, syncedMain.sessionId).apply()
            return publish(syncedMain.sessionId)
        }

        // Backward compatibility: first conversation session if it exists and is not a side chat
        prefs.getString(KEY_FIRST_CONVERSATION_SESSION, null)?.let { id ->
            if (!isDraftId(id) && id !in hidden && chatRepository.getSession(id) != null) {
                val isSide = runCatching {
                    io.github.nanomuse.sync.SyncDatabase.get(context).dao().conversations()
                        .any { it.sessionId == id && it.kind == "side" }
                }.getOrDefault(false)
                if (!isSide) {
                    prefs.edit().putString(KEY_SESSION, id).apply()
                    return publish(id)
                }
            }
        }

        // Do NOT hijack listSessions()! Maintain complete independence for main chat.
        val d = draft ?: "__new__${UUID.randomUUID()}".also { draft = it }
        return publish(d)
    }

    /**
     * No home conversation any more (contract C10: the one there was belongs to another
     * account): the home is a fresh draft until the person writes, when [onPromoted] pins it.
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION)
            .remove(KEY_LAST_ACTIVE_SESSION)
            .remove(KEY_LAST_ACTIVE_TIME)
            .apply()
        val d = "__new__${UUID.randomUUID()}"
        draft = d
        promotedDraft = null
        _lastPromotedDraft.value = null
        _sessionId.value = d
    }

    /**
     * Archives current main chat and rotates the home session to a fresh draft.
     * Returns the newly generated draft session id.
     */
    fun archiveAndRotate(context: Context): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION)
            .remove(KEY_FIRST_CONVERSATION_SESSION)
            .remove(KEY_LAST_ACTIVE_SESSION)
            .remove(KEY_LAST_ACTIVE_TIME)
            .apply()
        val d = "__new__${UUID.randomUUID()}"
        draft = d
        promotedDraft = null
        _lastPromotedDraft.value = null
        _sessionId.value = d
        return d
    }

    /**
     * The account changed (contract C12): whatever was resolved is forgotten and the next
     * [resolve] starts from the preferences as they are now — the returning account's own main
     * chat if it was kept, else the newest of its chats, else a draft.
     */
    fun reset() {
        draft = null
        promotedDraft = null
        _lastPromotedDraft.value = null
        _sessionId.value = null
    }

    /** Called by ChatViewModel.ensureSession when a draft gets its database row. */
    fun onPromoted(context: Context, draftId: String, realId: String) {
        setLastActive(context, realId)
        promotedDraft = draftId to realId
        _lastPromotedDraft.value = draftId to realId
        if (draftId != draft && draftId != _sessionId.value) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SESSION, realId).apply()
        draft = null
        _sessionId.value = realId
    }

    /** Make [id] the home conversation (the drawer's "set as main chat"). */
    fun set(context: Context, id: String) {
        if (isDraftId(id)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SESSION, id).apply()
        _sessionId.value = id
        setLastActive(context, id)
    }

    fun isMain(context: Context, id: String): Boolean {
        if (id == _sessionId.value || id == draft) return true
        promotedDraft?.let { (from, to) -> if (id == from && to == _sessionId.value) return true }
        return persisted(context) == id
    }

    fun isDraftId(id: String?): Boolean = id?.startsWith("__new__") == true

    private fun publish(id: String): String {
        _sessionId.value = id
        return id
    }
}
