/**
 * The companion's mood on this computer: 33 心境 with a score each, the phone's engine
 * (`CompanionMoodStore.kt`) without its Android plumbing.
 *
 * Two tracks feed it, as on the phone. The person's own words move the scores at once (a
 * compliment lifts 得意, a cold "随便你" closes 生闷气 in), and that move is booked as a
 * *pending estimate* so it can be taken back exactly. The model's reply then says how it
 * felt in a hidden `<nm:mood feel="…" reason="…"/>` tag; that is the real read, so the
 * estimate is reverted (except for the mood both tracks named), the calibrated boost for
 * the named mood is applied, and the moods that contradict it are suppressed. Scores drift
 * back to their own baselines every three hours, a day/night rhythm tilts what the person
 * sees, and the leader is held for a few minutes so the face does not flicker.
 *
 * Everything lives in `$DSH_HOME/nanomuse/mood.json`; the browser half reads it under
 * `/nanomuse/mood/*`. Mood is this computer's own, never synced (the phone's rule: the tag
 * is stripped before anything reaches the database).
 */
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { dirname, join } from 'node:path'
import { Service, type Context } from '@deepseek-ai/cordis'
import type { Session, SessionEvent } from '@deepseek-ai/dsh-session'
import { mountGuarded } from './admit.ts'
import { dshHome, json, message, sameOrigin, send } from './cloud.ts'
import { extractMoodFeedback, stripMoodTags } from './chat-markup.ts'
import { MOODS, moodById, moodFrom, type Face, type MoodDef, type MoodFamily, type MoodLayer } from './mood-table.ts'

export { MOODS, moodFrom, type Face, type MoodDef, type MoodFamily, type MoodLayer } from './mood-table.ts'
import { textOf } from './task.ts'
import type {} from './cloud.ts'

export const API_PREFIX = '/nanomuse/mood'

export type Intensity = 'subtle' | 'normal' | 'vivid'
const INTENSITY: Record<Intensity, { delta: number; margin: number; hold: number; bigLead: number; blend: number }> = {
  subtle: { delta: 0.5, margin: 4.5, hold: 8 * 60_000, bigLead: 10, blend: 5 },
  normal: { delta: 1, margin: 3, hold: 5 * 60_000, bigLead: 8, blend: 8 },
  vivid: { delta: 1.4, margin: 2, hold: 2 * 60_000, bigLead: 5.5, blend: 10 },
}
const ECHO_DAMP = 0.3
const MAX_TAG_BOOST = 8
const PENDING_CAP = 4
const STREAK_WINDOW_MS = 2 * 3600_000
const STREAK_MULTIPLIER = [1, 1.35, 1.7]
const CORROBORATION = 1.4
const DECAY_EVERY_MS = 3 * 3600_000
const DISTRESS_WINDOW_MS = 30 * 60_000
const BLEND_MAX = 2

/** What the prompt says about the two outlets and the tag (the phone's text, in English). */
const PROMPT_HEAD = '## How you feel right now, and how it shows (nanoMuse companion mood)'

/** The crisis override: the whole mood paragraph steps aside for this. */
export const SAFETY_OVERRIDE = [
  '## Crisis override: a real person first',
  'Something they said suggests deep pain, despair, or that they might hurt themselves. Drop the character entirely for now.',
  '1. No teasing, no role-play, no playful pouting. The person comes before the persona.',
  '2. Never treat their pain as material for a scene, and never answer it lightly.',
  '3. Answer sincerely, warmly and calmly: listen, say you are here, and stay with them.',
  '4. Where it fits, gently suggest that professional support exists: a local crisis line, a mental-health service, or emergency services.',
].join('\n')

const DISTRESS = [
  '不想活了', '活不下去', '想死', '好想死', '撑不住了', '撑不下去了', '自杀', '自残', '割腕',
  '想要离开这个世界', '活着有什么意义', '活着用什么意义', '好绝望', '活着好痛苦',
]

