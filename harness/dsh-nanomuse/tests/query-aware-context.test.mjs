import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MemoryStore } from '../src/memory-store.ts'

test('Query-aware memory retrieval filters needle facts dynamically', () => {
  const store = new MemoryStore(':memory:')

  store.upsert({
    id: 'mem_pinned',
    content: '用户偏好极简回答，禁止使用表情符号',
    is_pinned: 1,
    category: 'preference',
  })

  store.upsert({
    id: 'mem_arch',
    content: '主力操作系统为 Arch Linux，合成器使用 Niri',
    is_pinned: 0,
    category: 'device',
  })

  store.upsert({
    id: 'mem_coffee',
    content: '平时最喜欢喝无糖生椰拿铁',
    is_pinned: 0,
    category: 'preference',
  })

  // 1. Core pinned memories are always isolated
  const pinned = store.getPinnedMemories()
  assert.equal(pinned.length, 1)
  assert.equal(pinned[0].id, 'mem_pinned')

  // 2. Query for Linux/system retrieves Arch Linux as top hit, ignoring coffee
  const hitsLinux = store.search('电脑系统配置', { limit: 5 })
  assert.ok(hitsLinux.length > 0)
  assert.equal(hitsLinux[0].item.id, 'mem_arch')

  // 3. Query for drinks retrieves latte as top hit, ignoring Linux
  const hitsDrinks = store.search('想喝点热饮', { limit: 5 })
  assert.ok(hitsDrinks.length > 0)
  assert.equal(hitsDrinks[0].item.id, 'mem_coffee')

  store.close()
})
