package io.github.nanomuse.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** The mapping store as a few maps. */
class MemorySyncStore : SyncStore {
    val meta = HashMap<String, String>()
    val convs = LinkedHashMap<String, SyncConversation>()
    val msgs = LinkedHashMap<String, SyncMessage>()

    /** The page, as one write: anything thrown inside puts the three maps back as they were. */
    override fun <T> batch(block: suspend () -> T): T {
        val m = HashMap(meta)
        val c = LinkedHashMap(convs)
        val g = LinkedHashMap(msgs)
        return try {
            runBlocking { block() }
        } catch (e: Throwable) {
            meta.clear(); meta.putAll(m)
            convs.clear(); convs.putAll(c)
            msgs.clear(); msgs.putAll(g)
            throw e
        }
    }

    override suspend fun meta(key: String) = meta[key]
    override suspend fun putMeta(key: String, value: String?) {
        if (value == null) meta.remove(key) else meta[key] = value
    }
    override suspend fun conversations() = convs.values.toList()
    override suspend fun conversation(sessionId: String) = convs[sessionId]
    override suspend fun conversationByCid(cid: String) = convs.values.firstOrNull { it.cid == cid }
    override suspend fun putConversation(c: SyncConversation) {
        convs[c.sessionId] = c
    }
    override suspend fun removeConversation(sessionId: String) {
        convs.remove(sessionId)
    }
    override suspend fun messages(sessionId: String) = msgs.values.filter { it.sessionId == sessionId }
    override suspend fun messageByMid(mid: String) = msgs.values.firstOrNull { it.mid == mid }
    override suspend fun pulledMessages() = msgs.values.filter { it.device.isNotBlank() }
    override suspend fun putMessages(list: List<SyncMessage>) {
        for (m in list) msgs[m.messageId] = m
    }
    override suspend fun removeMessages(messageIds: List<String>) {
        messageIds.forEach { msgs.remove(it) }
    }
    override suspend fun removeMessagesOf(sessionId: String) {
        msgs.values.filter { it.sessionId == sessionId }.forEach { msgs.remove(it.messageId) }
    }
    override suspend fun markAllUnpushed() {
        for ((k, v) in msgs.toMap()) msgs[k] = v.copy(pushed = false)
        for ((k, v) in convs.toMap()) convs[k] = v.copy(pushed = false)
    }
    override suspend fun clear() {
        meta.clear(); convs.clear(); msgs.clear()
    }
}

/** The phone's chats as lists, with the same parts JSON shapes OpenMinis writes. */
class MemoryChats : LocalChats {
    class Session(val id: String, var title: String?, val createdAt: Long, var updatedAt: Long)

    val sessions = LinkedHashMap<String, Session>()
    val rows = LinkedHashMap<String, MutableList<LocalMessage>>()
    /** What a chat showed before its rows (the first conversation's opening), per session id. */
    val preludes = HashMap<String, List<TranscriptItem>>()
    var main: String? = null
    private var seq = 0
    val attachmentLine: (String) -> String = { "[Attachment $it]" }

    fun addSession(id: String, title: String? = null, createdAt: Long = 1_000_000L): Session =
        Session(id, title, createdAt, createdAt).also { sessions[id] = it; rows[id] = mutableListOf() }

