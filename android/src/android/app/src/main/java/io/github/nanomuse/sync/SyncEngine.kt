package io.github.nanomuse.sync

import java.util.UUID

/**
 * What a pull changed locally, for the UI: chats that got rows, and whether any chat row
 * changed. [replied] are the chats that got another device's assistant line — the reply
 * that clears the "kwai is working…" line (C9); [skipped] how many older messages a tail
 * page left on the relay.
 */
data class PullResult(
    val applied: Int,
    val touchedSessions: Set<String>,
    val cursor: Long,
    val replied: Set<String> = emptySet(),
    val skipped: Int = 0,
)

/**
 * The conversation sync of contract C7, apart from Android: a [SyncStore] for the ids, the
 * phone's [LocalChats], and the relay behind [SyncApi]. [ConversationSync] decides *when* to
 * call [push] and [pull]; this class decides *what* goes and what is applied, so the rules can
 * be tested with a fake relay.
 *
 * - Every local chat gets a `cid` the first time a push looks at it; the main chat is `main`.
 * - A push sends the person's messages and each turn's final reply ([Transcript]) that the relay
 *   has not accepted yet, tombstones for rows that are gone (an edit, a regenerate), and the
 *   chats whose title changed. A chat deleted here goes as a tombstone plus `DELETE`.
 * - A pull applies changes in `seq` order: new chats are created, known ones merge by `mid`,
 *   tombstones delete. The first remote `main` is adopted as this phone's main chat.
 * - A pull after the first reads what follows its cursor page by page until the relay says
 *   there is no page after, a page brings nothing this phone did not have, or a page is short
 *   of [PAGE]. The account counter travels beside the cursor ([SyncStore.HEAD]) and is not an
 *   end: the relay gives it out with a tail only, so a pull cut off after a page inside the
 *   store has no counter to read to, and the cursor alone says where it stopped. A walk left on
 *   record ([SyncStore.PAGES] and [SyncStore.WALK], written by a first pull cut off inside the
 *   store) is finished first, from the page it reached to the counter it set out for.
 * - `main_exists` in a push's answer re-homes the local main chat under the relay's cid and
 *   posts its messages again.
 *
 * Contract C8 (0.1.37) on top: a push is the whole eligible history, oldest first (the first
 * conversation's scripted opening included, see [LocalChats.prelude]); the person's row goes
 * out at send (the caller pushes then — [Transcript] already lets a lone user row through);
 * the main chat is the union of the local rows and the relay's, placed by time, one row per
 * `mid`, this phone's own echoes skipped; pulled side chats are ordinary, continuable chats;
 * rows written elsewhere remember their device for the "From Pixel 8" caption ([captions]).
 *
 * Contract C9 (0.1.38): main first. With [sideChats] off (the default) only the main chat
 * goes up and only `scope=main` comes down; side chats stay on the phone, and side rows that
 * arrive anyway are ignored. The first pull of an account — and the one right after the
 * side-chat switch is turned on — asks for the tail (`since=0&tail=300`): the newest rows
 * and their chats, applied as one write, so a fresh sign-in shows the chat at once instead
 * of replaying the account's whole history. Presence rides apart from the data: [working]
 * says a turn started or ended here; the rows this phone did not write are [remoteRows],
 * which no "interrupted — continue" detection may ever treat as this phone's unfinished turn.
 *
 * Contract C10 (0.1.39): the account's conversations only. A mapping remembers the account
 * it was first pushed to or pulled from ([SyncConversation.owner]); a chat with no mapping
 * has no owner. Signed in as B, the push takes B's and the ownerless (which become B's on
 * that push) and never A's; a pull never touches A's; the list the drawer shows leaves A's
 * out ([hidden]). A change of account keeps every mapping — switching back shows A's again —
 * and starts the cursor over; the home conversation follows the account ([ensureAccount]).
 */
