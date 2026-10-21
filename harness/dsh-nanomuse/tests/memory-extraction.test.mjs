import assert from 'node:assert/strict'
import { test } from 'node:test'
import { extractPassiveMemories } from '../src/memory-extraction.ts'
import { MemoryStore } from '../src/memory-store.ts'
import { buildFtsQuery, scoreCandidate } from '../src/memory-search.ts'

test('extractPassiveMemories correctly extracts personal traits, hardware, and guidelines', () => {
  // 1. Hardware / Device declaration
  const dev1 = extractPassiveMemories('我主力电脑操作系统换成了 Arch Linux，合成器用 Niri')
  assert.ok(dev1.length >= 1)
  assert.equal(dev1[0].category, 'device')
  assert.ok(dev1[0].content.includes('操作系统使用 Arch Linux') || dev1[0].content.includes('电脑使用'))

  // 2. Residence / Location
  const loc = extractPassiveMemories('我最近搬到了杭州市西湖区定居')
  assert.equal(loc.length, 1)
  assert.equal(loc[0].category, 'profile')
  assert.ok(loc[0].content.includes('现居 杭州市西湖区'))

  // 3. Name / Call me
  const name = extractPassiveMemories('叫我小明就行啦')
  assert.equal(name.length, 1)
  assert.equal(name[0].category, 'profile')
  assert.ok(name[0].content.includes('Call them: 小明'))

  // 4. Allergies
  const allergy = extractPassiveMemories('我对坚果严重过敏')
  assert.equal(allergy.length, 1)
  assert.equal(allergy[0].category, 'habit')
  assert.ok(allergy[0].content.includes('坚果过敏'))

  // 5. Food / Beverage Habits
  const drink = extractPassiveMemories('我平时只喝无糖拿铁，不喝含糖饮料')
  assert.ok(drink.length >= 1)
  assert.ok(drink.some((d) => d.content.includes('只喝无糖拿铁') || d.content.includes('不喝含糖饮料')))

  // 6. Standing Directive / Communication guideline
  const guideline = extractPassiveMemories('以后回答时不要用表情符号，保持极简')
  assert.ok(guideline.length >= 1)
  assert.ok(guideline[0].content.includes('沟通规范'))

  // 7. Transient commands should NOT be extracted
  const transient1 = extractPassiveMemories('帮我看一下这段代码怎么报错了？')
  assert.equal(transient1.length, 0)

  const transient2 = extractPassiveMemories('今天天气真不错，现在几点了？')
  assert.equal(transient2.length, 0)

  const transient3 = extractPassiveMemories('/goal 查一下机票价格')
  assert.equal(transient3.length, 0)
})

test('Concept synonym expansion retrieves and ranks non-literal semantic matches', () => {
  const store = new MemoryStore(':memory:')

  store.upsert({
    id: 'mem_drink',
    content: '最爱喝无糖生椰拿铁',
    category: 'preference',
  })
  store.upsert({
    id: 'mem_pc',
    content: 'PC 操作系统为 Arch Linux，合成器为 Niri',
    category: 'device',
  })

  // 1. Semantic query "热饮" / "饮品" retrieves "拿铁"
  const ftsQuery = buildFtsQuery('想喝什么热饮')
  assert.ok(ftsQuery.includes('拿铁') || ftsQuery.includes('咖啡') || ftsQuery.includes('热饮'))

  const drinkHits = store.search('想喝热饮', { includeArchived: false })
  assert.ok(drinkHits.length > 0, 'Semantic query "热饮" should recall "拿铁"')
  assert.equal(drinkHits[0].item.id, 'mem_drink')

  // 2. Semantic query "电脑系统" retrieves "PC 操作系统"
  const pcHits = store.search('电脑系统配置', { includeArchived: false })
  assert.ok(pcHits.length > 0, 'Semantic query "电脑" should recall "PC"')
  assert.equal(pcHits[0].item.id, 'mem_pc')

  store.close()
})
