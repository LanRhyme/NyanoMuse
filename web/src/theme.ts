/**
 * Light or dark — a device setting like the locale. Light is the default whatever the
 * system prefers; dark only when chosen here (or "system" to follow it). index.html applies
 * the stored choice before the first paint; this module keeps <html data-theme> and the
 * theme-color meta in step afterwards.
 */
import { useSyncExternalStore } from "react";

export type ThemeSetting = "light" | "dark" | "system";
export type Theme = "light" | "dark";
export type ThemePalette = "morandi" | "oled" | "sakura" | "default";

export interface WallpaperConfig {
  url: string | null;
  dim: number; // 0 to 0.85
  blur: number; // 0 to 30px
  glass: boolean; // frosted glass styling
}

const STORAGE_KEY = "nanomuse_theme";
const PALETTE_KEY = "nanomuse_palette";
const WALLPAPER_KEY = "nanomuse_wallpaper_config";
const COLORS: Record<Theme, string> = { light: "#ffffff", dark: "#181819" };

function loadTheme(): ThemeSetting {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === "light" || v === "dark" || v === "system") return v;
  } catch {
    // no storage: light
  }
  return "light";
}

function loadPalette(): ThemePalette {
  try {
    const v = localStorage.getItem(PALETTE_KEY);
    if (v === "morandi" || v === "oled" || v === "sakura" || v === "default") return v;
  } catch {}
  return "morandi";
}

function loadWallpaper(): WallpaperConfig {
  const def: WallpaperConfig = { url: null, dim: 0.25, blur: 8, glass: true };
  try {
    const v = localStorage.getItem(WALLPAPER_KEY);
    if (v) {
      const parsed = JSON.parse(v);
      return {
        url: typeof parsed.url === "string" ? parsed.url : null,
        dim: typeof parsed.dim === "number" ? Math.max(0, Math.min(0.85, parsed.dim)) : 0.25,
        blur: typeof parsed.blur === "number" ? Math.max(0, Math.min(30, parsed.blur)) : 8,
        glass: typeof parsed.glass === "boolean" ? parsed.glass : true,
      };
    }
  } catch {}
  return def;
}

let setting: ThemeSetting = loadTheme();
let currentPalette: ThemePalette = loadPalette();
let currentWallpaper: WallpaperConfig = loadWallpaper();

const listeners = new Set<() => void>();
const media = typeof matchMedia === "function" ? matchMedia("(prefers-color-scheme: dark)") : null;

function systemTheme(): Theme {
  return media?.matches ? "dark" : "light";
}

export function getThemeSetting(): ThemeSetting {
  return setting;
}

export function getTheme(): Theme {
  return setting === "system" ? systemTheme() : setting;
}

export function setThemeSetting(next: ThemeSetting): void {
  setting = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {}
  applyTheme();
  listeners.forEach((fn) => fn());
}

export function getPalette(): ThemePalette {
  return currentPalette;
}

export function setPalette(palette: ThemePalette): void {
  currentPalette = palette;
  try {
    localStorage.setItem(PALETTE_KEY, palette);
  } catch {}
  applyTheme();
  listeners.forEach((fn) => fn());
}

export function getWallpaper(): WallpaperConfig {
  return currentWallpaper;
}

export function setWallpaper(cfg: Partial<WallpaperConfig>): void {
  currentWallpaper = { ...currentWallpaper, ...cfg };
  try {
    localStorage.setItem(WALLPAPER_KEY, JSON.stringify(currentWallpaper));
  } catch {}
  applyTheme();
  listeners.forEach((fn) => fn());
}

export function clearWallpaper(): void {
  currentWallpaper = { ...currentWallpaper, url: null };
  try {
    localStorage.setItem(WALLPAPER_KEY, JSON.stringify(currentWallpaper));
  } catch {}
  applyTheme();
  listeners.forEach((fn) => fn());
}

/** <html data-theme>, <html data-palette>, and <html data-glass> follow the resolved theme. */
export function applyTheme(): void {
  if (typeof document === "undefined") return;
  const theme = getTheme();
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.palette = currentPalette;
  document.documentElement.dataset.glass = currentWallpaper.glass ? "true" : "false";
  document.documentElement.dataset.hasWallpaper = currentWallpaper.url ? "true" : "false";

  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", COLORS[theme]);
}

media?.addEventListener?.("change", () => {
  if (setting === "system") {
    applyTheme();
    listeners.forEach((fn) => fn());
  }
});

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function useThemeSetting(): ThemeSetting {
  return useSyncExternalStore(subscribe, getThemeSetting, getThemeSetting);
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, getTheme, getTheme);
}

export function usePalette(): ThemePalette {
  return useSyncExternalStore(subscribe, getPalette, getPalette);
}

export function useWallpaper(): WallpaperConfig {
  return useSyncExternalStore(subscribe, getWallpaper, getWallpaper);
}
