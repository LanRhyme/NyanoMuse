// The stylesheet is one string; a few rules are load-bearing for the window chrome and are
// checked here as text, so that a later restyle cannot quietly bring the macOS bug back where
// the header face over the drag region stopped taking clicks.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// A Windows checkout may carry CRLF line endings (core.autocrlf); the tests look for
// `\n<selector> {\n`, so the file is read with LF endings whatever the checkout did.
const css = readFileSync(fileURLToPath(new URL('../src/client/styles.ts', import.meta.url)), 'utf8').replace(/\r\n/g, '\n')

/** The declarations of the first rule whose selector list is exactly `selector`. */
function rule(selector) {
  const lines = css.split('\n')
  const prefix = `${selector} {`
  const line = lines.find((l) => l.startsWith(prefix))
  assert.ok(line, `no rule for ${selector}`)
  return line.slice(prefix.length)
}

/** The same, for a rule written over several lines. */
function block(selector) {
  const at = css.indexOf(`\n${selector} {\n`)
  assert.ok(at >= 0, `no rule for ${selector}`)
  return css.slice(at, css.indexOf('\n}', at))
}

test('the header fills its bar as a row, without a transform', () => {
  const header = rule('.nm-header')
  assert.doesNotMatch(header, /transform\s*:/)
  // The block takes the bar's whole box, start to end. The face, the name and the capsules
  // lead from the left, as the harness's own title row does: the middle column takes the
  // slack, so a long name shortens instead of pushing the actions off the end.
  assert.match(header, /inset:\s*0/)
  assert.match(header, /display:\s*grid/)
  assert.match(header, /grid-template-columns:\s*auto minmax\(0, 1fr\) auto/)
  assert.match(rule('.nm-header-group'), /grid-column:\s*2/)
  assert.match(rule('.nm-header-group'), /justify-self:\s*start/)
  assert.match(rule('.nm-header-actions'), /grid-column:\s*3/)
  // and its controls take their clicks while the bar around them does not
  assert.match(header, /pointer-events:\s*auto/)
  // the old escape hatch is gone with the wrapper's pointer-events:none
  assert.doesNotMatch(css, /^\.nm-header > \* \{ pointer-events: auto; \}/m)
})

test('the face, the header block and the rail face are no-drag islands on macOS and Windows', () => {
  const noDrag = css.split('\n').filter((l) => l.includes('-webkit-app-region: no-drag') && l.includes("[data-nm-platform='darwin']") && l.includes("[data-nm-platform='win32']"))
  const covers = (sel) => noDrag.some((l) => l.includes(sel))
  assert.ok(covers('.nm-header'), '.nm-header')
  assert.ok(covers('.nm-header-face'), '.nm-header-face')
  assert.ok(covers('.nm-rail-face'), '.nm-rail-face')
  // the chat header stays a drag handle
  assert.match(css, /\[data-nm-platform='win32'\]\) \[data-window-drag\][^\n]*-webkit-app-region: drag/)
})

