/**
 * The app fences the agent writes in a chat, shown as cards instead of code (the
 * phone's `NanoMuseBlock`): "Goal created" with the title, why, steps and cadence
 * and a See in Goals button; "Goal update" with a progress bar and the note;
 * "Posted to your feed" with the tile and title; the new look, with its words.
 *
 * The harness renders every fenced block as a `.md-code-block` and drops the tag
 * from the DOM for unknown languages, so the fence is told by its JSON: a block whose
 * whole text parses as an object with the fence's keys. The block is hidden and the
 * card inserted beside it; a block still streaming is left alone until it parses.
 */
import { createElement as h } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { parseNamingBlock } from '../fences.ts'
import type { Translate } from './api.ts'
import { stillUrl } from './Avatar.tsx'
import { peekLive } from './live.ts'
import { NamingCard } from './NamingCard.tsx'
import { nav } from './rooms.ts'
import { FEED_PANEL, GOALS_PANEL } from './panels.ts'
import { composerCard, composerEditable, prefillComposer } from './composer.ts'

const DONE = 'data-nm-fence'

type Fence =
  | { kind: 'goal'; title: string; why: string; steps: string[]; everyHours: number; checkTime: string }
  | { kind: 'goal-update'; progress: number; status: 'on_track' | 'attention' | 'done'; note: string }
  | { kind: 'feed'; title: string; emoji: string; body: string }
  | { kind: 'avatar'; desc: string; chosen: number }
  /** The first conversation's ```nanomuse-naming block: the chooser when it suggests names, nothing to show otherwise. */
  | { kind: 'naming'; suggestions: string[]; chooser: boolean }
  | { kind: 'question'; question: string; options: string[] }

export function parseQuestionTag(text: string): { question: string; options: string[] } | undefined {
  const match = /<question>([\s\S]*?)(?:<\/question>|$)/i.exec(text)
  if (!match) return undefined
  try {
    const obj = JSON.parse(match[1]!.trim())
    const q = obj.question || obj.title || obj.header
    const opts = obj.options || (Array.isArray(obj.questions) && obj.questions[0]?.options)
    const questionText = q || (Array.isArray(obj.questions) && obj.questions[0]?.question)
    if (questionText && Array.isArray(opts) && opts.length > 0) {
      return {
        question: String(questionText).trim(),
        options: opts.map((x: unknown) => {
          if (x && typeof x === 'object') {
            const lbl = (x as Record<string, unknown>).label || ''
            const desc = (x as Record<string, unknown>).description || ''
            return desc ? `${lbl} - ${desc}` : String(lbl)
          }
          return String(x || '').trim()
        }).filter(Boolean),
      }
    }
  } catch {}
  return undefined
}

function readFence(text: string): Fence | undefined {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return undefined
  let o: Record<string, unknown>
  try {
    const value: unknown = JSON.parse(trimmed)
    if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
    o = value as Record<string, unknown>
  } catch {
    return undefined
  }
  const str = (v: unknown) => (typeof v === 'string' ? v.trim() : typeof v === 'number' ? String(v) : '')
  if (('user_address' in o || 'suggest' in o || 'agent_name' in o) && !('title' in o) && !('status' in o) && !('desc' in o)) {
    const naming = parseNamingBlock('```nanomuse-naming\n' + trimmed + '\n```')
    return { kind: 'naming', suggestions: naming?.suggestions ?? [], chooser: (naming?.suggestions.length ?? 0) > 0 }
  }
  if (typeof o.desc === 'string' && 'chosen' in o && Array.isArray(o.files)) return { kind: 'avatar', desc: str(o.desc), chosen: Number(o.chosen) || 1 }
  if (('goal_id' in o || 'progress' in o) && 'status' in o && !('title' in o)) {
    const status = str(o.status)
    const p = Number(o.progress)
    return { kind: 'goal-update', progress: Number.isFinite(p) && o.progress !== undefined ? Math.min(100, Math.max(-1, Math.round(p))) : -1, status: status === 'done' || status === 'attention' ? status : 'on_track', note: str(o.note) }
  }
  if (typeof o.title === 'string' && ('why' in o || 'steps' in o || 'check_every_hours' in o || 'first_check' in o)) {
    const m = /(\d{1,2}):(\d{2})/.exec(str(o.check_time))
    return { kind: 'goal', title: str(o.title), why: str(o.why), steps: Array.isArray(o.steps) ? o.steps.map(str).filter(Boolean).slice(0, 5) : [], everyHours: Math.max(0, Math.round(Number(o.check_every_hours) || 0)), checkTime: m ? `${m[1]!.padStart(2, '0')}:${m[2]}` : '09:00' }
  }
  if (typeof o.title === 'string' && typeof o.body === 'string' && ('emoji' in o || 'type' in o || 'source' in o)) return { kind: 'feed', title: str(o.title), emoji: str(o.emoji) || '📝', body: str(o.body) }
  if (typeof o.question === 'string' && Array.isArray(o.options) && o.options.length > 0) {
    return { kind: 'question', question: str(o.question), options: o.options.map(str).filter(Boolean) }
  }
  if (Array.isArray(o.questions) && o.questions.length > 0 && typeof o.questions[0] === 'object') {
    const first = o.questions[0] as Record<string, unknown>
    const q = str(first.question || first.title || first.header)
    const rawOpts = Array.isArray(first.options) ? first.options : []
    const opts = rawOpts.map((x: unknown) => {
      if (x && typeof x === 'object') {
        const lbl = (x as Record<string, unknown>).label || ''
        const desc = (x as Record<string, unknown>).description || ''
        return desc ? `${lbl} - ${desc}` : String(lbl)
      }
      return String(x || '').trim()
    }).filter(Boolean)
    if (q && opts.length > 0) {
      return { kind: 'question', question: q, options: opts }
    }
  }
  return undefined
}

