// The companion's mood engine, without Android: the person's words move the scores at once
// (and are booked so a tag can take the guess back), the model's tag is the real read, the
// leader is held, the scores drift home every three hours, and the day/night tilt is display
// only.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EMPTY_STATE, MOODS, SAFETY_OVERRIDE, applyMoodFeedback, circadian, decay, judgeText, matchesDistress, moodAddendum, moodFrom, snapshotOf } from '../lib/mood.js'

const fresh = () => structuredClone(EMPTY_STATE)
const score = (state, id) => state.scores[id]

test('the table is the phone\'s: 33 moods, 11 base, 18 composite, 4 modern', () => {
  assert.equal(MOODS.length, 33)
  assert.equal(MOODS.filter((mood) => mood.layer === 'base').length, 11)
  assert.equal(MOODS.filter((mood) => mood.layer === 'composite').length, 18)
  assert.equal(MOODS.filter((mood) => mood.layer === 'modern').length, 4)
  assert.equal(moodFrom('calm').zh, '平静')
  assert.equal(moodFrom('PROUD').id, 'proud')
  assert.equal(moodFrom('生气').id, 'sulk')
  assert.equal(moodFrom('nonsense'), undefined)
})

test('a compliment lifts 得意 and 开心 and closes 生闷气 in, and the guess is booked', () => {
  const state = fresh()
  const sulkBefore = score(state, 'sulk')
  judgeText(state, '你真厉害，做得好棒', 1_000)
  assert.ok(score(state, 'proud') > 12)
  assert.ok(score(state, 'happy') > 30)
  assert.ok(score(state, 'sulk') < sulkBefore)
  assert.ok(Object.keys(state.pending ?? {}).length > 0)
  assert.ok(state.affection > 0.7)
})

test('the pending guess is capped, so one message cannot teleport a mood', () => {
  const state = fresh()
  for (let i = 0; i < 20; i++) judgeText(state, '你真厉害，做得好棒，靠谱，喜欢你', 1_000 + i)
  for (const delta of Object.values(state.pending ?? {})) assert.ok(Math.abs(delta) <= 4.0001)
})

test('the tag is the real read: the guess is reverted and the named mood boosted', () => {
  const state = fresh()
  const sulkBefore = score(state, 'sulk')
  judgeText(state, '随便你', 1_000)
  assert.ok(score(state, 'sulk') > sulkBefore)
  const after = applyMoodFeedback(state, 'calm', '没什么', 2_000)
  assert.ok(after)
  assert.equal(state.pending, undefined)
  // the sulk the heuristic added is taken back, and calm rises
  assert.ok(score(state, 'sulk') < sulkBefore + 0.001)
  assert.ok(score(state, 'calm') > 32)
})

test('an unknown tag discards the turn instead of defaulting to calm', () => {
  const state = fresh()
  assert.equal(applyMoodFeedback(state, 'not-a-mood', undefined, 1_000), undefined)
  assert.equal(applyMoodFeedback(state, '', undefined, 1_000), undefined)
})

test('naming the mood already worn moves it only a little (the echo guard)', () => {
  const worn = fresh()
  const other = fresh()
  applyMoodFeedback(worn, 'calm', undefined, 1_000)
  applyMoodFeedback(other, 'happy', undefined, 1_000)
  assert.ok(score(worn, 'calm') - 32 < score(other, 'happy') - 30)
})

test('one reply cannot boost a mood past the cap, however loud it was', () => {
  // vivid, a streak of the same tag and a guess that agrees: the strongest single reply there is
  const state = fresh()
  state.intensity = 'vivid'
  for (let i = 0; i < 3; i++) {
    judgeText(state, '好兴奋，冲冲冲', 1_000 + i)
    applyMoodFeedback(state, 'excited', '燃', 2_000 + i * 1_000)
  }
  const before = score(state, 'excited')
  judgeText(state, '好兴奋，冲冲冲', 9_000)
  applyMoodFeedback(state, 'excited', '燃', 10_000)
  assert.ok(score(state, 'excited') - before <= 8.0001)
})

test('the leader is held: a small lead does not switch it, a big one does', () => {
  const state = fresh()
  state.primary = 'calm'
  state.lastSwitchAt = 10_000
  state.scores.calm = 40
  state.scores.happy = 41
  applyMoodFeedback(state, 'calm', undefined, 20_000)
  assert.equal(state.primary, 'calm')
  state.scores.happy = 60
  applyMoodFeedback(state, 'calm', undefined, 21_000)
  assert.equal(state.primary, 'happy')
})

test('three hours of quiet pull the scores back toward their baselines', () => {
  const state = fresh()
  state.scores.happy = 70
  state.scores.calm = 10
  decay(state, 3 * 3600_000)
  assert.ok(score(state, 'happy') < 70)
  assert.ok(score(state, 'calm') > 10)
  // one pass moves a quarter of the way home, with a deterministic flutter of at most 1.5
  assert.ok(score(state, 'happy') > 58 && score(state, 'happy') < 62, String(score(state, 'happy')))
})

test('the day/night tilt is display only', () => {
  const state = fresh()
  const stored = score(state, 'tired')
  const night = new Date('2026-03-10T23:30:00+08:00').getTime()
  const snapshot = snapshotOf(state, night)
  const tired = snapshot.scores.find((entry) => entry.id === 'tired')
  assert.ok(tired.score > stored)
  assert.equal(score(state, 'tired'), stored)
})

test('the snapshot names the mood and its companions', () => {
  const state = fresh()
  const snapshot = snapshotOf(state, Date.now())
  assert.equal(snapshot.primary.id, 'calm')
  assert.match(snapshot.title, /Calm/)
  assert.match(snapshot.titleZh, /平静/)
  assert.equal(typeof snapshot.face, 'string')
  assert.equal(snapshot.scores.length, 33)
})

test('the prompt carries the mood, the tag and the near misses, in one paragraph', () => {
  const text = moodAddendum(snapshotOf(fresh(), Date.now()))
  assert.match(text, /What you are wearing/)
  assert.match(text, /<nm:mood feel="mood-id" reason="what moved it, a few words"\/>/)
  assert.match(text, /烦躁 is the fire going out at someone/)
  assert.ok(text.length < 4000)
})

test('a distress phrase switches the whole paragraph to the crisis override', () => {
  assert.equal(matchesDistress('我不想活了'), true)
  assert.equal(matchesDistress('今天天气不错'), false)
  const state = fresh()
  judgeText(state, '我不想活了', 1_000)
  const snapshot = snapshotOf(state, 1_500)
  assert.equal(snapshot.distressed, true)
  assert.match(SAFETY_OVERRIDE, /Crisis override/)
  assert.doesNotMatch(SAFETY_OVERRIDE, /nm:mood/)
})

test('the night is heavy, the morning light, the evening winding down', () => {
  const at = (hour) => new Date(`2026-03-10T${String(hour).padStart(2, '0')}:00:00+08:00`).getTime()
  assert.ok(circadian(at(2), 0).tired > 0)
  assert.ok(circadian(at(7), 0).happy > 0)
  assert.ok(circadian(at(22), 0).tired > 0)
  assert.deepEqual(circadian(at(14), 0), {})
})