test('on Windows the top bars clear the caption buttons by the title bar area, out of full screen only', () => {
  const scope = "html[data-nm-platform='win32']:not([data-nm-fullscreen])"
  const lines = css.split('\n')
  // one width, defined once, read from the window's title bar area rather than a fixed number
  const defs = lines.filter((l) => l.includes('--nm-caption-inset:'))
  assert.equal(defs.length, 1)
  assert.ok(defs[0].startsWith(`${scope} {`), defs[0])
  assert.match(defs[0], /env\(titlebar-area-x, 0px\)/)
  assert.match(defs[0], /env\(titlebar-area-width, 100vw\)/)
  assert.doesNotMatch(defs[0], /[1-9]\d*px/)
  // and their height, the same way, for the one bar that moves down instead
  const heightDefs = lines.filter((l) => l.includes('--nm-caption-height:'))
  assert.equal(heightDefs.length, 1)
  assert.ok(heightDefs[0].startsWith(`${scope} {`), heightDefs[0])
  assert.match(heightDefs[0], /--nm-caption-height: env\(titlebar-area-height, 0px\);/)
  // every use in the same scope: anywhere else the variable is undefined and the padding would drop to 0
  const uses = lines.filter((l) => l.includes('var(--nm-caption-inset)'))
  assert.ok(uses.length >= 9)
  const heightUses = lines.filter((l) => l.includes('var(--nm-caption-height)'))
  assert.ok(heightUses.length >= 1)
  for (const l of [...uses, ...heightUses]) assert.ok(l.startsWith(`${scope} `), l)
  // a task's detail is a narrow column: it moves down rather than giving up the width, and the
  // Schedules title row clears the corner only while no detail takes it
  assert.ok(heightUses.some((l) => l.includes('[data-detail-tab]')))
  assert.ok(!uses.some((l) => l.includes('[data-detail-tab]')))
  assert.ok(uses.some((l) => l.includes('task-manager-page"]:not(:has(> aside))')))
  // the harness's bars by its markers; a class name only by its stem (_pageHeading), never a full
  // hashed CSS module class
  for (const marker of ['[data-plugin-panel]', 'task-manager-page', '[data-dockkit-strip-chrome]']) {
    assert.ok(uses.some((l) => l.includes(marker)), marker)
  }
  for (const l of [...uses, ...heightUses]) assert.doesNotMatch(l, /\.[\w-]*_/, l)
  // the split pane's bars sit below its own top strip and get nothing
  for (const l of uses.filter((l) => /\.nm-(room-head|view-bar)/.test(l))) assert.match(l, /:not\(\.nm-split \*\)/, l)
})

test('the desk-a additions sit at the end of the sheet, under their marker', () => {
  const marker = css.indexOf('/* desk-a */')
  assert.ok(marker > 0)
  assert.ok(css.indexOf('.nm-rail-face, button:has(> .nm-rail-face)') > marker)
})

test('the lights breathe: nothing runs round, sweeps across or flows in the stylesheet', () => {
  // the phone's rhythm — 2.4 s in, 2.4 s out — on every light that says "working"
  for (const sel of ['.nm-chat-dot.nm-live', '.nm-status-dot.nm-live', '.nm-traj-live', '.nm-mic.nm-live', '.nm-remote-working::before']) {
    const r = rule(sel)
    assert.match(r, /animation:\s*nm-(pulse 2\.4s|breathe-light 4\.8s|mic-pulse 4\.8s) ease-in-out infinite/, sel)
  }
  assert.match(rule('html[data-nm-muse] [data-chat-running]::after'), /nm-breathe-light 4\.8s ease-in-out infinite/)
  // no marquee, no shimmer, no running border, no flowing gradient
  assert.doesNotMatch(css, /conic-gradient/)
  // a still background-position is fine (the wallpaper is centred, not drifting); one inside a
  // keyframe would be a sweep
  for (const m of css.matchAll(/@keyframes[\s\S]*?\n\}/g)) assert.doesNotMatch(m[0], /background-position/)
  assert.doesNotMatch(css, /@keyframes nm-(dots|ripple|stage-pulse|flow|sweep|shimmer|marquee)\b/)
  assert.doesNotMatch(css, /\.nm-stage/)
  // the breathing keyframes go between a dim and a full light, no travel
  assert.match(css, /@keyframes nm-breathe-light \{ 0%, 100% \{ opacity: 0\.3; \} 50% \{ opacity: 1; \} \}/)
})

