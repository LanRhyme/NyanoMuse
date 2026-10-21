/**
 * The agent's page, the panel the face opens (android …/ui/profile/AgentProfileScreen.kt
 * on a desktop): the big face with a pen badge whose menu is the phone's — Change
 * avatar (words land in the chat, the flow in AvatarChat.tsx takes them), Edit name,
 * Your own clip… and Default face (OwnClipChooser.tsx, #324), Avatar studio… — a share button, the name, "online" under it, and four panes:
 * Activity (what each chat did, day by day; an entry opens the task's steps),
 * Approvals (the standing "always allow" grants with Manage permissions, then the
 * answers given on cards), Daily (the goals' routines, Manage routines) and Soul &
 * memory (the name with Edit, SOUL and Memory cards that open in the editor). The
 * name and the simple looks are written to the account through the host, so the
 * phone wears them too. Escape closes it.
 */
import { createElement as h, Fragment, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { call, type Translate, failureText } from './api.ts'
import { Avatar } from './Avatar.tsx'
import { profileBus, settingsBus, useProfileOpen } from './bus.ts'
import { IconAlarm, IconBell, IconCheck, IconChevronDown, IconChevronRight, IconClock, IconClose, IconDoc, IconList, IconPencil, IconShare, IconShield, IconSparkle, IconSpinner, IconSquare } from './icons.tsx'
import { AvatarShareSheet } from './AvatarShare.tsx'
import { studioBus } from './AvatarStudio.tsx'
import { prefillComposer } from './composer.ts'
import { useOwnClipChooser } from './OwnClipChooser.tsx'
import { useLive, type LiveProfile } from './live.ts'
import type { ChatListState, ChatStatus } from './MuseChats.tsx'
import { usePrefs } from './prefs.ts'
import { useRooms, type ActivityRecord, type GoalAutomation } from './rooms.ts'
import { TaskDetail } from './TaskDetail.tsx'
import { ago, clockLabel, dayLabel } from './ui.tsx'
import { win } from './win.ts'

const COMPUTER_SECTION = 'nanomuse-computer'

type Tab = 'activity' | 'approvals' | 'reminders' | 'identity'

export interface ProfileDrawerProps {
  t: Translate
  openSchedules(): void
  stop(sessionId: string): Promise<void>
  useSessions?: (<S>(selector: (state: ChatListState) => S) => S) | undefined
  useSessionStatus?: (<S>(selector: (snapshot: ReadonlyMap<string, ChatStatus>) => S) => S) | undefined
}

const EMOJI_CHOICES = ['✨', '🐉', '🌙', '🍀', '🦊', '🐼', '🌸', '⚡', '🎧', '🫧']
const COLOR_CHOICES = ['#0064d4', '#c8743a', '#7a4ea8', '#2f9e5f', '#c0392b', '#e0a13a', '#1d8a8a', '#5b6b7a']

export function makeProfileDrawer(t: Translate, stop: (sessionId: string) => Promise<void>) {
  return function ProfileDrawer({ openSchedules, useSessions, useSessionStatus }: Omit<ProfileDrawerProps, 't' | 'stop'>): ReactNode {
    const open = useProfileOpen()
    if (!open) return null
    return h(Drawer, { t, stop, openSchedules, useSessions, useSessionStatus })
  }
}

function Drawer({ t, stop, openSchedules, useSessions, useSessionStatus }: ProfileDrawerProps): ReactNode {
  const live = useLive()
  const rooms = useRooms()
  const prefs = usePrefs()
  const [tab, setTab] = useState<Tab>('activity')
  const [editing, setEditing] = useState<'none' | 'name' | 'look'>('none')
  const [menu, setMenu] = useState(false)
  const [share, setShare] = useState(false)
  const [detail, setDetail] = useState<string | null>(null)
  const panel = useRef<HTMLDivElement>(null)
  // a clip of the person's own as the face (#324): the dialog, the preview and the refusal live here
  const clipChooser = useOwnClipChooser(t)

  // Like Muse's panel it stays beside the chat until closed (×, Escape); the
  // chat column makes room for it meanwhile.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      if (panel.current?.contains(document.activeElement) || document.activeElement === document.body) profileBus.close()
    }
    document.addEventListener('keydown', onKey)
    document.documentElement.setAttribute('data-nm-profile', '')
    return () => {
      document.removeEventListener('keydown', onKey)
      document.documentElement.removeAttribute('data-nm-profile')
    }
  }, [])
  useEffect(() => { panel.current?.focus() }, [])

  const name = live.profile.name || t('brand')
  // the status line under the name follows what the agent is doing right now
  const running = rooms.activity.find((r) => r.status === 'running' || r.status === 'waiting')
  // "online" as on the phone: the agent answers here; the account's state is a matter for Settings
  let status: ReactNode
  if (running) status = h('span', { className: 'nm-pf-status nm-live' }, h(IconSpinner, { size: 13 }), statusWords(t, running))
  else if (live.cloud.signedIn && !live.hub.connected) status = h('span', { className: 'nm-pf-status' }, h('span', { className: 'nm-status-dot nm-wait' }), t('pfOffline'))
  else status = h('span', { className: 'nm-pf-status' }, h('span', { className: 'nm-status-dot nm-on' }), t('pfOnline'))
  // the phone's "Change avatar": the words land in the chat and the avatar flow takes them from there
  const changeAvatar = () => {
    setMenu(false)
    profileBus.close()
    prefillComposer(t('pfChangeAvatarPrefill'))
  }

  const tabs: { id: Tab; label: string; icon: ReactNode }[] = [
    { id: 'activity', label: t('pfActivity'), icon: h(IconList, { size: 17 }) },
    { id: 'approvals', label: t('pfApprovals'), icon: h(IconShield, { size: 17 }) },
    { id: 'reminders', label: t('pfDaily'), icon: h(IconClock, { size: 17 }) },
    { id: 'identity', label: t('pfSoul'), icon: h(IconSparkle, { size: 17 }) },
  ]

  let body: ReactNode
  if (editing === 'name') body = h(NameEditor, { t, profile: live.profile, onDone: () => setEditing('none') })
  else if (editing === 'look') body = h(LookEditor, { t, profile: live.profile, onDone: () => setEditing('none') })
  else if (tab === 'activity') body = h(Activity, { t, name, stop, useSessions, open: (id) => setDetail(id) })
  else if (tab === 'approvals') body = h(Approvals, { t, name, approvals: prefs.approvals, grants: live.grants })
  else if (tab === 'reminders') body = h(Reminders, { t, name, openSchedules: () => { profileBus.close(); openSchedules() } })
  else body = h(Identity, { t, name, onEditName: () => setEditing('name') })

  return h('aside', { ref: panel, tabIndex: -1, className: 'nm-pf', role: 'complementary', 'aria-label': name },
    h('div', { className: 'nm-pf-top', 'data-window-drag': true },
      h('button', { type: 'button', className: 'nm-icon-btn', 'aria-label': t('close'), onClick: () => profileBus.close() }, h(IconClose, { size: 16 })),
      h('span', { className: 'nm-pf-top-space' }),
      h('button', { type: 'button', className: 'nm-icon-btn', 'aria-label': t('acShareTitle'), title: t('acShareTitle'), onClick: () => setShare(true) }, h(IconShare, { size: 16 }))),
    h('div', { className: 'nm-pf-head' },
      h('div', { className: 'nm-pf-face' },
        // the face itself opens the same menu as the pen (as on the phone): the look, the name, the studio
        h('button', { type: 'button', className: 'nm-pf-face-btn', 'aria-label': t('pfChangeLook'), 'aria-haspopup': 'menu', 'aria-expanded': menu, onClick: () => setMenu((m) => !m) },
          h(Avatar, { size: 86, profile: live.profile, mood: running ? 'working' : 'idle' })),
        h('button', { type: 'button', className: 'nm-pf-pen', 'aria-label': t('pfEditName'), 'aria-haspopup': 'menu', 'aria-expanded': menu, onClick: () => setMenu((m) => !m) }, h(IconPencil, { size: 13 })),
        menu
          ? h('div', { className: 'nm-menu nm-pf-menu', role: 'menu' },
              h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', onClick: changeAvatar }, h('span', { className: 'nm-menu-item-label' }, t('pfChangeAvatar'))),
              h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', onClick: () => { setMenu(false); setEditing('name') } }, h('span', { className: 'nm-menu-item-label' }, t('pfEditName'))),
              h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', disabled: clipChooser.busy, onClick: () => { setMenu(false); clipChooser.choose() } }, h('span', { className: 'nm-menu-item-label' }, t('pfOwnClip'))),
              live.ownClip
                ? h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', onClick: () => { setMenu(false); void call('own-clip/clear', {}).catch(() => undefined) } }, h('span', { className: 'nm-menu-item-label' }, t('ocDefault')))
                : null,
              h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', onClick: () => { setMenu(false); profileBus.close(); studioBus.open?.(live.profile.description || '', live.profile.style || 'muse') } }, h('span', { className: 'nm-menu-item-label' }, t('pfAvatarStudio'))),
              h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', onClick: () => { setMenu(false); setEditing('look') } }, h('span', { className: 'nm-menu-item-label' }, t('pfChangeLook'))))
          : null),
      h('div', { className: 'nm-pf-name' }, name),
      status),
    editing === 'none'
      ? h('div', { className: 'nm-seg', role: 'tablist' }, tabs.map((item) =>
          h('button', { key: item.id, type: 'button', role: 'tab', className: `nm-seg-btn${tab === item.id ? ' nm-active' : ''}`, 'aria-selected': tab === item.id, 'aria-label': item.label, title: item.label, onClick: () => setTab(item.id) }, item.icon)))
      : null,
    h('div', { className: 'nm-pf-body' }, body),
    share ? h(AvatarShareSheet, { t, name, onClose: () => setShare(false) }) : null,
    detail ? h(TaskDetail, { t, sessionId: detail, useSessions, onClose: () => setDetail(null) }) : null,
    clipChooser.element)
}

