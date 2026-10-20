import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { tokenizeForSearch, buildFtsQuery, rankCandidates } from '../src/memory-search.ts'

test('tokenizeForSearch generates CJK bigrams and lowercase words', () => {
  const cjk = '界面偏好低饱和度莫兰迪色系'
  const tokenized = tokenizeForSearch(cjk)
  assert.equal(tokenized, '界面 面偏 偏好 好低 低饱 饱和 和度 度莫 莫兰 兰迪 迪色 色系')

  const mixed = 'Arch Linux 与 Niri 合成器 (Wayland)'
  const tokenizedMixed = tokenizeForSearch(mixed)
  assert.ok(tokenizedMixed.includes('arch'))
  assert.ok(tokenizedMixed.includes('linux'))
  assert.ok(tokenizedMixed.includes('niri'))
  assert.ok(tokenizedMixed.includes('wayland'))
  assert.ok(tokenizedMixed.includes('合成 成器'))
})

test('buildFtsQuery builds safely quoted tokens joined with OR', () => {
  const query = '莫兰迪 UI'
  const fts = buildFtsQuery(query)
  assert.equal(fts, '"莫兰" OR "兰迪" OR "ui"')
})

test('evaluation against golden dataset matches top expectations', () => {
  const fixtureUrl = new URL('../../../tests/fixtures/memory/memory_eval_dataset.json', import.meta.url)
  const raw = readFileSync(fixtureUrl, 'utf8')
  const dataset = JSON.parse(raw)

  for (const q of dataset.queries) {
    const results = rankCandidates(q.query, dataset.facts, 1740001000000)
    assert.ok(results.length > 0, `Query "${q.query}" should have returned candidates`)
    const topId = results[0].item.id
    assert.ok(
      q.expected_top.includes(topId),
      `Query "${q.query}" expected top in [${q.expected_top.join(', ')}], got "${topId}" (${results[0].item.content})`,
    )
  }
})
