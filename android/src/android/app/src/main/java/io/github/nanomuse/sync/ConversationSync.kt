package io.github.nanomuse.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.account.AccountData
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.hub.Hub
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The conversations, the same on every device of the account (contract C7). The relay keeps
 * their text; this object decides when the phone talks to it — [SyncEngine] decides what is
 * said. On by default once signed in; Settings → Data controls turns it off (the relay then
 * deletes what it kept).
 *
 * - **Push** at once when the person sends a message ([sent], from the view model's send
 *   path — contract C8), two seconds after any other row is written, a chat renamed or deleted
 *   (the hooks in `ChatRepository`), and when the app goes to the background.
 * - **Pull** when the app comes to the foreground, when the network comes back, on the hub's
 *   `sync` frame (another device pushed), and every minute while in the foreground.
 * - 409 `sync_off` turns the local switch off; 401 stops everything until the next sign-in;
 *   a failure that is not one of those waits longer each time it happens again ([RETRY_MS])
 *   and never more than [RETRY_MAX_MS], so a relay that is down is asked once a minute rather
 *   than on every keystroke. Any call that succeeds starts the wait over.
 * - Offline is not a failure: while the phone has no network a trigger does nothing and the
 *   network callback does the pull when there is one, instead of three table scans and a
 *   socket that fails in a few milliseconds.
 *
 * Contract C9 (0.1.38): every conversation travels, side chats included, unless "Also sync
 * side chats" is turned off ([sideChats], per device, on by default); the first pull after a
 * sign-in asks for the tail
 * and is applied off the main thread, in one write, before this phone's own history goes up.
 * Presence: [sent] tells the other devices a turn started here, [turnEnded] that it ended;
 * their `working` frames land in [working], which the chat shows as "kwai is working…" under
 * the last line that device wrote. [remoteRows] are the rows this phone did not write.
 */
