import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MemoryStore } from '../src/memory-store.ts'

test('MemoryStore initializes and executes CRUD with synchronized FTS', () => {
  const store = new MemoryStore(':memory:')

  // 1. Insert
  const item = store.upsert({
    id: 'mem_arch',
    content: '电脑系统使用 Arch Linux (CachyOS) 与 Niri 合成器',
    category: 'device',
    is_pinned: 1,
    importance: 0.9,
  })
  assert.equal(item.id, 'mem_arch')
  assert.equal(item.is_pinned, 1)
  assert.ok(item.search_text.includes('arch'))
  assert.ok(item.search_text.includes('niri'))

  // Verify FTS row
  const ftsRow = store.db.prepare('SELECT * FROM memories_fts WHERE id = ?').get('mem_arch')
  assert.ok(ftsRow, 'FTS table should contain the inserted item')

  // 2. Search
  const searchResults = store.search('Niri 合成器')
  assert.equal(searchResults.length, 1)
  assert.equal(searchResults[0].item.id, 'mem_arch')
  assert.equal(searchResults[0].item.use_count, 1)

  // 3. Update
  store.upsert({
    id: 'mem_arch',
    content: '电脑系统使用 Arch Linux (CachyOS) 与 Wayland 窗口管理器',
    category: 'device',
  })
  const updated = store.get('mem_arch')
  assert.ok(updated.content.includes('Wayland'))
  assert.ok(updated.search_text.includes('wayland'))

  // Verify old term no longer in FTS
  const oldSearch = store.search('Niri')
  assert.equal(oldSearch.length, 0, 'Old content term should be purged from FTS')

  const newSearch = store.search('Wayland')
  assert.equal(newSearch.length, 1)

  // 4. Soft Delete
  store.delete('mem_arch')
  assert.equal(store.get('mem_arch').deleted, 1)
  assert.equal(store.search('Wayland').length, 0, 'Deleted items should not match search')

  store.close()
})

test('MemoryStore archives stale items to Cold without losing retrieval', () => {
  const store = new MemoryStore(':memory:')

  // Insert 5 items
  for (let i = 1; i <= 5; i++) {
    store.upsert({
      id: `mem_${i}`,
      content: `关于用户的临时偏好细节条目第 ${i} 项`,
      importance: i * 0.1,
      is_pinned: i === 5 ? 1 : 0, // mem_5 is pinned
    })
  }

  assert.equal(store.activeCount(), 5)

  // Archive stale down to active threshold of 3
  const archivedCount = store.archiveStale(3)
  assert.equal(archivedCount, 2)
  assert.equal(store.activeCount(), 3)

  // mem_5 (pinned) should never be archived
  const mem5 = store.get('mem_5')
  assert.equal(mem5.is_archived, 0)

  // Archived items still queryable with includeArchived: true
  const results = store.search('偏好细节', { includeArchived: true, limit: 10 })
  assert.equal(results.length, 5)

  // Active query only returns active items
  const activeResults = store.search('偏好细节', { includeArchived: false, limit: 10 })
  assert.equal(activeResults.length, 3)

  store.close()
})

test('MemoryStore imports legacy array from rooms.json', () => {
  const store = new MemoryStore(':memory:')
  const legacy = [
    { id: 'old_1', text: '喜欢莫兰迪色调', at: 1740000000000, source: 'person' },
    { id: 'old_2', text: '临时文件存放在 ~/tmp', at: 1740000100000, source: 'agent' },
  ]

  const count = store.importLegacy(legacy)
  assert.equal(count, 2)
  assert.equal(store.activeCount(), 2)

  const hit = store.search('莫兰迪')
  assert.equal(hit.length, 1)
  assert.equal(hit[0].item.id, 'old_1')

  store.close()
})

test('MemoryStore deduplicates records by content when id is omitted', () => {
  const store = new MemoryStore(':memory:')
  const m1 = store.upsert({ content: '偏好使用莫兰迪色系' })
  const m2 = store.upsert({ content: '偏好使用莫兰迪色系' })

  assert.equal(m1.id, m2.id, 'Duplicate content should reuse existing record id')
  assert.equal(store.activeCount(), 1)
  store.close()
})

