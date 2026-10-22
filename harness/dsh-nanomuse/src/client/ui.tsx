/**
 * The small pieces the rooms share: an anchored menu behind a ⋯ button, a
 * centred sheet (the harness's modal with the Muse card inside), an empty
 * state, a relative time, and a tiny Markdown renderer for what the agent
 * writes into the rooms (paragraphs, headings, lists, links, emphasis, code,
 * quotes, tables) — the harness's renderer lives behind its own module table
 * and is not ours to import.
 */
import { Modal } from '@deepseek-ai/dsh-client-ui-primitives'
import { createElement as h, Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { openLink } from './bridge.ts'
import { IconMore, IconPanelLeft } from './icons.tsx'
import { nav } from './rooms.ts'
import { useWin } from './win.ts'

export interface MenuChoice {
  id: string
  label: string
  icon?: ReactNode
  danger?: boolean
  onSelect(): void
}

/** A menu under (or over) its anchor, flush with the anchor's right edge when there is no room to the right. */
export function PopMenu({ anchor, items, onClose }: { anchor: HTMLElement; items: (MenuChoice | 'sep')[]; onClose(): void }): ReactNode {
  const menu = useRef<HTMLDivElement>(null)
  const rect = anchor.getBoundingClientRect()
  useEffect(() => {
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node
      if (menu.current?.contains(target) || anchor.contains(target)) return
      onClose()
    }
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
      }
    }
    document.addEventListener('pointerdown', onPointer, true)
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('pointerdown', onPointer, true)
      document.removeEventListener('keydown', onKey, true)
    }
  }, [anchor, onClose])
  useEffect(() => { menu.current?.querySelector('button')?.focus() }, [])
  const below = rect.bottom + 44 * items.length + 16 < window.innerHeight
  const rightward = rect.left + 220 < window.innerWidth
  const style: Record<string, number> = {
    ...(rightward ? { left: rect.left } : { right: Math.max(8, window.innerWidth - rect.right) }),
    ...(below ? { top: rect.bottom + 4 } : { bottom: window.innerHeight - rect.top + 4 }),
  }
  return h('div', { ref: menu, className: 'nm-menu', role: 'menu', style },
    items.map((item, index) => item === 'sep'
      ? h('div', { key: `sep-${index}`, className: 'nm-menu-sep', role: 'separator' })
      : h('button', { key: item.id, type: 'button', role: 'menuitem', className: `nm-menu-item${item.danger ? ' nm-danger' : ''}`, onClick: () => { onClose(); item.onSelect() } },
          item.icon ?? null,
          h('span', { className: 'nm-menu-item-label' }, item.label))))
}

/** The ⋯ button with its menu; `quiet` shows it on the row's hover only. */
export function MoreButton({ label, items, quiet, size = 30, icon }: { label: string; items: (MenuChoice | 'sep')[]; quiet?: boolean; size?: number; icon?: ReactNode }): ReactNode {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const button = useRef<HTMLButtonElement | null>(null)
  return h(Fragment, null,
    h('button', {
      type: 'button',
      ref: button,
      className: `nm-icon-btn nm-more${quiet ? ' nm-quiet' : ''}`,
      style: { width: size, height: size },
      title: label,
      'aria-label': label,
      'aria-haspopup': 'menu',
      'aria-expanded': anchor !== null,
      onClick: (event: MouseEvent) => { event.stopPropagation(); setAnchor((current) => (current ? null : button.current)) },
    }, icon ?? h(IconMore, { size: 18 })),
    anchor ? h(PopMenu, { anchor, items, onClose: () => setAnchor(null) }) : null)
}

/** A centred card over the page: title row with ×, body, optional footer. */
export function Sheet({ title, onClose, closeLabel, children, footer, wide, header }: { title: string; onClose(): void; closeLabel: string; children?: ReactNode; footer?: ReactNode; wide?: boolean; header?: ReactNode }): ReactNode {
  return h(Modal, { open: true, onClose, title, headless: true, className: `nm-sheet-modal${wide ? ' nm-wide' : ''}` },
    h('div', { className: 'nm-sheet' },
      h('div', { className: 'nm-sheet-head' },
        h('h2', { className: 'nm-sheet-title' }, title),
        header ?? null,
        h('button', { type: 'button', className: 'nm-close', 'aria-label': closeLabel, title: closeLabel, onClick: onClose }, '×')),
      h('div', { className: 'nm-sheet-body' }, children),
      footer ? h('div', { className: 'nm-sheet-foot' }, footer) : null))
}