function el(tag: string, className: string, text?: string): HTMLElement {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text !== undefined) node.textContent = text
  return node
}

function button(label: string, onClick: () => void): HTMLElement {
  const b = el('button', 'nm-fence-btn', label) as HTMLButtonElement
  b.type = 'button'
  b.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); onClick() })
  return b
}

const roots = new Map<HTMLElement, Root>()

function cleanOptionPrefix(text: string): string {
  return text.split(/\s+[-–—]\s+/)[0]?.trim() || text.trim()
}

function questionCard(t: Translate, q: { question: string; options: string[] }): HTMLElement {
  const root = el('div', 'nm-question-card')
  let selectedAnswer: string | null = root.getAttribute('data-nm-answer') || null
  let isExpanded = !selectedAnswer

  const render = () => {
    root.innerHTML = ''
    if (selectedAnswer && !isExpanded) {
      // === Collapsed View: Compact Summary Pill ===
      root.className = 'nm-question-card nm-question-collapsed'
      const row = el('div', 'nm-question-collapsed-row')

      const checkBadge = el('div', 'nm-question-check-badge')
      checkBadge.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>'
      row.append(checkBadge)

      const title = el('span', 'nm-question-collapsed-title', q.question)
      title.title = q.question
      row.append(title)

      const sep = el('span', 'nm-question-sep', '·')
      row.append(sep)

      const chip = el('div', 'nm-question-answer-chip')
      const chipText = el('span', '', selectedAnswer)
      chipText.title = selectedAnswer
      chip.append(chipText)
      row.append(chip)

      const chev = el('button', 'nm-question-chevron-btn') as HTMLButtonElement
      chev.type = 'button'
      chev.title = '展开查看选项'
      chev.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>'
      row.append(chev)

      row.addEventListener('click', (e) => {
        e.preventDefault()
        e.stopPropagation()
        isExpanded = true
        render()
      })
      root.append(row)
    } else {
      // === Expanded View: Interactive Question Card ===
      root.className = 'nm-question-card nm-question-expanded'

      const header = el('div', 'nm-question-header')
      const iconBadge = el('div', 'nm-question-icon-badge')
      iconBadge.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>'
      header.append(iconBadge)

      const title = el('span', 'nm-question-title', q.question)
      header.append(title)

      if (selectedAnswer) {
        header.append(el('span', 'nm-question-answered-badge', '已回答'))
        const collapseBtn = el('button', 'nm-question-collapse-btn') as HTMLButtonElement
        collapseBtn.type = 'button'
        collapseBtn.innerHTML = '<span>收起</span><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15"></polyline></svg>'
        collapseBtn.addEventListener('click', (e) => {
          e.preventDefault()
          e.stopPropagation()
          isExpanded = false
          render()
        })
        header.append(collapseBtn)
      }
      root.append(header)

      const list = el('div', 'nm-question-options')
      for (const opt of q.options) {
        const isRecommended = /^\((?:Recommended|推荐)\)/i.test(opt) || opt.includes('（推荐）') || opt.startsWith('(推荐)')
        const cleanText = opt.replace(/^\((?:Recommended|推荐)\)\s*/i, '').trim()
        const isThisSelected = selectedAnswer !== null && (
          selectedAnswer.toLowerCase() === cleanText.toLowerCase() ||
          selectedAnswer.toLowerCase().startsWith(cleanOptionPrefix(cleanText).toLowerCase()) ||
          cleanText.toLowerCase().startsWith(cleanOptionPrefix(selectedAnswer).toLowerCase())
        )

        let optClass = 'nm-question-option'
        if (isThisSelected) optClass += ' nm-selected'
        else if (isRecommended && !selectedAnswer) optClass += ' nm-recommended'
        else if (selectedAnswer) optClass += ' nm-answered-other'

        const optRow = el('div', optClass)

        if (isThisSelected) {
          const radio = el('div', 'nm-question-radio nm-selected')
          radio.innerHTML = '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>'
          optRow.append(radio)
        } else if (isRecommended && !selectedAnswer) {
          optRow.append(el('span', 'nm-question-badge', '推荐'))
        } else {
          optRow.append(el('div', 'nm-question-radio nm-unselected'))
        }

        optRow.append(el('span', 'nm-question-opt-text', cleanText))

        optRow.addEventListener('click', (e) => {
          e.preventDefault()
          e.stopPropagation()
          selectedAnswer = cleanText
          root.setAttribute('data-nm-answer', cleanText)
          isExpanded = false
          render()

          prefillComposer(cleanText)
          window.setTimeout(() => {
            const card = composerCard()
            const sendBtn = card?.querySelector<HTMLButtonElement>('[class*="_primary"]:not(:disabled)')
            if (sendBtn) {
              sendBtn.click()
            } else {
              const editable = composerEditable()
              editable?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', which: 13, keyCode: 13, bubbles: true }))
            }
          }, 50)
        })
        list.append(optRow)
      }
      root.append(list)
    }
  }

  render()
  return root
}

