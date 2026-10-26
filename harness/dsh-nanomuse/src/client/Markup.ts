/**
 * The three tags of a Muse reply, drawn on the harness's chat (the desktop's half of what
 * the phone's transcript builder does):
 *
 * - `<nm:bubble/>` splits one finished reply into the two or three bubbles it was written
 *   as. The body the harness rendered is left untouched and hidden; a stack of bubbles is
 *   built from a *clone* of it, so React's tree is never rearranged and the Markdown (code,
 *   links, lists) survives the split.
 * - `<nm:meme id="…"/>` becomes the sticker's picture, resolved against the packs on this
 *   computer; a tag that names nothing stays as text, as on the phone.
 * - `<nm:mood …/>` is hidden: the host read it from the session when the turn ended, and
 *   the person never sees it.
 *
 * One MutationObserver, idempotent sweeps, mutations we make ourselves ignored, the rest
 * coalesced over 100 ms (the pattern `RemoteBubbles.ts` already uses). A turn that is still
 * streaming is never split, only cleaned.
 */
import { hasBubbleTag, hasMemeTag, hasMoodTag, MOOD_TAG, MEME_TAG } from '../chat-markup.ts'
import type { Translate } from './api.ts'
import { getStickers, refreshStickers, subscribeStickers } from './stickers.ts'

const SWEEP_DELAY_MS = 100
const BODY = '[class*="_body"]'
const ITEM = '[data-chat-flow-kind]'

/** Code is code: never cut a tag out of it, and never split it. */
function inCode(node: Node): boolean {
  for (let el = node.parentElement; el; el = el.parentElement) {
    const tag = el.tagName
    if (tag === 'PRE' || tag === 'CODE') return true
    if (el.hasAttribute('data-chat-flow-kind')) return false
  }
  return false
}

function textNodes(root: Node): Text[] {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const found: Text[] = []
  while (walker.nextNode()) found.push(walker.currentNode as Text)
  return found
}

/** The body the harness rendered a message into, or the item itself. */
function bodyOf(item: HTMLElement): HTMLElement | null {
  const node = item.querySelector('[data-slot="conversation.chat.node"]') ?? item
  const body = node.querySelector(BODY)
  return (body as HTMLElement | null) ?? (node as HTMLElement)
}

/** The mood tag out of the visible text; a paragraph left empty by it goes too. */
function hideMoodTags(root: HTMLElement): void {
  for (const node of textNodes(root)) {
    if (!hasMoodTag(node.data) || inCode(node)) continue
    node.data = node.data.replace(new RegExp(MOOD_TAG.source, 'gi'), '').replace(/[ \t]+$/gm, '')
    const parent = node.parentElement
    if (parent && parent !== root && parent.children.length === 0 && !(parent.textContent ?? '').trim()) {
      parent.style.display = 'none'
    }
  }
}

/** A sticker tag becomes its picture; one that names nothing is left alone. */
function renderMemes(root: HTMLElement): void {
  const catalogue = getStickers()
  if (catalogue.items.length === 0) return
  for (const node of textNodes(root)) {
    if (!hasMemeTag(node.data) || inCode(node)) continue
    const text = node.data
    const scan = new RegExp(MEME_TAG.source, 'gi')
    let match: RegExpExecArray | null
    let last = 0
    const frag = document.createDocumentFragment()
    let changed = false
    while ((match = scan.exec(text)) !== null) {
      const query = (match[1] ?? match[2] ?? '').trim()
      const sticker = query ? catalogue.items.find((item) => item.id === query || item.seq === query || item.name === query) ?? undefined : undefined
      if (!sticker) continue
      changed = true
      frag.append(document.createTextNode(text.slice(last, match.index)))
      const wrap = document.createElement('span')
      wrap.className = 'nm-meme'
      const img = document.createElement('img')
      img.src = sticker.url
      img.alt = sticker.name || 'sticker'
      img.title = sticker.name || sticker.packName
      img.loading = 'lazy'
      img.decoding = 'async'
      wrap.append(img)
      frag.append(wrap)
      last = match.index + match[0].length
    }
    if (!changed) continue
    frag.append(document.createTextNode(text.slice(last)))
    node.replaceWith(frag)
  }
}

/** One inline tag cut out of a text node, replaced by a marker at the same spot. */
function cutBubbleTags(root: HTMLElement): number {
  let cuts = 0
  for (const node of textNodes(root)) {
    if (!hasBubbleTag(node.data) || inCode(node)) continue
    const parts = node.data.split(/<\s*nm:bubble\s*\/?>|<\s*\/\s*nm:bubble\s*>/i)
    if (parts.length < 2) continue
    const frag = document.createDocumentFragment()
    parts.forEach((part, index) => {
      if (index > 0) {
        const marker = document.createElement('span')
        marker.className = 'nm-cut'
        frag.append(marker)
        cuts++
      }
      if (part) frag.append(document.createTextNode(part))
    })
    node.replaceWith(frag)
  }
  return cuts
}