/**
 * The small sidebar-toggle at a room's top left, as Muse's rooms have it: it
 * opens the chat beside the room (the split) and closes it again. Hidden
 * while the room is already beside the chat.
 */
export function RoomToggle({ t, panel }: { t: Translate; panel: string }): ReactNode {
  const { split } = useWin()
  const open = split === panel
  return h('button', {
    type: 'button',
    className: `nm-room-toggle${open ? ' nm-active' : ''}`,
    'aria-label': open ? t('splitClose') : t('splitOpen'),
    title: open ? t('splitClose') : t('splitOpen'),
    'aria-pressed': open,
    onClick: () => nav.split(open ? null : panel),
  }, h(IconPanelLeft, { size: 18 }))
}

export function Empty({ icon, text, children }: { icon: ReactNode; text: string; children?: ReactNode }): ReactNode {
  return h('div', { className: 'nm-empty' },
    h('div', { className: 'nm-empty-icon' }, icon),
    h('div', { className: 'nm-empty-text' }, text),
    children ?? null)
}

/** `2 min ago`-style, from the shared words. */
export function ago(t: Translate, at: number): string {
  if (!at) return ''
  const delta = Math.max(0, Date.now() - at) / 1000
  if (delta < 90) return t('justNow')
  if (delta < 3600) return t('minutesAgo', { n: Math.round(delta / 60) })
  if (delta < 86400) return t('hoursAgo', { n: Math.round(delta / 3600) })
  return t('daysAgo', { n: Math.round(delta / 86400) })
}

/** Today / Yesterday / a date, for timeline groups. */
export function dayLabel(t: Translate, at: number, lang: string): string {
  const d = new Date(at)
  const now = new Date()
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((startOf(now) - startOf(d)) / 86400000)
  if (days === 0) return t('today')
  if (days === 1) return t('yesterday')
  try {
    return d.toLocaleDateString(lang || undefined, { month: 'long', day: 'numeric' })
  } catch {
    return d.toLocaleDateString()
  }
}