/** The words under the face for a running task: the step under way, else the job itself
 * ("On it: book the table"), never a state of mind. */
export function statusWords(t: Translate, record: ActivityRecord): string {
  if (record.status === 'waiting') return t('statusWaiting')
  const name = record.current?.name ?? ''
  const base = name.replace(/^mcp__[^_]+(?:_[^_]+)*?__/, '')
  if (!name) return onIt(t, record)
  // the step's own words, as the trace shows them ("打开携程网站"): the model's description
  // where the tool takes one, else the kind of step and what it is on ("Reading · ctrip.com")
  const title = (record.current?.title ?? '').trim()
  if (title && record.current?.own) return title
  const kind = stepKind(t, base)
  if (title) return `${kind} · ${title.length > 40 ? `${title.slice(0, 39)}…` : title}`
  return kind
}

/** The kind of step a tool name stands for, in the agent's words. */
function stepKind(t: Translate, base: string): string {
  if (/screen|look|see|observe/i.test(base)) return t('statusLooking')
  if (/computer|act|click|type|key|mouse|press|open_app/i.test(base)) return t('statusComputer')
  if (/web_search|search/i.test(base)) return t('statusSearching')
  if (/web_fetch|fetch|read_file|glob|grep/i.test(base)) return t('statusReading')
  if (/write|edit|create|str_replace|present/i.test(base)) return t('statusWriting')
  if (/bash|pwsh|shell|command/i.test(base)) return t('statusRunning')
  if (/ask_user|question/i.test(base)) return t('statusAsking')
  if (/draw|image|studio/i.test(base)) return t('statusDrawing')
  if (/schedule|remind/i.test(base)) return t('statusScheduling')
  return t('statusWorking')
}

