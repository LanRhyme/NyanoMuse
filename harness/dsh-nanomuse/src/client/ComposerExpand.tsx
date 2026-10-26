/**
 * The composer, made roomy: a button in the composer's tool row that trades the harness's
 * capped input height for a 70vh writing view, the way the dsh-composer-expand plugin does
 * it. The switch lives in the prefs (localStorage) and the stylesheet does the rest; the
 * harness keeps its own Enter and send behaviour, which is the one thing this button does
 * not touch. The state is re-applied after the harness rebuilds the composer (switching
 * sessions, changing the view), because the card is a new element each time.
 */
import { createElement as h, useEffect, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { setPrefs, usePrefs } from './prefs.ts'
import { IconExpand } from './icons.tsx'

const ATTR = 'data-nm-composer-expand'

/** Mark the document while the expanded view is on, so the stylesheet can do the rest. */
export function applyComposerExpand(on: boolean): void {
  if (on) document.documentElement.setAttribute(ATTR, '')
  else document.documentElement.removeAttribute(ATTR)
}

export function makeComposerExpand(t: Translate) {
  return function ComposerExpand(): ReactNode {
    const prefs = usePrefs()
    const expanded = prefs.composerExpand
    useEffect(() => { applyComposerExpand(expanded) }, [expanded])
    return h('button', {
      type: 'button',
      className: `nm-mic nm-expand${expanded ? ' nm-live' : ''}`,
      'aria-label': expanded ? t('expandClose') : t('expandOpen'),
      title: expanded ? t('expandClose') : t('expandOpen'),
      'aria-pressed': expanded,
      onMouseDown: (event: { preventDefault(): void }) => event.preventDefault(),
      onClick: () => setPrefs({ composerExpand: !expanded }),
    }, h(IconExpand, { size: 17 }))
  }
}
