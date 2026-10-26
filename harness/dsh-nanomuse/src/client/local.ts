/**
 * The plugin's own routes (`/nanomuse/*`), from the browser half: the same origin fence
 * the rest of the client uses (the `x-nanomuse` header marks it as ours), the JSON in and
 * out, and the error code the pages know how to phrase.
 */

export interface RouteError {
  error?: { code?: string; message?: string }
}

export async function local<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const m = method ?? (body === undefined ? 'GET' : 'POST')
  const res = await fetch(path, {
    method: m,
    headers: { 'content-type': 'application/json', 'x-nanomuse': '1' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (res.status === 204) return undefined as T
  const raw: unknown = await res.json().catch(() => ({}))
  const json = raw as RouteError
  if (!res.ok) throw Object.assign(new Error(json.error?.message ?? `${res.status}`), { code: json.error?.code ?? `${res.status}` })
  return raw as T
}
