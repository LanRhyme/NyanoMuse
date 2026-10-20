/**
 * Memory — what the agent remembers about the person, one line each, kept on this
 * computer (`rooms.json`) and read into every chat's prompt. The drawer's Memory tab
 * lists it; Muse's "import memory" sheet pastes a text from another assistant in,
 * one line per memory; the Data controls page downloads everything as a zip or
 * resets the agent.
 */
import { failureText } from './api.ts'
import { createElement as h, Fragment, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { IconBrain, IconClose } from './icons.tsx'
import { roomsCall, useRooms, type MemoryItem } from './rooms.ts'
import { ago, Sheet } from './ui.tsx'

/** One row of `/memory/search`, which reaches the archived memories the list leaves out. */
interface MemoryHit {
  id: string
  content: string
  category: string
  score: number
  is_pinned: number
  is_archived: number
  updated_at: number
}

/** The memory list with an add field, a search field and the import button — the drawer's Memory tab. */
export function MemoryList({ t, onImport }: { t: Translate; onImport(): void }): ReactNode {
  const rooms = useRooms()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<MemoryHit[] | null>(null)
  const [searching, setSearching] = useState(false)
  const items = [...rooms.memory].sort((a, b) => b.at - a.at)
  const inSearch = Boolean(query.trim())

  // The list holds the hot memories only; a search reaches the cold ones too, which is
  // the point: a fact moved out of the list for age is still findable and forgettable.
  useEffect(() => {
    const q = query.trim()
    if (!q) {
      setHits(null)
      setSearching(false)
      return
    }
    let live = true
    setSearching(true)
    const timer = setTimeout(() => {
      roomsCall<{ results: MemoryHit[] }>('memory/search', { query: q, limit: 50, includeArchived: true })
        .then((r) => { if (live) setHits(r.results) })
        .catch(() => { if (live) setHits([]) })
        .finally(() => { if (live) setSearching(false) })
    }, 220)
    return () => { live = false; clearTimeout(timer) }
  }, [query])

  const add = (event: FormEvent) => {
    event.preventDefault()
    const line = text.trim()
    if (!line) return
    setBusy(true)
    roomsCall('memory/add', { text: line }).then(() => setText('')).catch(() => undefined).finally(() => setBusy(false))
  }
  const forgetHit = (id: string) => {
    setHits((prev) => (prev ? prev.filter((m) => m.id !== id) : prev))
    void roomsCall('memory/delete', { id }).catch(() => undefined)
  }
  return h(Fragment, null,
    h('form', { className: 'nm-mem-add', onSubmit: add },
      h('input', { className: 'nm-mem-input', value: text, placeholder: t('memAddPlaceholder'), maxLength: 400, onChange: (e: FormEvent<HTMLInputElement>) => setText(e.currentTarget.value) }),
      h('button', { type: 'submit', className: 'nm-pill nm-pill-sm', disabled: busy || !text.trim() }, t('memAdd'))),
    h('input', { className: 'nm-mem-input', value: query, placeholder: t('memSearch'), maxLength: 200, onChange: (e: FormEvent<HTMLInputElement>) => setQuery(e.currentTarget.value) }),
    inSearch
      ? (hits === null || searching
          ? null
          : hits.length
            ? h('div', { className: 'nm-pf-rows' }, hits.map((hit) => h(MemoryHitRow, { key: hit.id, t, hit, onForget: forgetHit })))
            : h('p', { className: 'nm-pf-empty' }, t('memNoMatch')))
      : (items.length
          ? h('div', { className: 'nm-pf-rows' }, items.map((item) => h(MemoryRow, { key: item.id, t, item })))
          : h('p', { className: 'nm-pf-empty' }, t('memEmpty'))),
    !inSearch && rooms.memoryArchived > 0
      ? h('p', { className: 'nm-pf-empty' }, t('memArchivedNote', { n: rooms.memoryArchived }))
      : null,
    h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
      h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: onImport }, t('memImport'))))
}

function MemoryHitRow({ t, hit, onForget }: { t: Translate; hit: MemoryHit; onForget(id: string): void }): ReactNode {
  return h('div', { className: 'nm-pf-row' },
    h('span', { className: 'nm-pf-row-icon' }, h(IconBrain, { size: 18 })),
    h('div', { className: 'nm-pf-row-main' },
      h('div', { className: 'nm-pf-row-title nm-wrap' }, hit.content),
      h('div', { className: 'nm-pf-row-sub' }, hit.is_archived ? `${t('memArchived')} · ${ago(t, hit.updated_at)}` : ago(t, hit.updated_at))),
    h('button', { type: 'button', className: 'nm-icon-btn nm-mem-forget', 'aria-label': t('memForget'), title: t('memForget'), onClick: () => onForget(hit.id) }, h(IconClose, { size: 14 })))
}