/** "On it: <the request, briefly>" — its first line, folded, cut at a word; "Hard at work" without one. */
export function onIt(t: Translate, record: Pick<ActivityRecord, 'request'>): string {
  const brief = requestBrief(record.request)
  return brief ? t('statusOn', { request: brief }) : t('statusWorking')
}

export function requestBrief(text: string, max = 36): string {
  const line = (text || '').split(/\r?\n/).map((l) => l.trim()).find((l) => l.length > 0) ?? ''
  const folded = line.replace(/\s+/g, ' ')
  if (folded.length <= max) return folded
  const cut = folded.slice(0, max)
  const at = cut.lastIndexOf(' ')
  return `${(at > max / 2 ? cut.slice(0, at) : cut).trimEnd()}…`
}

function Activity({ t, name, stop, useSessions, open }: { t: Translate; name: string; stop(id: string): Promise<void>; useSessions: ProfileDrawerProps['useSessions']; open(id: string): void }): ReactNode {
  const live = useLive()
  const rooms = useRooms()
  const titles = typeof useSessions === 'function'
    ? useSessions((s) => new Map(s.ids
      .map((id) => [id, s.byId[id]] as [string, ChatListState['byId'][string] | undefined])
      .filter((pair): pair is [string, NonNullable<ChatListState['byId'][string]>] => pair[1] !== undefined && pair[1].origin !== 'subagent')
      .map(([id, c]) => [id, c.displayTitle] as const)))
    : new Map<string, string>()
  const [stopping, setStopping] = useState<string | null>(null)
  interface Row { id: string; at: number; title: string; sub: string; state: 'live' | 'done' | 'error' | 'avatar' | 'notice' }
  const rows: Row[] = rooms.activity
    .filter((r) => titles.size === 0 || titles.has(r.sessionId))
    .map((r) => ({
      id: r.sessionId,
      at: r.updatedAt,
      title: titles.get(r.sessionId) || r.request || t('chUntitled'),
      sub: r.status === 'running' || r.status === 'waiting' ? statusWords(t, r) : r.words || r.request,
      state: r.status === 'running' || r.status === 'waiting' ? 'live' : r.status === 'error' ? 'error' : r.steps.some((s) => /draw_new_look|studio/.test(s.name)) ? 'avatar' : 'done',
    }))
  for (const notice of live.notices) rows.push({ id: '', at: notice.at, title: notice.title, sub: notice.from, state: 'notice' })
  rows.sort((a, b) => b.at - a.at)
  if (rows.length === 0) return h('p', { className: 'nm-pf-empty' }, t('pfNoActivity', { name }))
  const groups = new Map<string, Row[]>()
  for (const row of rows.slice(0, 60)) {
    const label = dayLabel(t, row.at, rooms.lang)
    const list = groups.get(label) ?? []
    list.push(row)
    groups.set(label, list)
  }
  const icon = (row: Row) => row.state === 'live' ? h(IconSpinner, { size: 18 }) : row.state === 'avatar' ? h(IconSparkle, { size: 18 }) : h(IconCheck, { size: 18 })
  return h('div', { className: 'nm-pf-rows' }, [...groups].map(([label, items]) => h('div', { key: label },
    h('div', { className: 'nm-pf-day' }, label),
    items.map((r, i) => h('div', { key: `${r.id}-${r.at}-${i}`, className: `nm-pf-row nm-pf-task${r.id ? ' nm-clickable' : ''}`, role: r.id ? 'button' : undefined, tabIndex: r.id ? 0 : undefined, onClick: () => { if (r.id) open(r.id) }, onKeyDown: (e: KeyboardEvent) => { if (r.id && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); open(r.id) } } },
      h('span', { className: `nm-pf-row-icon${r.state === 'live' ? ' nm-live' : r.state === 'error' ? ' nm-err' : ''}` }, icon(r)),
      h('div', { className: 'nm-pf-row-main' },
        h('div', { className: 'nm-pf-row-title' }, r.title),
        r.sub ? h('div', { className: 'nm-pf-row-sub' }, r.sub) : null),
      r.state === 'live' && r.id
        ? h('button', { type: 'button', className: 'nm-pf-stop', title: t('capsuleStop'), 'aria-label': t('capsuleStop'), disabled: stopping === r.id, onClick: (e: MouseEvent) => { e.stopPropagation(); setStopping(r.id); void stop(r.id).finally(() => setStopping(null)) } }, h(IconSquare, { size: 14 }))
        : h('span', { className: 'nm-pf-row-time' }, clockLabel(r.at, rooms.lang)))))))
}

