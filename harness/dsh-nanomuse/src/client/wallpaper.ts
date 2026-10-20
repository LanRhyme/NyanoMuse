/**
 * Client-side wallpaper state and DOM management for nanoMuse desktop.
 * Coordinates custom background, blur, dim, and synchronization with Noctalia system wallpaper.
 */
import { useSyncExternalStore } from 'react'
import { call } from './api.ts'

export interface WallpaperConfig {
  enabled: boolean
  useSystem: boolean
  blur: number
  dim: number
  hasCustom: boolean
  systemPath: string
}

let modalOpen = false
const modalListeners = new Set<() => void>()

export const wallpaperBus = {
  isOpen: () => modalOpen,
  open: () => {
    if (!modalOpen) {
      modalOpen = true
      for (const l of modalListeners) l()
    }
  },
  close: () => {
    if (modalOpen) {
      modalOpen = false
      for (const l of modalListeners) l()
    }
  },
  toggle: () => {
    modalOpen = !modalOpen
    for (const l of modalListeners) l()
  },
  subscribe: (l: () => void) => {
    modalListeners.add(l)
    return () => {
      modalListeners.delete(l)
    }
  },
}

export function useWallpaperModalOpen(): boolean {
  return useSyncExternalStore(wallpaperBus.subscribe, wallpaperBus.isOpen, wallpaperBus.isOpen)
}

let currentConfig: WallpaperConfig = {
  enabled: false,
  useSystem: true,
  blur: 12,
  dim: 35,
  hasCustom: false,
  systemPath: '',
}
const configListeners = new Set<() => void>()

export const wallpaperConfigStore = {
  get: () => currentConfig,
  set: (next: WallpaperConfig) => {
    currentConfig = next
    applyWallpaperDOM(next)
    for (const l of configListeners) l()
  },
  subscribe: (l: () => void) => {
    configListeners.add(l)
    return () => {
      configListeners.delete(l)
    }
  },
}

export function useWallpaperConfig(): WallpaperConfig {
  return useSyncExternalStore(wallpaperConfigStore.subscribe, wallpaperConfigStore.get, wallpaperConfigStore.get)
}

export async function fetchWallpaperConfig(): Promise<WallpaperConfig> {
  const res = await call<WallpaperConfig>('wallpaper')
  wallpaperConfigStore.set(res)
  return res
}

export async function saveWallpaperConfig(patch: Partial<WallpaperConfig> & { imageData?: string }): Promise<WallpaperConfig> {
  const res = await call<WallpaperConfig>('wallpaper', patch)
  wallpaperConfigStore.set(res)
  return res
}

export async function resetWallpaperConfig(): Promise<WallpaperConfig> {
  const res = await call<WallpaperConfig>('wallpaper', {}, 'DELETE')
  wallpaperConfigStore.set(res)
  return res
}

/** The phone sets its blur in dp on a ~2.5x display, so its 4 reads as about ten CSS pixels
 *  wide; the desktop's own pixels are half that, and a 4px blur over a 1280px window shows the
 *  picture's detail straight through the text. Scale it so a value the person set on the phone
 *  (or kept at the default) looks like the same material here. */
const BLUR_SCALE = 2.4

export function applyWallpaperDOM(conf: WallpaperConfig): void {
  if (typeof document === 'undefined') return
  let container = document.getElementById('nm-wallpaper-container')
  if (!container) {
    container = document.createElement('div')
    container.id = 'nm-wallpaper-container'
    const img = document.createElement('div')
    img.id = 'nm-wallpaper-img'
    const dim = document.createElement('div')
    dim.id = 'nm-wallpaper-dim'
    container.appendChild(img)
    container.appendChild(dim)
    document.body.prepend(container)
  }

  const img = document.getElementById('nm-wallpaper-img')
  const dim = document.getElementById('nm-wallpaper-dim')

  if (conf.enabled) {
    document.documentElement.setAttribute('data-nm-wallpaper', 'true')
    container.style.display = 'block'
    if (img) {
      img.style.backgroundImage = `url('/nanomuse/cloud/wallpaper/image?v=${Date.now()}')`
      img.style.filter = `blur(${Math.round(conf.blur * BLUR_SCALE)}px) saturate(0.92)`
    }
    if (dim) {
      dim.style.background = `rgba(0, 0, 0, ${conf.dim / 100})`
    }
  } else {
    document.documentElement.removeAttribute('data-nm-wallpaper')
    container.style.display = 'none'
  }
}

let initialized = false
export function initWallpaper(): void {
  if (initialized) return
  initialized = true
  fetchWallpaperConfig().catch(() => undefined)
}
