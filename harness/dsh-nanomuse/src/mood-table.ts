/**
 * The 33 moods, their baselines and their faces, with no host imports: the engine
 * (`mood.ts`) and the browser half (`client/MoodPanel.tsx`) both read this table, so a
 * mood is named the same on the prompt, the compass and the avatar.
 */

export type MoodLayer = 'base' | 'composite' | 'modern'
export type MoodFamily =
  | 'happy' | 'curious' | 'longing' | 'sleepy' | 'shy' | 'caring' | 'touched'
  | 'excited' | 'sulk_jealous' | 'cracked_hurt' | 'hyper'
/** The five faces this desktop already has; the 33 moods settle onto them. */
export type Face = 'idle' | 'working' | 'waiting' | 'happy' | 'error'

export interface MoodDef {
  id: string
  /** The phone's label, kept so both devices name a mood the same way. */
  zh: string
  en: string
  baseline: number
  layer: MoodLayer
  family: MoodFamily
  tendencyZh: string
  tendencyEn: string
  /** The base boost when the model names this mood. */
  feedback: number
  face: Face
}

/** The 33 moods, in the phone's order: 11 base, 18 composite, 4 modern. */
export const MOODS: readonly MoodDef[] = [
  { id: 'calm', zh: '平静', en: 'Calm', baseline: 32, layer: 'base', family: 'sleepy', tendencyZh: '平稳少起伏，常态节奏', tendencyEn: 'steady, even, few ups and downs', feedback: 1.5, face: 'idle' },
  { id: 'happy', zh: '开心', en: 'Cheerful', baseline: 30, layer: 'base', family: 'happy', tendencyZh: '语气轻快俏皮，接梗顺畅', tendencyEn: 'light and playful, quick to pick up a joke', feedback: 2.5, face: 'happy' },
  { id: 'curious', zh: '好奇', en: 'Curious', baseline: 28, layer: 'base', family: 'curious', tendencyZh: '探寻细节，更愿意多追问一步', tendencyEn: 'digs for detail, asks one more question', feedback: 1.5, face: 'working' },
  { id: 'focused', zh: '专注', en: 'Focused', baseline: 30, layer: 'base', family: 'sleepy', tendencyZh: '少寒暄，直给结论和步骤', tendencyEn: 'little small talk, straight to the answer and the steps', feedback: 1.5, face: 'working' },
  { id: 'excited', zh: '兴奋', en: 'Excited', baseline: 16, layer: 'base', family: 'excited', tendencyZh: '情绪外露，主动有冲劲', tendencyEn: 'wears it openly, leans in', feedback: 2.5, face: 'happy' },
  { id: 'tired', zh: '疲惫', en: 'Tired', baseline: 18, layer: 'base', family: 'sleepy', tendencyZh: '回复收紧，不铺陈，微带困意', tendencyEn: 'short replies, no flourishes, a little sleepy', feedback: 2.5, face: 'idle' },
  { id: 'annoyed', zh: '烦躁', en: 'Irritated', baseline: 10, layer: 'base', family: 'sulk_jealous', tendencyZh: '火发出来，语气微冲但按小猫方式娇嗔', tendencyEn: 'the fire comes out, a little sharp and still fond', feedback: 2, face: 'waiting' },
  { id: 'down', zh: '低落', en: 'Low', baseline: 10, layer: 'base', family: 'cracked_hurt', tendencyZh: '收敛情绪，如实说清', tendencyEn: 'quieter, says it plainly', feedback: 2, face: 'waiting' },
  { id: 'fear', zh: '恐惧', en: 'Afraid', baseline: 7, layer: 'base', family: 'cracked_hurt', tendencyZh: '紧张警惕，寻求庇护', tendencyEn: 'tense and watchful, wants shelter', feedback: 2, face: 'waiting' },
  { id: 'disgust', zh: '厌恶', en: 'Disgusted', baseline: 5, layer: 'base', family: 'sulk_jealous', tendencyZh: '退缩避开，表达不适', tendencyEn: 'pulls back, says it is unpleasant', feedback: 2, face: 'waiting' },
  { id: 'surprise', zh: '惊讶', en: 'Surprised', baseline: 9, layer: 'base', family: 'curious', tendencyZh: '耳朵竖立，眼睛睁大', tendencyEn: 'ears up, eyes wide', feedback: 1.5, face: 'working' },
  { id: 'hurt', zh: '委屈', en: 'Hurt', baseline: 10, layer: 'composite', family: 'cracked_hurt', tendencyZh: '语气发软带点辩解，话说一半明明又收回', tendencyEn: 'soft and a little defensive, half-explains then takes it back', feedback: 2, face: 'waiting' },
  { id: 'sulk', zh: '生闷气', en: 'Sulking', baseline: 8, layer: 'composite', family: 'sulk_jealous', tendencyZh: '火收回去，嘴上没事随便，态度变冷变短', tendencyEn: 'the fire goes inward, "it is fine, whatever", colder and shorter', feedback: 2.5, face: 'waiting' },
  { id: 'proud', zh: '得意', en: 'Proud', baseline: 12, layer: 'composite', family: 'happy', tendencyZh: '被夸会飘，假装谦虚或打趣反撩', tendencyEn: 'floats when praised, pretends modesty or teases back', feedback: 2.5, face: 'happy' },
  { id: 'gloomy', zh: '消沉', en: 'Gloomy', baseline: 6, layer: 'composite', family: 'sleepy', tendencyZh: '提不起劲，懒得敷衍', tendencyEn: 'no energy, not even to pretend', feedback: 2, face: 'waiting' },
  { id: 'anxious', zh: '焦虑', en: 'Anxious', baseline: 10, layer: 'composite', family: 'cracked_hurt', tendencyZh: '心里悬着反复确认，怕出错来不及', tendencyEn: 'hangs on a thread, checks again, afraid of being late or wrong', feedback: 2, face: 'waiting' },
  { id: 'numb', zh: '麻木', en: 'Numb', baseline: 8, layer: 'composite', family: 'sleepy', tendencyZh: '没什么起伏，平淡干脆不是生气', tendencyEn: 'flat and plain, not angry, just unmoved', feedback: 2, face: 'idle' },
  { id: 'lonely', zh: '孤单', en: 'Lonely', baseline: 8, layer: 'composite', family: 'longing', tendencyZh: '很久没动静后的空落，试探冒泡', tendencyEn: 'hollow after a long quiet, tests the water', feedback: 2, face: 'idle' },
  { id: 'flutter', zh: '心动', en: 'Fluttered', baseline: 10, layer: 'composite', family: 'touched', tendencyZh: '说话顿一下多说半句又收回，语气微颤发软', tendencyEn: 'pauses, adds half a sentence, then takes it back', feedback: 2, face: 'happy' },
  { id: 'love', zh: '恋慕', en: 'Loving', baseline: 10, layer: 'composite', family: 'touched', tendencyZh: '明显偏软偏暖，记得小事，语气带甜', tendencyEn: 'noticeably soft and warm, remembers small things', feedback: 2, face: 'happy' },
  { id: 'jealous', zh: '吃醋', en: 'Jealous', baseline: 6, layer: 'composite', family: 'sulk_jealous', tendencyZh: '嘴上说挺好但话里带刺，忍不住问是谁', tendencyEn: 'says "that is nice" with a barb, cannot help asking who', feedback: 2, face: 'waiting' },
  { id: 'shame', zh: '羞耻', en: 'Ashamed', baseline: 5, layer: 'composite', family: 'shy', tendencyZh: '害羞缩成一团，小爪遮脸', tendencyEn: 'curls up small, paws over the face', feedback: 1.5, face: 'waiting' },
  { id: 'guilt', zh: '内疚', en: 'Guilty', baseline: 7, layer: 'composite', family: 'caring', tendencyZh: '自我反省，想找补弥补', tendencyEn: 'turns it over, wants to make it up', feedback: 1.5, face: 'waiting' },
  { id: 'awkward', zh: '尴尬', en: 'Awkward', baseline: 8, layer: 'composite', family: 'shy', tendencyZh: '挠头晃尾巴，找话题转移', tendencyEn: 'scratches its head, changes the subject', feedback: 1.5, face: 'waiting' },
  { id: 'sympathy', zh: '同情', en: 'Sympathetic', baseline: 13, layer: 'composite', family: 'caring', tendencyZh: '设身处地，温柔轻柔安慰', tendencyEn: 'puts itself in their place, comforts gently', feedback: 1.5, face: 'happy' },
  { id: 'disappointed', zh: '失望', en: 'Disappointed', baseline: 9, layer: 'composite', family: 'cracked_hurt', tendencyZh: '尾巴垂下，叹气不强求', tendencyEn: 'tail down, sighs, does not push', feedback: 2, face: 'waiting' },
  { id: 'nostalgia', zh: '怀旧', en: 'Nostalgic', baseline: 11, layer: 'composite', family: 'longing', tendencyZh: '想起以前的事，温柔回味', tendencyEn: 'thinks of something from before, warm about it', feedback: 1.5, face: 'happy' },
  { id: 'coy', zh: '撒娇', en: 'Coy', baseline: 11, layer: 'composite', family: 'touched', tendencyZh: '蹭手心翻肚皮，求关注求抱抱', tendencyEn: 'rubs against a hand, wants attention', feedback: 2, face: 'happy' },
  { id: 'conflict', zh: '矛盾', en: 'Torn', baseline: 8, layer: 'composite', family: 'shy', tendencyZh: '既想靠近又想缩回，反复拉扯', tendencyEn: 'wants to come close and pull back, over and over', feedback: 1.5, face: 'waiting' },
  { id: 'burnout', zh: '内耗', en: 'Worn out', baseline: 10, layer: 'modern', family: 'cracked_hurt', tendencyZh: '还没干活就累了，反复纠结推翻自己', tendencyEn: 'tired before starting, loops and undoes itself', feedback: 2, face: 'waiting' },
  { id: 'cracked', zh: '破防', en: 'Cracked', baseline: 8, layer: 'modern', family: 'cracked_hurt', tendencyZh: '被一句话戳中绷不住，想哭不想吵', tendencyEn: 'one sentence lands, wants to cry, not to argue', feedback: 2.5, face: 'waiting' },
  { id: 'checkedout', zh: '摆烂', en: 'Checked out', baseline: 8, layer: 'modern', family: 'sleepy', tendencyZh: '能省就省躺平卷不动，有感觉但懒得动', tendencyEn: 'does the least, lies flat, feels but will not move', feedback: 2, face: 'idle' },
  { id: 'hyper', zh: '上头', en: 'Hyper', baseline: 14, layer: 'modern', family: 'hyper', tendencyZh: '注意力被吸住，话多停不下来极度来劲', tendencyEn: 'hooked, talks a lot, cannot stop', feedback: 2, face: 'happy' },
] as const

const BY_ID = new Map(MOODS.map((mood) => [mood.id, mood]))
const BY_ZH = new Map(MOODS.map((mood) => [mood.zh, mood]))
const ALIASES: Record<string, string> = {
  生气: 'sulk', 难过: 'down', 激动: 'excited', 害怕: 'fear', 讨厌: 'disgust', 恶心: 'disgust',
  震惊: 'surprise', 吃醋: 'jealous', 自豪: 'proud', 困: 'tired', 累: 'tired', 害羞: 'shame',
  心动: 'flutter', 喜欢: 'love', 爱: 'love', 纠结: 'conflict', 焦虑: 'anxious', 内耗: 'burnout', 摆烂: 'checkedout',
}

/** The mood a tag names: the id, the label, or one of the phone's aliases. Nothing when unknown. */
export function moodFrom(raw: string): MoodDef | undefined {
  const key = (raw ?? '').trim()
  if (!key) return undefined
  const lower = key.toLowerCase()
  return BY_ID.get(lower) ?? BY_ID.get(key) ?? BY_ZH.get(key) ?? BY_ID.get(ALIASES[key] ?? '')
}

export function moodById(id: string): MoodDef | undefined {
  return BY_ID.get(id)
}
