/**
 * The companion's mood, on the desktop: a small pill in the header that says what the
 * agent is wearing, and the compass behind it (the phone's 心境罗盘).
 *
 * The state lives in the host (`src/mood.ts`); this file reads it over
 * `/nanomuse/mood/state`, shows the leading mood with its two companions, the last shift,
 * the intensity switch and the whole 33, and lets the person reset the scores or turn the
 * engine off. The pill also carries the DeepSeek billing window, which is the other thing
 * worth knowing at a glance.
 */
import { createElement as h, useEffect, useMemo, useState, type ReactNode } from 'react'
import { billingWindow, type BillingWindow } from '../billing.ts'
import { MOODS } from '../mood-table.ts'
import type { Translate } from './api.ts'
import { IconClose, IconRefresh } from './icons.tsx'
import { local } from './local.ts'

export interface MoodScore {
  id: string
  score: number
  baseline: number
  layer: 'base' | 'composite' | 'modern'
}

export interface MoodState {
  primary: { id: string; en: string; zh: string; tendencyEn: string; tendencyZh: string; face: string }
  secondary: Array<{ id: string; en: string; zh: string }>
  title: string
  titleZh: string
  scores: MoodScore[]
  leaderScore: number
  facts?: { at: number; from: string; to: string; snippet: string; narrative?: string }
  affection: number
  intensity: 'subtle' | 'normal' | 'vivid'
  enabled: boolean
  face: string
  distressed: boolean
}

const POLL_MS = 30_000

const BY_ID = new Map(MOODS.map((mood) => [mood.id, mood]))

/** The labels, named one by one so a missing translation is a type error, not a blank chip. */
const INTENSITY_KEYS = { subtle: 'moodIntensity_subtle', normal: 'moodIntensity_normal', vivid: 'moodIntensity_vivid' } as const
const LAYER_KEYS = { all: 'moodLayer_all', base: 'moodLayer_base', composite: 'moodLayer_composite', modern: 'moodLayer_modern' } as const

/** The mood's name in the page's language; the id when the table has no such mood. */
function moodName(id: string): string {
  const mood = BY_ID.get(id)
  if (!mood) return id
  return /^zh/i.test(navigator.language) ? mood.zh : mood.en
}

export function useMoodState(enabled = true): { mood: MoodState | null; reload: () => void } {
  const [mood, setMood] = useState<MoodState | null>(null)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!enabled) return undefined
    let alive = true
    const read = (): void => {
      local<MoodState>('/nanomuse/mood/state').then((state) => { if (alive) setMood(state) }).catch(() => undefined)
    }
    read()
    const timer = window.setInterval(read, POLL_MS)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [enabled, tick])
  return { mood, reload: () => setTick((n) => n + 1) }
}

/** The header pill: a dot in the mood's colour and its name; clicking opens the compass. */
export function MoodPill({ t }: { t: Translate }): ReactNode {
  const { mood } = useMoodState()
  const [open, setOpen] = useState(false)
  const Compass = useMemo(() => makeMoodCompass(t, () => undefined), [t])
  if (!mood || !mood.enabled) return null
  return h('span', { className: 'nm-mood-anchor' },
    h('button', {
      type: 'button',
      className: `nm-mood-pill nm-mood-${mood.face}${mood.distressed ? ' nm-mood-distressed' : ''}`,
      title: `${mood.titleZh} · ${mood.primary.tendencyZh}`,
      'aria-label': mood.title,
      onClick: () => setOpen(true),
    }, h('span', { className: 'nm-mood-dot' }), h('span', null, mood.title)),
    open ? h(Compass as (props: { onClose: () => void }) => ReactNode, { onClose: () => setOpen(false) }) : null)
}

/** The billing window chip: green while the half-price window is on, amber while it is not. */
export function BillingChip({ t }: { t: Translate }): ReactNode {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])
  const period: BillingWindow = billingWindow(now)
  const left = Math.max(0, period.until - now)
  const hours = Math.floor(left / 3600_000)
  const minutes = Math.floor((left % 3600_000) / 60_000)
  const countdown = hours > 0 ? `${hours}h ${String(minutes).padStart(2, '0')}m` : `${minutes}m`
  return h('span', {
    className: `nm-billing-chip ${period.offPeak ? 'nm-billing-off' : 'nm-billing-peak'}`,
    title: `${period.detail} · ${t('billingNext', { time: countdown })}`,
  },
  h('span', { className: 'nm-billing-dot' }),
  h('span', null, period.offPeak ? t('billingOff') : t('billingPeak')),
  h('span', { className: 'nm-billing-left' }, countdown))
}

const LAYERS = ['all', 'base', 'composite', 'modern'] as const

