/**
 * The person's sticker packs, as the browser half sees them: the catalogue the host serves
 * (`/nanomuse/stickers/list`), one small observable so the picker, the composer button and
 * the bubbles all agree, and the writes the panel makes. The pictures come straight from
 * `/nanomuse/stickers/img/<pack>/<file>`.
 */
import { useSyncExternalStore } from 'react'
import { EMPTY_CATALOGUE, findSticker, type StickerCatalogue, type StickerItemView } from '../sticker-index.ts'
import { local } from './local.ts'

let current: StickerCatalogue = EMPTY_CATALOGUE
const listeners = new Set<() => void>()
let loading: Promise<void> | undefined

function publish(next: StickerCatalogue): void {
  current = next
  for (const listener of listeners) listener()
}

export function getStickers(): StickerCatalogue {
  return current
}

export function subscribeStickers(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Read the catalogue again; one request at a time, whichever caller asks. */
export function refreshStickers(): Promise<void> {
  loading = (loading ?? Promise.resolve())
    .then(async () => {
      const next = await local<StickerCatalogue>('/nanomuse/stickers/list')
      publish(next)
    })
    .catch(() => undefined)
    .finally(() => { loading = undefined })
  return loading
}

export function useStickers(): StickerCatalogue {
  return useSyncExternalStore(subscribeStickers, getStickers, getStickers)
}

/** The sticker a tag names, from the catalogue already read. */
export function resolveSticker(query: string): StickerItemView | undefined {
  return findSticker(current, query)
}

async function fileDataUrl(file: File): Promise<string> {
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(new Error('could not read the picture'))
    reader.readAsDataURL(file)
  })
}

/** One picture into a pack; the name defaults to the file's. */
export async function addSticker(packId: string, file: File, name = ''): Promise<void> {
  const data = await fileDataUrl(file)
  await local('/nanomuse/stickers/add', { packId, data, name: name || file.name.replace(/\.[^.]*$/, ''), filename: file.name })
  await refreshStickers()
}

export async function removeSticker(packId: string, id: string): Promise<void> {
  await local('/nanomuse/stickers/remove', { packId, id })
  await refreshStickers()
}

export async function renameSticker(packId: string, id: string, name: string): Promise<void> {
  await local('/nanomuse/stickers/rename', { packId, id, name })
  await refreshStickers()
}

export async function createPack(name: string): Promise<void> {
  await local('/nanomuse/stickers/pack', { name })
  await refreshStickers()
}

export async function deletePack(id: string): Promise<void> {
  await local('/nanomuse/stickers/pack', { id, remove: true })
  await refreshStickers()
}