    fun user(sessionId: String, text: String, at: Long = next()): String = add(sessionId, "user", textParts(text), at)
    fun assistant(sessionId: String, text: String, at: Long = next()): String = add(sessionId, "assistant", textParts(text), at)
    fun assistantTool(sessionId: String, text: String, at: Long = next()): String = add(
        sessionId, "assistant",
        JSONArray().apply {
            if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("value", text))
            put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "t1").put("name", "shell_execute").put("input", "{}")))
        }.toString(),
        at,
    )
    fun assistantQuestion(sessionId: String, text: String, questionArgs: String, at: Long = next()): String = add(
        sessionId, "assistant",
        JSONArray().apply {
            if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("value", text))
            put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "t_q").put("name", "ask_user_question").put("input", questionArgs)))
        }.toString(),
        at,
    )
    fun toolResult(sessionId: String, at: Long = next()): String = add(
        sessionId, "user",
        JSONArray().put(JSONObject().put("type", "toolResult").put("value", JSONObject().put("toolUseId", "t1").put("output", "ok").put("success", true))).toString(),
        at,
    )
    fun questionResult(sessionId: String, selectedAnswer: String, at: Long = next()): String = add(
        sessionId, "user",
        JSONArray().put(JSONObject().put("type", "toolResult").put("value", JSONObject().put("toolUseId", "t_q").put("name", "ask_user_question").put("output", "User selected: $selectedAnswer").put("success", true))).toString(),
        at,
    )
    fun userWithPicture(sessionId: String, text: String, file: String, at: Long = next()): String = add(
        sessionId, "user",
        JSONArray().apply {
            put(JSONObject().put("type", "text").put("value", text))
            put(JSONObject().put("type", "mediaRef").put("value", JSONObject().put("id", "m").put("relativePath", "a/$file").put("mimeType", "image/png")))
        }.toString(),
        at,
    )

    fun removeRow(id: String) {
        rows.values.forEach { list -> list.removeAll { it.id == id } }
    }

    fun next(): Long = 1_700_000_000_000L + (++seq) * 1000L

    private fun textParts(text: String) = JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString()

    private fun add(sessionId: String, role: String, parts: String, at: Long): String {
        val id = "row-${++seq}"
        rows.getValue(sessionId) += LocalMessage(id, sessionId, role, parts, at)
        sessions.getValue(sessionId).updatedAt = maxOf(sessions.getValue(sessionId).updatedAt, at)
        return id
    }

    override suspend fun sessions() = sessions.values.map { LocalSession(it.id, it.title?.takeIf { t -> t.isNotBlank() }, it.createdAt, it.updatedAt) }
    override suspend fun session(id: String) = sessions[id]?.let { LocalSession(it.id, it.title?.takeIf { t -> t.isNotBlank() }, it.createdAt, it.updatedAt) }
    override suspend fun messages(sessionId: String) = rows[sessionId]?.sortedBy { it.createdAt }?.toList() ?: emptyList()
    override suspend fun mainSessionId() = main?.takeIf { it in sessions }
    override suspend fun setMainSession(id: String?) {
        main = id
    }
    override suspend fun createSession(title: String?, createdAt: Long, updatedAt: Long, main: Boolean): String {
        val id = "local-${++seq}"
        sessions[id] = Session(id, title, createdAt, updatedAt)
        rows[id] = mutableListOf()
        if (main) this.main = id
        return id
    }
    override suspend fun renameSession(id: String, title: String?, updatedAt: Long) {
        sessions.getValue(id).title = title
        sessions.getValue(id).updatedAt = updatedAt
    }
    override suspend fun deleteSession(id: String) {
        sessions.remove(id); rows.remove(id)
    }
    override suspend fun clearMessages(sessionId: String) {
        rows[sessionId]?.clear()
    }
    override suspend fun insertMessage(sessionId: String, role: String, text: String, attachments: List<Attachment>, createdAt: Long): String {
        val id = "pulled-${++seq}"
        rows.getValue(sessionId) += LocalMessage(id, sessionId, role, Transcript.partsJson(text, attachments.map { attachmentLine(it.name) }), createdAt)
        return id
    }
    override suspend fun deleteMessage(id: String) = removeRow(id)
    override suspend fun prelude(sessionId: String): List<TranscriptItem> = preludes[sessionId].orEmpty()
}

/**
 * The relay of contract C7 in memory: one seq counter, idempotent mids, the newer title per
 * cid, one `main` per account (the older wins, the other is told `main_exists`), tombstones,
 * `sync_off` as 409.
 */
class FakeRelay(private val names: Map<String, String> = emptyMap()) : SyncApi {
    class Conv(val cid: String, val kind: String, var title: String?, val device: String, val createdAt: Long, var updatedAt: Long, var deleted: Boolean, var seq: Long)
    class Msg(val mid: String, val cid: String, val device: String, val role: String, var text: String, val attachments: List<Attachment>, val createdAt: Long, var deleted: Boolean, var seq: Long)

    var enabled = true
    var seq = 0L
    val convs = LinkedHashMap<String, Conv>()
    val msgs = LinkedHashMap<String, Msg>()
    val deletes = mutableListOf<String>()
    var wipes = 0
    var pushes = 0
    val pushedBatches = mutableListOf<Int>()
    /** Set to make every call fail with this. */
    var failWith: SyncException? = null
    /** Set to make only `push` fail, for a while: count of pushes that still go through. */
    var pushesToSucceed: Int = Int.MAX_VALUE
    /** The account is at its side-chat limit: a new side conversation is refused `conversation_limit`. */
    var full = false