/** Lift a marker out of its inline parents until it sits between blocks, splitting them. */
function liftMarker(marker: HTMLElement, root: HTMLElement): void {
  let current: HTMLElement = marker
  while (current.parentElement && current.parentElement !== root) {
    const parent = current.parentElement
    const clone = parent.cloneNode(false) as HTMLElement
    let sibling = current.nextSibling
    while (sibling) {
      const next = sibling.nextSibling
      clone.append(sibling)
      sibling = next
    }
    parent.after(clone)
    current = parent
  }
}

/** A finished reply, cut into the bubbles it asked for. The original body is hidden. */
function splitBubbles(item: HTMLElement): void {
  const body = bodyOf(item)
  if (!body || body.dataset['nmBubbled'] === '1') return
  if (item.closest('[data-chat-running]')) return
  if (!hasBubbleTag(body.textContent ?? '')) return
  const clone = body.cloneNode(true) as HTMLElement
  if (cutBubbleTags(clone) === 0) return
  const markers = Array.from(clone.querySelectorAll<HTMLElement>('.nm-cut'))
  for (const marker of markers) liftMarker(marker, clone)
  // group the top-level blocks between the markers
  const groups: Node[][] = [[]]
  for (const child of Array.from(clone.childNodes)) {
    if (child instanceof HTMLElement && child.classList.contains('nm-cut')) {
      groups.push([])
      continue
    }
    groups[groups.length - 1]!.push(child)
  }
  const filled = groups.filter((nodes) => nodes.some((node) => (node.textContent ?? '').trim() || (node instanceof HTMLElement && node.querySelector('img'))))
  if (filled.length < 2) return
  const stack = document.createElement('div')
  stack.className = 'nm-bubble-stack'
  filled.forEach((nodes, index) => {
    const seg = document.createElement('div')
    seg.className = `nm-bubble-seg${index === 0 ? ' nm-bubble-first' : ''}${index === filled.length - 1 ? ' nm-bubble-last' : ''}`
    for (const node of nodes) seg.append(node)
    stack.append(seg)
  })
  const parent = body.parentElement
  if (!parent) return
  for (const old of Array.from(parent.querySelectorAll(':scope > .nm-bubble-stack'))) old.remove()
  body.dataset['nmBubbled'] = '1'
  body.style.display = 'none'
  parent.insertBefore(stack, body.nextSibling)
}

/** A code block taller than this folds, with a button to open it (the phone's long-code rule). */
const FOLD_AT_PX = 420

function foldCode(root: HTMLElement, t: Translate): void {
  for (const pre of Array.from(root.querySelectorAll('pre'))) {
    if (pre.dataset['nmFold'] === '1') continue
    if (pre.scrollHeight <= FOLD_AT_PX) continue
    pre.dataset['nmFold'] = '1'
    pre.classList.add('nm-code-fold')
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'nm-code-more'
    button.textContent = t('codeMore')
    button.addEventListener('click', () => {
      pre.classList.remove('nm-code-fold')
      button.remove()
    })
    pre.after(button)
  }
}

function sweep(t: Translate): void {
  for (const item of Array.from(document.querySelectorAll<HTMLElement>(ITEM))) {
    const kind = item.getAttribute('data-chat-flow-kind') ?? ''
    if (kind !== 'assistant-step' && kind !== 'user') continue
    const body = bodyOf(item)
    if (!body) continue
    hideMoodTags(body)
    renderMemes(body)
    foldCode(body, t)
    // our own bubble stack is cleaned too, so a sticker inside a bubble renders
    for (const stack of Array.from(body.parentElement?.querySelectorAll<HTMLElement>(':scope > .nm-bubble-stack') ?? [])) {
      hideMoodTags(stack)
      renderMemes(stack)
      foldCode(stack, t)
    }
    if (kind === 'assistant-step') splitBubbles(item)
  }
}

/** Watch the chat and keep the three tags drawn. Returns the uninstaller. */
export function installMarkup(t: Translate): () => void {
  let timer: number | undefined
  const schedule = (): void => {
    if (timer !== undefined) return
    timer = window.setTimeout(() => {
      timer = undefined
      try {
        sweep(t)
      } catch (error) {
        console.error('[nanomuse] chat markup sweep failed:', error)
      }
    }, SWEEP_DELAY_MS)
  }
  const observer = new MutationObserver((records) => {
    for (const record of records) {
      if (record.target instanceof Element && record.target.closest('.nm-bubble-stack, .nm-meme')) continue
      schedule()
      return
    }
  })
  observer.observe(document.body, { childList: true, subtree: true, characterData: true })
  const unsubscribe = subscribeStickers(schedule)
  void refreshStickers()
  schedule()
  return () => {
    observer.disconnect()
    unsubscribe()
    if (timer !== undefined) window.clearTimeout(timer)
  }
}