test('a thread from other devices in the hero layout is bottom-aligned by an auto margin, so it scrolls (#261)', () => {
  const scroll = '[data-conversation-content][data-content-phase="hero"] > [data-conversation-scroll]'
  // the bubbles and the run card in the scrolling body: the alignment of the column itself stays at
  // the start, since a column aligned to its end or centred puts its overflow above the top edge,
  // where no scroll reaches it
  assert.match(rule(`${scroll}:has(.nm-remote, .nm-traj)`), /justify-content:\s*flex-start/)
  for (const line of css.split('\n').filter((l) => l.startsWith(scroll))) {
    assert.doesNotMatch(line, /justify-content:\s*(flex-end|end|center|safe)/, line)
  }
  // the first of them takes the free space above it while there is some
  const first = rule(`${scroll} > :is(.nm-remote, .nm-traj):not(:is(.nm-remote, .nm-traj) ~ :is(.nm-remote, .nm-traj))`)
  assert.match(first, /margin-top:\s*auto/)
  // and the old first-child margin, which did nothing once the bubbles overflowed, is gone
  assert.doesNotMatch(css, /\.nm-remote:first-child \{ margin-top: 16px/)
})

test('the composer is docked at the foot of the window and the thread scrolls under it (#494)', () => {
  const seat = rule('html[data-nm-muse] [class*="_composerSeat"]')
  assert.match(seat, /position:\s*sticky/)
  assert.match(seat, /bottom:\s*0/)
  // short threads push it down, long ones keep it there
  assert.match(seat, /margin-top:\s*auto/)
  // the breathing room under the pill lives on the stack, in both the empty and the active chat
  assert.match(rule('html[data-nm-muse] [class*="_composerStack"]'), /padding-bottom:\s*14px !important/)
  assert.doesNotMatch(css, /data-content-phase="active"\] \[data-composer-card\] \{ margin-bottom/)
})

test('under prefers-reduced-motion every breathing light is steady', () => {
  const reduced = css.split('\n').filter((l) => l.startsWith('@media (prefers-reduced-motion: reduce)'))
  assert.ok(reduced.length >= 2)
  const steady = reduced.find((l) => l.includes('.nm-traj-live'))
  assert.ok(steady, 'the trajectory light is covered')
  for (const sel of ['.nm-chat-dot.nm-live', '.nm-status-dot.nm-live', '.nm-remote-working::before', '.nm-mic.nm-live', 'html[data-nm-muse] [data-chat-running]::after']) assert.ok(steady.includes(sel), sel)
  assert.match(steady, /animation: none; opacity: 1;/)
})

test('the chrome is cut from the harness\'s own material: one palette, ink on the canvas', () => {
  // One token block, and every colour in it is a harness token: the light and the dark come
  // from `--dsw-*`, so a theme change moves our surfaces with the harness's. What is ours is
  // the accent, the colour the person gave the agent.
  const at = css.indexOf('\nhtml[data-nanomuse] body {\n')
  assert.ok(at >= 0, 'the palette block')
  const palette = css.slice(at, css.indexOf('\n}', at))
  const tokens = Object.fromEntries([...palette.matchAll(/--(nm-[a-z-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
  const fromHarness = ['nm-base', 'nm-canvas', 'nm-sidebar', 'nm-field', 'nm-field-border', 'nm-card', 'nm-selected', 'nm-hover', 'nm-divider', 'nm-agent-bubble', 'nm-bubble-border', 'nm-user-bubble', 'nm-user-bubble-fg', 'nm-ink', 'nm-ink-fg', 'nm-send', 'nm-send-idle', 'nm-blue', 'nm-blue-hover', 'nm-on-accent', 'nm-primary']
  for (const name of fromHarness) assert.match(tokens[name] ?? '', /^var\(--dsw-/, name)
  // declared on <body>, because the harness declares its palette there and a token that
  // reads another token is resolved where it is declared
  assert.match(palette, /^\nhtml\[data-nanomuse\] body \{/)
  assert.doesNotMatch(css, /\nhtml\[data-nanomuse\] \{\n[^}]*--nm-base:/)
  // the radius and the font are the harness's too, from :root
  assert.match(css, /--nm-radius: var\(--dsw-radius-lg\)/)
  assert.match(css, /--nm-font: var\(--dsw-font-family\)/)
  // and there is no second, hand-written palette to drift from it
  assert.equal(css.includes('html[data-nanomuse] body[data-ds-dark-theme] {'), false)
  // the one colour that is ours, kept on <html> so setAccent() can replace it
  assert.match(css, /--nm-accent: #c8743a;/)
  assert.equal(tokens['nm-accent'], undefined)

  // The agent's words are the canvas's own, as the harness draws its replies: a card only
  // when the reply was deliberately cut into bubbles (Markup.ts), or over a wallpaper.
  assert.match(rule('html[data-nm-muse] [data-chat-flow-kind="assistant-step"] [data-slot="conversation.chat.node"] > [class*="_root"] > [class*="_body"]'), /background:\s*transparent/)
  assert.match(rule('html[data-nm-muse] .nm-remote-assistant .nm-remote-bubble'), /background:\s*transparent/)
  assert.match(block('.nm-bubble-seg'), /background:\s*var\(--nm-agent-bubble\)/)
  // your words are a pill of the harness's bubble colour, the radius the harness gives one
  for (const sel of [
    'html[data-nm-muse] [data-chat-flow-kind="user"] [class*="_bubble"]',
    'html[data-nm-muse] .nm-remote-user .nm-remote-bubble',
    '.nm-cd-from-you .nm-cd-bubble',
  ]) {
    const r = rule(sel)
    assert.match(r, /background:\s*var\(--nm-user-bubble\)/, sel)
    assert.match(r, /color:\s*var\(--nm-user-bubble-fg\)/, sel)
  }
  assert.match(rule('html[data-nm-muse] [data-chat-flow-kind="user"] [class*="_bubble"]'), /border-radius:\s*var\(--dsw-radius-xl\)/)
  // the send disc and the scroll-to-latest disc: the accent's ink, the glyph in the opposite colour
  assert.match(rule('html[data-nm-muse] [data-composer-card] [class*="_primary"]'), /color:\s*var\(--nm-ink-fg\)/)
  const toBottom = rule('html[data-nm-muse] [data-conversation-scroll] [class*="_toBottomSlot"] > button[class*="_toBottom"]')
  assert.match(toBottom, /background:\s*var\(--nm-send\)/)
  assert.match(toBottom, /color:\s*var\(--nm-ink-fg\)/)
})

test('in the Muse look the header is the phone\'s bar, and the thread starts under it', () => {
  const header = 'html[data-nm-muse] header[data-window-drag]:has(.nm-header)'
  const bar = rule(header)
  assert.match(bar, /position:\s*absolute/)
  assert.match(bar, /height:\s*var\(--nm-header-h\)/)
  // the bar ends in a hairline (the thread gets a top edge; the phone had none, and the
  // thread used to bleed into the bar's fade), and its scrim clears before the thread starts
  assert.match(bar, /border-bottom:\s*1px solid color-mix\(in srgb, var\(--nm-divider\) 60%, transparent\)/)
  assert.match(bar, /linear-gradient\(to bottom, color-mix\(in srgb, var\(--nm-canvas\) 88%, transparent\) 0%, color-mix\(in srgb, var\(--nm-canvas\) 62%, transparent\) 70%, transparent 100%\)/)
  assert.match(bar, /pointer-events:\s*none/)
  // the controls in it still take clicks, and the drag strip still drags
  const controls = css.split('\n').find((l) => l.startsWith(`${header} :is(`) && l.includes('pointer-events: auto'))
  assert.ok(controls, 'the header controls take their clicks back')
  for (const sel of ['button', 'a', '.nm-header', '.nm-header-drag', '[role="tab"]']) assert.ok(controls.includes(sel), sel)
  // the thread clears the bar and the window chrome above it
  const thread = 'html[data-nm-muse] [data-slot="conversation.header"]:has(.nm-header) ~ [data-conversation-content]'
  assert.match(rule(`${thread} > [data-conversation-scroll]`), /padding-top:\s*calc\(var\(--nm-header-top, 0px\) \+ var\(--nm-header-h\) \+ 8px\)/)
  assert.match(rule('header[data-window-drag]:has(.nm-header)'), /min-height:\s*var\(--nm-header-h\)/)
  // each platform pushes the bar below its title bar; the bar's own height never changes
  assert.match(rule("html[data-nm-platform='darwin']:not([data-nm-fullscreen])"), /--nm-header-top:\s*36px/)
  assert.match(rule("html[data-nm-platform='win32']:not([data-nm-fullscreen])"), /--nm-header-top:\s*26px/)
  // the chat's canvas, and the harness's own base token following it there
  const content = rule(thread)
  assert.match(content, /background:\s*var\(--nm-canvas\)/)
  assert.match(content, /--dsw-alias-bg-base:\s*var\(--nm-canvas\)/)
})