object ConversationSync {
    private const val TAG = "ConversationSync"
    private const val PREFS = "nanomuse_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SIDE_CHATS = "side_chats"
    private const val PUSH_DELAY_MS = 200L
    private const val PULL_EVERY_MS = 15_000L
    /** The turn's last rows are settled in SQLite WAL; reply goes immediately. */
    private const val TURN_END_SETTLE_MS = 200L
    /** Waits after a failed call that is not 401 or `sync_off`, in order; the last one holds. */
    private val RETRY_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000)
    private const val RETRY_MAX_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    /**
     * The same time, two lanes: a push never waits for a pull. A pull of a page takes as long
     * as the relay needs, and a message the person just typed must not sit behind it.
     */
    private val pushLock = Mutex()
    private val pullLock = Mutex()
    @Volatile private var app: Context? = null
    /** The relay refused the key: nothing more until a new sign-in. */
    @Volatile private var halted = false
    @Volatile private var pushJob: Job? = null
    @Volatile private var ticker: Job? = null
    /** The app is in front (between ON_START and ON_STOP). */
    @Volatile private var visible = false
    /** The phone has a network that says it is connected. */
    @Volatile private var online = true
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** How many calls in a row have failed since the last one that went through. */
    private val failures = AtomicInteger(0)
    @Volatile private var retryAt = 0L
    /** Chats this phone said `working: true` for and has not cleared yet. */
    private val workingSent = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _enabled = MutableStateFlow(true)
    /** The switch as this phone knows it (the relay's word wins when it differs). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _sideChats = MutableStateFlow(true)
    /** "Also sync side chats" (C9): this phone's setting, on by default. */
    val sideChats: StateFlow<Boolean> = _sideChats.asStateFlow()

    private val _captions = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Local message row id → the name of the device it was written on, for the bubble's "From Pixel 8" (C8: per message, never per chat). */
    val captions: StateFlow<Map<String, String>> = _captions.asStateFlow()

    private val _remoteRows = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Local row id → the device that wrote it, for rows another device wrote (C9): never this phone's unfinished turn, never resumed from here. */
    val remoteRows: StateFlow<Map<String, String>> = _remoteRows.asStateFlow()

    private val _working = MutableStateFlow<Map<String, WorkingPresence>>(emptyMap())
    /** Local chat id → the device working on it right now (C9), while its `at` is under ten minutes old. */
    val working: StateFlow<Map<String, WorkingPresence>> = _working.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Local chat ids that are not the signed-in account's: synced under another account
     * (contract C10) or owned by another account or by nobody (contract C12,
     * [io.github.nanomuse.account.AccountData]) — on the phone, kept, but not in the list, not
     * pushed, not the home. Signed out, the chats made while signed out show and every
     * account's are hidden. `ChatRepository.observeSessions()` leaves these out, so the drawer,
     * the search and the "working" line follow.
     */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _pulled = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    /** Chats that just got rows from another device; an open chat reloads itself. */
    val pulled: SharedFlow<Set<String>> = _pulled.asSharedFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** App start: the switch, the lifecycle, the network, the captions the bubbles show. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        _enabled.value = prefs(ctx).getBoolean(KEY_ENABLED, true)
        _sideChats.value = prefs(ctx).getBoolean(KEY_SIDE_CHATS, true)
        online = hasNetwork(ctx)
        watchNetwork(ctx)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        visible = true
                        syncSoon(ctx)
                        startTicker(ctx)
                    }
                    Lifecycle.Event.ON_STOP -> {
                        visible = false
                        ticker?.cancel()
                        ticker = null
                        pushNow(ctx)
                    }
                    else -> {}
                }
            },
        )
        scope.launch {
            lock.withLock {
                runCatching {
                    val store = RoomSyncStore(ctx)
                    val account = accountKey(ctx)
                    _hidden.value = SyncEngine.hidden(store, account) + AccountData.reconcile(ctx)
                    _captions.value = SyncEngine.captions(store, Hub.deviceId(ctx), account)
                    _remoteRows.value = SyncEngine.remoteRows(store, Hub.deviceId(ctx), account)
                    val chatRepo = (ctx.applicationContext as? MinisApp)?.chatRepository
                    if (chatRepo != null) {
                        val allSessions = chatRepo.dao.listSessions()
                        for (s in allSessions) {
                            val t = s.title.orEmpty().trim()
                            if (t.startsWith("动态 · ") || t.startsWith("Feed · ") || (t == "动态" && s.source == "sync") || (t == "Feed" && s.source == "sync")) {
                                chatRepo.dao.deleteMessages(s.id)
                                chatRepo.dao.deleteSession(s.id)
                                store.removeMessagesOf(s.id)
                                store.removeConversation(s.id)
                                AppLogger.info(TAG, "Purged internal batch sync session: ${s.id} ($t)")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The account the ids are scoped to: the relay's id for it (`/v1/me` → `account.id`), or the
     * key itself on a relay that gives none ([AccountData.key]); null while signed out.
     */
    private fun accountKey(ctx: Context): String? = AccountData.key(ctx).takeIf { it.isNotEmpty() }

    /**
     * Runs [block] while no push or pull can: the place to take an account's chats out of the
     * database (contract C12), so the engine never reads the gap as a deletion.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        pushJob?.cancel()
        pushLock.withLock {
            pullLock.withLock {
                return lock.withLock { block() }
            }
        }
    }

    /** Every chat has an owner and the hidden set is current (C12); after a sign-in, a sign-out, a restore. */
    suspend fun reconcileOwners(context: Context) {
        val ctx = context.applicationContext
        runCatching {
            val store = RoomSyncStore(ctx)
            _hidden.value = SyncEngine.hidden(store, accountKey(ctx)) + AccountData.reconcile(ctx)
        }.onFailure { AppLogger.warning(TAG, "owners: ${it.message}") }
    }

    /** The signed-in account is not the one the last sync ran for (C10): nothing of the old one is shown as the new one's. */
    private fun accountChanged() {
        _working.value = emptyMap()
        workingSent.clear()
        _captions.value = emptyMap()
        _remoteRows.value = emptyMap()
        // the hub's device list went with Hub.restart() at sign-in; the cursor and the home
        // conversation are the engine's (SyncEngine.ensureAccount)
    }

    /** Signed in, and the switch is on, and the relay has not refused the key. */
    fun active(context: Context): Boolean = !halted && _enabled.value && NanoMuseCloud.isSignedIn(context)

    // ── triggers ──────────────────────────────────────────────────────────

    /** A row was written, a chat renamed or deleted: a push in a moment (the hooks in ChatRepository). */
    fun changed() {
        val ctx = app ?: return
        if (!active(ctx)) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            push(ctx)
        }
    }

    /** The app went to the background: what is unsaid goes now. */
    fun pushNow(context: Context) {
        val ctx = context.applicationContext
        if (!active(ctx)) return
        pushJob?.cancel()
        scope.launch {
            push(ctx)
            memorySoon(ctx)
        }
    }

    /**
     * The person sent a message (its row is written) in chat [sessionId]: it reaches the other
     * devices now, not at the end of the turn (contract C8), and right behind it goes
     * `working: true` (C9), so their chats say this phone is on it. The reply still goes with
     * the turn's last row, through [changed] — [Transcript] holds it back until the turn is
     * finished — and [turnEnded] clears the presence.
     */
    fun sent(sessionId: String? = null) {
        val ctx = app ?: return
        if (!active(ctx)) return
        pushJob?.cancel()
        scope.launch {
            withEngine(ctx) {
                it.push()
                if (sessionId != null && runCatching { it.working(sessionId, true) }.getOrDefault(false)) workingSent += sessionId
            }
        }
    }

    /**
     * The turn in [sessionId] is over (the stream flag dropped): the reply goes up, then
     * `working: false`. Nothing happens for a chat this phone never said `true` for.
     */
    fun turnEnded(sessionId: String) {
        val ctx = app ?: return
        if (!workingSent.remove(sessionId)) return
        pushJob?.cancel()
        scope.launch {
            delay(TURN_END_SETTLE_MS)
            withEngine(ctx) {
                it.push()
                runCatching { it.working(sessionId, false) }
            }
            // a turn often ends with a memory written (`memory_write`): it travels on the same beat
            memorySoon(ctx)
        }
    }

    fun pullSoon(context: Context) {
        val ctx = context.applicationContext
        if (!active(ctx)) return
        scope.launch { pull(ctx) }
    }

    /** Launch and foreground: the others' changes first, then ours; who is working, from the relay's memory. */
    fun syncSoon(context: Context) {
        val ctx = context.applicationContext
        if (!active(ctx)) return
        scope.launch {
            val pulled = pull(ctx)
            withEngine(ctx) {
                it.push()
                runCatching { seedPresence(it, it.state().working) }
            }
            if (pulled) memorySoon(ctx)
        }
    }

    /**
     * Memory changes with the account (C15), on the same beats as a conversation sync. Best
     * effort: a failure leaves the ops queued for the next beat, and it never blocks a chat.
     */
    private fun memorySoon(ctx: Context) {
        if (!active(ctx) || !online || System.currentTimeMillis() < retryAt) return
        scope.launch {
            runCatching {
                val token = NanoMuseCloud.apiKey(ctx) ?: return@runCatching
                val api = RelaySyncApi(NanoMuseCloud.baseUrl(ctx), token, "nanoMuse-Android/${BuildConfig.VERSION_NAME}")
                val db = io.github.nanomuse.memory.LocalMemoryDb.getInstance(ctx)
                io.github.nanomuse.memory.MemorySync(db) { AppLogger.debug(TAG, it) }.run(api, Hub.deviceId(ctx))
            }.onFailure { AppLogger.warning(TAG, "memory sync failed: ${it.message}") }
        }
    }

    /** The hub's `{"type": "sync", "what": "conversations", "from": …}`: another device pushed. */
    fun onFrame(context: Context, frame: JSONObject) {
        if (frame.optString("from") == Hub.deviceId(context)) return // our own write coming back
        if (frame.has("what") && frame.optString("what") != "conversations") {
            if (frame.optString("what") == "feed") {
                io.github.nanomuse.feed.FeedSync.triggerSync(context)
            }
            return
        }
        pullSoon(context)
    }

    /**
     * The hub's `{"type": "working", "cid", "from", "device_name", "working", "at"}` (C9): a
     * turn started or ended on another device. Mapped to the local chat and kept while it is
     * fresh; a `false` from the device that said `true` clears it.
     */
    fun onWorkingFrame(context: Context, frame: JSONObject) {
        val ctx = context.applicationContext
        val from = frame.optString("from")
        if (from.isBlank() || from == Hub.deviceId(ctx)) return
        val cid = frame.optString("cid").takeIf { it.isNotBlank() } ?: return
        val on = frame.optBoolean("working", true)
        val presence = if (on) SyncJson.working(frame) else null
        scope.launch {
            val sessionId = runCatching { RoomSyncStore(ctx).conversationByCid(cid)?.sessionId }.getOrNull() ?: return@launch
            if (sessionId in _hidden.value) return@launch // C10: another account's chat is not on this screen
            _working.update { map ->
                val cur = map[sessionId]
                when {
                    presence != null -> map + (sessionId to presence)
                    cur != null && cur.from == from -> map - sessionId
                    else -> map
                }
            }
        }
    }

    /** `GET /v1/sync/state` lists who is working: what the hub said while this phone was away. */
    private suspend fun seedPresence(e: SyncEngine, list: List<WorkingPresence>) {
        val me = app?.let { Hub.deviceId(it) }
        val now = System.currentTimeMillis() / 1000
        val fresh = HashMap<String, WorkingPresence>()
        for (p in list) {
            if (p.from == me || !p.live(now)) continue
            val sessionId = e.sessionOf(p.cid) ?: continue
            fresh[sessionId] = p
        }
        _working.update { map -> map.filterValues { it.live(now) } + fresh }
    }

    /**
     * A new key (sign-in): the stop after a 401 is over; the account's chats come down — the
     * tail first (C9), applied here on the IO dispatcher and shown as soon as it is written;
     * this phone's own history goes up only after that, and never holds the screen.
     */
    fun signedIn(context: Context) {
        halted = false
        failures.set(0)
        retryAt = 0
        val ctx = context.applicationContext
        scope.launch {
            run(ctx) {
                // C10: the list is this account's from the first frame — another account's chats
                // go out of sight before anything is pulled, and the home follows
                it.accountSignedIn()
                _hidden.value = it.hidden() + AccountData.reconcile(ctx)
                val state = runCatching { it.state() }.getOrNull()
                if (state != null) setLocalEnabled(ctx, state.enabled)
                if (_enabled.value) applyPull(it.pull())
                if (state != null) seedPresence(it, state.working)
            }
            // the history goes up on the push lane: a tail page never holds it
            if (_enabled.value) {
                push(ctx)
                memorySoon(ctx)
            }
        }
    }

    /**
     * Signed out: the switch on, side chats off, nothing shown as anyone's. The ids and the
     * cursor stay for an account that was kept (C10, C12): the same account signing in again
     * goes on where it was, another one starts its own cursor and sees only its own. The
     * hidden set becomes the signed-out one — every account's chats out of sight, the phone's
     * own in.
     */
    fun forget(context: Context) {
        val ctx = context.applicationContext
        halted = false
        pushJob?.cancel()
        prefs(ctx).edit().remove(KEY_ENABLED).remove(KEY_SIDE_CHATS).apply()
        _enabled.value = true
        _sideChats.value = false
        _captions.value = emptyMap()
        _remoteRows.value = emptyMap()
        _working.value = emptyMap()
        workingSent.clear()
        scope.launch { lock.withLock { reconcileOwners(ctx) } }
    }

    private fun startTicker(ctx: Context) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(PULL_EVERY_MS)
                if (active(ctx) && pull(ctx)) memorySoon(ctx)
            }
        }
    }

    // ── the switch and the delete (Data controls) ─────────────────────────

    /** The relay's view of the switch; also brings the local one in line. Throws [SyncException]. */
    suspend fun refreshState(context: Context): SyncState? = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val e = engine(ctx) ?: return@withContext null
        val state = lock.withLock { e.state() }
        setLocalEnabled(ctx, state.enabled)
        state
    }

    /** Off: the relay deletes everything of the account. On: this phone's chats go out again. Throws [SyncException]. */
    suspend fun setEnabled(context: Context, on: Boolean) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val e = engine(ctx) ?: throw SyncException(401, "bad_key", "Not signed in")
        pushJob?.cancel()
        lock.withLock { e.setEnabled(on) }
        setLocalEnabled(ctx, on)
        if (on) {
            halted = false
            syncSoon(ctx)
        }
    }

    /**
     * "Also sync side chats" (C9), this phone only. On: one pull from zero over the whole
     * scope, then the side chats go up, oldest first. Off: side chats stop going either way;
     * what was synced stays where it is.
     */
    fun setSideChats(context: Context, on: Boolean) {
        val ctx = context.applicationContext
        prefs(ctx).edit().putBoolean(KEY_SIDE_CHATS, on).apply()
        _sideChats.value = on
        if (!on || !active(ctx)) return
        pushJob?.cancel()
        scope.launch {
            // the cursor starts over, so the pull is the one that brings the whole scope down
            withEngine(ctx) { it.sideChatsTurnedOn() }
            pull(ctx)
            push(ctx)
        }
    }

    /** "Delete synced conversations": the relay's store goes; the switch and every device's chats stay. Throws [SyncException]. */
    suspend fun deleteSynced(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val e = engine(ctx) ?: throw SyncException(401, "bad_key", "Not signed in")
        lock.withLock { e.wipe() }
    }

    private fun setLocalEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun engine(ctx: Context): SyncEngine? {
        val repo = (ctx.applicationContext as? MinisApp)?.chatRepositoryOrNull ?: return null
        val token = NanoMuseCloud.apiKey(ctx) ?: return null
        // the ids are per account: the relay's id for it, or the key itself on a relay that gives none
        val account = accountKey(ctx) ?: return null
        return SyncEngine(
            store = RoomSyncStore(ctx),
            chats = RoomChats(ctx, repo),
            api = RelaySyncApi(NanoMuseCloud.baseUrl(ctx), token, "nanoMuse-Android/${BuildConfig.VERSION_NAME}"),
            deviceId = Hub.deviceId(ctx),
            account = account,
            sideChats = { _sideChats.value },
            onAccountChanged = { accountChanged() },
            log = { AppLogger.info(TAG, it) },
        )
    }

    /** A call with the engine that is not a page: no lane, no retry. */
    private suspend fun <T> withEngine(ctx: Context, block: suspend (SyncEngine) -> T): T? {
        val e = engine(ctx) ?: return null
        return try {
            lock.withLock { block(e) }
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            AppLogger.warning(TAG, "sync: ${x.message}")
            null
        }
    }

    /**
     * One call with the engine, on the push lane: a push never waits for a pull. Returns what
     * [block] returned, or null when it did not go through (or had nothing to do).
     */
    private suspend fun <T> run(ctx: Context, block: suspend (SyncEngine) -> T): T? =
        call(ctx, pushLock, block)

    /**
     * One pull, on its own lane; true when the relay was reached and the page applied. The
     * answer `push()` needs before it sends anything (adoption of the account's main chat)
     * comes from here, so it is the one call the two lanes share.
     */
    private suspend fun pull(ctx: Context): Boolean = call(ctx, pullLock) { applyPull(it.pull()) } != null

    /** A push: nothing while the phone is offline, and never on the back of a pull. */
    private suspend fun push(ctx: Context) {
        call(ctx, pushLock) { e ->
            // a phone that never pulled this account has no main chat to adopt: pull first, or
            // seed a second main chat, which the relay would refuse and this phone would rehome
            if (e.cursor() == null) adoptPull(ctx)
            e.push()
        }
    }

    /**
     * The account's newest rows and its main chat, pulled before a push that finds no cursor:
     * a fresh install must adopt the relay's main chat rather than mint a second one. Nothing
     * while a pull is already running, since that is what it is doing.
     */
    private suspend fun adoptPull(ctx: Context) {
        if (pullLock.isLocked) return
        call(ctx, pullLock) { applyPull(it.pull()) }
    }

    private suspend fun <T> call(ctx: Context, lane: Mutex, block: suspend (SyncEngine) -> T): T? {
        val e = engine(ctx) ?: return null
        return lane.withLock {
            // a failure a moment ago is not asked again this instant
            if (!online || System.currentTimeMillis() < retryAt) return null
            try {
                val out = lock.withLock { block(e) }
                failures.set(0)
                retryAt = 0
                out
            } catch (x: CancellationException) {
                throw x
            } catch (x: SyncException) {
                when {
                    x.status == 401 -> {
                        halted = true
                        AppLogger.info(TAG, "the relay refused the key; sync waits for the next sign-in")
                    }
                    x.status == 409 && x.code == "sync_off" -> {
                        setLocalEnabled(ctx, false)
                        AppLogger.info(TAG, "sync is off for the account")
                    }
                    else -> {
                        AppLogger.info(TAG, "relay: ${x.status} ${x.code}")
                        backOff(x)
                    }
                }
                null
            } catch (x: Exception) {
                AppLogger.warning(TAG, "sync: ${x.message}")
                backOff(null)
                null
            } finally {
                runCatching {
                    _hidden.value = e.hidden() + AccountData.reconcile(ctx)
                    _captions.value = e.captions()
                    _remoteRows.value = e.remoteRows()
                }
            }
        }
    }

    /**
     * A call did not go through: the next one waits longer. Offline is the one case with no
     * wait — the network callback is the trigger then, and it is the right one.
     */
    private fun backOff(x: SyncException?) {
        if (x != null && x.status == 0) {
            online = false
            return
        }
        val n = failures.incrementAndGet()
        val wait = RETRY_MS[minOf(n, RETRY_MS.size) - 1].coerceAtMost(RETRY_MAX_MS)
        retryAt = System.currentTimeMillis() + wait
    }

    /** Whether the phone has a network that reports a connection (any transport). */
    private fun hasNetwork(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (x: Exception) {
        AppLogger.warning(TAG, "network state: ${x.message}")
        true // unknown: let the calls themselves find out
    }

    /**
     * The network comes and goes. Back: the wait is over and a pull goes as soon as there is
     * one, without waiting for the next trigger. Gone: pushes stop until it returns, instead
     * of each one spending a socket timeout to learn it.
     */
    private fun watchNetwork(ctx: Context) {
        if (networkCallback != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = cameBack()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                cameBack()
            }

            override fun onLost(network: Network) {
                // another network may still be up (Wi-Fi dropping to mobile); ask
                if (!hasNetwork(ctx)) online = false
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { AppLogger.warning(TAG, "network watch: ${it.message}") }
    }

    private fun cameBack() {
        val wasOffline = !online
        online = true
        if (!wasOffline) return // a capability change on a network that was already there
        failures.set(0)
        retryAt = 0
        val ctx = app ?: return
        if (!visible) return // in the background: ON_START's own sync brings it up to date
        pullSoon(ctx)
    }

    private fun applyPull(r: PullResult) {
        if (r.applied > 0) AppLogger.info(TAG, "pulled ${r.applied} change(s) up to ${r.cursor}" + if (r.skipped > 0) " (${r.skipped} older left on the relay)" else "")
        // the reply arrived: whoever was working on that chat is done (C9)
        if (r.replied.isNotEmpty()) _working.update { map -> map - r.replied }
        if (r.touchedSessions.isNotEmpty()) _pulled.tryEmit(r.touchedSessions)
    }

}
