/**
 * `failureText`: what a failed call to the host says to the person. The wire's and the
 * relay's common refusals become a plain sentence in the UI language; anything else keeps
 * the message as it came, behind "That did not work".
 */
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const here = fileURLToPath(new URL('.', import.meta.url))
let dir
let failureText
let en
let zh

const ENTRY = `
export { failureText } from './src/client/api.ts'
export { en, zh } from './src/client/locales.ts'
`

/** The client's `t`: the dictionary's sentence with its holes filled. */
const translator = (words) => (key, vars = {}) => {
  const text = words[key]
  if (text === undefined) throw new Error(`no key ${key}`)
  return text.replace(/\{(\w+)\}/g, (_, name) => String(vars[name] ?? `{${name}}`))
}

before(async () => {
  dir = await mkdtemp(join(tmpdir(), 'nm-failure-text-'))
  await build({
    stdin: { contents: ENTRY, resolveDir: join(here, '..'), loader: 'js', sourcefile: 'entry.js' },
    outfile: join(dir, 'api.mjs'),
    format: 'esm',
    platform: 'node',
    target: 'node22',
    bundle: true,
    logLevel: 'silent',
  })
  ;({ failureText, en, zh } = await import(pathToFileURL(join(dir, 'api.mjs')).href))
})

after(async () => {
  await rm(dir, { recursive: true, force: true })
})

/** A failed `call`: the Error the client throws, with the relay-style code on it. */
const failed = (code, message = 'raw text from the wire') => Object.assign(new Error(message), { code })

test('the wire: the relay out of reach or past its deadline, in both languages', () => {
  const t = translator(en)
  assert.equal(failureText(t, failed('unreachable', 'fetch failed')), en.errUnreachable)
  assert.equal(failureText(t, failed('timeout', 'The operation was aborted due to timeout')), en.errTimeout)
  assert.equal(failureText(t, failed('504')), en.errTimeout)
  assert.equal(failureText(translator(zh), failed('unreachable', 'fetch failed')), zh.errUnreachable)
  assert.doesNotMatch(failureText(t, failed('unreachable', 'fetch failed')), /fetch failed/)
})

test('the relay: too many requests, a sign-in no longer valid, a 5xx', () => {
  const t = translator(en)
  assert.equal(failureText(t, failed('rate_limited')), en.errBusy)
  assert.equal(failureText(t, failed('429')), en.errBusy)
  assert.equal(failureText(t, failed('signed_out')), en.errSignedOut)
  assert.equal(failureText(t, failed('401')), en.errSignedOut)
  assert.equal(failureText(t, failed('http_503')), 'The self-hosted relay ran into a problem (503). Try again in a minute.')
  assert.equal(failureText(t, failed('502')), 'The self-hosted relay ran into a problem (502). Try again in a minute.')
})

test('the host itself gone: fetch to loopback failed with no status and no code', () => {
  const t = translator(en)
  assert.equal(failureText(t, new TypeError('Failed to fetch')), en.errHostDown)
  assert.equal(failureText(t, new TypeError('NetworkError when attempting to fetch resource.')), en.errHostDown)
})

test('anything else keeps the message as it came, and an object with a code and a message works too', () => {
  const t = translator(en)
  assert.equal(failureText(t, failed('not_found', 'No such goal')), 'That did not work: No such goal')
  assert.equal(failureText(t, failed('internal', 'ENOENT: profile.json')), 'That did not work: ENOENT: profile.json')
  assert.equal(failureText(t, { code: 'bad_request', message: 'name is required' }), 'That did not work: name is required')
  assert.equal(failureText(t, new Error('plain')), 'That did not work: plain')
  assert.equal(failureText(t, 'a string'), 'That did not work: a string')
})
