import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MemoryStore } from '../src/memory-store.ts'
import { selectMemoriesForPrompt, MEMORY_PROMPT_LIMITS } from '../src/rooms-memory-prompt.ts'

test('selectMemoriesForPrompt: caps pinned memories to prevent attention dilution', () => {
  // Construct 10 pinned memories
  const memory = []
  for (let i = 1; i <= 10; i++) {
    memory.push({
      id: `mem_pin_${i}`,
      at: 1000 + i,
      text: `Pinned core rule number ${i}`,
      source: 'person',
      is_pinned: true,
    })
  }

  const result = selectMemoriesForPrompt(memory, null, undefined)
  // Max pinned count must be strictly bounded by MAX_PINNED_COUNT (6)
  assert.equal(result.items.length, MEMORY_PROMPT_LIMITS.MAX_PINNED_COUNT)
  assert.equal(result.injectedCount, MEMORY_PROMPT_LIMITS.MAX_PINNED_COUNT)
  assert.ok(result.lines.some((l) => l.includes('(4 more memories in store, search with recall_memory)')))
})

test('selectMemoriesForPrompt: query dynamically pulls top-k matching memory ahead of hot memories', () => {
  const store = new MemoryStore(':memory:')

  const itemPinned = store.upsert({
    id: 'mem_pinned',
    content: '用户偏好极简回答，禁止使用表情符号',
    is_pinned: 1,
    category: 'preference',
  })

  const itemArch = store.upsert({
    id: 'mem_arch',
    content: '主力操作系统为 Arch Linux，合成器使用 Niri',
    is_pinned: 0,
    category: 'device',
  })

  const itemCoffee = store.upsert({
    id: 'mem_coffee',
    content: '平时最喜欢喝无糖生椰拿铁',
    is_pinned: 0,
    category: 'preference',
  })

  const memory = [
    { id: itemPinned.id, at: itemPinned.updated_at, text: itemPinned.content, source: 'person', is_pinned: true },
    { id: itemCoffee.id, at: itemCoffee.updated_at, text: itemCoffee.content, source: 'person', is_pinned: false },
    { id: itemArch.id, at: itemArch.updated_at, text: itemArch.content, source: 'person', is_pinned: false },
  ]

  // Querying for Linux should prioritize mem_arch over mem_coffee
  const resLinux = selectMemoriesForPrompt(
    memory,
    '电脑系统配置',
    (q, opts) => store.search(q, opts),
  )

  assert.equal(resLinux.items[0].id, 'mem_pinned') // Pinned comes first
  assert.equal(resLinux.items[1].id, 'mem_arch')   // Query match comes second

  // Querying for drink should prioritize mem_coffee over mem_arch
  const resDrink = selectMemoriesForPrompt(
    memory,
    '想喝点热饮',
    (q, opts) => store.search(q, opts),
  )

  assert.equal(resDrink.items[0].id, 'mem_pinned')
  assert.equal(resDrink.items[1].id, 'mem_coffee')

  store.close()
})

test('selectMemoriesForPrompt: fallback cleanly populates newest hot memories when no query', () => {
  const memory = [
    { id: 'mem_hot_1', at: 5000, text: 'Hot item 1', source: 'person', is_pinned: false },
    { id: 'mem_hot_2', at: 4000, text: 'Hot item 2', source: 'person', is_pinned: false },
    { id: 'mem_hot_3', at: 3000, text: 'Hot item 3', source: 'person', is_pinned: false },
  ]

  const res = selectMemoriesForPrompt(memory, '')
  assert.equal(res.items.length, 3)
  assert.equal(res.items[0].id, 'mem_hot_1')
  assert.equal(res.items[1].id, 'mem_hot_2')
  assert.equal(res.items[2].id, 'mem_hot_3')
  assert.ok(res.lines[0].startsWith('- [mem_hot_1] Hot item 1'))
})

test('selectMemoriesForPrompt: enforces max total character budget', () => {
  const customLimits = {
    MAX_PINNED_COUNT: 4,
    MAX_PINNED_CHARS: 200,
    MAX_TOTAL_COUNT: 5,
    MAX_TOTAL_CHARS: 100, // strict 100 char limit
  }

  const memory = [
    { id: 'm1', at: 1000, text: 'A'.repeat(60), source: 'person', is_pinned: false },
    { id: 'm2', at: 900, text: 'B'.repeat(60), source: 'person', is_pinned: false },
  ]

  const res = selectMemoriesForPrompt(memory, null, undefined, customLimits)
  // Only first item should fit because 60 + 60 > 100
  assert.equal(res.items.length, 1)
  assert.equal(res.items[0].id, 'm1')
  assert.ok(res.lines.some((l) => l.includes('(1 more memories in store, search with recall_memory)')))
})
