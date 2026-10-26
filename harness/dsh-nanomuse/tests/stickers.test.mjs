// The sticker catalogue's pure half: one numbering across the packs, the phone's lookup
// order (a number, an id, a name, part of a name, then the keywords), and the paragraph the
// model reads.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { comparePacks, findSticker, flattenPacks, memePrompt, memeUrl } from '../lib/sticker-index.js'

const item = (packId, id, name, keywords = '') => ({ id, seq: '', name, filename: `${id}.jpg`, format: 'jpg', packId, packName: packId, keywords, url: memeUrl(packId, `${id}.jpg`) })
const pack = (id, name, builtin, items) => ({ id, name, description: '', count: items.length, builtin, items })

const catalogue = flattenPacks([
  pack('dafeiyu', '大肥鱼', true, [item('dafeiyu', 'dfy_deyi', '得意', '得意 好耶 开心'), item('dafeiyu', 'dfy_ganfan', '干饭', '吃饭 饿')]),
  pack('pack_a', '我的表情包', false, [item('pack_a', 'a1', '猫猫抱抱')]),
])

test('one numbering runs across the packs, in pack order', () => {
  assert.deepEqual(catalogue.items.map((entry) => entry.seq), ['001', '002', '003'])
  assert.equal(catalogue.items[2].name, '猫猫抱抱')
})

test('the built-in pack comes first, then the person\'s by name', () => {
  const sorted = [pack('pack_b', '乙', false, []), pack('dafeiyu', '大肥鱼', true, []), pack('pack_a', '甲', false, [])].sort(comparePacks)
  assert.deepEqual(sorted.map((entry) => entry.id), ['dafeiyu', 'pack_a', 'pack_b'])
})

test('a query finds a sticker by number, id, name, part of a name, then keywords', () => {
  assert.equal(findSticker(catalogue, '1')?.id, 'dfy_deyi')
  assert.equal(findSticker(catalogue, '003')?.id, 'a1')
  assert.equal(findSticker(catalogue, 'dfy_ganfan')?.id, 'dfy_ganfan')
  assert.equal(findSticker(catalogue, '干饭')?.id, 'dfy_ganfan')
  assert.equal(findSticker(catalogue, '抱抱')?.id, 'a1')
  assert.equal(findSticker(catalogue, '好耶')?.id, 'dfy_deyi')
  assert.equal(findSticker(catalogue, 'nope'), undefined)
  assert.equal(findSticker(catalogue, '  '), undefined)
})

test('the prompt names every pack, numbers its stickers, and keeps the rule short', () => {
  const text = memePrompt(catalogue)
  assert.match(text, /【大肥鱼】: \[001:得意\] \[002:干饭\]/)
  assert.match(text, /【我的表情包】: \[003:猫猫抱抱\]/)
  assert.match(text, /<nm:meme id="序号或名称"\/>/)
  assert.match(text, /At most one per reply/)
  assert.equal(memePrompt(flattenPacks([])), null)
  // a big pack is capped so the prompt cannot grow without bound
  const many = flattenPacks([pack('p', 'P', false, Array.from({ length: 60 }, (_, i) => item('p', `s${i}`, `名字${i}`)))])
  assert.equal(memePrompt(many).split('[').length - 1, 40)
})

test('a picture URL fences the pack and the file name', () => {
  assert.equal(memeUrl('dafeiyu', 'deyi.jpg'), '/nanomuse/stickers/img/dafeiyu/deyi.jpg')
  assert.equal(memeUrl('a b', 'x y.jpg'), '/nanomuse/stickers/img/a%20b/x%20y.jpg')
})