test('MemoryStore inserts properly when explicit new id is provided even if content matches', () => {
  const store = new MemoryStore(':memory:')
  const m1 = store.upsert({ content: '临时脚本放 ~/tmp' })
  assert.ok(m1, 'First insert should succeed')

  const m2 = store.upsert({ id: 'mem_nonexistent', content: '临时脚本放 ~/tmp' })
  assert.ok(m2, 'Second insert with explicit id must not return null')
  assert.equal(m2.id, 'mem_nonexistent')
  assert.equal(store.activeCount(), 2)

  store.close()
})

test('MemoryStore end-to-end search achieves 8/8 recall on golden dataset', async () => {
  const { readFileSync } = await import('node:fs')
  const fixtureUrl = new URL('../../../tests/fixtures/memory/memory_eval_dataset.json', import.meta.url)
  const dataset = JSON.parse(readFileSync(fixtureUrl, 'utf8'))

  const store = new MemoryStore(':memory:')
  for (const fact of dataset.facts) {
    store.upsert(fact)
  }

  let passed = 0
  for (const q of dataset.queries) {
    const results = store.search(q.query, { limit: 5 })
    assert.ok(results.length > 0, `Query "${q.query}" should have returned candidates`)
    const topId = results[0].item.id
    assert.ok(
      q.expected_top.includes(topId),
      `Query "${q.query}" expected top in [${q.expected_top.join(', ')}], got "${topId}" (${results[0].item.content})`,
    )
    passed++
  }

  assert.equal(passed, 8, 'All 8 evaluation queries must pass top-1 rank')
  store.close()
})

test('MemoryStore supersede atomically archives old memory and activates new memory', () => {
  const store = new MemoryStore(':memory:')

  const old = store.upsert({
    id: 'mem_city',
    content: '住在北京市海淀区',
    category: 'profile',
    is_pinned: 1,
    importance: 0.8,
  })
  assert.equal(store.activeCount(), 1)
  assert.equal(store.archivedCount(), 0)

  const { newRecord, archivedOld } = store.supersede('mem_city', '搬家至杭州市西湖区定居')
  assert.ok(newRecord, 'New memory should be created')
  assert.ok(archivedOld, 'Old memory should be returned as archived')
  assert.equal(archivedOld.id, 'mem_city')
  assert.equal(archivedOld.is_archived, 1)
  assert.equal(archivedOld.is_pinned, 0, 'Archived memory should not stay pinned')

  assert.equal(newRecord.content, '搬家至杭州市西湖区定居')
  assert.equal(newRecord.is_archived, 0)
  assert.equal(newRecord.category, 'profile', 'Should inherit category')
  assert.equal(newRecord.is_pinned, 1, 'Should inherit pinned state')

  assert.equal(store.activeCount(), 1)
  assert.equal(store.archivedCount(), 1)

  // Search active only finds the new city
  const activeHits = store.search('杭州', { includeArchived: false })
  assert.equal(activeHits.length, 1)
  assert.equal(activeHits[0].item.id, newRecord.id)

  const oldHits = store.search('北京', { includeArchived: false })
  assert.equal(oldHits.length, 0, 'Old city should not match active search')

  const coldHits = store.search('北京', { includeArchived: true })
  assert.equal(coldHits.length, 1, 'Old city should still exist in cold archive')

  store.close()
})

test('MemoryStore findDirectConflict detects attribute collisions', () => {
  const store = new MemoryStore(':memory:')

  store.upsert({ id: 'mem_name', content: 'Call them: Alice' })
  store.upsert({ id: 'mem_home', content: '住在深圳市南山区' })
  store.upsert({ id: 'mem_tab', content: '当前平板使用一加平板2 Pro' })
  store.upsert({ id: 'mem_tea', content: '平时喜欢喝大红袍' })

  // 1. Name collision
  const nameConflict = store.findDirectConflict('Call them: Bob')
  assert.ok(nameConflict)
  assert.equal(nameConflict.id, 'mem_name')

  // 2. Residence collision
  const homeConflict = store.findDirectConflict('现居杭州市西湖区')
  assert.ok(homeConflict)
  assert.equal(homeConflict.id, 'mem_home')

  // 3. Hardware collision
  const tabConflict = store.findDirectConflict('当前平板换成了 iPad Pro')
  assert.ok(tabConflict)
  assert.equal(tabConflict.id, 'mem_tab')

  // 4. Non-colliding preference
  const teaConflict = store.findDirectConflict('平时喜欢吃苹果')
  assert.equal(teaConflict, null)

  store.close()
})