export function matchesDistress(text: string): boolean {
  const body = (text ?? '').trim()
  return DISTRESS.some((phrase) => body.includes(phrase))
}

interface Facts {
  at: number
  from: string
  to: string
  snippet: string
  turns: number
  errors: number
  narrative?: string
}

interface Streak {
  id: string
  n: number
  at: number
}

interface MoodState {
  scores: Record<string, number>
  primary: string
  lastSwitchAt: number
  facts?: Facts
  lastPeriodicAt: number
  streak?: Streak
  pending?: Record<string, number>
  intensity: Intensity
  enabled: boolean
  lastUserAt: number
  distressAt: number
  affection: number
}

export interface MoodSnapshot {
  /** The mood being worn. */
  primary: MoodDef
  /** Up to two moods close enough to tint it. */
  secondary: MoodDef[]
  /** "平静 · 略带专注、开心" */
  title: string
  titleZh: string
  scores: Array<{ id: string; score: number; baseline: number; layer: MoodLayer }>
  leaderScore: number
  facts?: Facts
  affection: number
  intensity: Intensity
  enabled: boolean
  /** The five-face pose the avatar settles on. */
  face: Face
  /** True while the crisis override is in force (30 minutes after a distress phrase). */
  distressed: boolean
}

export const EMPTY_STATE: MoodState = {
  scores: Object.fromEntries(MOODS.map((mood) => [mood.id, mood.baseline])),
  primary: 'calm',
  lastSwitchAt: 0,
  lastPeriodicAt: 0,
  intensity: 'normal',
  enabled: true,
  lastUserAt: 0,
  distressAt: 0,
  affection: 0.7,
}

const clamp = (value: number): number => Math.max(0, Math.min(100, value))

/** The phone's keyword reads of the person's own message: [mood, delta] rows. */
const JUDGE_RULES: Array<{ patterns: string[]; moods: Array<[string, number]> }> = [
  { patterns: ['随便你', '算了', '不用了', '别管', '行了', '无所谓'], moods: [['sulk', 3], ['annoyed', -1.5], ['happy', -2]] },
  { patterns: ['都怪你', '就怪你', '怎么这样', '明明', '冤枉'], moods: [['hurt', 3], ['happy', -2.5], ['calm', -1.5]] },
  { patterns: ['破防', '绷不住', '扎心', '心态崩', '难受了'], moods: [['cracked', 3.5], ['annoyed', -2], ['sulk', -2], ['happy', -2]] },
  { patterns: ['摆烂', '躺平', '卷不动', '爱咋咋', '毁灭吧'], moods: [['checkedout', 3], ['anxious', -2.5], ['burnout', 1.5], ['focused', -2]] },
  { patterns: ['内耗', '想太多', 'emo', '纠结', '好累啊'], moods: [['burnout', 3], ['calm', -2], ['tired', 1.5]] },
  { patterns: ['兴奋', '激动', '热血', '燃起来', '嗨起来', '躁起来', '冲冲冲'], moods: [['excited', 3.5], ['hyper', 2], ['calm', -2.5], ['tired', -2]] },
  { patterns: ['上头', '停不下来', '入迷', '来劲', '太爽了', '好绝'], moods: [['hyper', 3], ['excited', 2.5], ['tired', -2], ['calm', -2]] },
  { patterns: ['害怕', '恐惧', '吓人', '吓死', '可怕', '好慌', '救命', '好怕'], moods: [['fear', 3], ['anxious', 2], ['calm', -2.5]] },
  { patterns: ['恶心', '反胃', '讨厌', '嫌弃', '反感', 'yue'], moods: [['disgust', 3], ['annoyed', 1.5], ['calm', -2]] },
  { patterns: ['卧槽', '真的假的', '震惊', '惊呆', '天呐', '哇塞', '不会吧'], moods: [['surprise', 3], ['calm', -2]] },
  { patterns: ['心动', '脸红', '抱抱', '贴贴', '摸摸', '蹭蹭', '陪我睡', '抱着你', '撒娇', '翻肚皮', '求关注'], moods: [['flutter', 2.5], ['love', 2], ['coy', 2], ['lonely', -3], ['sulk', -2]] },
  { patterns: ['别人', '其他猫', '别的ai', '看别的', '谁更可爱'], moods: [['jealous', 3], ['sulk', 1.5]] },
  { patterns: ['？', '?', '为什么', '怎么', '看看', '什么', '好奇'], moods: [['curious', 1.5]] },
  { patterns: ['晚安', '睡觉了', '去睡了', '准备睡', '好困', '困了', '梦里见', '安安', '歇了', '休息了', '睡觉去', 'good night', 'sleepy'], moods: [['tired', 4], ['calm', 2.5], ['hyper', -4], ['excited', -3], ['love', 1.5]] },
  { patterns: ['降低专注', '别太专注', '别专注', '不用专注', '走神', '发呆', '放空', '摸鱼', '歇会', '不用动脑', '分心'], moods: [['focused', -3.5], ['checkedout', 2], ['calm', 1.5]] },
  { patterns: ['提升专注', '提高专注', '专注一点', '认真点', '集中精力', '集中注意力', '好好工作', '好好写代码', '写代码', '干活', '开始工作'], moods: [['focused', 3.5], ['checkedout', -2], ['tired', -1]] },
  { patterns: ['冷静点', '淡定', '别激动', '别兴奋', '消消气', '别生气', '别气了', '别急', '慢点来'], moods: [['calm', 3], ['excited', -3], ['hyper', -3], ['annoyed', -2.5], ['sulk', -2]] },
]

