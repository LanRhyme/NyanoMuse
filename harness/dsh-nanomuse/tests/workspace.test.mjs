// Workspaces, both halves of the choice: the host row that keeps which folder new chats
// start in (`describeWorkspaces`, `parseWorkspaceState`), and the browser store the chats
// column reads (`workspace.ts` under src/client), which is built from source with esbuild
// the way the other client-only modules are. The point of each assertion is the same: a
// folder that is gone must never be the folder a chat is started in.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { describeWorkspaces, parseWorkspaceState } from '../lib/workspace.js'

const here = fileURLToPath(new URL('.', import.meta.url))

/** A registry face with two folders, the shape `ctx.workspaceRegistry` reports. */
const registry = {
  list: () => [
    { id: 'ws_a', title: 'nanoMuse', path: '/home/me/nanoMuse', sessionIds: ['s1', 's2'] },
    { id: 'ws_b', title: 'site', path: '/home/me/site', sessionIds: [] },
  ],
}

test('the registry is the list: titles, folders, and how many chats each holds', () => {
  const list = describeWorkspaces(registry, undefined)
  assert.equal(list.supported, true)
  assert.deepEqual(list.workspaces, [
    { id: 'ws_a', title: 'nanoMuse', path: '/home/me/nanoMuse', sessions: 2 },
    { id: 'ws_b', title: 'site', path: '/home/me/site', sessions: 0 },
  ])
  // no choice is not the same as a choice of nothing: the key is absent, not empty
  assert.equal('current' in list, false)
})

test('a choice the registry knows comes back; one it does not is dropped, not guessed', () => {
  assert.equal(describeWorkspaces(registry, 'ws_b').current, 'ws_b')
  const gone = describeWorkspaces(registry, 'ws_deleted')
  assert.equal('current' in gone, false)
  assert.deepEqual(gone.workspaces.map((w) => w.id), ['ws_a', 'ws_b'])
})

test('a harness without a workspace registry says so, and lists nothing', () => {
  const list = describeWorkspaces(undefined, 'ws_a')
  assert.equal(list.supported, false)
  assert.deepEqual(list.workspaces, [])
  assert.equal('current' in list, false)
})

test('the store on disk reads as a choice only when it holds one', () => {
  assert.deepEqual(parseWorkspaceState('{ "current": "ws_a" }'), { current: 'ws_a' })
  assert.deepEqual(parseWorkspaceState('{}'), {})
  assert.deepEqual(parseWorkspaceState('{ "current": "" }'), {})
  assert.deepEqual(parseWorkspaceState('{ "current": 42 }'), {})
  assert.deepEqual(parseWorkspaceState('{ "current": null }'), {})
  assert.deepEqual(parseWorkspaceState('not json'), {})
  assert.deepEqual(parseWorkspaceState(''), {})
})

// ---- the browser half ----------------------------------------------------------------

let dir
let store

before(async () => {
  dir = await mkdtemp(join(tmpdir(), 'nm-workspace-'))
  await build({
    entryPoints: [join(here, '..', 'src', 'client', 'workspace.ts')],
    bundle: true,
    format: 'esm',
    platform: 'node',
    outfile: join(dir, 'workspace.mjs'),
    logLevel: 'silent',
  })
  store = await import(pathToFileURL(join(dir, 'workspace.mjs')).href)
})

after(async () => {
  await rm(dir, { recursive: true, force: true })
})

/** Answer the next fetch with `body`; records what was asked. */
function answer(body, status = 200) {
  const calls = []
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init })
    return { ok: status < 400, status, json: async () => body }
  }
  return calls
}

test('the store starts with no folder, and reads the host\'s choice', async () => {
  assert.deepEqual(store.workspaceStore.getSnapshot(), { supported: true, ids: [] })
  assert.equal(store.currentWorkspaceId(), undefined)

  const calls = answer({ supported: true, current: 'ws_a', workspaces: [{ id: 'ws_a', title: 'nanoMuse', path: '/p', sessions: 1 }] })
  await store.refreshWorkspace()
  assert.equal(calls[0].url, '/nanomuse/workspace')
  assert.equal(calls[0].init.method, 'GET')
  assert.deepEqual(store.workspaceStore.getSnapshot(), { supported: true, current: 'ws_a', ids: ['ws_a'] })
  assert.equal(store.currentWorkspaceId(), 'ws_a')
})

test('a folder the host no longer lists is never the one a chat starts in', async () => {
  answer({ supported: true, current: 'ws_gone', workspaces: [{ id: 'ws_a', title: 'nanoMuse', path: '/p', sessions: 1 }] })
  await store.refreshWorkspace()
  // the host filters it, so the snapshot has no `current` at all
  assert.equal(store.workspaceStore.getSnapshot().current, undefined)
  assert.equal(store.currentWorkspaceId(), undefined)
})

test('picking a folder writes the choice back through the host', async () => {
  const calls = answer({ supported: true, current: 'ws_b', workspaces: [{ id: 'ws_b', title: 'site', path: '/s', sessions: 0 }] })
  await store.pickWorkspace('ws_b')
  assert.equal(calls[0].url, '/nanomuse/workspace/current')
  assert.equal(calls[0].init.method, 'POST')
  assert.deepEqual(JSON.parse(calls[0].init.body), { id: 'ws_b' })
  assert.equal(store.currentWorkspaceId(), 'ws_b')

  // "no folder" is an empty id, which the host reads as forgetting the choice
  const clearing = answer({ supported: true, workspaces: [] })
  await store.pickWorkspace(undefined)
  assert.deepEqual(JSON.parse(clearing[0].init.body), { id: '' })
  assert.equal(store.currentWorkspaceId(), undefined)
})

test('a subscriber hears about a change once, with a new snapshot', async () => {
  const seen = []
  const off = store.workspaceStore.subscribe(() => seen.push(store.workspaceStore.getSnapshot()))
  answer({ supported: true, current: 'ws_a', workspaces: [{ id: 'ws_a', title: 'nanoMuse', path: '/p', sessions: 1 }] })
  await store.refreshWorkspace()
  off()
  answer({ supported: true, current: 'ws_b', workspaces: [{ id: 'ws_b', title: 'site', path: '/s', sessions: 0 }] })
  await store.refreshWorkspace()
  assert.equal(seen.length, 1)
  assert.equal(seen[0].current, 'ws_a')
})