function Approvals({ t, name, approvals, grants }: { t: Translate; name: string; approvals: { at: number; toolName: string; reason: string; outcome: 'allowed' | 'rejected' }[]; grants: { id: string; target: string; at: number }[] }): ReactNode {
  const [openRow, setOpenRow] = useState<number | null>(null)
  const openPermissions = () => { profileBus.close(); settingsBus.openSection?.(COMPUTER_SECTION) }
  const manage = h('div', { className: 'nm-pf-row nm-clickable', role: 'button', tabIndex: 0, onClick: openPermissions, onKeyDown: (e: { key: string; preventDefault(): void }) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPermissions() } } },
    h('span', { className: 'nm-pf-row-icon' }, h(IconShield, { size: 18 })),
    h('div', { className: 'nm-pf-row-main' }, h('div', { className: 'nm-pf-row-title' }, t('pfManagePermissions'))),
    h('span', { className: 'nm-pf-row-chev' }, h(IconChevronRight, { size: 16 })))
  // the standing grants first (the phone's list), then what was answered on cards
  const standing = grants.length
    ? h(Fragment, null,
        h('div', { className: 'nm-pf-day' }, t('pfApprovalsAlways')),
        grants.map((g) => h('div', { key: g.id, className: 'nm-pf-row' },
          h('span', { className: 'nm-pf-row-icon' }, h(IconCheck, { size: 18 })),
          h('div', { className: 'nm-pf-row-main' },
            h('div', { className: 'nm-pf-row-title' }, t('pfAlwaysAllowed', { target: g.target.replace(/^computer_app:/, '') })),
            h('div', { className: 'nm-pf-row-sub' }, ago(t, g.at))),
          h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: () => void call('grants/revoke', { id: g.id }).catch(() => undefined) }, t('pmRevoke')))))
    : h('p', { className: 'nm-pf-empty' }, t('pfApprovalsEmpty', { name }))
  if (approvals.length === 0) return h('div', { className: 'nm-pf-rows' }, standing, manage)
  return h('div', { className: 'nm-pf-rows' },
    standing,
    manage,
    h('div', { className: 'nm-pf-day' }, t('pfApprovalLog')),
    approvals.map((a, i) => h('div', { key: `${a.at}-${i}`, className: 'nm-pf-row nm-pf-approval', onClick: () => setOpenRow(openRow === i ? null : i) },
      h('span', { className: 'nm-pf-row-icon' }, h(IconShield, { size: 18 })),
      h('div', { className: 'nm-pf-row-main' },
        h('div', { className: 'nm-pf-row-title' }, firstWords(a.reason || a.toolName, openRow === i ? 400 : 60)),
        h('div', { className: 'nm-pf-row-sub' }, `${a.outcome === 'allowed' ? t('pfAllowed') : t('pfDenied')} · ${ago(t, a.at)}`)),
      h('span', { className: `nm-pf-row-chev${openRow === i ? ' nm-open' : ''}` }, h(IconChevronDown, { size: 16 })))))
}