    private fun check() {
        failWith?.let { throw it }
    }

    override fun state(): SyncState {
        check()
        return SyncState(enabled, seq, convs.size, msgs.size, working = workingNow.toList())
    }

    override fun setEnabled(on: Boolean): SyncState {
        check()
        enabled = on
        if (!on) { convs.clear(); msgs.clear() }
        return state()
    }

    /** Every `changes` call as (since, scope, tail), to check what the engine asked for. */
    val changeCalls = mutableListOf<Triple<Long, String, Int>>()
    /** Where the account's counter stands: what a pull that read the lot ends on. */
    val cursorNow: Long get() = seq
    /** Every `working` call as (device, cid, working). */
    val workingCalls = mutableListOf<Triple<String, String, Boolean>>()
    /** What `state()` reports under `working` (another device's presence). */
    val workingNow = mutableListOf<WorkingPresence>()

    /** A relay from before C9 that sends everything whatever the scope says. */
    var ignoreScope = false

    override fun changes(since: Long, limit: Int, scope: String, tail: Int): Changes {
        check()
        if (!enabled) throw SyncException(409, "sync_off", "sync is off")
        changeCalls += Triple(since, scope, tail)
        if (since == 0L && tail > 0) {
            // C9 tail: the newest `tail` live messages and the rows of their conversations; the
            // rest is reported as skipped, and `cursor` is where the account stands — the pull
            // reports what it applied, the post state what was in the store
            val mainOnly = scope == SyncApi.SCOPE_MAIN && !ignoreScope
            val live = msgs.values.filter { !it.deleted && (!mainOnly || convs[it.cid]?.kind == "main") }.sortedBy { it.seq }
            val kept = live.takeLast(tail)
            val parents = kept.map { it.cid }.toSet()
            val rows = convs.values.filter { it.cid in parents || (!mainOnly && it.kind == "main") }.sortedBy { it.seq }
            return Changes(
                cursor = seq, more = false, skipped = live.size - kept.size,
                conversations = rows.map { it.remote() },
                messages = kept.map { it.remote() },
            )
        }
        val mainOnly = scope == SyncApi.SCOPE_MAIN && !ignoreScope
        val inScope: (String) -> Boolean = { cid -> !mainOnly || convs[cid]?.kind == "main" }
        // a cursor beyond the counter reads as a fresh device, as the relay's does; so does 0,
        // because a device that restarts its cursor asks for the whole store from the start
        val from = if (since > seq) 0L else since
        val cs = convs.values.filter { it.seq > from && inScope(it.cid) }.sortedBy { it.seq }
        val ms = msgs.values.filter { it.seq > from && inScope(it.cid) }.sortedBy { it.seq }
        // a limit of 0 is no limit, as at the relay; and the page holds rows, so 500 is 500
        // of them, conversation lines included
        val cap = if (limit > 0) limit else Int.MAX_VALUE
        val cutSeq = (cs.map { it.seq } + ms.map { it.seq }).sorted()
            .let { if (it.size > cap) it[cap - 1] else it.lastOrNull() ?: from }
        return Changes(
            cursor = cutSeq,
            more = cs.any { it.seq > cutSeq } || ms.any { it.seq > cutSeq },
            conversations = cs.filter { it.seq <= cutSeq }.map { it.remote() },
            messages = ms.filter { it.seq <= cutSeq }.map { it.remote() },
        )
    }

    private fun Conv.remote() = RemoteConversation(cid, kind, title, device, names[device] ?: device, createdAt, updatedAt, deleted, seq)
    private fun Msg.remote() = RemoteMessage(mid, cid, seq, device, role, text, text.length > 16_384, attachments, createdAt, deleted, names[device] ?: "")

    override fun working(device: String, cid: String, working: Boolean) {
        check()
        workingCalls += Triple(device, cid, working)
    }

