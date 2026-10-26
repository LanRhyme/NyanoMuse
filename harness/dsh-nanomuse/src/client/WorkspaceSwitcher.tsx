/**
 * The workspace switcher at the top of the chats column: which folder the
 * agent works in, and where a new chat starts.
 *
 * A workspace is the harness's own (`ctx.workspaceRegistry`): one directory,
 * the chats whose `cwd` is that directory, in the order the person put them
 * in. This control is the Muse-shaped face of it — the column's title row —
 * because in Muse mode the harness's workspace browser is not on screen. The
 * button names the current folder; the menu lists the others (with their
 * paths), adds one, and drops one. Dropping removes the registration only:
 * the folder and every chat log stay on disk, which is why it asks for a
 * second click instead of a dialog.
 *
 * Adding asks the host for its own folder chooser first (`pickDirectory`);
 * a host without one throws, and the menu falls back to a path field, so the
 * control works in a browser as well as in the desktop shell.
 */
import { createElement as h, useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { IconCheck, IconChevronDown, IconClose, IconFolder, IconPlus } from './icons.tsx'

/** One workspace as the switcher needs it. */
export interface SwitcherWorkspace {
  id: string
  title: string
  path: string
}

export interface WorkspaceSwitcherProps {
  t: Translate
  /** The folder new chats start in; absent means the column is not grouped. */
  current?: string | undefined
  workspaces: readonly SwitcherWorkspace[]
  /** The host's own folder chooser; rejects when this host has none. */
  browse?: (() => Promise<string | null>) | undefined
  /** Adopt a folder as a workspace and make it current. */
  add(path: string): Promise<void>
  /** Make a workspace current; `undefined` clears it (the column is not grouped). */
  pick(id: string | undefined): void
  /** Drop a workspace registration; the folder and its chats stay. */
  remove(id: string): Promise<void>
}

export function WorkspaceSwitcher({ t, current, workspaces, browse, add, pick, remove }: WorkspaceSwitcherProps): ReactNode {
  const [open, setOpen] = useState(false)
  const [at, setAt] = useState<{ left: number; top: number } | null>(null)
  const [manual, setManual] = useState(false)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirming, setConfirming] = useState<string | null>(null)
  const button = useRef<HTMLButtonElement | null>(null)
  const menu = useRef<HTMLDivElement | null>(null)
  const field = useRef<HTMLInputElement | null>(null)

  const active = workspaces.find((w) => w.id === current)
  const close = () => {
    setOpen(false)
    setManual(false)
    setDraft('')
    setError('')
    setConfirming(null)
  }
  const openMenu = () => {
    const rect = button.current?.getBoundingClientRect()
    if (rect) setAt({ left: rect.left, top: rect.bottom + 6 })
    setOpen(true)
  }

  useEffect(() => {
    if (!open) return undefined
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node
      if (menu.current?.contains(target) || button.current?.contains(target)) return
      close()
    }
    const onKey = (event: globalThis.KeyboardEvent) => { if (event.key === 'Escape') close() }
    document.addEventListener('pointerdown', onPointer, true)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer, true)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])
  useEffect(() => {
    if (open && !manual) menu.current?.querySelector<HTMLButtonElement>('button')?.focus()
    if (manual) field.current?.focus()
  }, [open, manual])

  const adopt = async (path: string) => {
    const folder = path.trim()
    if (!folder) return
    setBusy(true)
    setError('')
    try {
      await add(folder)
      close()
    } catch {
      setError(t('wsAddFailed'))
    } finally {
      setBusy(false)
    }
  }
  // One click: the host's chooser when it has one, the path field when it does not.
  const choose = async () => {
    setError('')
    if (!browse) { setManual(true); return }
    setBusy(true)
    try {
      const picked = await browse()
      if (picked) await adopt(picked)
    } catch {
      setManual(true)
    } finally {
      setBusy(false)
    }
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    void adopt(draft)
  }
  const onFieldKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') { event.preventDefault(); setManual(false); setError('') }
  }
  const drop = async (id: string) => {
    if (confirming !== id) { setConfirming(id); return }
    setBusy(true)
    try {
      await remove(id)
      close()
    } catch {
      setError(t('wsAddFailed'))
    } finally {
      setBusy(false)
    }
  }

  const row = (key: string, label: string, hint: string | undefined, checked: boolean, onSelect: () => void, icon?: ReactNode) =>
    h('button', { key, type: 'button', role: 'menuitemradio', 'aria-checked': checked, className: `nm-ws-item${checked ? ' nm-active' : ''}`, onClick: onSelect },
      h('span', { className: 'nm-ws-item-icon' }, icon ?? (checked ? h(IconCheck, { size: 15 }) : null)),
      h('span', { className: 'nm-ws-item-body' },
        h('span', { className: 'nm-ws-item-label' }, label),
        hint ? h('span', { className: 'nm-ws-item-hint' }, hint) : null))

  return h('div', { className: 'nm-ws' },
    h('button', {
      type: 'button',
      className: `nm-ws-btn${open ? ' nm-open' : ''}`,
      ref: (el: HTMLButtonElement | null): void => { button.current = el },
      'aria-haspopup': 'menu',
      'aria-expanded': open,
      title: active ? active.path : t('wsSwitch'),
      onClick: () => { if (open) close(); else openMenu() },
    },
      h(IconFolder, { size: 16 }),
      h('span', { className: 'nm-ws-name' },
        h('span', { className: 'nm-ws-title' }, active ? active.title : t('wsNone')),
        h('span', { className: 'nm-ws-path' }, active ? active.path : t('wsNoneHint'))),
      h(IconChevronDown, { size: 14, className: 'nm-ws-chevron' })),
    open && at
      ? h('div', { ref: menu, className: 'nm-menu nm-ws-menu', role: 'menu', style: { left: at.left, top: at.top } },
          row('none', t('wsNone'), t('wsNoneHint'), !active, () => { pick(undefined); close() }),
          workspaces.length ? h('div', { className: 'nm-menu-sep', role: 'separator' }) : null,
          ...workspaces.map((w) => row(w.id, w.title, w.path, w.id === current, () => { pick(w.id); close() })),
          h('div', { className: 'nm-menu-sep', role: 'separator' }),
          manual
            ? h('form', { className: 'nm-ws-form', onSubmit: submit },
                h('input', {
                  ref: field,
                  className: 'nm-ws-input',
                  value: draft,
                  placeholder: t('wsPathHint'),
                  'aria-label': t('wsPathHint'),
                  spellCheck: false,
                  onChange: (event: FormEvent<HTMLInputElement>) => setDraft(event.currentTarget.value),
                  onKeyDown: onFieldKey,
                }),
                h('button', { type: 'submit', className: 'nm-pill nm-pill-sm', disabled: busy || draft.trim() === '' }, t('wsAddConfirm')))
            : h('button', { type: 'button', role: 'menuitem', className: 'nm-menu-item', disabled: busy, onClick: () => { void choose() } },
                h(IconPlus, { size: 15 }),
                h('span', { className: 'nm-menu-item-label' }, t('wsAdd'))),
          active
            ? h('button', { type: 'button', role: 'menuitem', className: `nm-menu-item${confirming === active.id ? ' nm-danger' : ''}`, disabled: busy, onClick: () => { void drop(active.id) } },
                h(IconClose, { size: 15 }),
                h('span', { className: 'nm-menu-item-label' }, confirming === active.id ? t('wsRemoveConfirm', { title: active.title }) : t('wsRemove')))
            : null,
          error ? h('div', { className: 'nm-ws-error', role: 'alert' }, error) : null)
      : null)
}