function firstWords(text: string, n: number): string {
  const s = text.replace(/\s+/g, ' ').trim()
  return s.length > n ? `${s.slice(0, n - 1)}…` : s
}

function Reminders({ t, name, openSchedules }: { t: Translate; name: string; openSchedules(): void }): ReactNode {
  const rooms = useRooms()
  const all: { goal: string; automation: GoalAutomation }[] = []
  for (const goal of rooms.goals) for (const automation of rooms.automations[goal.id] ?? []) all.push({ goal: goal.title, automation })
  const once = all.filter((a) => !/^(daily|weekly|cron|every)/.test(a.automation.rule))
  const recurring = all.filter((a) => /^(daily|weekly|cron|every)/.test(a.automation.rule))
  const when = (a: GoalAutomation) => {
    const next = Date.parse(a.next)
    if (Number.isFinite(next)) return `${dayLabel(t, next, rooms.lang)}, ${clockLabel(next, rooms.lang)}`
    return a.rule
  }
  const row = (entry: { goal: string; automation: GoalAutomation }, icon: ReactNode) => h('div', { key: entry.automation.id, className: 'nm-pf-row' },
    h('span', { className: 'nm-pf-row-icon' }, icon),
    h('div', { className: 'nm-pf-row-main' },
      h('div', { className: 'nm-pf-row-title' }, entry.automation.title || entry.goal),
      h('div', { className: 'nm-pf-row-sub' }, `${when(entry.automation)}${entry.automation.title ? ` · ${entry.goal}` : ''}`)))
  if (all.length === 0) {
    return h('div', { className: 'nm-pf-stack' },
      h('p', { className: 'nm-pf-empty' }, t('pfRemindersEmpty', { name })),
      h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: openSchedules }, t('pfManageRoutines')))
  }
  return h('div', { className: 'nm-pf-rows' },
    once.length ? h('div', { className: 'nm-pf-day' }, t('pfRemindersOnce')) : null,
    once.map((entry) => row(entry, h(IconBell, { size: 18 }))),
    recurring.length ? h('div', { className: 'nm-pf-day' }, t('pfRemindersDaily')) : null,
    recurring.map((entry) => row(entry, h(IconAlarm, { size: 18 }))),
    h('div', { className: 'nm-pf-foot' }, h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: openSchedules }, t('pfManageRoutines'))))
}