    override fun push(device: String, conversations: List<OutConversation>, messages: List<OutMessage>): PushResult {
        check()
        if (!enabled) throw SyncException(409, "sync_off", "sync is off")
        if (messages.size > 200) throw SyncException(413, "http_413", "too many")
        // a batch that fails takes the rest of the push with it, as the real one does
        if (pushesToSucceed <= 0) throw SyncException(0, "unreachable", "unreachable")
        if (messages.isNotEmpty()) pushesToSucceed--
        pushes++
        pushedBatches += messages.size
        var accepted = 0
        val rejected = mutableListOf<Rejection>()
        for (c in conversations) {
            val known = convs[c.cid]
            if (known == null) {
                if (c.kind == "main") {
                    val main = convs.values.firstOrNull { it.kind == "main" && !it.deleted }
                    if (main != null) {
                        rejected += Rejection(null, c.cid, "main_exists", main.cid)
                        continue
                    }
                }
                if (full && c.kind == "side" && !c.deleted) {
                    rejected += Rejection(null, c.cid, "conversation_limit", null)
                    continue
                }
                convs[c.cid] = Conv(c.cid, c.kind, c.title, device, c.createdAt, c.updatedAt, c.deleted, ++seq)
                accepted++
            } else if (c.updatedAt >= known.updatedAt || c.deleted) {
                known.title = c.title; known.updatedAt = c.updatedAt; known.deleted = known.deleted || c.deleted; known.seq = ++seq
                accepted++
            }
        }
        for (m in messages) {
            val known = msgs[m.mid]
            if (known != null) {
                if (m.deleted && !known.deleted) { known.deleted = true; known.text = ""; known.seq = ++seq; accepted++ }
                continue
            }
            if (m.deleted) continue
            if (m.cid !in convs) { rejected += Rejection(m.mid, null, "unknown_cid", null); continue }
            if (m.role != "user" && m.role != "assistant") { rejected += Rejection(m.mid, null, "bad_role", null); continue }
            msgs[m.mid] = Msg(m.mid, m.cid, device, m.role, m.text.take(16_384), m.attachments, m.createdAt, false, ++seq)
            accepted++
        }
        return PushResult(seq, accepted, rejected)
    }

    override fun deleteConversation(cid: String) {
        check()
        deletes += cid
        convs[cid]?.let { it.deleted = true; it.seq = ++seq }
        msgs.values.filter { it.cid == cid }.forEach { it.deleted = true; it.text = "" }
    }

    override fun wipe() {
        check()
        wipes++
        convs.clear(); msgs.clear(); memOps.clear()
    }

    // ── C15: the memory oplog, with the same idempotent ids and one seq counter ──

    class MemOp(val opId: String, val opType: String, val clientTime: Long, val payload: JSONObject, val seq: Long)

    val memOps = LinkedHashMap<String, MemOp>()
    /** How many ops each push carried. */
    val memPushes = mutableListOf<Int>()

    override fun pushMemory(device: String, ops: List<OutMemoryOp>): MemoryPushResult {
        check()
        if (!enabled) throw SyncException(409, "sync_off", "sync is off")
        memPushes += ops.size
        var accepted = 0
        val assigned = mutableListOf<AssignedMemoryOp>()
        for (op in ops) {
            val known = memOps[op.opId]
            if (known != null) {
                assigned += AssignedMemoryOp(op.opId, known.seq)
                continue
            }
            val s = ++seq
            memOps[op.opId] = MemOp(op.opId, op.opType, op.clientTime, op.payload, s)
            assigned += AssignedMemoryOp(op.opId, s)
            accepted++
        }
        return MemoryPushResult(seq, accepted, assigned)
    }

    override fun pullMemory(since: Long, limit: Int): MemoryPullResult {
        check()
        if (!enabled) throw SyncException(409, "sync_off", "sync is off")
        val page = memOps.values.filter { it.seq > since }.sortedBy { it.seq }
        val more = page.size > limit
        val rows = if (more) page.take(limit) else page
        return MemoryPullResult(
            cursor = if (more && rows.isNotEmpty()) rows.last().seq else seq,
            more = more,
            ops = rows.map { RemoteMemoryOp(it.opId, it.seq, it.opType, it.payload, it.clientTime) },
        )
    }

    /** The newest message's seq: a row seeded here at this seq is the last one the relay has. */
    val lastSeq: Long get() = msgs.values.maxOfOrNull { it.seq } ?: 0L

    /** A device [device] starting a chat on the relay directly (what another client would do). */
    fun seed(device: String, cid: String, kind: String, title: String?, at: Long): Conv =
        Conv(cid, kind, title, device, at, at, false, ++seq).also { convs[cid] = it }

    fun seedMessage(device: String, cid: String, mid: String, role: String, text: String, at: Long, attachments: List<Attachment> = emptyList()): Msg =
        Msg(mid, cid, device, role, text, attachments, at, false, ++seq).also { msgs[mid] = it }
}
