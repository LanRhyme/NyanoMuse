/**
 * The little tags nanoMuse's chat writes into a reply, and the pure functions that read
 * them. Both halves of the desktop use this file: the host (to judge a mood and to resolve a
 * sticker) and the browser half (to draw the reply), and the phone writes the same tags, so a
 * turn synced from a phone splits into the same bubbles here.
 *
 *   <nm:bubble/>                          one bubble ends, the next begins
 *   <nm:mood feel="id" reason="…"/>       how the reply felt; hidden, kept in the mood store
 *   <nm:meme id="001"/>                   a sticker from the person's packs
 *   [表情:001]                            the same sticker, written the old way
 *
 * Every reader is tolerant on purpose (the phone learned it the hard way): a closing tag, a
 * single quote, a fenced block that holds nothing but the tag, and an attribute split across
 * a line are all the same tag. A fenced block holding real code is never touched.
 */

/** One bubble separator, in any spelling the model reaches for. */
export const BUBBLE_TAG = /<\s*nm:bubble\s*\/?>|<\s*\/\s*nm:bubble\s*>/gi

/** The most bubbles one reply becomes: the phone's rule, so a synced turn reads the same on both. */
export const MAX_BUBBLES = 3

export function hasBubbleTag(text: string): boolean {
  return /<\s*nm:bubble/i.test(text) || /<\s*\/\s*nm:bubble/i.test(text)
}

/** The tag gone, a paragraph break where it stood. */
export function stripBubbleTags(text: string): string {
  return text.replace(BUBBLE_TAG, '\n\n').replace(/\n{3,}/g, '\n\n').trim()
}

/** Positions of the fenced code blocks in `text`, as [open, close] offsets. */
function fences(text: string): Array<[number, number]> {
  const spans: Array<[number, number]> = []
  const open = /^[ \t]*(`{3,}|~{3,})/gm
  let match: RegExpExecArray | null
  let from = -1
  let marker = ''
  while ((match = open.exec(text)) !== null) {
    const run = match[1] ?? '```'
    if (from < 0) {
      from = match.index
      marker = run[0] ?? '`'
    } else if (run[0] === marker) {
      spans.push([from, match.index + run.length])
      from = -1
    }
  }
  // an unclosed fence runs to the end: nothing after it is a bubble boundary
  if (from >= 0) spans.push([from, text.length])
  return spans
}

/**
 * A reply cut into the bubbles it asked for. Separators inside a fenced code block are left
 * alone, empty pieces are dropped, and past [MAX_BUBBLES] the tail stays one bubble (so a
 * chatty reply does not become a wall of cards). No tags in, one bubble out.
 */
export function splitBubbles(text: string, max = MAX_BUBBLES): string[] {
  const body = text ?? ''
  if (!hasBubbleTag(body)) return body.trim() ? [body.trim()] : []
  const blocked = fences(body)
  const inside = (at: number): boolean => blocked.some(([from, to]) => at >= from && at < to)
  const parts: string[] = []
  let last = 0
  const scan = new RegExp(BUBBLE_TAG.source, 'gi')
  let match: RegExpExecArray | null
  while ((match = scan.exec(body)) !== null) {
    if (inside(match.index)) continue
    parts.push(body.slice(last, match.index))
    last = match.index + match[0].length
  }
  parts.push(body.slice(last))
  const clean = parts.map((part) => part.trim()).filter(Boolean)
  if (clean.length <= 1) return clean
  if (clean.length <= max) return clean
  return [...clean.slice(0, max - 1), clean.slice(max - 1).join('\n\n')]
}

/** A mood tag anywhere in the text (closing tag optional, quotes optional). */
export const MOOD_TAG = /<\s*nm:mood\b([^>]*)>\s*(?:<\s*\/\s*nm:mood\s*>)?/gi
const ATTR_FEEL = /(?:^|[^\w])(?:feel|mood|emotion|type)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s/>]+))/i
const ATTR_REASON = /(?:^|[^\w])reason\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s/>]+))/i
/** A fenced block that holds nothing but one tag, and a backtick pair around one. */
const MOOD_FENCE = /```[a-zA-Z]*[ \t]*\n?[ \t]*(?:<\s*nm:mood\b[^>]*\/\s*>)[ \t]*\n?```/gi
const MOOD_BARE_TICK = /`\s*(<\s*nm:mood\b[^>]*\/\s*>)\s*`/gi

export interface MoodFeedback {
  feel: string
  reason?: string
}

/** The last mood tag's feel and reason; a tag whose feel is empty is no tag. */
export function extractMoodFeedback(text: string): MoodFeedback | null {
  const scan = new RegExp(MOOD_TAG.source, 'gi')
  let found: MoodFeedback | null = null
  let match: RegExpExecArray | null
  while ((match = scan.exec(text ?? '')) !== null) {
    const attrs = match[1] ?? ''
    const feel = ATTR_FEEL.exec(attrs)
    if (!feel) continue
    const value = (feel[1] ?? feel[2] ?? feel[3] ?? '').trim()
    if (!value) continue
    const reason = ATTR_REASON.exec(attrs)
    const why = reason ? (reason[1] ?? reason[2] ?? reason[3] ?? '').trim() : ''
    found = why ? { feel: value, reason: why } : { feel: value }
  }
  return found
}

export function hasMoodTag(text: string): boolean {
  return /<\s*nm:mood/i.test(text ?? '')
}

/** The mood tags gone; a fence or a backtick pair that held only the tag goes with it. */
export function stripMoodTags(text: string): string {
  let out = text ?? ''
  for (let guard = 0; guard < 8; guard++) {
    const next = out.replace(MOOD_FENCE, '').replace(MOOD_BARE_TICK, '$1')
    if (next === out) break
    out = next
  }
  return out.replace(new RegExp(MOOD_TAG.source, 'gi'), '').trimEnd()
}

/** A sticker reference, in either spelling, with the query it names. */
export const MEME_TAG = /<\s*nm:meme\s+(?:id|name)\s*=\s*["']?([^"'>\s/]+)["']?[^>]*?\/?>|\[(?:表情|meme)\s*:\s*([^\]]+)\]/gi

export interface MemeRef {
  /** Offsets in the source text. */
  start: number
  end: number
  /** What the tag names: a sequence number, an id or a sticker's name. */
  query: string
}

export function findMemeRefs(text: string): MemeRef[] {
  const refs: MemeRef[] = []
  const scan = new RegExp(MEME_TAG.source, 'gi')
  let match: RegExpExecArray | null
  while ((match = scan.exec(text ?? '')) !== null) {
    const query = (match[1] ?? match[2] ?? '').trim()
    if (!query) continue
    refs.push({ start: match.index, end: match.index + match[0].length, query })
  }
  return refs
}

export function hasMemeTag(text: string): boolean {
  return /<\s*nm:meme/i.test(text ?? '') || /\[(?:表情|meme)\s*:/i.test(text ?? '')
}

export function stripMemeTags(text: string): string {
  return (text ?? '').replace(new RegExp(MEME_TAG.source, 'gi'), '').trim()
}
