/**
 * Memory changes travel as operations (C15).
 *
 * Every write in `memory-store.ts` is queued with its own `op_id`; this pushes the queued
 * ones to the relay, which gives each an account-wide `seq`, and pulls what the other
 * devices wrote since this device's cursor. Only text and flags move. Each device rebuilds
 * its own index from the text, so no vector and no tokenised column ever crosses the wire.
 *
 * The order matters: push first, then pull. A local edit that is pushed first gets a seq of
 * its own, so the echo that comes back is recognised by `op_id` and any older remote change
 * loses to it.
 */
import type { MemoryOp, MemoryStore } from './memory-store.ts'
import type { SyncRelay } from './sync.ts'

export interface MemorySyncResult {
  pushed: number
  applied: number
}

const PUSH_BATCH = 100
const PULL_PAGE = 200
/** A very large first pull (a new device) takes several rounds; this stops a runaway loop. */
const MAX_ROUNDS = 50

export class MemorySync {
  private running: Promise<MemorySyncResult> | undefined
  private readonly store: MemoryStore
  private readonly log: ((level: 'info' | 'warn' | 'debug', text: string) => void) | undefined

  constructor(store: MemoryStore, log?: (level: 'info' | 'warn' | 'debug', text: string) => void) {
    this.store = store
    this.log = log
  }

  /** One pass: push what is queued here, then take what is new on the account. Never throws. */
  run(relay: SyncRelay, apiKey: string, device: string): Promise<MemorySyncResult> {
    if (this.running) return this.running
    const p = this.doRun(relay, apiKey, device)
      .catch((error: unknown) => {
        this.log?.('debug', `memory sync: ${String(error)}`)
        return { pushed: 0, applied: 0 }
      })
      .finally(() => {
        if (this.running === p) this.running = undefined
      })
    this.running = p
    return p
  }

  private async doRun(relay: SyncRelay, apiKey: string, device: string): Promise<MemorySyncResult> {
    const pushed = await this.push(relay, apiKey, device)
    const applied = await this.pull(relay, apiKey)
    if (applied > 0) this.log?.('info', `memory: ${applied} change(s) from another device`)
    return { pushed, applied }
  }

  private async push(relay: SyncRelay, apiKey: string, device: string): Promise<number> {
    let pushed = 0
    for (let round = 0; round < MAX_ROUNDS; round++) {
      const ops: MemoryOp[] = this.store.pendingOps(PUSH_BATCH)
      if (!ops.length) break
      const res = await relay.pushMemory(apiKey, device, ops)
      this.store.markAccepted(res.assigned)
      pushed += res.accepted
      // nothing accepted and nothing assigned: the relay knows these ids already, stop asking
      if (res.accepted === 0 && res.assigned.length === 0) break
    }
    if (pushed > 0) this.log?.('debug', `memory: ${pushed} change(s) pushed`)
    return pushed
  }

  private async pull(relay: SyncRelay, apiKey: string): Promise<number> {
    let applied = 0
    let since = this.store.cursor()
    for (let round = 0; round < MAX_ROUNDS; round++) {
      const page = await relay.pullMemory(apiKey, since, PULL_PAGE)
      for (const op of page.ops) {
        if (this.store.applyRemote(op)) applied++
      }
      if (page.cursor > since) since = page.cursor
      this.store.setCursor(since)
      if (!page.more) break
    }
    return applied
  }
}