function Identity({ t, name, onEditName }: { t: Translate; name: string; onEditName(): void }): ReactNode {
  const rooms = useRooms()
  const live = useLive()
  const memoryAt = rooms.memory.reduce((max, m) => Math.max(max, m.at), 0)
  const stamp = (at: number) => (at ? new Date(at).toLocaleDateString(rooms.lang || undefined, { year: '2-digit', month: '2-digit', day: '2-digit' }) : t('pfDocUntouched'))
  const openDoc = (doc: 'identity' | 'soul' | 'memory') => { win.openDoc({ kind: 'own', doc }); win.touchRecent(`own:${doc}`, doc === 'identity' ? 'IDENTITY.md' : doc === 'soul' ? 'SOUL.md' : 'MEMORY.md') }
  return h('div', { className: 'nm-pf-stack' },
    h('div', { className: 'nm-pf-card' },
      h('div', { className: 'nm-pf-card-name' }, name),
      live.profile.description ? h('div', { className: 'nm-pf-card-sub' }, live.profile.description) : null,
      h('div', { className: 'nm-pf-card-actions' },
        h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm nm-pill-wide', onClick: () => openDoc('identity') }, h(IconPencil, { size: 14 }), t('pfEdit')),
        h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: onEditName }, t('pfEditName')))),
    h('button', { type: 'button', className: 'nm-pf-doc nm-pf-doc-soul', onClick: () => openDoc('soul') },
      h('div', { className: 'nm-pf-doc-title' }, 'SOUL'),
      h('div', { className: 'nm-pf-doc-sub' }, t('pfDocOpen')),
      h('div', { className: 'nm-pf-doc-foot' }, h('span', null, stamp(rooms.docs.soulAt)), h('span', { 'aria-hidden': true }, '♥'))),
    h('div', { style: { display: 'flex', gap: 6, width: '100%' } },
      h('button', {
        type: 'button',
        className: 'nm-pf-doc nm-pf-doc-memory',
        style: { flex: 1, marginBottom: 8 },
        onClick: () => { profileBus.close(); settingsBus.openSection?.('nanomuse-memory') },
      },
        h('div', { className: 'nm-pf-doc-title' }, t('pfMemory')),
        h('div', { className: 'nm-pf-doc-sub' }, rooms.memory.length ? t('pfMemoryCount', { n: rooms.memory.length }) : t('pfDocOpen')),
        h('div', { className: 'nm-pf-doc-foot' }, h('span', null, stamp(memoryAt)), h('span', { 'aria-hidden': true }, '♥'))),
      h('button', {
        type: 'button',
        className: 'nm-icon-btn',
        style: { height: 'auto', marginBottom: 8, padding: '0 12px', borderRadius: 14, background: 'var(--nm-card)' },
        title: t('memOpenDoc'),
        'aria-label': t('memOpenDoc'),
        onClick: () => openDoc('memory'),
      }, h(IconDoc, { size: 15 }))),
    h('p', { className: 'nm-pf-empty' }, t('pfHandleWithCare', { name })),
    live.profile.avatar === 'face' ? h('p', { className: 'nm-pf-empty' }, t('pfFaceNote')) : null)
}

