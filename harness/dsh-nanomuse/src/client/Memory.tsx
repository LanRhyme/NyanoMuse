/**
 * Memory — what the agent remembers about the person, one line each, kept on this
 * computer (`rooms.json` / `memory.db`) and read into every chat's prompt.
 *
 * Modernized with Morandi glassmorphism aesthetic: instant CJK+token search,
 * Hot/Cold status filtering, category filtering (preference, habit, device,
 * profile, custom), card actions (Pin, Archive, Edit, Delete), and import/export tools.
 */
import { failureText, type Translate } from './api.ts'
import { createElement as h, Fragment, useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { IconArchive, IconBrain, IconClose, IconDoc, IconPencil, IconPin, IconTrash } from './icons.tsx'
import { roomsCall, useRooms, type MemoryItem } from './rooms.ts'
import { ago, Sheet } from './ui.tsx'
import { win } from './win.ts'

export interface MemoryRecord {
  id: string
  content: string
  search_text: string
  category: 'preference' | 'habit' | 'profile' | 'device' | 'custom'
  source: 'person' | 'agent' | 'import' | 'extracted' | 'tool'
  is_pinned: number
  is_archived: number
  importance: number
  use_count: number
  last_used_at: number
  confidence: number
  created_at: number
  updated_at: number
  deleted: number
  seq: number
}

interface MemoryHit {
  id: string
  content: string
  category: string
  score: number
  is_pinned: number
  is_archived: number
  updated_at: number
}

type StatusFilter = 'all' | 'active' | 'archived'
type CategoryFilter = 'all' | 'preference' | 'habit' | 'device' | 'profile' | 'custom'

export function makeMemorySection(t: Translate) {
  return function MemorySection(): ReactNode {
    return h(MemorySettingsPage, { t })
  }
}

export function MemorySettingsPage({ t }: { t: Translate }): ReactNode {
  const [items, setItems] = useState<MemoryRecord[]>([])
  const [activeCount, setActiveCount] = useState(0)
  const [archivedCount, setArchivedCount] = useState(0)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [catFilter, setCatFilter] = useState<CategoryFilter>('all')
  const [showAdd, setShowAdd] = useState(false)
  const [newContent, setNewContent] = useState('')
  const [newCat, setNewCat] = useState<MemoryRecord['category']>('preference')
  const [editItem, setEditItem] = useState<MemoryRecord | null>(null)
  const [editContent, setEditContent] = useState('')
  const [editCat, setEditCat] = useState<MemoryRecord['category']>('preference')
  const [deleteConfirm, setDeleteConfirm] = useState<MemoryRecord | null>(null)
  const [showImport, setShowImport] = useState(false)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    roomsCall<{ items: MemoryRecord[]; activeCount: number; archivedCount: number }>('memory/list', {})
      .then((r) => {
        setItems(r.items || [])
        setActiveCount(r.activeCount ?? 0)
        setArchivedCount(r.archivedCount ?? 0)
      })
      .catch(() => undefined)
  }, [])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    const q = query.trim()
    if (!q) {
      load()
      return
    }
    let live = true
    const timer = setTimeout(() => {
      roomsCall<{ results: MemoryHit[] }>('memory/search', { query: q, limit: 100, includeArchived: true })
        .then((r) => {
          if (!live) return
          setItems(r.results.map((hit) => ({
            id: hit.id,
            content: hit.content,
            search_text: '',
            category: (hit.category as MemoryRecord['category']) || 'preference',
            source: 'agent',
            is_pinned: hit.is_pinned,
            is_archived: hit.is_archived,
            importance: hit.score,
            use_count: 0,
            last_used_at: 0,
            confidence: 1,
            created_at: hit.updated_at,
            updated_at: hit.updated_at,
            deleted: 0,
            seq: 0,
          })))
        })
        .catch(() => { if (live) setItems([]) })
    }, 200)
    return () => { live = false; clearTimeout(timer) }
  }, [query, load])

  const togglePin = (item: MemoryRecord) => {
    const next = item.is_pinned ? 0 : 1
    setItems((prev) => prev.map((m) => m.id === item.id ? { ...m, is_pinned: next } : m))
    roomsCall('memory/pin', { id: item.id, is_pinned: next }).then(load).catch(load)
  }

  const toggleArchive = (item: MemoryRecord) => {
    const next = item.is_archived ? 0 : 1
    setItems((prev) => prev.map((m) => m.id === item.id ? { ...m, is_archived: next } : m))
    roomsCall('memory/archive', { id: item.id, is_archived: next }).then(load).catch(load)
  }

  const doDelete = (id: string) => {
    setItems((prev) => prev.filter((m) => m.id !== id))
    setDeleteConfirm(null)
    roomsCall('memory/delete', { id }).then(load).catch(load)
  }

  const saveEdit = (e: FormEvent) => {
    e.preventDefault()
    if (!editItem || !editContent.trim()) return
    setBusy(true)
    roomsCall('memory/update', { id: editItem.id, content: editContent.trim(), category: editCat })
      .then(() => {
        setEditItem(null)
        load()
      })
      .finally(() => setBusy(false))
  }

  const addMemory = (e: FormEvent) => {
    e.preventDefault()
    const content = newContent.trim()
    if (!content) return
    setBusy(true)
    roomsCall('memory/add', { text: content, category: newCat })
      .then(() => {
        setNewContent('')
        setShowAdd(false)
        load()
      })
      .finally(() => setBusy(false))
  }

  const openDoc = () => {
    win.openDoc({ kind: 'own', doc: 'memory' })
    win.touchRecent('own:memory', 'MEMORY.md')
  }

  const filtered = items.filter((item) => {
    if (statusFilter === 'active' && item.is_archived) return false
    if (statusFilter === 'archived' && !item.is_archived) return false
    if (catFilter !== 'all' && item.category !== catFilter) return false
    return true
  })

  const categoryLabel = (cat: string) => {
    switch (cat) {
      case 'preference': return t('memCatPreference')
      case 'habit': return t('memCatHabit')
      case 'device': return t('memCatDevice')
      case 'profile': return t('memCatProfile')
      case 'custom': return t('memCatCustom')
      default: return cat
    }
  }

  const sourceLabel = (source: string) => {
    switch (source) {
      case 'person':
      case 'manual': return t('memFromYou')
      case 'import': return t('memFromImport')
      default: return t('memFromAgent')
    }
  }

  return h('div', { className: 'nm-mem-page' },
    // 1. Dashboard summary cards
    h('div', { className: 'nm-mem-dashboard', title: t('memStats', { active: activeCount, archived: archivedCount }) },
      h('div', { className: 'nm-mem-stat-card' },
        h('span', { className: 'nm-mem-stat-val' }, activeCount),
        h('span', { className: 'nm-mem-stat-lbl' }, t('memStatusActive'))),
      h('div', { className: 'nm-mem-stat-card' },
        h('span', { className: 'nm-mem-stat-val' }, archivedCount),
        h('span', { className: 'nm-mem-stat-lbl' }, t('memStatusArchived'))),
      h('div', { className: 'nm-mem-stat-card' },
        h('span', { className: 'nm-mem-stat-val' }, activeCount + archivedCount),
        h('span', { className: 'nm-mem-stat-lbl' }, t('memStatusAll')))),

    // 2. Search & Filter controls
    h('div', { className: 'nm-mem-controls' },
      h('div', { className: 'nm-mem-bar' },
        h('div', { className: 'nm-mem-search-box' },
          h('input', {
            className: 'nm-mem-input',
            value: query,
            placeholder: t('memSearch'),
            maxLength: 200,
            onChange: (e: FormEvent<HTMLInputElement>) => setQuery(e.currentTarget.value),
          }),
          query
            ? h('button', {
                type: 'button',
                className: 'nm-mem-search-clear',
                onClick: () => setQuery(''),
                'aria-label': t('close'),
              }, h(IconClose, { size: 14 }))
            : null),
        h('button', {
          type: 'button',
          className: `nm-pill nm-pill-sm${showAdd ? ' nm-active' : ''}`,
          onClick: () => setShowAdd((s) => !s),
        }, showAdd ? t('cancel') : t('memAdd'))),

      // Status & Category filter pills
      h('div', { className: 'nm-mem-pills-row' },
        h('button', {
          type: 'button',
          className: `nm-pill nm-pill-sm${statusFilter === 'all' ? ' nm-active' : ' nm-pill-ghost'}`,
          onClick: () => setStatusFilter('all'),
        }, t('memStatusAll')),
        h('button', {
          type: 'button',
          className: `nm-pill nm-pill-sm${statusFilter === 'active' ? ' nm-active' : ' nm-pill-ghost'}`,
          onClick: () => setStatusFilter('active'),
        }, `${t('memStatusActive')} (${activeCount})`),
        h('button', {
          type: 'button',
          className: `nm-pill nm-pill-sm${statusFilter === 'archived' ? ' nm-active' : ' nm-pill-ghost'}`,
          onClick: () => setStatusFilter('archived'),
        }, `${t('memStatusArchived')} (${archivedCount})`),
        h('div', { className: 'nm-mem-divider-v' }),
        (['all', 'preference', 'habit', 'device', 'profile', 'custom'] as const).map((cat) =>
          h('button', {
            key: cat,
            type: 'button',
            className: `nm-pill nm-pill-sm${catFilter === cat ? ' nm-active' : ' nm-pill-ghost'}`,
            onClick: () => setCatFilter(cat),
          }, cat === 'all' ? t('memCatAll') : categoryLabel(cat))))),

    // 3. New Memory creation card
    showAdd
      ? h('form', { className: 'nm-mem-create-card', onSubmit: addMemory },
          h('div', { className: 'nm-mem-create-row' },
            h('select', {
              className: 'nm-mem-cat-select',
              value: newCat,
              onChange: (e: FormEvent<HTMLSelectElement>) => setNewCat(e.currentTarget.value as MemoryRecord['category']),
            },
              h('option', { value: 'preference' }, categoryLabel('preference')),
              h('option', { value: 'habit' }, categoryLabel('habit')),
              h('option', { value: 'device' }, categoryLabel('device')),
              h('option', { value: 'profile' }, categoryLabel('profile')),
              h('option', { value: 'custom' }, categoryLabel('custom'))),
            h('input', {
              className: 'nm-mem-input',
              value: newContent,
              placeholder: t('memAddPlaceholder'),
              maxLength: 400,
              autoFocus: true,
              onChange: (e: FormEvent<HTMLInputElement>) => setNewContent(e.currentTarget.value),
            }),
            h('button', {
              type: 'submit',
              className: 'nm-pill nm-pill-sm',
              disabled: busy || !newContent.trim(),
            }, t('memAdd'))))
      : null,

    // 4. Memory Stream Cards
    h('div', { className: 'nm-mem-stream' },
      filtered.length === 0
        ? h('p', { className: 'nm-pf-empty' }, query.trim() ? t('memNoMatch') : t('memEmpty'))
        : filtered.map((item) =>
            h(MemoryCardItem, {
              key: item.id,
              item,
              t,
              categoryLabel,
              sourceLabel,
              onTogglePin: () => togglePin(item),
              onToggleArchive: () => toggleArchive(item),
              onEdit: () => {
                setEditItem(item)
                setEditContent(item.content)
                setEditCat(item.category)
              },
              onDelete: () => setDeleteConfirm(item),
            }))),

    // 5. Bottom utilities (Import & Open Doc)
    h('div', { className: 'nm-mem-footer-card' },
      h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
        h('button', {
          type: 'button',
          className: 'nm-pill nm-pill-ghost nm-pill-sm',
          onClick: () => setShowImport(true),
        }, t('memImport')),
        h('button', {
          type: 'button',
          className: 'nm-pill nm-pill-ghost nm-pill-sm',
          onClick: openDoc,
        }, h(IconDoc, { size: 14 }), t('memOpenDoc')))),

    // Edit Modal
    editItem
      ? h(Sheet, {
          title: t('memEditTitle'),
          closeLabel: t('close'),
          onClose: () => setEditItem(null),
          footer: h('div', { className: 'nm-sheet-actions' },
            h('span', { style: { flex: 1 } }),
            h('button', {
              type: 'button',
              className: 'nm-pill nm-pill-ghost nm-pill-sm',
              onClick: () => setEditItem(null),
            }, t('cancel')),
            h('button', {
              type: 'submit',
              form: 'nm-mem-edit-form',
              className: 'nm-pill nm-pill-sm',
              disabled: busy || !editContent.trim(),
            }, busy ? t('save') : t('memSave'))),
        },
        h('form', { id: 'nm-mem-edit-form', className: 'nm-sheet-form', onSubmit: saveEdit },
          h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12 } },
            h('label', { className: 'nm-pf-label' }, t('memCategory')),
            h('select', {
              className: 'nm-mem-cat-select',
              value: editCat,
              onChange: (e: FormEvent<HTMLSelectElement>) => setEditCat(e.currentTarget.value as MemoryRecord['category']),
            },
              h('option', { value: 'preference' }, categoryLabel('preference')),
              h('option', { value: 'habit' }, categoryLabel('habit')),
              h('option', { value: 'device' }, categoryLabel('device')),
              h('option', { value: 'profile' }, categoryLabel('profile')),
              h('option', { value: 'custom' }, categoryLabel('custom')))),
          h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6 } },
            h('label', { className: 'nm-pf-label' }, t('memContent')),
            h('textarea', {
              className: 'nm-textarea',
              rows: 5,
              value: editContent,
              maxLength: 600,
              onChange: (e: FormEvent<HTMLTextAreaElement>) => setEditContent(e.currentTarget.value),
              autoFocus: true,
            }))))
      : null,

    // Delete Confirmation Modal
    deleteConfirm
      ? h(Sheet, {
          title: t('memForget'),
          closeLabel: t('close'),
          onClose: () => setDeleteConfirm(null),
          footer: h('div', { className: 'nm-sheet-actions' },
            h('span', { style: { flex: 1 } }),
            h('button', {
              type: 'button',
              className: 'nm-pill nm-pill-ghost nm-pill-sm',
              onClick: () => setDeleteConfirm(null),
            }, t('cancel')),
            h('button', {
              type: 'button',
              className: 'nm-pill nm-pill-sm nm-pill-danger',
              onClick: () => doDelete(deleteConfirm.id),
            }, t('memForget'))),
        },
        h('div', { className: 'nm-sheet-form' },
          h('p', { className: 'nm-sheet-lead' }, t('memDeleteConfirm')),
          h('blockquote', { className: 'nm-mem-card-body', style: { padding: '8px 12px', background: 'var(--nm-card)', borderRadius: 8 } }, deleteConfirm.content)))
      : null,

    // Import Sheet
    showImport
      ? h(ImportMemorySheet, { t, onClose: () => { setShowImport(false); load() } })
      : null)
}

