package io.github.nanomuse.memory

import io.github.nanomuse.sync.OutMemoryOp
import io.github.nanomuse.sync.SyncApi

/**
 * Memory changes with the account (C15), the phone's half.
 *
 * Every write in [LocalMemoryDb] is queued with an id of its own; this pushes the queued ones
 * to the relay, which gives each an account-wide seq, and pulls what the other devices wrote
 * since this phone's cursor. Only text and flags travel: each device rebuilds its own index.
 *
 * Push first, then pull: a local edit that goes up gets a seq of its own, so the echo that
 * comes back is recognised by `op_id` and any older remote change loses to it. Blocking; the
 * caller runs it off the main thread.
 */
class MemorySync(private val db: LocalMemoryDb, private val log: ((String) -> Unit)? = null) {

    fun run(api: SyncApi, device: String): MemorySyncResult {
        val pushed = push(api, device)
        val applied = pull(api)
        if (applied > 0) log?.invoke("memory: $applied change(s) from another device")
        return MemorySyncResult(pushed, applied)
    }

    private fun push(api: SyncApi, device: String): Int {
        var pushed = 0
        for (round in 0 until MAX_ROUNDS) {
            val queued = db.pendingOps(PUSH_BATCH)
            if (queued.isEmpty()) break
            val ops = queued.map { OutMemoryOp(it.opId, it.opType, it.clientTime, it.payload.toJson()) }
            val res = api.pushMemory(device, ops)
            db.markAccepted(res.assigned.map { it.opId to it.seq })
            pushed += res.accepted
            // nothing accepted and nothing assigned: the relay knows these ids already
            if (res.accepted == 0 && res.assigned.isEmpty()) break
        }
        return pushed
    }

    private fun pull(api: SyncApi): Int {
        var applied = 0
        var since = db.cursor()
        for (round in 0 until MAX_ROUNDS) {
            val page = api.pullMemory(since, PULL_PAGE)
            for (op in page.ops) {
                val changed = db.applyRemote(
                    MemoryOp(
                        opId = op.opId,
                        seq = op.seq,
                        opType = op.opType,
                        payload = MemoryOpPayload.fromJson(op.payload),
                        clientTime = op.clientTime,
                    ),
                )
                if (changed) applied++
            }
            if (page.cursor > since) since = page.cursor
            db.setCursor(since)
            if (!page.more) break
        }
        return applied
    }

    companion object {
        private const val PUSH_BATCH = 100
        private const val PULL_PAGE = 200
        private const val MAX_ROUNDS = 50
    }
}

data class MemorySyncResult(val pushed: Int, val applied: Int)
