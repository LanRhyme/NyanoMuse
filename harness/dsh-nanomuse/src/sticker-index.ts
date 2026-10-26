/**
 * The sticker catalogue's shape and its pure lookups, shared by the host (which owns the
 * packs) and the browser half (which draws the picker and the bubbles). One rule is the
 * phone's, unchanged, so a name means the same sticker on both: a sequence number, an id, a
 * name, part of a name, then the keywords.
 */

export const STICKERS_PREFIX = '/nanomuse/stickers'
export const IMG_PREFIX = `${STICKERS_PREFIX}/img`

export interface StickerItemView {
  id: string
  /** The three-digit number the prompt shows and the person can say out loud ("001"). */
  seq: string
  name: string
  filename: string
  format: string
  packId: string
  packName: string
  keywords: string
  /** Where the browser fetches the picture. */
  url: string
}

export interface StickerPackView {
  id: string
  name: string
  description: string
  count: number
  /** A pack that ships with the app: it can be emptied but not removed. */
  builtin: boolean
  items: StickerItemView[]
}

export interface StickerCatalogue {
  packs: StickerPackView[]
  /** Every sticker, in pack order, each with its number. */
  items: StickerItemView[]
}

export const EMPTY_CATALOGUE: StickerCatalogue = { packs: [], items: [] }

/** The URL of one sticker's picture, with the pack's folder fenced off from the rest of the disk. */
export function memeUrl(packId: string, filename: string): string {
  return `${IMG_PREFIX}/${encodeURIComponent(packId)}/${encodeURIComponent(filename)}`
}

/** Pack order: the built-in pack first, then the person's by name. */
export function comparePacks(a: { id: string; builtin: boolean; name: string }, b: { id: string; builtin: boolean; name: string }): number {
  if (a.builtin !== b.builtin) return a.builtin ? -1 : 1
  return a.name.localeCompare(b.name, 'zh-Hans-CN', { numeric: true })
}

/** The flat list the lookups run over, renumbering from 1 in pack order. */
export function flattenPacks(packs: StickerPackView[]): StickerCatalogue {
  const items: StickerItemView[] = []
  const numbered: StickerPackView[] = packs.map((pack) => ({
    ...pack,
    items: pack.items.map((item) => {
      const seq = String(items.length + 1).padStart(3, '0')
      const next = { ...item, seq }
      items.push(next)
      return next
    }),
  }))
  return { packs: numbered, items }
}

/**
 * The sticker a query names: a number ("1", "001"), an id (whole or as a prefix), an exact
 * name, part of a name, then the keywords. Nothing for an empty query or no catalogue.
 */
export function findSticker(catalogue: StickerCatalogue, query: string): StickerItemView | undefined {
  const q = (query ?? '').trim()
  if (!q) return undefined
  const items = catalogue.items
  if (items.length === 0) return undefined
  const bare = (value: string): string => value.replace(/^0+/, '')
  const bySeq = items.find((item) => item.seq === q || bare(item.seq) === bare(q))
  if (bySeq) return bySeq
  const lower = q.toLowerCase()
  const byId = items.find((item) => item.id.toLowerCase() === lower) ?? items.find((item) => item.id.toLowerCase().startsWith(lower))
  if (byId) return byId
  const byName = items.find((item) => item.name.toLowerCase() === lower)
  if (byName) return byName
  const byPart = items.find((item) => item.name.toLowerCase().includes(lower) || lower.includes(item.name.toLowerCase()))
  if (byPart) return byPart
  return items.find((item) => item.keywords.toLowerCase().includes(lower))
}

/** The one sticker a pack holds under that id (or filename), for a delete or a rename. */
export function stickerInPack(pack: StickerPackView, key: string): StickerItemView | undefined {
  return pack.items.find((item) => item.id === key || item.filename === key)
}

/**
 * What the model is told: the packs and their stickers, numbered, with the rule for using
 * them. Null when there is nothing to send. `limit` caps the entries per pack so a large
 * collection cannot crowd the prompt.
 */
export function memePrompt(catalogue: StickerCatalogue, limit = 40): string | null {
  const lines: string[] = []
  for (const pack of catalogue.packs) {
    const named = pack.items.filter((item) => item.name)
    if (named.length === 0) continue
    const shown = named.slice(0, limit).map((item) => `[${item.seq}:${item.name}]`).join(' ')
    lines.push(`  【${pack.name}】: ${shown}`)
  }
  if (lines.length === 0) return null
  return [
    'Stickers (表情包). When the chat is light — a joke lands, you are teased, coaxed or complained at — you may send one sticker by writing `<nm:meme id="序号或名称"/>` on a line of its own or at the end of a bubble. At most one per reply.',
    '  Example: 哈哈确实！<nm:bubble/><nm:meme id="001"/>',
    '  Example: 太真实了<nm:bubble/><nm:meme id="得意"/><nm:bubble/>我明天也这么干',
    'The packs you can draw from:',
    ...lines,
    'Only a number or a name from the list above. Never in code, a long technical answer, or a serious conversation, and never twice in one reply.',
  ].join('\n')
}