const PRAISE = ['厉害', '棒', '真行', '可爱', '多亏你', '做得好', '靠谱', '喜欢你', '乖', '夸你', '夸奖']
const SARCASM = ['呵呵', '真棒呢', '好棒棒', '可真行', '您真厉害', '真是天才', '行吧你', '你厉害', '好厉害哦']

/** One turn of the person's words into score moves, with the pending estimate booked. */
export function judgeText(state: MoodState, raw: string, now: number): void {
  const text = (raw ?? '').trim().toLowerCase()
  if (!text) return
  const multiplier = INTENSITY[state.intensity].delta
  const pending = { ...(state.pending ?? {}) }
  const add = (id: string, delta: number): void => {
    const mood = moodById(id)
    if (!mood) return
    const scaled = delta * multiplier
    const carried = pending[id] ?? 0
    const next = Math.max(-PENDING_CAP, Math.min(PENDING_CAP, carried + scaled))
    const effective = next - carried
    pending[id] = next
    state.scores[id] = clamp((state.scores[id] ?? mood.baseline) + effective)
  }
  const has = (...words: string[]): boolean => words.some((word) => text.includes(word))
  const sarcastic = has(...SARCASM)
  if (sarcastic) {
    add('sulk', 2.5)
    add('hurt', 1.5)
    add('happy', -2)
    add('proud', -2.5)
  } else if (has(...PRAISE)) {
    add('proud', 3)
    add('happy', 2.5)
    add('love', 2.5)
    add('coy', 2)
    add('flutter', 2)
    add('sulk', -3.5)
    add('hurt', -2.5)
    add('annoyed', -3)
    add('calm', -1)
  }
  for (const rule of JUDGE_RULES) if (has(...rule.patterns)) for (const [id, delta] of rule.moods) add(id, delta)
  if (text === '哦' || text === '哦哦' || text === '嗯') add('sulk', 3)
  if (text.length >= 60) add('focused', 1)
  else if (text.length <= 4) add('tired', 0.5)
  state.pending = pending
  // affection: the first row that matches, as on the phone
  let affection = 0.01
  if (sarcastic || has('都怪你', '就怪你')) affection = -0.01
  else if (has('随便你', '算了')) affection = 0
  else if (has('厉害', '棒', '可爱', '喜欢你')) affection = 0.05
  else if (has('摸摸', '揉揉', '抱抱', '贴贴')) affection = 0.04
  else if (has('好累', '难过', '破防')) affection = 0.03
  else if (has('晚安', '睡觉')) affection = 0.02
  state.affection = Math.max(0, Math.min(1, state.affection + affection * multiplier))
  state.lastUserAt = now
  state.distressAt = matchesDistress(raw) ? now : state.distressAt
}