export function makeMoodCompass(t: Translate, onClose: () => void) {
  return function MoodCompass({ onClose: close }: { onClose?: () => void } = {}): ReactNode {
    const closePanel = close ?? onClose
    const { mood, reload } = useMoodState()
    const [layer, setLayer] = useState<(typeof LAYERS)[number]>('all')
    const [busy, setBusy] = useState(false)
    if (!mood) return null
    const shown = mood.scores
      .filter((entry) => layer === 'all' || entry.layer === layer)
      .slice()
      .sort((a, b) => b.score - a.score)
    const write = (path: string, body: unknown): void => {
      setBusy(true)
      local(path, body).then(() => reload()).catch(() => undefined).finally(() => setBusy(false))
    }
    return h('div', { className: 'nm-mood-overlay', role: 'dialog', 'aria-label': t('moodTitle'), onClick: (event: { target: unknown; currentTarget: unknown }) => { if (event.target === event.currentTarget) closePanel() } },
      h('div', { className: 'nm-mood-sheet' },
        h('div', { className: 'nm-mood-head' },
          h('div', null,
            h('div', { className: 'nm-mood-heading' }, t('moodTitle')),
            h('div', { className: 'nm-mood-sub' }, t('moodSub'))),
          h('div', { className: 'nm-mood-head-actions' },
            h('button', { type: 'button', className: 'nm-icon-btn', title: t('moodReset'), 'aria-label': t('moodReset'), disabled: busy, onClick: () => write('/nanomuse/mood/reset', {}) }, h(IconRefresh, { size: 16 })),
            h('button', { type: 'button', className: 'nm-icon-btn', title: t('close'), 'aria-label': t('close'), onClick: closePanel }, h(IconClose, { size: 16 })))),
        h('div', { className: 'nm-mood-lead' },
          h('div', { className: 'nm-mood-lead-name' }, mood.title),
          h('div', { className: 'nm-mood-lead-zh' }, mood.titleZh),
          h('div', { className: 'nm-mood-lead-score' }, `${mood.leaderScore.toFixed(1)} / 100`),
          h('div', { className: 'nm-mood-lead-tendency' }, mood.primary.tendencyEn)),
        mood.facts
          ? h('div', { className: 'nm-mood-facts' },
            h('span', { className: 'nm-mood-facts-time' }, new Date(mood.facts.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })),
            h('span', null, t('moodShift', { from: moodName(mood.facts.from), to: moodName(mood.facts.to) })),
            mood.facts.snippet ? h('span', { className: 'nm-mood-facts-snippet' }, `「${mood.facts.snippet}」`) : null,
            mood.facts.narrative ? h('span', { className: 'nm-mood-facts-why' }, `AI: ${mood.facts.narrative}`) : null)
          : null,
        h('div', { className: 'nm-mood-controls' },
          h('div', { className: 'nm-mood-intensity' },
            h('span', { className: 'nm-mood-control-label' }, t('moodIntensity')),
            (['subtle', 'normal', 'vivid'] as const).map((level) => h('button', {
              key: level,
              type: 'button',
              className: `nm-mood-chip${mood.intensity === level ? ' nm-on' : ''}`,
              disabled: busy,
              onClick: () => write('/nanomuse/mood/intensity', { intensity: level }),
            }, t(`moodIntensity_${level}` as never)))),
          h('label', { className: 'nm-mood-switch' },
            h('input', { type: 'checkbox', checked: mood.enabled, onChange: (event: { target: { checked: boolean } }) => write('/nanomuse/mood/enabled', { enabled: event.target.checked }) }),
            h('span', null, t('moodEnabled')))),
        h('div', { className: 'nm-mood-tabs' },
          LAYERS.map((key) => h('button', {
            key,
            type: 'button',
            className: `nm-mood-chip${layer === key ? ' nm-on' : ''}`,
            onClick: () => setLayer(key),
          }, t(LAYER_KEYS[key])))),
        h('div', { className: 'nm-mood-list' },
          shown.map((entry) => h('div', { key: entry.id, className: `nm-mood-row${entry.id === mood.primary.id ? ' nm-lead' : ''}${mood.secondary.some((s) => s.id === entry.id) ? ' nm-second' : ''}` },
            h('div', { className: 'nm-mood-row-head' },
              h('span', { className: 'nm-mood-row-name' }, moodName(entry.id)),
              h('span', { className: 'nm-mood-row-layer' }, t(LAYER_KEYS[entry.layer])),
              entry.id === mood.primary.id ? h('span', { className: 'nm-mood-badge' }, t('moodLead')) : null,
              h('span', { className: 'nm-mood-row-score' }, `${entry.score.toFixed(1)} / ${Math.round(entry.baseline)}`)),
            h('div', { className: 'nm-mood-bar' }, h('span', { style: { width: `${Math.max(0, Math.min(100, entry.score))}%` } })))))),
    )
  }
}
