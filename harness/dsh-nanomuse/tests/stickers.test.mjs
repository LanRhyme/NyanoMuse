// The sticker catalogue's pure half: one numbering across the packs, the phone's lookup
// order (a number, an id, a name, part of a name, then the keywords), and the paragraph the
// model reads.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { comparePacks, findSticker, flattenPacks, memePlacements, memePrompt, memeTags, memeUrl } from '../lib/sticker-index.js'

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

test('a meme tag resolves the way the phone resolves it, and leaves the rest of the text alone', () => {
  // the number the prompt shows, the same number without its zeros, the id, the name, part of
  // a name, a keyword, and the phone's bracket form: one sticker, whichever was written
  const same = ['<nm:meme id="002"/>', '<nm:meme id="2"/>', '<nm:meme id="dfy_ganfan"/>', '<nm:meme name="干饭"/>', '<nm:meme id="干"/>', '<nm:meme id="吃饭"/>', '[表情: 干饭]', '[meme: 饿]']
  for (const tag of same) {
    const spots = memePlacements(catalogue, tag)
    assert.equal(spots.length, 1, tag)
    assert.equal(spots[0].sticker.id, 'dfy_ganfan', tag)
    assert.equal(tag.slice(spots[0].start, spots[0].end), tag)
  }
  // a tag that names nothing has no sticker, and a renderer decides what to do with it
  assert.deepEqual(memePlacements(catalogue, '<nm:meme id="999"/>'), [])
  assert.deepEqual(memePlacements(catalogue, '<nm:meme id=""/>'), [])
  const unknown = memeTags(catalogue, '喏<nm:meme id="999"/>')
  assert.equal(unknown.length, 1)
  assert.equal(unknown[0].sticker, undefined)
  assert.equal(unknown[0].query, '999')
  assert.equal(unknown[0].start, 1)
  // two tags in one paragraph, with the words between them left in place
  const text = '好耶<nm:meme id="001"/>然后去[表情: 猫猫抱抱]了'
  const spots = memePlacements(catalogue, text)
  assert.deepEqual(spots.map((s) => s.sticker.id), ['dfy_deyi', 'a1'])
  let rest = ''
  let last = 0
  for (const spot of spots) { rest += text.slice(last, spot.start); last = spot.end }
  rest += text.slice(last)
  assert.equal(rest, '好耶然后去了')
  // an empty catalogue names nothing, and an empty text has nothing to name
  assert.deepEqual(memePlacements(flattenPacks([]), '<nm:meme id="001"/>'), [])
  assert.deepEqual(memePlacements(catalogue, ''), [])
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