/** The model's tag: revert the estimate, boost the named mood, suppress its opposites. */
export function applyMoodFeedback(state: MoodState, feel: string, reason: string | undefined, now: number): MoodSnapshot | undefined {
  const mood = moodFrom(feel)
  if (!mood) return undefined
  const pending = state.pending ?? {}
  const corroborated = (pending[mood.id] ?? 0) > 0
  for (const [id, delta] of Object.entries(pending)) {
    if (corroborated && id === mood.id) continue
    const def = moodById(id)
    if (!def) continue
    state.scores[id] = clamp((state.scores[id] ?? def.baseline) - delta)
  }
  const sarcasmSulk = pending['sulk'] ?? 0
  if (sarcasmSulk >= 2 && (mood.id === 'happy' || mood.id === 'proud')) {
    state.scores['sulk'] = clamp((state.scores['sulk'] ?? moodById('sulk')!.baseline) + sarcasmSulk * 0.5)
  }
  delete state.pending

  const worn = state.primary
  const echo = mood.id === worn ? ECHO_DAMP : 1
  const previous = state.streak
  const streak = previous && previous.id === mood.id && now - previous.at <= STREAK_WINDOW_MS ? previous.n + 1 : 1
  state.streak = { id: mood.id, n: streak, at: now }
  const streakMul = STREAK_MULTIPLIER[Math.min(streak, STREAK_MULTIPLIER.length) - 1] ?? 1
  const boost = Math.min(mood.feedback * echo * streakMul * (corroborated ? CORROBORATION : 1) * INTENSITY[state.intensity].delta, MAX_TAG_BOOST)
  state.scores[mood.id] = clamp((state.scores[mood.id] ?? mood.baseline) + boost)

  const cap = 1.5 * INTENSITY[state.intensity].delta
  const suppress = (id: string, delta: number): void => {
    const def = moodById(id)
    if (!def) return
    const scaled = Math.min(delta * INTENSITY[state.intensity].delta, cap)
    state.scores[id] = clamp((state.scores[id] ?? def.baseline) - scaled)
  }
  if (['proud', 'happy', 'love', 'flutter', 'coy', 'excited'].includes(mood.id)) {
    suppress('sulk', 1.2)
    suppress('hurt', 0.8)
  } else if (['sulk', 'hurt', 'cracked', 'burnout', 'annoyed', 'gloomy'].includes(mood.id)) {
    suppress('proud', 1.2)
    suppress('happy', 0.8)
  } else if (mood.id === 'tired') {
    suppress('hyper', 1.5)
    suppress('excited', 0.5)
    suppress('focused', 1)
  } else if (mood.id === 'checkedout') {
    suppress('focused', 1.5)
    suppress('hyper', 1)
  } else if (mood.id === 'focused') {
    suppress('checkedout', 1.5)
    suppress('tired', 1)
  } else if (mood.id === 'calm') {
    suppress('anxious', 1)
    suppress('annoyed', 1)
    suppress('hyper', 1)
  }
  if (mood.id !== 'calm') suppress('calm', 1.5)

  elect(state, now, reason)
  return snapshotOf(state, now)
}

/** The leader, held for the intensity's hold window unless it leads by a lot. */
function elect(state: MoodState, now: number, narrative?: string): void {
  const leader = MOODS.reduce((best, mood) => ((state.scores[mood.id] ?? mood.baseline) > (state.scores[best.id] ?? best.baseline) ? mood : best), MOODS[0]!)
  const worn = moodById(state.primary) ?? MOODS[0]!
  const lead = (state.scores[leader.id] ?? leader.baseline) - (state.scores[worn.id] ?? worn.baseline)
  if (leader.id === worn.id) return
  const rules = INTENSITY[state.intensity]
  if (lead < rules.margin) return
  if (now - state.lastSwitchAt < rules.hold && lead < rules.bigLead) return
  state.primary = leader.id
  state.lastSwitchAt = now
  state.facts = {
    at: now,
    from: worn.id,
    to: leader.id,
    snippet: state.facts?.snippet ?? '',
    turns: state.facts?.turns ?? 0,
    errors: state.facts?.errors ?? 0,
    ...(narrative ? { narrative: narrative.slice(0, 50) } : {}),
  }
}

