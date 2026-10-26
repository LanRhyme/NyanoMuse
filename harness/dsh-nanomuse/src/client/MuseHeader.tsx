/**
 * The agent pinned over the conversation, the way the Muse desktop keeps its
 * face and name at the top of every chat, with one small line under the name
 * while something is happening: what step it is on (looking at the screen,
 * searching, writing, running a command, waiting for your word), "done" for a
 * moment when a turn ends, and a passing "N approvals allowed" after an
 * approval card. Idle, it is the face and the name alone. Hovering the face
 * enlarges it; clicking opens the profile. Occupies `conversation.header.leading`.
 */
import { createElement as h, Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { dismissStar, markStarred, nudgeText, openStar } from './AccountPage.tsx'
import { call, type Translate } from './api.ts'
import { Avatar, motionStatus, type Mood } from './Avatar.tsx'
import { describeCall } from './Capsule.tsx'
import { IconCheck, IconChevronLeft, IconHeart, IconMenu, IconPalette, IconPanelRight, IconSpinner } from './icons.tsx'
import { openRightPanel } from './keys.ts'
import { useLive } from './live.ts'
import { BillingChip, MoodPill, useMoodState } from './MoodPanel.tsx'
import { mainChatId } from './MuseChats.tsx'
import { usePrefs } from './prefs.ts'
import { statusWords } from './ProfileDrawer.tsx'
import { activityOf, nav, useRooms } from './rooms.ts'
import { wallpaperBus } from './wallpaper.ts'
import { useWin } from './win.ts'

/** "Done" stays this long after a turn ends; the approvals toast this long. */
const DONE_MS = 2600
const TOAST_MS = 3200
/** The first seconds of a turn read as "gathering thoughts". */
const PLANNING_MS = 4000
/** A star ask stays in the status line this long, unless answered. */
const STAR_MS = 25000

interface SessionStatus {
  running: boolean | undefined
  pendingInteraction: unknown
}
export type UseSessionStatus = <S>(selector: (snapshot: ReadonlyMap<string, SessionStatus>) => S) => S

export interface MuseHeaderProps {
  t: Translate
  stop(sessionId: string): Promise<void>
  openProfile(): void
  useSessionStatus?: UseSessionStatus | undefined
}

export function MuseHeader({ t, openProfile, useSessionStatus }: MuseHeaderProps): ReactNode {
  const live = useLive()
  const rooms = useRooms()
  const prefs = usePrefs()
  const { current, split } = useWin()
  const status = typeof useSessionStatus === 'function' ? useSessionStatus((map) => (current ? map.get(current) : undefined)) : undefined
  const record = activityOf(rooms, current)
  const running = status?.running === true || record?.status === 'running'
  const pending = status?.pendingInteraction as { kind?: string } | undefined
  const waiting = pending !== undefined
  // an approval request reads "needs approval"; a question (or plan review) "waiting for you"
  const askingYou = waiting && pending?.kind !== 'approval'
  const [, tick] = useState(0)
  const [toast, setToast] = useState<{ n: number; at: number } | null>(null)
  const [balance, setBalance] = useState<string | null>(null)
  const approvals = useRef(prefs.approvals.length)

  useEffect(() => {
    let cancelled = false
    const fetchBalance = () => {
      call<{ available: boolean; balance?: string }>('balance')
        .then((res) => {
          if (!cancelled && res?.available && res.balance) {
            setBalance(res.balance)
          }
        })
        .catch(() => undefined)
    }
    fetchBalance()
    const timer = window.setInterval(fetchBalance, 60_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])
  // The star (C1): the host counts the tasks that ran to their end, the days and the goals,
  // and keeps one ask at a time in `nudges.current`; a fresh one shows here for a while, or
  // until it is answered. (Settings' Account page and the studio show theirs as cards.)
  const ask = rooms.nudges.current
  const hostCounted = ask !== null && (ask.moment === 'tasks' || ask.moment === 'days_used' || ask.moment === 'goal_done')
  const starAsk = ask && hostCounted && Date.now() - ask.at < STAR_MS ? ask : null
  const [, starTick] = useState(0)
  useEffect(() => {
    if (!starAsk) return undefined
    const timer = window.setTimeout(() => starTick((n) => n + 1), STAR_MS - (Date.now() - starAsk.at) + 50)
    return () => window.clearTimeout(timer)
  }, [starAsk])

  // "done" lingers, "gathering thoughts" ages into "working": re-render on a clock while it matters
  useEffect(() => {
    const endedAt = record?.endedAt ?? 0
    const young = running && record && Date.now() - record.startedAt < PLANNING_MS
    const fresh = !running && endedAt && Date.now() - endedAt < DONE_MS
    if (!young && !fresh) return undefined
    const timer = window.setTimeout(() => tick((n) => n + 1), young ? PLANNING_MS - (Date.now() - record!.startedAt) + 50 : DONE_MS - (Date.now() - endedAt) + 50)
    return () => window.clearTimeout(timer)
  }, [running, record?.startedAt, record?.endedAt])
  // the approvals toast: how many were allowed in one go
  useEffect(() => {
    const before = approvals.current
    approvals.current = prefs.approvals.length
    if (prefs.approvals.length <= before) return undefined
    const allowed = prefs.approvals.slice(0, prefs.approvals.length - before).filter((a) => a.outcome === 'allowed').length
    if (!allowed) return undefined
    setToast((t0) => ({ n: (t0 && Date.now() - t0.at < TOAST_MS ? t0.n : 0) + allowed, at: Date.now() }))
    const timer = window.setTimeout(() => setToast(null), TOAST_MS)
    return () => window.clearTimeout(timer)
  }, [prefs.approvals.length])

  // While nothing is running or waiting, the face wears the companion's mood: the same
  // 33-way state the compass shows, settled onto one of the five faces this desktop has.
  const companion = useMoodState().mood
  const calls = live.hands.calls
  const hands = calls.length ? calls[calls.length - 1] : undefined
  let mood: Mood = 'idle'
  let line: ReactNode = null
  let tone = ''
  if (waiting) {
    mood = 'waiting'
    tone = 'nm-wait'
    line = askingYou ? t('statusAsking') : t('statusNeedsApproval')
  } else if (running) {
    mood = 'working'
    tone = 'nm-live'
    if (hands && current && hands.sessionId === current) line = describeCall(t, hands)
    else if (record) line = statusWords(t, record)
    else line = t('statusWorking')
  } else if (record?.endedAt && Date.now() - record.endedAt < DONE_MS) {
    tone = 'nm-done'
    line = record.status === 'error' ? t('statusFailed') : t('statusDone')
  } else if (live.streaming && !live.cloud.signedIn) {
    line = t('statusSignedOut')
  } else if (live.streaming && !live.hub.connected && live.hub.lastError) {
    line = t('statusHubOffline')
  } else if (motionStatus(live, t)) {
    // the studio animating the face, in the background
    line = motionStatus(live, t)
  }

  // Esc leaves any other conversation view (the trajectory a tool row's "Inspect" opens) for
  // the chat — the first tab of the harness's view ring, which Muse mode otherwise hides
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      const tabs = document.querySelector('[data-conversation-tabs]')
      const picked = tabs?.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
      const first = tabs?.querySelector<HTMLElement>('[role="tab"]')
      if (picked && first && picked !== first) { event.preventDefault(); first.click() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  if (!running && !waiting && companion?.enabled) mood = companion.face as Mood
  // a side chat carries a way back to the main chat at its top left; beside a room
  // (the split) the chat column is headed "≡ 聊天" instead, which brings the chats back.
  // The back control is the bar's navigation seat, as the phone puts its menu there.
  const lead = split !== null
    ? h('button', { type: 'button', className: 'nm-header-back nm-header-chats', 'aria-label': t('railChats'), title: t('splitClose'), onClick: () => nav.split(null) }, h(IconMenu, { size: 18 }), h('span', null, t('railChats')))
    : null
  // The phone's bar: the face, the name and the balance capsule centred as one row, the back
  // control on the left and the background button on the right. A second line under the name
  // carries the status only while there is something to say, so an idle bar is exactly the
  // phone's single line. The thread starts below the bar, so nothing runs behind the face.
  return h(Fragment, null,
    // The only drag handle over the chat (macOS/Windows): an empty strip above the bar.
    h('div', { className: 'nm-header-drag', 'data-window-drag': true, 'aria-hidden': 'true' }),
    h('div', { className: 'nm-header', role: 'status', 'aria-live': 'polite' },
      lead,
      h('div', { className: 'nm-header-group' },
        h('button', { type: 'button', className: `nm-header-face${running ? ' nm-live' : waiting ? ' nm-wait' : ''}`, 'aria-label': live.profile.name || t('brand'), title: t('railProfile'), onClick: openProfile },
          h(Avatar, { size: 30, profile: live.profile, mood })),
        h('div', { className: 'nm-header-titles' },
          h('div', { className: 'nm-header-name-row' },
            h('span', { className: 'nm-header-name' }, live.profile.name || t('brand')),
            balance ? h('span', { className: 'nm-header-balance-pill', title: t('balanceTitle') },
              h('span', { className: 'nm-balance-dot' }),
              h('span', null, `¥${balance}`),
            ) : null,
            h(BillingChip, { t }),
            h(MoodPill, { t })),
        toast
          ? h('div', { className: 'nm-header-status nm-header-toast' }, h(IconCheck, { size: 12 }), h('span', { className: 'nm-header-line' }, t('statusApproved', { n: toast.n })))
          : starAsk
            ? h('div', { className: 'nm-header-status nm-header-star' }, h(IconHeart, { size: 12 }), h('span', { className: 'nm-header-line' }, nudgeText(t, starAsk)),
                h('button', { type: 'button', className: 'nm-ob-link nm-inline', onClick: () => openStar() }, t('starAction')),
                h('button', { type: 'button', className: 'nm-ob-link nm-inline', onClick: () => dismissStar() }, t('starLater')),
                h('button', { type: 'button', className: 'nm-ob-link nm-inline', onClick: () => markStarred() }, t('starDone')))
            : line
              ? h('div', { className: `nm-header-status ${tone}`.trim() }, running || waiting ? h(IconSpinner, { size: 12 }) : null, h('span', { className: 'nm-header-line' }, line))
              : null)),
      h('div', { className: 'nm-header-actions' },
        // The workbench (dsh-better-sidebar's right rail). Its own button lives in the
        // harness's session header, under this bar; pressing the harness's chord opens it.
        h('button', {
          type: 'button',
          className: 'nm-header-icon-btn nm-header-workbench',
          title: t('workbenchOpen'),
          'aria-label': t('workbenchOpen'),
          onClick: () => { openRightPanel() },
        }, h(IconPanelRight, { size: 17 })),
        h('button', {
          type: 'button',
          className: 'nm-header-icon-btn',
          title: t('wallpaperOpen'),
          'aria-label': t('wallpaperOpen'),
          onClick: () => wallpaperBus.open(),
        }, h(IconPalette, { size: 17 })))))
}