function card(t: Translate, fence: Fence): HTMLElement {
  const root = el('div', `nm-fence nm-fence-${fence.kind}`)
  switch (fence.kind) {
    case 'naming': {
      // the chooser is React: it follows the host's first-run state (the names, the pick)
      roots.set(root, createRoot(root))
      roots.get(root)!.render(h(NamingCard, { t, suggested: fence.suggestions }))
      return root
    }
    case 'question': {
      return questionCard(t, fence)
    }
    case 'goal': {
      root.append(el('div', 'nm-fence-kind', t('fcGoalCreated')))
      root.append(el('div', 'nm-fence-title', fence.title))
      if (fence.why) root.append(el('div', 'nm-fence-sub', fence.why))
      if (fence.steps.length) {
        const ul = el('ul', 'nm-fence-steps')
        for (const step of fence.steps) ul.append(el('li', '', step))
        root.append(ul)
      }
      const foot = el('div', 'nm-fence-foot')
      foot.append(el('span', 'nm-fence-fine', fence.everyHours > 0 ? t('fcEveryHours', { n: fence.everyHours }) : t('fcDailyAt', { time: fence.checkTime })))
      foot.append(button(t('fcSeeGoals'), () => { if (nav.activePanel() !== null) nav.showChats(); nav.split(GOALS_PANEL) }))
      root.append(foot)
      return root
    }
    case 'goal-update': {
      const head = el('div', 'nm-fence-head')
      head.append(el('span', 'nm-fence-kind', t('fcGoalUpdate')))
      head.append(el('span', `nm-fence-status nm-${fence.status}`, fence.status === 'done' ? t('goalsDone') : fence.status === 'attention' ? t('goalAttention') : t('fcOnTrack')))
      root.append(head)
      if (fence.progress >= 0) {
        const bar = el('div', `nm-goal-bar${fence.status === 'attention' ? ' nm-attention' : ''}`)
        bar.setAttribute('role', 'progressbar')
        bar.setAttribute('aria-valuenow', String(fence.progress))
        const fill = el('span', '')
        fill.style.width = `${fence.progress}%`
        bar.append(fill)
        root.append(bar)
      }
      if (fence.note) root.append(el('div', 'nm-fence-sub', fence.note))
      return root
    }
    case 'feed': {
      const row = el('div', 'nm-fence-row')
      row.append(el('span', 'nm-fence-tile', fence.emoji))
      const main = el('div', 'nm-fence-main')
      main.append(el('div', 'nm-fence-kind', t('fcFeedPosted')))
      main.append(el('div', 'nm-fence-title', fence.title))
      const first = fence.body.split('\n').map((l) => l.replace(/^[-*\s]+/, '').trim()).find(Boolean)
      if (first) main.append(el('div', 'nm-fence-sub nm-clamp2', first))
      row.append(main)
      row.append(button(t('fcSeeFeed'), () => { if (nav.activePanel() !== null) nav.showChats(); nav.split(FEED_PANEL) }))
      root.append(row)
      return root
    }
    case 'avatar': {
      const row = el('div', 'nm-fence-row')
      const face = document.createElement('img')
      face.className = 'nm-fence-face'
      face.alt = ''
      face.src = stillUrl(peekLive().profile, 'happy')
      face.onerror = () => { face.src = 'nanomuse/assets/dragon-happy.webp' }
      row.append(face)
      const main = el('div', 'nm-fence-main')
      main.append(el('div', 'nm-fence-kind', t('fcNewLook')))
      main.append(el('div', 'nm-fence-sub', fence.desc))
      row.append(main)
      root.append(row)
      return root
    }
  }
}