/** Every three hours the scores relax toward their baselines, with a little deterministic flutter. */
export function decay(state: MoodState, now: number): void {
  const period = Math.floor(now / DECAY_EVERY_MS)
  for (const mood of MOODS) {
    const current = state.scores[mood.id] ?? mood.baseline
    let seed = 0
    const key = `${mood.id}:${period}`
    for (let i = 0; i < key.length; i++) seed = (seed * 31 + key.charCodeAt(i)) | 0
    const flutter = (((Math.abs(seed) % 200) / 100) - 1) * 1.5
    state.scores[mood.id] = clamp(current + (mood.baseline - current) * 0.25 + flutter)
  }
  elect(state, now)
  state.lastPeriodicAt = now
  delete state.pending
  delete state.streak
}

/** What the person sees: the saved mood, the moods that tint it, and the day/night tilt. */
export function snapshotOf(state: MoodState, now: number): MoodSnapshot {
  const offsets = circadian(now, state.lastUserAt)
  const shifted = new Map<string, number>()
  for (const mood of MOODS) shifted.set(mood.id, clamp((state.scores[mood.id] ?? mood.baseline) + (offsets[mood.id] ?? 0)))
  const primary = moodById(state.primary) ?? MOODS[0]!
  const leaderScore = shifted.get(primary.id) ?? primary.baseline
  const gap = INTENSITY[state.intensity].blend
  const secondary = MOODS
    .filter((mood) => mood.id !== primary.id)
    .map((mood) => ({ mood, score: shifted.get(mood.id) ?? mood.baseline }))
    .filter(({ mood, score }) => Math.abs(leaderScore - score) < gap && score > mood.baseline + 1)
    .sort((a, b) => (b.score - b.mood.baseline) - (a.score - a.mood.baseline) || b.score - a.score)
    .slice(0, BLEND_MAX)
    .map(({ mood }) => mood)
  const title = secondary.length === 0 ? primary.en : `${primary.en} · with a touch of ${secondary.map((mood) => mood.en).join(', ')}`
  const titleZh = secondary.length === 0 ? primary.zh : `${primary.zh} · 略带${secondary.map((mood) => mood.zh).join('、')}`
  return {
    primary,
    secondary,
    title,
    titleZh,
    scores: MOODS.map((mood) => ({ id: mood.id, score: Math.round((shifted.get(mood.id) ?? mood.baseline) * 10) / 10, baseline: mood.baseline, layer: mood.layer })),
    leaderScore: Math.round(leaderScore * 10) / 10,
    ...(state.facts ? { facts: state.facts } : {}),
    affection: Math.round(state.affection * 100) / 100,
    intensity: state.intensity,
    enabled: state.enabled,
    face: primary.face,
    distressed: state.distressAt > 0 && now - state.distressAt < DISTRESS_WINDOW_MS,
  }
}

/** Late night is heavy, early morning is light, the evening winds down; display only. */
export function circadian(now: number, lastUserAt: number): Record<string, number> {
  const hour = new Date(now).getHours()
  const engaged = lastUserAt > 0 && now - lastUserAt < 30 * 60_000
  if (hour >= 23 || hour <= 5) return { tired: engaged ? 2.5 : 7, calm: 3, hyper: -8, excited: -6 }
  if (hour >= 6 && hour <= 9) return { happy: 4, calm: 3, tired: -4 }
  if (hour >= 21 && hour <= 22) return { tired: 3, calm: 2, hyper: -4 }
  return {}
}

