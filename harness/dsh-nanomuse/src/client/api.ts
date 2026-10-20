/** The loopback API the host half serves under `/nanomuse/cloud/*` (see `cloud.ts`). */
import type { LiveHub, LiveProfile } from './live.ts'
import type { Words } from './locales.ts'

export type Translate = (key: Words, values?: Record<string, string | number>) => string

export interface Account {
  id: string
  channel: string
  hint: string
  member: boolean
  tokens: { unlimited: boolean; granted: number; used: number; remaining: number }
  /** The pool in yuan (relay 0.14+): what was spent, the grant, what is left, and the 80% heads-up. */
  spend?: { grant?: number; total: number; left: number | null; unlimited: boolean; warn: boolean; usdCny?: number; inviteBonusCny?: number; inviteeBonusCny?: number; ownKeyDocs?: string }
  /** Data controls (relay 0.9); absent on an older relay. */
  contribute?: { on: boolean; samples: number; defaultOn?: boolean; privacyUrl: string }
}

export interface Model {
  id: string
  name: string
  kind: string
  /** `chat`, `gui`, or both (relay 0.1.34+); absent means chat. */
  for?: string[]
  recommended?: boolean
  inputModalities?: string[]
}

export interface CloudStatus {
  signedIn: boolean
  ready: boolean
  baseURL: string
  account?: Account
  models: Model[]
  /** The account chat model new chats use; empty when another provider is the default (relay 0.1.34 host). */
  chatModel?: string
  /** The model the hands see the screen with. */
  handsModel?: string
  profile: LiveProfile
  hub: LiveHub
  error?: { code: string; message: string }
}

const API = 'nanomuse/cloud'

/** GET when there is no body (`status`, `invite` — the read-only routes), POST with one; optional method override; 204 resolves to undefined. */
export async function call<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const m = method ?? (body === undefined ? 'GET' : 'POST')
  const res = await fetch(`${API}/${path}`, {
    method: m,
    headers: { 'content-type': 'application/json', 'x-nanomuse': '1' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (res.status === 204) return undefined as T
  const json = (await res.json().catch(() => ({}))) as { error?: { code?: string; message?: string } }
  if (!res.ok) throw Object.assign(new Error(json.error?.message ?? `${res.status}`), { code: json.error?.code ?? `${res.status}` })
  return json as T
}

/** The relay-style `code` a failed `call` carries (`signed_out`, `not_found`, …), or the HTTP status. */
export function errorCode(err: unknown): string {
  return typeof err === 'object' && err !== null && 'code' in err && typeof (err as { code: unknown }).code === 'string' ? (err as { code: string }).code : ''
}

/**
 * What a failed `call` says to the person: the wire's and the relay's common refusals as a
 * plain sentence in the UI language (the relay out of reach, a deadline passed, too many
 * requests, a sign-in no longer valid, the relay in trouble, this computer's host not
 * answering), and otherwise `That did not work: <the message as it came>`.
 */
export function failureText(t: Translate, err: unknown): string {
  const code = errorCode(err)
  const text = typeof err === 'object' && err !== null && 'message' in err ? String((err as { message: unknown }).message ?? '') : String(err ?? '')
  const status = Number(/^(?:http_)?(\d{3})$/.exec(code)?.[1] ?? 0)
  // `fetch` itself failed: the host on loopback is gone (no status, no code)
  if (!code && err instanceof TypeError && /fetch/i.test(text)) return t('errHostDown')
  if (code === 'unreachable') return t('errUnreachable')
  if (code === 'timeout' || code === 'http_504' || status === 504) return t('errTimeout')
  if (code === 'rate_limited' || code === 'too_many_in_flight' || code === 'provider_busy' || status === 429) return t('errBusy')
  if (code === 'signed_out' || code === 'bad_key' || status === 401) return t('errSignedOut')
  if (status >= 500 && status !== 504) return t('errRelay', { status })
  // the host's own refusals of a form, said in the person's language
  if (code === 'url_userinfo') return t('errUrlUserinfo')
  if (code === 'no_key') return t('errNoKey')
  if (code === 'key_shape') return t('errKeySpaces')
  if (code === 'bad_provider') return t('errSignInRow')
  // the relay's sign-in refusals that have no sentence elsewhere in the client
  if (code === 'domain_limited') return t('errDomainLimited')
  if (code === 'channel_unsupported') return t('errChannelUnsupported')
  return t('failed', { message: text || code || '?' })
}

export const column: Record<string, string | number> = { display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 440 }
export const row: Record<string, string | number> = { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }
export const muted: Record<string, string | number> = { color: 'var(--dsw-alias-label-secondary, #6b6b6b)', fontSize: 13, lineHeight: 1.5 }
export const errorStyle: Record<string, string | number> = { color: 'var(--dsw-alias-state-error-primary, #b42318)', fontSize: 13 }
