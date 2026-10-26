// The tags a Muse reply carries: the bubble split the phone taught the model, the hidden
// mood tag, and the sticker reference. Every reader is deliberately tolerant; the cases
// below are the spellings a real model produced on the phone (each one cost a bug there).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { extractMoodFeedback, findMemeRefs, hasBubbleTag, splitBubbles, stripBubbleTags, stripMoodTags } from '../lib/chat-markup.js'

test('a reply splits at every spelling of the bubble tag', () => {
  for (const tag of ['<nm:bubble/>', '<nm:bubble />', '<NM:BUBBLE>', '</nm:bubble>', '< nm:bubble />']) {
    assert.deepEqual(splitBubbles(`在呀${tag}怎么啦`), ['在呀', '怎么啦'], tag)
  }
})

test('no tag is one bubble, and a blank reply is none', () => {
  assert.deepEqual(splitBubbles('just one line'), ['just one line'])
  assert.deepEqual(splitBubbles(''), [])
  assert.deepEqual(splitBubbles('   \n  '), [])
})

test('the split caps at three bubbles, the tail staying one', () => {
  assert.deepEqual(splitBubbles('a<nm:bubble/>b<nm:bubble/>c<nm:bubble/>d'), ['a', 'b', 'c\n\nd'])
  assert.deepEqual(splitBubbles('a<nm:bubble/>b'), ['a', 'b'])
})

test('empty pieces around a tag are dropped', () => {
  assert.deepEqual(splitBubbles('a<nm:bubble/><nm:bubble/>b'), ['a', 'b'])
  assert.deepEqual(splitBubbles('<nm:bubble/>a'), ['a'])
  assert.deepEqual(splitBubbles('a<nm:bubble/>'), ['a'])
})

test('a tag inside a fenced code block is left alone', () => {
  const text = 'Look:\n```\nprint("a<nm:bubble/>b")\n```\nDone.'
  assert.deepEqual(splitBubbles(text), [text])
})

test('the bubble tag flattens to a paragraph break when it is not drawn', () => {
  assert.equal(stripBubbleTags('在呀<nm:bubble/>怎么啦'), '在呀\n\n怎么啦')
  assert.equal(hasBubbleTag('x<nm:bubble/>y'), true)
  assert.equal(hasBubbleTag('nothing'), false)
})

test('the mood tag is read from any spelling, and the last one wins', () => {
  const cases = [
    ['晚安。<nm:mood feel="tired" reason="深夜"/>', 'tired', '深夜'],
    ["晚安。<nm:mood feel='tired' reason='深夜' />", 'tired', '深夜'],
    ['晚安。<nm:mood feel="tired"></nm:mood>', 'tired', undefined],
    ['晚安。`<nm:mood feel="tired"/>`', 'tired', undefined],
    ['晚安。\n```\n<nm:mood feel="tired"/>\n```', 'tired', undefined],
    ['<NM:MOOD mood="proud" reason="被夸"/>', 'proud', '被夸'],
    ['<nm:mood type="coy"/>', 'coy', undefined],
    ['<nm:mood feel=tired reason=深夜>', 'tired', '深夜'],
  ]
  for (const [text, feel, reason] of cases) {
    const found = extractMoodFeedback(text)
    assert.equal(found?.feel, feel, text)
    assert.equal(found?.reason, reason, text)
  }
  assert.equal(extractMoodFeedback('a<nm:mood feel="happy"/><nm:mood feel="sulk"/>')?.feel, 'sulk')
})

test('a tag without a feel is no tag, and feedback="x" is not a feel', () => {
  assert.equal(extractMoodFeedback('<nm:mood feedback="happy"/>'), null)
  assert.equal(extractMoodFeedback('<nm:mood feel=""/>'), null)
  assert.equal(extractMoodFeedback('no tag here'), null)
})

test('the mood tag is stripped but an ordinary code block survives', () => {
  assert.equal(stripMoodTags('晚安。<nm:mood feel="tired"/>'), '晚安。')
  assert.equal(stripMoodTags('晚安。`<nm:mood feel="tired"/>`'), '晚安。')
  assert.equal(stripMoodTags('晚安。\n```\n<nm:mood feel="tired"/>\n```'), '晚安。')
  const code = 'Run:\n```bash\nls -la\n```'
  assert.equal(stripMoodTags(code), code)
})

test('a sticker reference is read in either spelling', () => {
  const refs = findMemeRefs('a<nm:meme id="001"/>b[表情: 得意]c<nm:meme name="大肥鱼-得意"/>')
  assert.deepEqual(refs.map((ref) => ref.query), ['001', '得意', '大肥鱼-得意'])
  assert.deepEqual(findMemeRefs('nothing'), [])
})
