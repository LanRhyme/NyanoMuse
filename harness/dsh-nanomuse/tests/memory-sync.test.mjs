import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MemoryStore } from '../src/memory-store.ts'
import { MemorySync } from '../src/memory-sync.ts'

/** A relay that keeps ops and hands out seqs, like `/v1/sync/memory/*`. */
function fakeRelay() {
  const ops = []
  let seq = 0
  return {
    ops,
    async pushMemory(_key, _device, incoming) {
      const assigned = []
      for (const op of incoming) {
        if (ops.some((o) => o.op_id === op.op_id)) continue
        seq += 1
        ops.push({ ...op, seq })
        assigned.push({ op_id: op.op_id, seq })
      }
      return { accepted: assigned.length, assigned }
    },
    async pullMemory(_key, since, limit = 200) {
      const page = ops.filter((o) => o.seq > since).sort((a, b) => a.seq - b.seq)
      const more = page.length > limit
      const rows = more ? page.slice(0, limit) : page
      return { cursor: more ? rows[rows.length - 1].seq : seq, more, ops: rows }
    },
  }
}

test('a local write queues one op and a repeat of the same line queues nothing', () => {
  const store = new MemoryStore(':memory:')
  const item = store.upsert({ content: '临时脚本放 ~/tmp' })
  assert.equal(store.pendingOps().length, 1)

  store.upsert({ content: '临时脚本放 ~/tmp' })
  assert.equal(store.pendingOps().length, 1, 'the same line is not queued twice')
  assert.equal(store.pendingOps()[0].payload.id, item.id)
  store.close()
})

test('an accepted op gives the row and the op the relay seq', () => {
  const store = new MemoryStore(':memory:')
  const item = store.upsert({ content: '电脑是 Arch Linux' })
  const [op] = store.pendingOps()
  store.markAccepted([{ op_id: op.op_id, seq: 7 }])

  assert.equal(store.pendingOps().length, 0)
  assert.equal(store.get(item.id).seq, 7)
  store.close()
})

test('a remote change applies once, newer wins, and a local pending edit is kept', () => {
  const store = new MemoryStore(':memory:')
  const item = store.upsert({ content: '住在上海' })
  store.markAccepted([{ op_id: store.pendingOps()[0].op_id, seq: 1 }])

  const remote = {
    op_id: 'remote-1',
    seq: 2,
    op_type: 'upsert_memory',
    client_time: Date.now(),
    payload: { id: item.id, content: '住在北京', before_content: '住在上海', category: 'profile', source: 'agent', is_pinned: false, is_archived: false, importance: 0.5, updated_at: Date.now(), deleted: false },
  }
  assert.equal(store.applyRemote(remote), true)
  assert.equal(store.get(item.id).content, '住在北京')
  assert.equal(store.applyRemote(remote), false, 'the same op twice is a no-op')

  // an older change cannot take the row back
  assert.equal(store.applyRemote({ ...remote, op_id: 'remote-0', seq: 0, payload: { ...remote.payload, content: '住在广州' } }), false)
  assert.equal(store.get(item.id).content, '住在北京')

  // a local edit not yet pushed outranks a remote change that arrives meanwhile
  store.upsert({ id: item.id, content: '住在深圳' })
  assert.equal(store.applyRemote({ ...remote, op_id: 'remote-2', seq: 3, payload: { ...remote.payload, content: '住在杭州' } }), false)
  assert.equal(store.get(item.id).content, '住在深圳')
  store.close()
})

test('a remote delete hides the memory from search', () => {
  const store = new MemoryStore(':memory:')
  const item = store.upsert({ content: '临时脚本放 ~/tmp' })
  store.markAccepted([{ op_id: store.pendingOps()[0].op_id, seq: 1 }])

  store.applyRemote({
    op_id: 'remote-del',
    seq: 2,
    op_type: 'delete_memory',
    client_time: Date.now(),
    payload: { id: item.id, content: '', before_content: '临时脚本放 ~/tmp', category: 'habit', source: 'agent', is_pinned: false, is_archived: false, importance: 0.5, updated_at: Date.now(), deleted: true },
  })

  assert.deepEqual(store.search('临时脚本'), [])
  assert.equal(store.get(item.id).deleted, 1)
  store.close()
})

test('archiving a memory is a change the other devices see', () => {
  const store = new MemoryStore(':memory:')
  for (let i = 0; i < 3; i++) store.upsert({ id: `mem_${i}`, content: `某条事实 ${i}`, importance: 0.1 })
  let seq = 0
  for (const op of store.pendingOps(10)) store.markAccepted([{ op_id: op.op_id, seq: ++seq }])
  assert.equal(store.pendingOps().length, 0)

  assert.equal(store.archiveStale(1), 2)
  const queued = store.pendingOps()
  assert.equal(queued.length, 2)
  assert.ok(queued.every((op) => op.payload.is_archived === true))
  store.close()
})

test('MemorySync pushes what is queued and takes what the account has', async () => {
  const store = new MemoryStore(':memory:')
  const relay = fakeRelay()
  const sync = new MemorySync(store)

  const local = store.upsert({ content: '主平板是 OnePlus Pad 2 Pro' })
  const first = await sync.run(relay, 'key', 'desk')
  assert.deepEqual(first, { pushed: 1, applied: 0 })
  assert.equal(store.pendingOps().length, 0)
  assert.equal(relay.ops.length, 1)

  // another device writes; this one pulls it on the next pass
  relay.pushMemory('key', 'phone', [
    {
      op_id: 'phone-1',
      op_type: 'upsert_memory',
      client_time: Date.now(),
      payload: { id: 'mem_phone', content: '喜欢莫兰迪配色', before_content: '', category: 'preference', source: 'agent', is_pinned: true, is_archived: false, importance: 0.9, updated_at: Date.now(), deleted: false },
    },
  ])
  const second = await sync.run(relay, 'key', 'desk')
  assert.deepEqual(second, { pushed: 0, applied: 1 })
  assert.ok(store.get('mem_phone'))
  assert.ok(store.get(local.id))
  assert.equal(store.cursor(), relay.ops.length)

  // the echo of our own op is not applied a second time
  const third = await sync.run(relay, 'key', 'desk')
  assert.deepEqual(third, { pushed: 0, applied: 0 })
  store.close()
})
