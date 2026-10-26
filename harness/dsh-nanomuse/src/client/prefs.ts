/**
 * The few switches of this desktop that live in the browser half alone, kept
 * in localStorage (per host origin): whether the harness's own controls show
 * beside the Muse chrome, whether the display stays awake while the agent
 * works, and the look preferences the Muse desktop keeps in Settings → General.
 * A small observable so every reader re-renders when one flips.
 */
import { useSyncExternalStore } from 'react'

export interface Prefs {
  /** Show DeepSeek Harness's own controls: the model picker, the modes, the workspace browser. */
  showHarness: boolean
  /** Keep the display awake while a turn runs (through the Electron bridge). */
  keepAwake: boolean
  /** Show the agent's steps (tool rows) in the chat — on by default since 0.1.37; off, the chat keeps to the conversation and the words under the face say what it is on. A stored `false` wins. */
  showSteps: boolean
  /** Answers given on approval cards, newest first — the drawer's Approvals tab. */
  approvals: ApprovalRecord[]
  /** The Feed's "About the feed" card was read ("Got it"); it stays away after. */
  feedIntroSeen: boolean
  /** The composer opens tall (70vh) for a long draft; Enter then writes a new line. */
  composerExpand: boolean
}

export interface ApprovalRecord {
  at: number
  toolName: string
  reason: string
  outcome: 'allowed' | 'rejected'
}

const KEY = 'nanomuse.prefs'
const DEFAULTS: Prefs = { showHarness: false, keepAwake: true, showSteps: true, approvals: [], feedIntroSeen: false, composerExpand: false }
const MAX_APPROVALS = 50

let current: Prefs = read()
const listeners = new Set<() => void>()

function read(): Prefs {
  try {
    const raw = window.localStorage.getItem(KEY)
    if (!raw) return DEFAULTS
    const parsed = JSON.parse(raw) as Partial<Prefs>
    return {
      showHarness: parsed.showHarness === true,
      keepAwake: parsed.keepAwake !== false,
      showSteps: parsed.showSteps !== false,
      approvals: Array.isArray(parsed.approvals) ? parsed.approvals.slice(0, MAX_APPROVALS) : [],
      // a `stage` place written before 0.1.40 (the picture-in-picture) is read no more and dropped on the next write
      feedIntroSeen: parsed.feedIntroSeen === true,
      composerExpand: parsed.composerExpand === true,
    }
  } catch {
    return DEFAULTS
  }
}

export function getPrefs(): Prefs {
  return current
}

export function setPrefs(patch: Partial<Prefs>): void {
  current = { ...current, ...patch }
  try {
    window.localStorage.setItem(KEY, JSON.stringify(current))
  } catch {
    // private mode: the switch holds for this page only
  }
  for (const listener of listeners) listener()
}

export function recordApproval(record: ApprovalRecord): void {
  setPrefs({ approvals: [record, ...current.approvals].slice(0, MAX_APPROVALS) })
}

export function subscribePrefs(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
const subscribe = subscribePrefs

export function usePrefs(): Prefs {
  return useSyncExternalStore(subscribe, getPrefs, getPrefs)
}
