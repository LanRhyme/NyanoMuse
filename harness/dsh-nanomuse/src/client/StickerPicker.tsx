/**
 * The sticker panel in the composer (表情包): a button beside the microphone that opens the
 * person's packs over the chat, the way the phone's composer does.
 *
 * Packs are chips ("All", then each pack with its count, then "New group"); under them a
 * search field and a grid of pictures. A click puts that sticker's tag into the draft —
 * the model reads the tag, and the bubble draws the picture — a small pencil or cross on a
 * tile renames or removes it, and the add tile takes one picture from this computer.
 * Everything is stored by the host (`src/stickers.ts`); this file only draws and asks.
 */
import { createElement as h, useEffect, useRef, useState, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { insertComposer } from './composer.ts'
import { IconClose, IconPlus, IconSearch, IconSmile, IconTrash } from './icons.tsx'
import { addSticker, createPack, deletePack, getStickers, refreshStickers, removeSticker, renameSticker, useStickers } from './stickers.ts'

export function makeStickerButton(t: Translate) {
  return function StickerButton(): ReactNode {
    const [open, setOpen] = useState(false)
    const [packId, setPackId] = useState<string | null>(null)
    const [query, setQuery] = useState('')
    const [adding, setAdding] = useState(false)
    const [naming, setNaming] = useState(false)
    const [groupName, setGroupName] = useState('')
    const [renaming, setRenaming] = useState<{ packId: string; id: string; name: string } | null>(null)
    const [error, setError] = useState('')
    const file = useRef<HTMLInputElement | null>(null)
    const catalogue = useStickers()
    const packs = catalogue.packs
    const active = packId === null ? null : packs.find((pack) => pack.id === packId) ?? null
    const items = (active ? active.items : catalogue.items).filter((item) => {
      const q = query.trim().toLowerCase()
      if (!q) return true
      return item.name.toLowerCase().includes(q) || item.seq.includes(q) || item.keywords.toLowerCase().includes(q)
    })

    useEffect(() => {
      if (open) void refreshStickers()
    }, [open])

    const pick = (id: string): void => {
      insertComposer(`<nm:meme id="${id}"/>`)
      setOpen(false)
      setQuery('')
    }
    const fail = (err: unknown): void => setError(err instanceof Error ? err.message : String(err))
    const onFile = async (chosen: File | undefined): Promise<void> => {
      if (!chosen) return
      setAdding(true)
      setError('')
      try {
        // A new picture goes into the pack being looked at, else the person's own pack, and
        // one is made when there is none: the shipped pack is not where their pictures belong.
        let target = active ?? getStickers().packs.find((pack) => !pack.builtin) ?? null
        if (!target) {
          await createPack(t('stkMyPack'))
          await refreshStickers()
          target = getStickers().packs.find((pack) => !pack.builtin) ?? null
        }
        if (!target) throw new Error(t('stkNoPack'))
        await addSticker(target.id, chosen, chosen.name.replace(/\.[^.]*$/, ''))
        setPackId(target.id)
      } catch (err) {
        fail(err)
      } finally {
        setAdding(false)
      }
    }
    const makePack = async (): Promise<void> => {
      const name = groupName.trim()
      if (!name) return
      try {
        await createPack(name)
        setNaming(false)
        setGroupName('')
        const next = getStickers().packs.find((pack) => pack.name === name)
        if (next) setPackId(next.id)
      } catch (err) {
        fail(err)
      }
    }

    return h('div', { className: 'nm-sticker-anchor' },
      h('button', {
        type: 'button',
        className: `nm-mic${open ? ' nm-live' : ''}`,
        'aria-label': t('stkOpen'),
        title: t('stkOpen'),
        'aria-pressed': open,
        onMouseDown: (event: { preventDefault(): void }) => event.preventDefault(),
        onClick: () => setOpen((value) => !value),
      }, h(IconSmile, { size: 17 })),
      open
        ? h('div', { className: 'nm-sticker-panel', role: 'dialog', 'aria-label': t('stkOpen') },
          h('div', { className: 'nm-sticker-head' },
            h('span', { className: 'nm-sticker-title' }, t('stkOpen')),
            h('span', { className: 'nm-sticker-count' }, String(items.length)),
            h('button', { type: 'button', className: 'nm-icon-btn', 'aria-label': t('close'), title: t('close'), onClick: () => setOpen(false) }, h(IconClose, { size: 16 }))),
          h('div', { className: 'nm-sticker-packs' },
            h('button', { type: 'button', className: `nm-sticker-chip${packId === null ? ' nm-on' : ''}`, onClick: () => setPackId(null) }, t('stkAll')),
            packs.map((pack) => h('span', { key: pack.id, className: `nm-sticker-chip-wrap${packId === pack.id ? ' nm-on' : ''}` },
              h('button', { type: 'button', className: `nm-sticker-chip${packId === pack.id ? ' nm-on' : ''}`, onClick: () => setPackId(pack.id), title: pack.description || pack.name }, `${pack.name} (${pack.count})`),
              pack.builtin || packId !== pack.id
                ? null
                : h('button', { type: 'button', className: 'nm-sticker-chip-x', 'aria-label': t('stkDeleteGroup'), title: t('stkDeleteGroup'), onClick: () => { void deletePack(pack.id).then(() => setPackId(null)).catch(fail) } }, '×'))),
            naming
              ? h('span', { className: 'nm-sticker-new' },
                h('input', {
                  autoFocus: true,
                  value: groupName,
                  placeholder: t('stkGroupName'),
                  onChange: (event: { target: { value: string } }) => setGroupName(event.target.value),
                  onKeyDown: (event: { key: string; preventDefault(): void }) => {
                    if (event.key === 'Enter') { event.preventDefault(); void makePack() }
                    if (event.key === 'Escape') { setNaming(false); setGroupName('') }
                  },
                }),
                h('button', { type: 'button', className: 'nm-sticker-chip nm-on', onClick: () => void makePack() }, t('stkCreate')))
              : h('button', { type: 'button', className: 'nm-sticker-chip nm-add', onClick: () => setNaming(true) }, h(IconPlus, { size: 12 }), t('stkNewGroup'))),
          h('label', { className: 'nm-sticker-search' },
            h(IconSearch, { size: 14 }),
            h('input', { value: query, placeholder: t('stkSearch'), onChange: (event: { target: { value: string } }) => setQuery(event.target.value) })),
          renaming
            ? h('div', { className: 'nm-sticker-new' },
              h('input', {
                autoFocus: true,
                value: renaming.name,
                placeholder: t('stkStickerName'),
                onChange: (event: { target: { value: string } }) => setRenaming({ ...renaming, name: event.target.value }),
                onKeyDown: (event: { key: string; preventDefault(): void }) => {
                  if (event.key === 'Escape') { event.preventDefault(); setRenaming(null); return }
                  if (event.key !== 'Enter') return
                  event.preventDefault()
                  const name = renaming.name.trim()
                  if (!name) return
                  void renameSticker(renaming.packId, renaming.id, name).then(() => setRenaming(null)).catch(fail)
                },
              }),
              h('button', {
                type: 'button',
                className: 'nm-sticker-chip nm-on',
                onClick: () => {
                  const name = renaming.name.trim()
                  if (!name) return
                  void renameSticker(renaming.packId, renaming.id, name).then(() => setRenaming(null)).catch(fail)
                },
              }, t('stkRename')),
              h('button', { type: 'button', className: 'nm-sticker-chip', onClick: () => setRenaming(null) }, t('close')))
            : null,
          error ? h('div', { className: 'nm-sticker-error' }, error) : null,
          h('div', { className: 'nm-sticker-grid' },
            h('button', {
              type: 'button',
              className: 'nm-sticker-add',
              title: t('stkAdd'),
              onClick: () => file.current?.click(),
              disabled: adding,
            }, h(IconPlus, { size: 20 }), h('span', null, t('stkAdd'))),
            items.map((item) => h('div', { key: `${item.packId}:${item.id}`, className: 'nm-sticker-tile' },
              h('button', { type: 'button', className: 'nm-sticker-pick', title: item.name || item.filename, onClick: () => pick(item.id) },
                h('img', { src: item.url, alt: item.name || 'sticker', loading: 'lazy', decoding: 'async' }),
                item.format === 'gif' ? h('span', { className: 'nm-sticker-gif' }, 'GIF') : null),
              h('span', { className: 'nm-sticker-name' }, item.name || item.filename),
              h('span', { className: 'nm-sticker-tools' },
                h('button', {
                  type: 'button',
                  title: t('stkRename'),
                  'aria-label': t('stkRename'),
                  onClick: () => setRenaming({ packId: item.packId, id: item.id, name: item.name }),
                }, '✎'),
                h('button', {
                  type: 'button',
                  title: t('stkDelete'),
                  'aria-label': t('stkDelete'),
                  onClick: () => { void removeSticker(item.packId, item.id).catch(fail) },
                }, h(IconTrash, { size: 12 }))))),
            items.length === 0 ? h('div', { className: 'nm-sticker-empty' }, t('stkEmpty')) : null),
          h('input', {
            ref: file,
            type: 'file',
            accept: 'image/png,image/jpeg,image/gif,image/webp',
            style: { display: 'none' },
            onChange: (event: { target: { files?: FileList | null; value: string } }) => {
              const chosen = event.target.files?.[0]
              event.target.value = ''
              void onFile(chosen)
            },
          }))
        : null)
  }
}