function NameEditor({ t, profile, onDone }: { t: Translate; profile: LiveProfile; onDone(): void }): ReactNode {
  const [value, setValue] = useState(profile.name)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const save = (event: FormEvent) => {
    event.preventDefault()
    const name = value.trim()
    if (!name) return
    setBusy(true)
    setError(undefined)
    call('profile', { name })
      .then(() => onDone())
      .catch((err: unknown) => setError(failureText(t, err)))
      .finally(() => setBusy(false))
  }
  return h('form', { className: 'nm-pf-stack', onSubmit: save },
    h('label', { className: 'nm-pf-label' }, t('pfNameLabel')),
    h('input', { className: 'nm-field', value, maxLength: 60, autoFocus: true, 'aria-label': t('pfNameLabel'), onChange: (e: FormEvent<HTMLInputElement>) => setValue(e.currentTarget.value) }),
    error ? h('div', { className: 'nm-ob-error', role: 'alert' }, error) : null,
    h('div', { className: 'nm-pf-actions' },
      h('button', { type: 'submit', className: 'nm-pill nm-pill-sm', disabled: busy || value.trim().length === 0 }, t('save')),
      h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', disabled: busy, onClick: onDone }, t('cancel'))))
}

function LookEditor({ t, profile, onDone }: { t: Translate; profile: LiveProfile; onDone(): void }): ReactNode {
  const [avatar, setAvatar] = useState<'dragon' | 'emoji'>(profile.avatar === 'emoji' ? 'emoji' : 'dragon')
  const [emoji, setEmoji] = useState(profile.emoji || '✨')
  const [color, setColor] = useState(profile.color || '#0064d4')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const preview: LiveProfile = { ...profile, avatar, emoji, color }
  const save = () => {
    setBusy(true)
    setError(undefined)
    call('profile', avatar === 'emoji' ? { avatar, emoji, color } : { avatar })
      .then(() => onDone())
      .catch((err: unknown) => setError(failureText(t, err)))
      .finally(() => setBusy(false))
  }
  return h('div', { className: 'nm-pf-stack' },
    h('div', { className: 'nm-pf-preview' }, h(Avatar, { size: 72, profile: preview })),
    h('div', { className: 'nm-seg', role: 'radiogroup' },
      h('button', { type: 'button', role: 'radio', 'aria-checked': avatar === 'dragon', className: `nm-seg-btn nm-seg-text${avatar === 'dragon' ? ' nm-active' : ''}`, onClick: () => setAvatar('dragon') }, t('lookDragon')),
      h('button', { type: 'button', role: 'radio', 'aria-checked': avatar === 'emoji', className: `nm-seg-btn nm-seg-text${avatar === 'emoji' ? ' nm-active' : ''}`, onClick: () => setAvatar('emoji') }, t('lookEmoji')),
      h('button', { type: 'button', role: 'radio', 'aria-checked': false, className: 'nm-seg-btn nm-seg-text', onClick: () => { onDone(); profileBus.close(); studioBus.open?.(profile.description || '', profile.style || 'muse') } }, t('lookDraw'))),
    avatar === 'emoji'
      ? h('div', { className: 'nm-pf-stack' },
          h('label', { className: 'nm-pf-label' }, t('pfEmoji')),
          h('div', { className: 'nm-pf-choices' }, EMOJI_CHOICES.map((e) => h('button', { key: e, type: 'button', className: `nm-pf-choice${emoji === e ? ' nm-active' : ''}`, onClick: () => setEmoji(e) }, e))),
          h('input', { className: 'nm-field', value: emoji, maxLength: 16, 'aria-label': t('pfEmoji'), onChange: (e: FormEvent<HTMLInputElement>) => setEmoji(e.currentTarget.value) }),
          h('label', { className: 'nm-pf-label' }, t('pfColor')),
          h('div', { className: 'nm-pf-choices' }, COLOR_CHOICES.map((c) => h('button', { key: c, type: 'button', className: `nm-pf-swatch${color === c ? ' nm-active' : ''}`, style: { background: c }, 'aria-label': c, onClick: () => setColor(c) }))))
      : profile.avatar === 'face' ? h('p', { className: 'nm-pf-empty' }, t('pfFaceNote')) : null,
    error ? h('div', { className: 'nm-ob-error', role: 'alert' }, error) : null,
    h('div', { className: 'nm-pf-actions' },
      h('button', { type: 'button', className: 'nm-pill nm-pill-sm', disabled: busy, onClick: save }, t('save')),
      h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', disabled: busy, onClick: onDone }, t('cancel'))))
}