function MemoryRow({ t, item }: { t: Translate; item: MemoryItem }): ReactNode {
  return h('div', { className: 'nm-pf-row' },
    h('span', { className: 'nm-pf-row-icon' }, h(IconBrain, { size: 18 })),
    h('div', { className: 'nm-pf-row-main' },
      h('div', { className: 'nm-pf-row-title nm-wrap' }, item.text),
      h('div', { className: 'nm-pf-row-sub' }, `${t(item.source === 'agent' ? 'memFromAgent' : item.source === 'import' ? 'memFromImport' : 'memFromYou')} · ${ago(t, item.at)}`)),
    h('button', { type: 'button', className: 'nm-icon-btn nm-mem-forget', 'aria-label': t('memForget'), title: t('memForget'), onClick: () => { void roomsCall('memory/delete', { id: item.id }).catch(() => undefined) } }, h(IconClose, { size: 14 })))
}

/** Muse's "import memory": paste what another assistant knew; each line becomes a memory. */
export function ImportMemorySheet({ t, onClose }: { t: Translate; onClose(): void }): ReactNode {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<number | undefined>()
  const [error, setError] = useState<string | undefined>()
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!text.trim()) return
    setBusy(true)
    setError(undefined)
    roomsCall<{ added: number }>('memory/import', { text }).then((r) => setDone(r.added)).catch((err: unknown) => setError(failureText(t, err))).finally(() => setBusy(false))
  }
  return h(Sheet, { title: t('memImport'), closeLabel: t('close'), onClose,
    footer: h('div', { className: 'nm-sheet-actions' },
      h('span', { style: { flex: 1 } }),
      h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: onClose }, done === undefined ? t('cancel') : t('close')),
      done === undefined ? h('button', { type: 'submit', form: 'nm-mem-import', className: 'nm-pill nm-pill-sm', disabled: busy || !text.trim() }, busy ? t('memImporting') : t('memImportDo')) : null) },
    h('form', { id: 'nm-mem-import', className: 'nm-sheet-form', onSubmit: submit },
      h('p', { className: 'nm-sheet-lead' }, t('memImportLead')),
      h('ol', { className: 'nm-sheet-steps' },
        h('li', null, t('memImportStep1')),
        h('li', null, t('memImportStep2')),
        h('li', null, t('memImportStep3'))),
      done === undefined
        ? h('textarea', { className: 'nm-textarea', rows: 8, value: text, placeholder: t('memImportPlaceholder'), maxLength: 60000, onChange: (e: FormEvent<HTMLTextAreaElement>) => setText(e.currentTarget.value), 'data-modal-autofocus': true })
        : h('p', { className: 'nm-sheet-lead' }, t('memImported', { n: done })),
      error ? h('div', { className: 'nm-room-error' }, error) : null))
}

/** Muse's Data controls, the local part: import memory, download the agent data, reset. */
export function DataRows({ t }: { t: Translate }): ReactNode {
  const [sheet, setSheet] = useState(false)
  const [notice, setNotice] = useState<string | undefined>()
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)
  const download = () => {
    setBusy(true)
    setNotice(undefined)
    roomsCall<{ path: string }>('data/export', {}).then((r) => setNotice(t('dataExported', { path: r.path }))).catch((err: unknown) => setNotice(failureText(t, err))).finally(() => setBusy(false))
  }
  const reset = () => {
    setBusy(true)
    roomsCall('data/reset', {}).then(() => window.location.reload()).catch((err: unknown) => { setNotice(failureText(t, err)); setBusy(false) })
  }
  return h(Fragment, null,
    h('div', { className: 'nm-card' },
      h('div', { className: 'nm-row' },
        h('div', { className: 'nm-row-main' },
          h('span', { className: 'nm-row-title' }, t('memImport')),
          h('span', { className: 'nm-row-sub nm-wrap' }, t('dataImportSub'))),
        h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: () => setSheet(true) }, t('dataImportDo'))),
      h('div', { className: 'nm-row' },
        h('div', { className: 'nm-row-main' },
          h('span', { className: 'nm-row-title' }, t('dataDownload')),
          h('span', { className: 'nm-row-sub nm-wrap' }, t('dataDownloadSub'))),
        h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', disabled: busy, onClick: download }, t('dataDownloadDo'))),
      h('div', { className: 'nm-row' },
        h('div', { className: 'nm-row-main' },
          h('span', { className: 'nm-row-title nm-danger' }, t('dataReset')),
          h('span', { className: 'nm-row-sub nm-wrap' }, t('dataResetSub'))),
        h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm nm-danger', disabled: busy, onClick: () => setConfirm(true) }, t('dataResetDo'))),
      notice ? h('div', { className: 'nm-row' }, h('span', { className: 'nm-row-sub nm-wrap' }, notice)) : null),
    sheet ? h(ImportMemorySheet, { t, onClose: () => setSheet(false) }) : null,
    confirm
      ? h(Sheet, { title: t('dataReset'), closeLabel: t('close'), onClose: () => setConfirm(false),
          footer: h('div', { className: 'nm-sheet-actions' },
            h('span', { style: { flex: 1 } }),
            h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', onClick: () => setConfirm(false) }, t('cancel')),
            h('button', { type: 'button', className: 'nm-pill nm-pill-sm nm-pill-danger', disabled: busy, onClick: reset }, busy ? t('dataResetting') : t('dataResetDo'))) },
          h('div', { className: 'nm-sheet-form' },
            h('p', { className: 'nm-sheet-lead' }, t('dataResetConfirm'))))
      : null)
}