export function clockLabel(at: number, lang: string): string {
  try {
    return new Date(at).toLocaleTimeString(lang || undefined, { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}

// ---- Markdown ------------------------------------------------------------------------------

/** Inline Markdown: links, bold, italic, code, images dropped (the rooms show images on their own). */
function inline(text: string, key = 0): ReactNode[] {
  const out: ReactNode[] = []
  const re = /(\*\*([^*]+)\*\*|__([^_]+)__|`([^`]+)`|\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)|\*([^*\n]+)\*|_([^_\n]+)_|<?(https?:\/\/[^\s<>)]+)>?)/g
  let last = 0
  let match: RegExpExecArray | null
  let i = 0
  while ((match = re.exec(text)) !== null) {
    if (match.index > last) out.push(text.slice(last, match.index))
    const k = `${key}-${i++}`
    if (match[2] !== undefined || match[3] !== undefined) out.push(h('strong', { key: k }, match[2] ?? match[3]))
    else if (match[4] !== undefined) out.push(h('code', { key: k }, match[4]))
    else if (match[5] !== undefined && match[6] !== undefined) {
      const href = match[6]
      out.push(h('a', { key: k, href, onClick: (e: MouseEvent) => { e.preventDefault(); openLink(href) } }, match[5]))
    } else if (match[7] !== undefined || match[8] !== undefined) out.push(h('em', { key: k }, match[7] ?? match[8]))
    else if (match[9] !== undefined) {
      const href = match[9]
      out.push(h('a', { key: k, href, onClick: (e: MouseEvent) => { e.preventDefault(); openLink(href) } }, href.replace(/^https?:\/\//, '').slice(0, 60)))
    }
    last = match.index + match[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

/** Block Markdown to React: enough for the agent's posts and documents. */
export function Markdown({ text, className }: { text: string; className?: string }): ReactNode {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let i = 0
  let key = 0
  const flushParagraph = (buffer: string[]) => {
    if (buffer.length) blocks.push(h('p', { key: key++ }, inline(buffer.join(' '), key)))
    buffer.length = 0
  }
  const paragraph: string[] = []
  while (i < lines.length) {
    const line = lines[i] ?? ''
    if (!line.trim()) {
      flushParagraph(paragraph)
      i++
      continue
    }
    if (/^```/.test(line)) {
      flushParagraph(paragraph)
      const code: string[] = []
      i++
      while (i < lines.length && !/^```/.test(lines[i] ?? '')) code.push(lines[i++] ?? '')
      i++
      blocks.push(h('pre', { key: key++ }, h('code', null, code.join('\n'))))
      continue
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      flushParagraph(paragraph)
      const level = Math.min(6, heading[1]!.length + 1)
      blocks.push(h(`h${level}`, { key: key++ }, inline(heading[2] ?? '', key)))
      i++
      continue
    }
    if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      flushParagraph(paragraph)
      blocks.push(h('hr', { key: key++ }))
      i++
      continue
    }
    if (/^>\s?/.test(line)) {
      flushParagraph(paragraph)
      const quote: string[] = []
      while (i < lines.length && /^>\s?/.test(lines[i] ?? '')) quote.push((lines[i++] ?? '').replace(/^>\s?/, ''))
      blocks.push(h('blockquote', { key: key++ }, h(Markdown, { text: quote.join('\n') })))
      continue
    }
    if (/^\s*([-*+]|\d+[.)])\s+/.test(line)) {
      flushParagraph(paragraph)
      const ordered = /^\s*\d+[.)]\s+/.test(line)
      const items: ReactNode[] = []
      while (i < lines.length && /^\s*([-*+]|\d+[.)])\s+/.test(lines[i] ?? '')) {
        let item = (lines[i++] ?? '').replace(/^\s*([-*+]|\d+[.)])\s+/, '')
        while (i < lines.length && /^\s{2,}\S/.test(lines[i] ?? '') && !/^\s*([-*+]|\d+[.)])\s+/.test(lines[i] ?? '')) item += ' ' + (lines[i++] ?? '').trim()
        const task = /^\[([ xX])\]\s+/.exec(item)
        if (task) item = item.slice(task[0].length)
        items.push(h('li', { key: items.length, className: task ? (task[1] === ' ' ? 'nm-todo' : 'nm-done') : undefined }, inline(item, key)))
      }
      blocks.push(h(ordered ? 'ol' : 'ul', { key: key++ }, items))
      continue
    }
    if (/^\|.*\|\s*$/.test(line) && /^\|?\s*:?-{2,}/.test(lines[i + 1] ?? '')) {
      flushParagraph(paragraph)
      const cells = (row: string) => row.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim())
      const head = cells(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && /^\|.*\|\s*$/.test(lines[i] ?? '')) rows.push(cells(lines[i++] ?? ''))
      blocks.push(h('table', { key: key++ },
        h('thead', null, h('tr', null, head.map((c, n) => h('th', { key: n }, inline(c, key))))),
        h('tbody', null, rows.map((r, n) => h('tr', { key: n }, r.map((c, m) => h('td', { key: m }, inline(c, key))))))))
      continue
    }
    paragraph.push(line.trim())
    i++
  }
  flushParagraph(paragraph)
  return h('div', { className: `nm-md${className ? ` ${className}` : ''}` }, blocks)
}

/** The first `n` characters of Markdown as plain text (a card's preview). */
export function plain(text: string, n = 160): string {
  const s = text.replace(/<nm:bubble\/>/g, ' ').replace(/```[\s\S]*?```/g, ' ').replace(/[#>*_`|]/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/\s+/g, ' ').trim()
  return s.length > n ? `${s.slice(0, n - 1)}…` : s
}