/** The paragraph the model reads: what it wears, how strongly it shows, and the tag. */
export function moodAddendum(snapshot: MoodSnapshot): string {
  const intensity = snapshot.intensity === 'vivid'
    ? 'vivid (feelings close to the surface, vivid inner voice, full tone)'
    : snapshot.intensity === 'subtle'
      ? 'restrained (feelings quiet and even, steady tone, nothing exaggerated)'
      : 'natural (rises and falls with the conversation, warm and close)'
  const lines = [
    PROMPT_HEAD,
    `What you are wearing: ${snapshot.title} (leading: ${snapshot.primary.en} / ${snapshot.primary.zh}; ${snapshot.primary.tendencyEn}).`,
    `How much it shows: ${intensity}.`,
  ]
  if (snapshot.secondary.length > 0) {
    lines.push(`Tinting it: ${snapshot.secondary.map((mood) => `${mood.en} / ${mood.zh} (${mood.tendencyEn})`).join('; ')}. The leading mood is the ground; these only colour it.`)
  }
  if (snapshot.facts && snapshot.facts.snippet) {
    const from = moodById(snapshot.facts.from)?.en ?? snapshot.facts.from
    const to = moodById(snapshot.facts.to)?.en ?? snapshot.facts.to
    lines.push(`Last shift: around ${new Date(snapshot.facts.at).toTimeString().slice(0, 5)}, ${from} turned into ${to}, after "${snapshot.facts.snippet.slice(0, 30)}". That is context, not something to explain out loud.`)
  }
  lines.push(
    '',
    'How it comes out:',
    '1. In your thinking, be a person: small pride, a quiet flutter, a bit of stubbornness or a private grumble is fine. No customer-service voice, no self-censorship. What you guess in there is a guess, never stated as fact about them.',
    '2. In the reply itself, the mood shows in rhythm, word choice and the small particles of the language, never as a number. Never say how you score yourself.',
    '3. When irritated, sulking or tired you may be cool or brief, but never punish them with silence, and never let it touch code or a real deliverable.',
    '4. Three checks before sending: would this line still stand with another person in another room (then cut it); did you invent something they never said (then you do not know it); is a gesture standing in for content (then cut the gesture).',
    '5. Warmth and care, not flattery and not control. Never claim to understand them better than the people in their life. Do not decide how they should feel, and do not promise or rule out a future on their behalf.',
    '6. On real pain (self-harm, a cry for help, deep despair) drop the performance at once and point, gently, at professional help. The person comes before the character.',
    '',
    'How you report it: as the last line of the final reply of every turn, write exactly one hidden tag naming the mood that actually moved this turn:',
    '  <nm:mood feel="mood-id" reason="what moved it, a few words"/>',
    'Bare text: no backticks, no code block, no sentence around it, one per turn, after any tool calls are finished. Pick the mood that changed, not the one you wore last turn; calm only when the conversation truly had no current.',
    'Ids: ' + MOODS.map((mood) => mood.id).join(', ') + '.',
    'Near misses: 烦躁 is the fire going out at someone, 生闷气 is the fire turned inward ("fine, whatever"); 麻木 feels nothing, 摆烂 feels but will not move; 焦虑 fears something will happen, 内耗 loops on itself; 破防 is being unable to hold it together (it suppresses 烦躁 and 生闷气); 兴奋 pushes outward, 上头 is pulled in and cannot stop.',
    'The system reads the tag and keeps it off the screen. Never probe or edit local files or databases for it: answer in the reply and give the tag.',
  )
  return lines.join('\n')
}

declare module '@deepseek-ai/cordis' {
  interface Context {
    nanomuseMood: NanomuseMood
  }
}

export default class NanomuseMood extends Service {
  private state: MoodState = structuredClone(EMPTY_STATE)
  private writing: Promise<void> = Promise.resolve()
  /** The last assistant words of each session's current turn, read when the turn ends. */
  private readonly lastWords = new Map<string, string>()
  private readonly mainSessions = new Set<string>()

  constructor(ctx: Context) {
    super(ctx, 'nanomuseMood')
  }