function MemoryCardItem(props: {
  item: MemoryRecord
  t: Translate
  categoryLabel: (cat: string) => string
  sourceLabel: (source: string) => string
  onTogglePin: () => void
  onToggleArchive: () => void
  onEdit: () => void
  onDelete: () => void
}): ReactNode {
  const { item, t, categoryLabel, sourceLabel, onTogglePin, onToggleArchive, onEdit, onDelete } = props
  return h('div', {
    className: `nm-mem-card${item.is_pinned ? ' is-pinned' : ''}${item.is_archived ? ' is-archived' : ''}`,
  },
    // Header row
    h('div', { className: 'nm-mem-card-head' },
      h('span', { className: `nm-mem-cat-pill nm-mem-cat-${item.category}` }, categoryLabel(item.category)),
      h('span', { className: 'nm-mem-src-pill', title: t('memSource') }, sourceLabel(item.source)),
      item.is_pinned
        ? h('span', { className: 'nm-mem-pin-pill' }, h(IconPin, { size: 12 }), t('memPin'))
        : null,
      h('span', { className: 'nm-mem-time' }, ago(t, item.updated_at))),

    // Content body
    h('div', { className: 'nm-mem-card-body' }, item.content),

    // Action buttons row
    h('div', { className: 'nm-mem-card-actions' },
      h('button', {
        type: 'button',
        className: `nm-mem-act-btn${item.is_pinned ? ' is-active' : ''}`,
        title: item.is_pinned ? t('memUnpin') : t('memPin'),
        onClick: onTogglePin,
      }, h(IconPin, { size: 13 }), item.is_pinned ? t('memUnpin') : t('memPin')),
      h('button', {
        type: 'button',
        className: `nm-mem-act-btn${item.is_archived ? ' is-active' : ''}`,
        title: item.is_archived ? t('memRestore') : t('memArchive'),
        onClick: onToggleArchive,
      }, h(IconArchive, { size: 13 }), item.is_archived ? t('memRestore') : t('memArchive')),
      h('button', {
        type: 'button',
        className: 'nm-mem-act-btn',
        title: t('memEdit'),
        onClick: onEdit,
      }, h(IconPencil, { size: 13 }), t('memEdit')),
      h('span', { style: { flex: 1 } }),
      h('button', {
        type: 'button',
        className: 'nm-mem-act-btn is-danger',
        title: t('memForget'),
        onClick: onDelete,
      }, h(IconTrash, { size: 13 }), t('memForget'))))
}

/** The legacy memory list with an add field, a search field and the import button. */
export function MemoryList({ t, onImport }: { t: Translate; onImport(): void }): ReactNode {
  const rooms = useRooms()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<MemoryHit[] | null>(null)
  const [searching, setSearching] = useState(false)
  const items = [...rooms.memory].sort((a, b) => b.at - a.at)
  const inSearch = Boolean(query.trim())

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
