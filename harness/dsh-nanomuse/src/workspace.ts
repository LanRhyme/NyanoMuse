/**
 * Workspaces on this computer: a folder the agent works in, the Muse desktop's
 * way of keeping one project's chats apart from another's.
 *
 * The harness already owns the list (`ctx.workspaceRegistry`): one durable
 * record per directory, the sessions whose header `cwd` is that directory, the
 * order the person put them in. This row adds the one thing the registry does
 * not keep, *which folder new chats start in*, in
 * `$DSH_HOME/nanomuse/workspace.json` as `{ "current": "<workspace id>" }`.
 * The browser half reads both over `/nanomuse/workspace`: the list to draw the
 * column's switcher, `current` to know where a new chat goes. A harness build
 * without a workspace registry answers `supported: false` and the column hides
 * the switcher rather than offering a switch that would do nothing.
 *
 * The id is stored even when the registry does not know it yet (a row created a
 * moment ago whose change frame has not arrived); `list()` filters an id that
 * no record carries, so a workspace deleted elsewhere reads as "no workspace"
 * instead of pointing at nothing. Host workspace ids are never reused, so a
 * stale id can never come back to life as another folder.
 */
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { dirname, join } from 'node:path'
import { Service, type Context } from '@deepseek-ai/cordis'
import { mountGuarded } from './admit.ts'
import { dshHome, json, message, sameOrigin, send } from './cloud.ts'
import type {} from './cloud.ts'

/** Where the browser half asks for the folders. */
export const WORKSPACE_PREFIX = '/nanomuse/workspace'

/** One workspace, as the column draws it. */
export interface WorkspaceRow {
  id: string
  title: string
  /** The canonical directory the agent's reads and commands run in. */
  path: string
  /** How many chats belong to it (the registry's own account). */
  sessions: number
}

/** The whole answer of the read route. */
export interface WorkspaceList {
  /** False when this harness has no workspace registry: the column draws no switcher. */
  supported: boolean
  /** The folder new chats start in; absent when the person has not picked one. */
  current?: string
  workspaces: WorkspaceRow[]
}

/** The part of the registry this row reads (a subset of `ctx.workspaceRegistry`). */
export interface RegistryFace {
  list(): { id: string; title: string; path: string; sessionIds: readonly string[] }[]
}

export interface WorkspaceState {
  current?: string
}

/** The registry's rows as the column draws them; a `current` no row carries reads as none. */
export function describeWorkspaces(registry: RegistryFace | undefined, current: string | undefined): WorkspaceList {
  const workspaces: WorkspaceRow[] = registry
    ? registry.list().map((w) => ({ id: w.id, title: w.title, path: w.path, sessions: w.sessionIds.length }))
    : []
  const known = current && workspaces.some((w) => w.id === current) ? current : undefined
  return { supported: registry !== undefined, ...(known === undefined ? {} : { current: known }), workspaces }
}

/** The store's `{ "current": "…" }`; unreadable text, or another shape, is no choice. */
export function parseWorkspaceState(text: string): WorkspaceState {
  try {
    const raw = JSON.parse(text) as { current?: unknown }
    return typeof raw.current === 'string' && raw.current ? { current: raw.current } : {}
  } catch {
    return {}
  }
}

declare module '@deepseek-ai/cordis' {
  interface Context {
    nanomuseWorkspace: NanomuseWorkspace
  }
}

export default class NanomuseWorkspace extends Service {
  private state: WorkspaceState = {}
  private writing: Promise<void> = Promise.resolve()

  constructor(ctx: Context) {
    super(ctx, 'nanomuseWorkspace')
  }

  async [Service.init](): Promise<void> {
    this.state = await this.read()
    mountGuarded(this.ctx, WORKSPACE_PREFIX, this.handle, 'nanomuse workspace: api')
  }

  get file(): string {
    return join(dshHome(), 'nanomuse', 'workspace.json')
  }

  /** The registry, when this harness has one. */
  private registry(): RegistryFace | undefined {
    return this.ctx.get('workspaceRegistry') as RegistryFace | undefined
  }

  /** The folders and the one new chats start in, read at call time. */
  list(): WorkspaceList {
    return describeWorkspaces(this.registry(), this.state.current)
  }

  /** Remember the folder new chats start in; an empty id forgets it. */
  setCurrent(id: string): WorkspaceList {
    if (id) this.state.current = id
    else delete this.state.current
    this.save()
    return this.list()
  }

  private async read(): Promise<WorkspaceState> {
    try {
      return parseWorkspaceState(await readFile(this.file, 'utf8'))
    } catch {
      return {}
    }
  }

  private save(): void {
    const body = JSON.stringify(this.state, null, 2)
    this.writing = this.writing
      .then(async () => {
        const path = this.file
        await mkdir(dirname(path), { recursive: true })
        const temp = `${path}.tmp`
        await writeFile(temp, body, 'utf8')
        await rename(temp, path)
      })
      .catch((error: unknown) => this.ctx.logger.warn('nanomuse workspace: save failed: %s', message(error)))
  }

  private readonly handle = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1')
    const route = url.pathname.slice(WORKSPACE_PREFIX.length) || '/'
    if (!sameOrigin(req)) return send(res, 403, { error: { code: 'forbidden', message: 'Same-origin requests only' } })
    try {
      if (req.method === 'GET' && route === '/') return send(res, 200, this.list())
      if (req.method === 'POST' && route === '/current') {
        const body = await json(req)
        return send(res, 200, this.setCurrent(String(body['id'] ?? '')))
      }
      return send(res, 404, { error: { code: 'not_found', message: 'No such route' } })
    } catch (error: unknown) {
      return send(res, 400, { error: { code: 'bad_request', message: message(error) } })
    }
  }
}