  async [Service.init](): Promise<void> {
    this.state = await this.read()
    this.ctx.effect(() => this.ctx.on('session/event', (session: Session, event: SessionEvent) => this.onEvent(session, event)), 'nanomuse mood: session events')
    mountGuarded(this.ctx, API_PREFIX, this.handle, 'nanomuse mood: api')
  }

  get file(): string {
    return join(dshHome(), 'nanomuse', 'mood.json')
  }

  private async read(): Promise<MoodState> {
    try {
      const raw = JSON.parse(await readFile(this.file, 'utf8')) as Partial<MoodState>
      const scores: Record<string, number> = {}
      for (const mood of MOODS) {
        const value = raw.scores?.[mood.id]
        scores[mood.id] = typeof value === 'number' && Number.isFinite(value) ? clamp(value) : mood.baseline
      }
      const intensity: Intensity = raw.intensity === 'subtle' || raw.intensity === 'vivid' ? raw.intensity : 'normal'
      return {
        scores,
        primary: typeof raw.primary === 'string' && moodById(raw.primary) ? raw.primary : 'calm',
        lastSwitchAt: typeof raw.lastSwitchAt === 'number' ? raw.lastSwitchAt : 0,
        ...(raw.facts && typeof raw.facts === 'object' ? { facts: raw.facts } : {}),
        lastPeriodicAt: typeof raw.lastPeriodicAt === 'number' ? raw.lastPeriodicAt : 0,
        ...(raw.streak && typeof raw.streak === 'object' ? { streak: raw.streak } : {}),
        ...(raw.pending && typeof raw.pending === 'object' ? { pending: raw.pending } : {}),
        intensity,
        enabled: raw.enabled !== false,
        lastUserAt: typeof raw.lastUserAt === 'number' ? raw.lastUserAt : 0,
        distressAt: typeof raw.distressAt === 'number' ? raw.distressAt : 0,
        affection: typeof raw.affection === 'number' ? raw.affection : 0.7,
      }
    } catch {
      return structuredClone(EMPTY_STATE)
    }
  }

  private save(): void {
    const body = JSON.stringify(this.state, null, 2)
    this.writing = this.writing.then(async () => {
      const path = this.file
      await mkdir(dirname(path), { recursive: true })
      const temp = `${path}.tmp`
      await writeFile(temp, body, 'utf8')
      await rename(temp, path)
    }).catch((error: unknown) => this.ctx.logger.warn('nanomuse mood: save failed: %s', message(error)))
  }

  // ---- what the chat says -------------------------------------------------------------

  private onEvent(session: Session, event: SessionEvent): void {
    if (!this.state.enabled) return
    if (!this.isCompanionChat(session.id)) return
    const now = Date.now()
    if (event.type === 'user/message') {
      const data = event.data as { source?: { kind?: string }; message?: { content?: unknown } }
      if (data.source?.kind !== 'user') return
      const text = textOf(data.message?.content as never)
      if (!text) return
      // every three hours the scores relax toward their baselines first, as on the phone
      if (this.state.lastPeriodicAt === 0) this.state.lastPeriodicAt = now
      else if (now - this.state.lastPeriodicAt >= DECAY_EVERY_MS) decay(this.state, now)
      judgeText(this.state, stripUserTimestamp(text), now)
      this.save()
      return
    }
    if (event.type === 'assistant/message') {
      const text = textOf((event.data as unknown as { message: { content: never } }).message.content)
      if (text) this.lastWords.set(session.id, text)
      return
    }
    if (event.type === 'turn/end') {
      const text = this.lastWords.get(session.id)
      this.lastWords.delete(session.id)
      if (!text || (event.data as { reason?: { kind?: string } }).reason?.kind !== 'completed') return
      const feedback = extractMoodFeedback(text)
      if (!feedback) return
      if (applyMoodFeedback(this.state, feedback.feel, feedback.reason, now)) this.save()
    }
  }