class SyncEngine(
    private val store: SyncStore,
    private val chats: LocalChats,
    private val api: SyncApi,
    private val deviceId: String,
    private val account: String,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Settings → Data controls → "Also sync side chats": on by default (C9). */
    private val sideChats: () -> Boolean = { true },
    /** The signed-in account is not the last one (C10): the caller drops what it cached of the old one. */
    private val onAccountChanged: suspend () -> Unit = {},
    /** One line for the log, so a refused push can be read back from a release build. */
    private val log: (String) -> Unit = {},
) {
    /** The cursor last applied, or null before the first pull of this account. */
    suspend fun cursor(): Long? = store.meta(SyncStore.CURSOR)?.toLongOrNull()

    /** This account's, or nobody's yet. */
    private fun SyncConversation.mine(): Boolean = owner == null || owner == account

    /** The main chat, unless it was synced under another account (then this account has none yet). */
    private suspend fun mainSessionId(): String? =
        chats.mainSessionId()?.takeIf { store.conversation(it)?.mine() != false }

    /**
     * A different account than the last one (C10): the mappings stay, so switching back finds
     * every chat as it was; the cursor starts over (a fresh tail pull); the home conversation
     * is this account's main chat when the phone has one, else a draft — never the other
     * account's chat; the caller clears what it cached of the old account.
     */
    private suspend fun ensureAccount() {
        val previous = store.meta(SyncStore.ACCOUNT)
        if (previous == account) return
        store.putMeta(SyncStore.ACCOUNT, account)
        forgetPull()
        val current = chats.mainSessionId()?.let { store.conversation(it) }
        if (current != null && !current.mine()) {
            val own = store.conversations().firstOrNull { it.kind == "main" && it.owner == account && !it.deleted && chats.session(it.sessionId) != null }
            chats.setMainSession(own?.sessionId)
        }
        // the first sign-in on a fresh store is not a change of account: nothing of anyone else's is cached
        if (previous != null) onAccountChanged()
    }

    /** A sign-in: the account-scoped state is this account's before anything else runs (C10), the switch on or off. */
    suspend fun accountSignedIn() = ensureAccount()

    // ── push ──────────────────────────────────────────────────────────────

    /** Sends what the relay does not have yet. Returns how many changes it accepted. */
    suspend fun push(): Int {
        ensureAccount()
        // adoption first: a phone that never pulled must not seed a second main chat. A push
        // right after a pull the caller made runs this only for a store that has no cursor
        // yet, and that one is from the end: the newest rows, not the whole of the history
        if (cursor() == null) pull(atEnd = true)
        return pushOnce(retryMain = true)
    }

    private suspend fun pushOnce(retryMain: Boolean): Int {
        val mainId = mainSessionId()
        val convs = mutableListOf<OutConversation>()
        val msgs = mutableListOf<OutMessage>()
        val pending = mutableListOf<SyncMessage>()
        val titles = mutableMapOf<String, Pair<SyncConversation, String?>>()
        val gone = mutableListOf<SyncMessage>()
        // C10: another account's chats are not pushed into this one — not their rows, not
        // their titles, not their tombstones; they wait, unseen, for that account to sign in
        val allLive = chats.sessions().filter { store.conversation(it.id)?.mine() != false }
        val liveIds = allLive.map { it.id }.toSet()
        val side = sideChats()
        // C9: with side chats off, only the main chat is this phone's business here — a side
        // chat gets no cid, no rows go up, and one synced earlier is left as it is
        val liveSessions = if (side) allLive else allLive.filter { it.id == mainId || store.conversation(it.id)?.kind == "main" }

        for (s in liveSessions) {
            var map = store.conversation(s.id)
            if (map == null) {
                map = SyncConversation(
                    sessionId = s.id,
                    cid = newId(),
                    kind = if (s.id == mainId) "main" else "side",
                    device = deviceId,
                    deviceName = "",
                    pushedTitle = null,
                    pushed = false,
                    owner = account,
                )
                store.putConversation(map)
            } else if (map.owner == null) {
                // a mapping from before C10: the account it goes to now is the one it is of
                map = map.copy(owner = account)
                store.putConversation(map)
            }
            val knownList = store.messages(s.id)
            val known = knownList.associateBy { it.messageId }
            val rows = chats.messages(s.id)
            // rows written on another device sit among ours (C8's merged main chat); they are not
            // this phone's turns — kept out of the transcript rule, never replaced from here
            val pulledIds = knownList.filter { it.device.isNotBlank() }.map { it.messageId }.toSet()
            val presentIds = rows.map { it.id }.toSet()
            // the first conversation's scripted opening rides ahead of the rows (C8: the whole history)
            val items = chats.prelude(s.id) + Transcript.items(rows.filter { it.id !in pulledIds })
            val itemIds = items.map { it.messageId }.toSet()
            val fresh = mutableListOf<SyncMessage>()
            for (item in items) {
                val m = known[item.messageId] ?: SyncMessage(item.messageId, newId(), s.id, pushed = false).also { fresh += it }
                if (m.pushed) continue
                pending += m
                msgs += OutMessage(
                    mid = m.mid, cid = map.cid, role = item.role,
                    text = item.text.take(TEXT_MAX), attachments = item.attachments,
                    createdAt = item.createdAt / 1000,
                )
            }
            store.putMessages(fresh)
            // rows that are gone (edited, regenerated, cut): their mids become tombstones
            for (m in known.values) {
                if (m.messageId in itemIds) continue
                if (m.messageId in pulledIds && m.messageId in presentIds) continue // another device's row, still here
                if (!m.pushed) {
                    // never reached the relay: forget it quietly
                    gone += m
                    continue
                }
                gone += m
                msgs += OutMessage(mid = m.mid, cid = map.cid, role = "user", text = "", createdAt = now() / 1000, deleted = true)
            }
            // the main chat has no title of its own on this phone: it is named once, never renamed from here
            val titleChanged = map.kind != "main" && map.pushedTitle != s.title
            val touched = msgs.any { it.cid == map.cid }
            if (!map.pushed || titleChanged || touched) {
                convs += OutConversation(map.cid, map.kind, s.title, s.createdAt / 1000, s.updatedAt / 1000)
                titles[map.cid] = map to s.title
            }
        }
        // chats deleted here: a tombstone, then the relay's delete route (side chats only while the switch is on)
        val deletedMaps = store.conversations().filter { it.mine() && it.sessionId !in liveIds && (side || it.kind == "main") }
        for (map in deletedMaps) {
            if (!map.pushed) {
                // the relay never saw it; nothing to tell
                store.removeMessagesOf(map.sessionId)
                store.removeConversation(map.sessionId)
                continue
            }
            if (!map.deleted) store.putConversation(map.copy(deleted = true))
            convs += OutConversation(map.cid, map.kind, map.pushedTitle, now() / 1000, now() / 1000, deleted = true)
        }

        if (convs.isEmpty() && msgs.isEmpty()) return 0

        var accepted = 0
        val rejected = mutableListOf<Rejection>()
        // oldest first across every chat (a backfill is the whole history; tombstones, dated now, last);
        // the conversations ride with the first batch so every message finds its cid
        val ordered = msgs.sortedBy { it.createdAt }
        val batches = if (ordered.isEmpty()) listOf(emptyList()) else ordered.chunked(BATCH)
        // every batch that came back marks its rows as said, and only those: a POST that throws
        // leaves the later batches unpushed for the next push, instead of marking the lot at the
        // end, which is how a push that failed on its second batch lost the third and fourth
        val sent = mutableSetOf<String>()
        val held = mutableSetOf<String>()
        batches.forEachIndexed { i, batch ->
            val r = api.push(deviceId, if (i == 0) convs else emptyList(), batch)
            accepted += r.accepted
            rejected += r.rejected
            // A row of a chat the relay refused for want of room was never taken and waits with
            // the chat (below): the cid says which batch rows are its own, so it is not marked
            // as said here. Everything else in the batch is.
            val full = r.rejected.filter { it.reason == "conversation_limit" }.mapNotNull { it.cid }.toSet()
            for (out in batch) {
                if (out.cid in full) held += out.mid else sent += out.mid
            }
            store.putMessages(pending.filter { it.mid in sent && it.mid !in held }.map { it.copy(pushed = true) })
        }
        val rejectedMids = rejected.mapNotNull { it.mid }.toSet()
        val rejectedCids = rejected.mapNotNull { it.cid }.toSet()

        // the tombstones among the rows that went up are gone here: the relay holds one instead
        store.removeMessages(gone.filter { it.mid in sent }.map { it.messageId })
        // `conversation_limit` (0.23): the account keeps at most 2000 live side chats and this
        // one is past it. The chat stays on the phone, not sent again, and its rows wait with
        // it: they go up when the account has room, so they are not called sent either
        val limitedCids = rejected.filter { it.reason == "conversation_limit" }.mapNotNull { it.cid }.toSet()
        for ((cid, pair) in titles) {
            val (map, title) = pair
            if (cid in limitedCids) {
                // the relay was told this cid takes no place, so the chat is left as unknown to
                // it: a later push, when there is room, offers it again from the start
                store.putConversation(map.copy(pushed = false, pushedTitle = null))
                continue
            }
            if (cid in rejectedCids) continue
            store.putConversation(map.copy(pushed = true, pushedTitle = title))
        }
        if (limitedCids.isNotEmpty()) {
            log("the account is at its conversation limit; ${limitedCids.size} chat(s) stay on this phone")
        }
        for (map in deletedMaps) {
            if (!map.pushed) continue
            runCatching { api.deleteConversation(map.cid) }
            store.removeMessagesOf(map.sessionId)
            store.removeConversation(map.sessionId)
        }
        // Two devices seeded a main chat each: the relay kept the older one, ours moves under
        // it. Done before the bookkeeping below, since its rows go up again with the answer's
        // own seqs: counting them as said first would leave the relay without them
        val mainClash = rejected.firstOrNull { it.reason == "main_exists" && it.cidMain != null }
        if (mainClash != null && retryMain) {
            val local = store.conversationByCid(mainClash.cid.orEmpty()) ?: store.conversation(mainId.orEmpty())
            if (local != null && local.cid != mainClash.cidMain) {
                rehome(local, mainClash.cidMain!!, "", "")
                return accepted + pushOnce(retryMain = false)
            }
        }
        // what the relay refused for good is not sent again: a bad role, an unknown cid, a
        // tombstone for a chat it never had
        if (rejectedMids.isNotEmpty()) {
            // a row of a chat the relay turned away for want of room was not refused itself:
            // it waits with the chat, so the account's room in time sends it along
            val drop = pending.filter { it.mid in rejectedMids && store.conversation(it.sessionId)?.cid !in limitedCids }
            store.putMessages(drop.map { it.copy(pushed = true) })
        }
        return accepted
    }

    /**
     * The local chat [local] is from now on the relay's conversation [cid]. Its rows go out
     * again under the new cid, all of them: a row the relay took under the old one is a
     * different mid there, so the new cid needs it too, and the relay drops the repeat by mid.
     */
    private suspend fun rehome(local: SyncConversation, cid: String, device: String, deviceName: String) {
        store.putConversation(local.copy(cid = cid, kind = "main", device = device, deviceName = deviceName, pushed = true, pushedTitle = null))
        store.putMessages(store.messages(local.sessionId).map { it.copy(pushed = false) })
    }

    // ── pull ──────────────────────────────────────────────────────────────

    /**
     * Fetches and applies everything after the stored cursor, or the tail of the scope (C9)
     * for an account this phone has never pulled. Each page is written as one transaction.
     *
     * Where the pull continues from is one number, the relay's own: the account's counter at
     * the end of a store, the seq of a page's last row in the middle of one. Both are plain
     * numbers, so [SyncStore.HEAD] is kept beside the cursor to tell a store read to the end
     * from one a pull stopped in: a cursor the counter is equal to has nothing after it.
     *
     * A page with nothing this phone did not have already ends the pull. The store's order
     * comes from rows of other devices, which this phone has or has not by its own lights, so
     * a gap in the seqs a page hands out does not say the phone lacks the rows in it; reading
     * on the chance it does would walk a store of thousands of rows for nothing on every pull.
     * The two pulls that must read past that are a phone that never had the account and one
     * whose cursor went back under the store's oldest rows: they walk it, page by page, from
     * [SyncStore.PAGES] to [SyncStore.WALK], and a walk that fails starts over where it was.
     */
    suspend fun pull(): PullResult = pull(atEnd = false)

    /**
     * [atEnd] says where to start when the caller knows: the push that follows a pull runs a
     * pull of its own only for a store this account has never had ([push]), and that one is
     * from the end.
     */
    private suspend fun pull(atEnd: Boolean): PullResult {
        ensureAccount()
        // ensureAccount may have started this account over: these are read after it, never before
        var cursor: Long? = store.meta(SyncStore.CURSOR)?.toLongOrNull()
        // the account's counter as the pages last saw it, for [save] to carry along
        var head: Long? = store.meta(SyncStore.HEAD)?.toLongOrNull()
        // where a walk got to, and the counter it set out for; absent when there is none
        //
        // A walk is a first pull (or one after the account was switched) that reads the store
        // from its start, a page at a time, to the counter the store stood at when it set out.
        // It is the only reading that reaches rows the tail could not, and the only one that
        // declares the store read to its end, so it alone writes [SyncStore.PULLED] "done".
        //
        // [SyncStore.PAGES] is where the walk reads next, not where it began: the page a walk
        // got to and the page it starts again from are the same page after a failure, so the
        // pull that carries on asks for it and goes on from wherever it ends. The cursor is no
        // measure of it. A reading takes its cursor to the highest seq a page held, and rows
        // near the end of the store are laid out past the reading's place all the time; a walk
        // whose page fell under such a cursor is still owed the rest of the store.
        var walkFrom = store.meta(SyncStore.PAGES)?.toLongOrNull()
        var walkTo = store.meta(SyncStore.WALK)?.toLongOrNull()
        // both or neither: a walk with a page and no counter to read to, or the other way
        // round, is a record of a pull that was cut short in the middle of writing it
        if ((walkFrom == null) != (walkTo == null)) {
            walkFrom = null
            walkTo = null
            forgetWalk()
        }
        // A store the relay trimmed: the cursor stands below the oldest rows the phone has
        // and the counter the pages last saw stands above it. The phone cannot tell which
        // rows the trim took from it, so it reads the store from its start, page by page, to
        // the counter, as a phone that never had the account does.
        val trimmed = cursor == 0L && head != null && head > 0L
        // a walk on record carries on from the page it reached; a pull with none starts one
        // with a tail, which reads the newest rows and the account's counter in one page, and
        // goes on to read the store from its start to that counter a page at a time. So the
        // counter a walk sets out for is known as it starts, never guessed from [HEAD]
        var walking = walkFrom != null || cursor == null || atEnd || trimmed
        // the tail is the first page of a walk the pull starts; a walk on record is already
        // past it, and a reading is not a walk at all
        var tailFirst = walking && walkFrom == null
        // whether the tail has been read: it is the first page and no other, whatever the
        // walk goes on to ask for
        var tailDone = false
        // where the pull reads first: the page a walk reached, or, with no walk, what follows
        // the cursor. A pull that has no cursor reads the store from its start
        var next = walkFrom ?: cursor ?: 0L
        if (walking && walkFrom == null) {
            store.batch {
                store.putMeta(SyncStore.PAGES, "0")
            }
        }
        var applied = 0
        var skipped = 0
        // whether the page the pull last read had more after it
        var more = false
        // where a walk that ran out ended, as the clock of the pull saw it
        var walkDone: Long? = null
        // the counter a walk set out for, as it was when the walk started: a walk under way
        // reads to it even when the account has moved past it in the meanwhile
        var bound = walkTo
        // how many changes the relay sent that were already here or of no use here
        var unchanged = 0
        // a page with nothing new ended the pull, and the page after it is not read
        var stalled = false
        val touched = mutableSetOf<String>()
        val replied = mutableSetOf<String>()
        val scope = if (sideChats()) SyncApi.SCOPE_ALL else SyncApi.SCOPE_MAIN
        // Where the store stood when the pull set out. A reading goes no further than that:
        // the rows a walk read past the counter it set out for are older ones the store laid
        // out later, and reading on to them would be reading the store from wherever the walk
        // stopped. The pull after this one reads on from where the store now stands.
        val cap = if (walking) null else head
        // a reading has nothing to walk to, so its own cursor is where it starts: one past it
        // reads a page ahead of what the pull came for. A walk goes by the page it got to and
        // the counter it set out for
        next = if (!walking) minOf(next, cursor ?: next) else next
        while (true) {
            val tailNow = tailFirst && !tailDone
            val page = api.changes(next, PAGE, scope, if (tailNow) TAIL else 0)
            unchanged = page.conversations.size + page.messages.size
            // the relay says whether the store goes on; the pages it hands back are what this
            // pull has left to read
            more = page.more
            // only a tail hands back the account's counter; every other page hands back a seq
            if (tailNow) {
                head = maxOf(head ?: 0L, page.cursor)
                bound = head
                walkTo = head
                tailDone = true
                // the walk is on record from the page it reads to the counter it set out for,
                // so a pull that stops between two of them has one to go on with
                store.batch {
                    store.putMeta(SyncStore.PAGES, "0")
                    store.putMeta(SyncStore.WALK, head.toString())
                }
            }
            if (page.conversations.isEmpty() && page.messages.isEmpty()) {
                // a page with nothing on it between two others is one the walk reads on from;
                // one the relay has nothing after is the store's end, and so is a walk's page
                // at the counter it set out for
                val atBound = walking && bound != null && next >= bound
                // a page with nothing at all, between two others, is one the walk reads on
                // from: what ends a pull is a page the relay has nothing after, a walk's page
                // past the counter it set out for, or a reading let as far as it goes
                val end = !page.more || atBound || (!walking && next >= (cap ?: next))
                if (cursor == null || page.cursor > cursor) cursor = page.cursor
                if (!page.more && head == null) head = next
                if (end) {
                    // a walk read every row the store had up to its counter, and one that ran
                    // out of pages before it read what there was
                    if (walking) {
                        walkDone = maxOf(page.cursor, next)
                        forgetWalk()
                    }
                    save(cursor ?: next, head ?: (cursor ?: next), "done")
                    break
                }
                save(cursor ?: next, head, "pages")
                next = maxOf(page.cursor, next)
                continue
            }
            // the tail reports as skipped what it left out, and the walk reads those rows:
            // only a page of the walk itself adds to the count
            if (!tailNow) skipped += page.skipped
            val rows = page.conversations.size + page.messages.size
            val n = store.batch {
                chats.transaction {
                    var count = 0
                    for (c in page.conversations) {
                        if (applyConversation(c, touched)) count++
                    }
                    for (m in page.messages) {
                        if (applyMessage(m, touched, replied)) count++
                    }
                    count
                }
            }
            applied += n
            unchanged -= n
            // where the page ends: never behind where it was asked from, so a relay that hands
            // back 0 for a page of rows this phone already holds cannot send the pull back to
            // the page it just read
            val cut = maxOf(
                page.cursor, next,
                page.conversations.maxOfOrNull { it.seq } ?: 0L,
                page.messages.maxOfOrNull { it.seq } ?: 0L,
            )
            // the counter is the account's, and it only ever goes up: the tail hands it back
            // whole, and a page's seq is where the store stood when that page was cut
            cursor = maxOf(cursor ?: 0L, cut)
            if (walking) {
                // the tail hands back the account's counter, not the store's seq: the walk
                // reads the store from its start, whatever page the tail ended at
                next = if (tailNow) 0L else cut
                // the tail is the newest rows and the counter, not a page of the walk: the
                // walk reads the store from its start, and what ends it is the page at the
                // counter it set out for, or one the relay has nothing after
                val walked = !tailNow
                val atBound = walked && bound != null && cut >= bound
                if (atBound || (walked && !page.more)) {
                    walkDone = cut
                    forgetWalk()
                    save(cursor, head ?: cursor, "done")
                    break
                }
                store.batch {
                    store.putMeta(SyncStore.PAGES, next.toString())
                    if (bound != null) store.putMeta(SyncStore.WALK, bound.toString())
                }
                // 0.24: a page hands back the seq of its last row whether or not more follows
                // it, so the word "pages" is what tells a pull left between two of them from
                // one that read to the end
                save(cursor, head, "pages")
                continue
            }
            // a reading that meets a page of rows this phone already holds has nothing to
            // learn from the pages after it: the rows of other devices are laid out here by
            // their own times, so what it lacks is not where the seqs leave a gap. A walk is
            // not a reading of that kind, and reads the store to its counter whatever the
            // pages hold
            if (page.more && n == 0) {
                stalled = true
                break
            }
            save(cursor, head, "pages")
            // a reading stops at the counter the store stood at when it set out: what the
            // account took after that is the next pull's business. A page short of the brim,
            // one read to the end of the store, or one that reached the counter is the last
            next = maxOf(cursor, cut)
            if (page.more && rows >= PAGE && (cap == null || cut < cap)) continue
            break
        }
        // a reading that stopped between pages leaves "pages" behind, and the next pull reads
        // on from its cursor; one that stopped at a page with nothing after it has read the
        // store to its end
        if (walkDone == null && !walking) save(cursor ?: 0L, head, if (more) "pages" else "done")
        return PullResult(applied, touched, cursor ?: 0L, replied, skipped)
    }

    /**
     * No walk is left to do: a pull that reads the store again starts from nothing rather than
     * from a page the store may have drifted past.
     */
    private suspend fun forgetWalk() {
        store.batch {
            store.putMeta(SyncStore.PAGES, null)
            store.putMeta(SyncStore.WALK, null)
        }
    }

    /**
     * Where the pull stands, as one write: the cursor, the account's counter as the pages saw
     * it, and how far it got — `pages` between two of them, `done` at the end, `nopages` when
     * a page held nothing at all.
     */
    private suspend fun save(cursor: Long, head: Long?, how: String) {
        store.batch {
            store.putMeta(SyncStore.CURSOR, cursor.toString())
            store.putMeta(SyncStore.PULLED, how)
            if (head != null) store.putMeta(SyncStore.HEAD, head.toString())
        }
    }

    private suspend fun applyConversation(c: RemoteConversation, touched: MutableSet<String>): Boolean {        // C9: side chats are not this phone's business while the switch is off — none should
        // arrive with scope=main; one that does makes no chat here and changes none
        if (c.kind != "main" && !sideChats()) return false
        val titleStr = c.title.orEmpty().trim()
        val isInternal = titleStr.startsWith("动态 · ") || titleStr.startsWith("Feed · ") || titleStr == "动态" || titleStr == "Feed"
        if (isInternal && !c.deleted) {
            val knownInternal = store.conversationByCid(c.cid)
            if (knownInternal != null) {
                chats.deleteSession(knownInternal.sessionId)
                store.removeMessagesOf(knownInternal.sessionId)
                store.removeConversation(knownInternal.sessionId)
                touched += knownInternal.sessionId
                return true
            }
            return false
        }
        var known = store.conversationByCid(c.cid)
        // C10: a cid of another account's chat (it cannot happen — cids are per account — but if it did, not ours to change)
        if (known != null && !known.mine()) return false
        if (known != null && known.owner == null) {
            // a mapping from before C10, found again under this account: it is this account's
            known = known.copy(owner = account)
            store.putConversation(known)
        }
        if (c.deleted) {
            if (known == null) return false
            val mainId = mainSessionId()
            if (known.sessionId == mainId) {
                // the home conversation stays; what was said in it goes
                chats.clearMessages(known.sessionId)
                store.removeMessagesOf(known.sessionId)
                store.putConversation(known.copy(pushedTitle = null))
            } else {
                chats.deleteSession(known.sessionId)
                store.removeMessagesOf(known.sessionId)
                store.removeConversation(known.sessionId)
            }
            touched += known.sessionId
            return true
        }
        if (known != null) {
            val s = chats.session(known.sessionId) ?: return false // gone here; the next push says so
            var renamed = false
            // A title that changed on another device and one changed here: the later change
            // wins, as it does everywhere else. The relay's title carries the second it was
            // written in, the local one the millisecond, so they can be compared.
            val remoteChanged = c.title != known.pushedTitle
            val localChanged = s.title != known.pushedTitle
            if (remoteChanged && c.title != s.title) {
                if (localChanged && s.updatedAt > c.updatedAt * 1000) {
                    // the local rename is the later one: it stays, and the next push carries
                    // it up, so the relay takes it back
                    store.putConversation(known)
                } else {
                    // another device renamed it
                    chats.renameSession(s.id, c.title, maxOf(s.updatedAt, c.updatedAt * 1000))
                    touched += s.id
                    renamed = true
                    if (c.title != known.pushedTitle) store.putConversation(known.copy(pushedTitle = c.title))
                }
            } else if (remoteChanged) {
                // only the other device renamed it, or the two names are the same
                store.putConversation(known.copy(pushedTitle = c.title))
            }
            return renamed
        }
        if (c.kind == "main") {
            // C10: a home conversation synced under another account is not this account's
            // main chat — this account's arrives as a new chat and becomes the home
            val existingMain = store.conversations().firstOrNull { it.kind == "main" && it.mine() }
            if (existingMain != null) {
                if (existingMain.cid != c.cid) {
                    rehome(existingMain, c.cid, c.device, c.deviceName)
                    store.putConversation(store.conversation(existingMain.sessionId)!!.copy(pushedTitle = c.title))
                }
                chats.setMainSession(existingMain.sessionId)
                touched += existingMain.sessionId
                return true
            }

            val mainId = mainSessionId()
            if (mainId != null) {
                val mine = store.conversation(mainId)
                if (mine != null && mine.kind == "side") {
                    // mainId was mistakenly pointing to a side chat! Decouple it and create a dedicated main session
                    chats.setMainSession(null)
                } else if (mine == null) {
                    // first sight of the account's main chat: ours is it from now on
                    store.putConversation(SyncConversation(mainId, c.cid, "main", c.device, c.deviceName, pushedTitle = c.title, pushed = true, owner = account))
                    touched += mainId
                    return true
                } else if (mine.cid != c.cid) {
                    // the relay's main is not the one we minted: move under it, post ours again
                    rehome(mine, c.cid, c.device, c.deviceName)
                    store.putConversation(store.conversation(mainId)!!.copy(pushedTitle = c.title))
                    touched += mainId
                    return true
                } else {
                    touched += mainId
                    return true
                }
            }
            val id = chats.createSession(c.title, c.createdAt * 1000, c.updatedAt * 1000, main = true)
            store.putConversation(SyncConversation(id, c.cid, "main", c.device, c.deviceName, pushedTitle = c.title, pushed = true, owner = account))
            touched += id
            return true
        }
        val id = chats.createSession(c.title, c.createdAt * 1000, c.updatedAt * 1000, main = false)
        store.putConversation(SyncConversation(id, c.cid, "side", c.device, c.deviceName, pushedTitle = c.title, pushed = true, owner = account))
        touched += id
        return true
    }

    private suspend fun applyMessage(m: RemoteMessage, touched: MutableSet<String>, replied: MutableSet<String>): Boolean {
        val known = store.messageByMid(m.mid)
        if (m.deleted) {
            if (known == null) return false
            if (!sideChats() && store.conversation(known.sessionId)?.kind == "side") return false // C9: side chats rest while off
            chats.deleteMessage(known.messageId)
            store.removeMessages(listOf(known.messageId))
            touched += known.sessionId
            return true
        }
        if (known != null) return false // ours, or seen before: never a second row for one mid
        if (m.device == deviceId) return false // our own echo under a mid this phone no longer maps
        if (m.role != "user" && m.role != "assistant") return false
        val conv = store.conversationByCid(m.cid) ?: return false
        if (conv.deleted || !conv.mine()) return false
        if (conv.kind != "main" && !sideChats()) return false // C9: a side row while the switch is off
        // by time among the chat's rows, not at the end (C8)
        val id = chats.insertMessage(conv.sessionId, m.role, m.text, m.attachments, m.createdAt * 1000)
        store.putMessages(
            listOf(SyncMessage(id, m.mid, conv.sessionId, pushed = true, seq = m.seq, device = m.device, deviceName = m.deviceName)),
        )
        touched += conv.sessionId
        if (m.role == "assistant") replied += conv.sessionId
        return true
    }

    // ── the switch and the delete ─────────────────────────────────────────

    /** The person turned the switch: the relay first, then what this phone remembers. */
    suspend fun setEnabled(on: Boolean): SyncState {
        val state = api.setEnabled(on)
        if (on) {
            // the relay's store is empty again: everything of this phone goes out
            ensureAccount()
            store.markAllUnpushed()
            forgetPull()
        }
        return state
    }

    /**
     * "Also sync side chats" was turned on (C9): the next pull starts from zero with the tail
     * of the whole scope — idempotent by `mid` and `cid`, so what is here already stays as it
     * is — and the next push backfills the side chats, oldest first. Turning it off needs
     * nothing here: [sideChats] is read on every push and pull.
     */
    suspend fun sideChatsTurnedOn() {
        ensureAccount()
        forgetPull()
    }

    /**
     * The next pull reads the store as a phone that has never had it: no cursor, no counter,
     * no walk, and a tail to start from. For the account that changed and for a store the
     * relay emptied.
     */
    private suspend fun forgetPull() {
        store.batch {
            for (key in arrayOf(SyncStore.CURSOR, SyncStore.HEAD, SyncStore.PULLED, SyncStore.PAGES, SyncStore.WALK)) {
                store.putMeta(key, null)
            }
        }
    }

    /** "Delete synced conversations": the relay's store goes, the switch and the local chats stay. */
    suspend fun wipe() {
        api.wipe()
    }

    /** The relay's view of the switch and the counts. */
    suspend fun state(): SyncState = api.state()

    // ── presence (C9) ─────────────────────────────────────────────────────

    /**
     * A turn started ([on]) or ended on local chat [sessionId]: the account's other devices
     * hear it. Only for a chat the relay knows; false when nothing was sent. Errors are the
     * caller's to ignore — presence is never retried.
     */
    suspend fun working(sessionId: String, on: Boolean): Boolean {
        val map = store.conversation(sessionId) ?: return false
        if (!map.pushed || map.deleted || !map.mine()) return false
        if (map.kind != "main" && !sideChats()) return false
        api.working(deviceId, map.cid, on)
        return true
    }

    /** The local chat a relay `cid` stands for, if this phone has it under this account. */
    suspend fun sessionOf(cid: String): String? = store.conversationByCid(cid)?.takeIf { it.mine() }?.sessionId

    /** Which local rows were written on another device, and that device's name — the "From Pixel 8" caption. */
    suspend fun captions(): Map<String, String> = captions(store, deviceId, account)

    /** The local rows another device wrote, and which device (C9: never this phone's unfinished turn). */
    suspend fun remoteRows(): Map<String, String> = remoteRows(store, deviceId, account)

    /** The local chats synced under another account — on the phone, not in this account's list (C10). */
    suspend fun hidden(): Set<String> = hidden(store, account)

    companion object {
        /** The session ids whose mapping belongs to an account other than [account] (C10); nothing while signed out ([account] null). */
        suspend fun hidden(store: SyncStore, account: String?): Set<String> {
            account ?: return emptySet()
            return store.conversations().filter { it.owner != null && it.owner != account }.map { it.sessionId }.toSet()
        }

        /** Local row id → the id of the device that wrote it, for the rows that came from the account's other devices (C9). */
        suspend fun remoteRows(store: SyncStore, deviceId: String, account: String? = null): Map<String, String> {
            val hidden = hidden(store, account)
            return store.pulledMessages()
                .filter { it.device.isNotBlank() && it.device != deviceId && it.sessionId !in hidden }
                .associate { it.messageId to it.device }
        }

        /**
         * Local row id → the name of the device it came from, for rows pulled from the account's
         * other devices (contract C8: a caption per message, no badge per chat — the main chat
         * is everyone's and a side chat is the same chat everywhere).
         */
        suspend fun captions(store: SyncStore, deviceId: String, account: String? = null): Map<String, String> {
            val hidden = hidden(store, account)
            return store.pulledMessages()
                .filter { it.device.isNotBlank() && it.device != deviceId && it.deviceName.isNotBlank() && it.sessionId !in hidden }
                .associate { it.messageId to it.deviceName }
        }

        /** The relay keeps 16 KB; sending more only to have it cut gains nothing. */
        const val TEXT_MAX = 16_384
        /** The relay's ceiling per POST. */
        const val BATCH = 200
        const val PAGE = 500
        /** The first pull of an account asks for this many of the newest messages (C9). */
        const val TAIL = 300
    }
}