/** Watch the document for fenced blocks of ours and turn them into cards; returns the stop function. */
export function renderFenceCards(t: Translate): () => void {
  let scheduled = 0
  const sweep = () => {
    scheduled = 0
    for (const block of document.querySelectorAll<HTMLElement>(`.md-code-block:not([${DONE}])`)) {
      const code = block.querySelector('[data-code-block-content]')?.textContent ?? ''
      if (!code.trimStart().startsWith('{')) {
        // not JSON at all: never ours; stop looking at it
        if (code.trim()) block.setAttribute(DONE, 'no')
        continue
      }
      const fence = readFence(code)
      if (!fence) continue
      block.setAttribute(DONE, fence.kind)
      block.style.display = 'none'
      // a naming block without suggestions (the address, the final name) only disappears
      if (fence.kind === 'naming' && !fence.chooser) continue
      block.insertAdjacentElement('afterend', card(t, fence))
    }
    for (const qEl of document.querySelectorAll<HTMLElement>(`question:not([${DONE}])`)) {
      qEl.setAttribute(DONE, 'yes')
      qEl.style.display = 'none'
      const q = parseQuestionTag(`<question>${qEl.textContent}</question>`)
      if (q) {
        qEl.insertAdjacentElement('afterend', questionCard(t, q))
      }
    }
    for (const p of document.querySelectorAll<HTMLElement>(`[class*="_markdown"] p:not([${DONE}])`)) {
      const text = p.textContent || ''
      if (!text.includes('<question>')) continue
      const q = parseQuestionTag(text)
      if (q) {
        p.setAttribute(DONE, 'yes')
        const clean = text.replace(/<question>[\s\S]*?(?:<\/question>|$)/i, '').trim()
        if (clean) {
          p.textContent = clean
        } else {
          p.style.display = 'none'
        }
        p.insertAdjacentElement('afterend', questionCard(t, q))
      }
    }
  }
  const schedule = () => { if (!scheduled) scheduled = window.requestAnimationFrame(sweep) }
  const observer = new MutationObserver(schedule)
  observer.observe(document.body, { childList: true, subtree: true, characterData: true })
  schedule()
  return () => {
    observer.disconnect()
    if (scheduled) window.cancelAnimationFrame(scheduled)
    for (const [node, root] of roots) { root.unmount(); node.remove() }
    roots.clear()
    for (const node of document.querySelectorAll('.nm-fence, .nm-question-card')) node.remove()
    for (const block of document.querySelectorAll<HTMLElement>(`[${DONE}]`)) {
      block.removeAttribute(DONE)
      if (block.classList.contains('md-code-block') || block.tagName.toLowerCase() === 'question') {
        block.style.display = ''
      }
    }
  }
}