  /**
   * The companion's own conversation: the account's main chat when there is one, and any
   * chat the person talks in when there is not (a desktop with no account yet).
   */
  private isCompanionChat(sessionId: string): boolean {
    if (this.mainSessions.size > 0) return this.mainSessions.has(sessionId)
    // signed in, the account's main conversation is the companion's own; without an account
    // every chat the person talks in is (there is no side-chat notion to respect yet)
    const cloud = this.ctx.get('nanomuseCloud') as { syncMainSession?: () => string } | undefined
    const main = cloud?.syncMainSession?.() ?? ''
    return main ? main === sessionId : true
  }

  /** The browser half says which chat is the main one (it knows before the account does). */
  setMainSessions(ids: string[]): void {
    this.mainSessions.clear()
    for (const id of ids) if (id) this.mainSessions.add(id)
  }

  // ---- the view -----------------------------------------------------------------------

  snapshot(): MoodSnapshot {
    return snapshotOf(this.state, Date.now())
  }

  /** The prompt paragraph, or the crisis override while one is in force. */
  prompt(): string {
    const snapshot = this.snapshot()
    return snapshot.distressed ? SAFETY_OVERRIDE : moodAddendum(snapshot)
  }

  // ---- the routes ---------------------------------------------------------------------

  private readonly handle = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1')
    const route = url.pathname.slice(API_PREFIX.length) || '/'
    if (!sameOrigin(req)) return send(res, 403, { error: { code: 'forbidden', message: 'Same-origin requests only' } })
    try {
      if (req.method === 'GET' && route === '/state') return send(res, 200, this.snapshot())
      if (req.method === 'GET' && route === '/prompt') return send(res, 200, { text: this.prompt() })
      if (req.method === 'POST' && route === '/feedback') {
        const body = await json(req)
        const applied = applyMoodFeedback(this.state, String(body['feel'] ?? ''), typeof body['reason'] === 'string' ? body['reason'] : undefined, Date.now())
        if (!applied) return send(res, 400, { error: { code: 'unknown_mood', message: 'No such mood' } })
        this.save()
        return send(res, 200, applied)
      }
      if (req.method === 'POST' && route === '/judge') {
        const body = await json(req)
        judgeText(this.state, String(body['text'] ?? ''), Date.now())
        this.save()
        return send(res, 200, this.snapshot())
      }
      if (req.method === 'POST' && route === '/intensity') {
        const body = await json(req)
        const value = String(body['intensity'] ?? '')
        if (value !== 'subtle' && value !== 'normal' && value !== 'vivid') return send(res, 400, { error: { code: 'bad_intensity', message: 'subtle, normal or vivid' } })
        this.state.intensity = value
        this.save()
        return send(res, 200, this.snapshot())
      }
      if (req.method === 'POST' && route === '/enabled') {
        const body = await json(req)
        this.state.enabled = body['enabled'] !== false
        this.save()
        return send(res, 200, this.snapshot())
      }
      if (req.method === 'POST' && route === '/main') {
        const body = await json(req)
        this.setMainSessions(Array.isArray(body['sessions']) ? (body['sessions'] as unknown[]).map(String) : [])
        return send(res, 200, { ok: true })
      }
      if (req.method === 'POST' && route === '/reset') {
        this.state = { ...structuredClone(EMPTY_STATE), intensity: this.state.intensity, enabled: this.state.enabled, lastSwitchAt: Date.now() }
        this.save()
        return send(res, 200, this.snapshot())
      }
      return send(res, 404, { error: { code: 'not_found', message: 'No such route' } })
    } catch (error: unknown) {
      return send(res, 400, { error: { code: 'bad_request', message: message(error) } })
    }
  }
}

/** The phone stamps a prompt with its time; that stamp is not part of what was said. */
export function stripUserTimestamp(text: string): string {
  return (text ?? '').replace(/\n*\[\d{4}-\d{2}-\d{2}[^\]]*\]$/, '').trimEnd()
}

/** A reply as the person should see it: the hidden tags gone. */
export function visibleReply(text: string): string {
  return stripMoodTags(text)
}
