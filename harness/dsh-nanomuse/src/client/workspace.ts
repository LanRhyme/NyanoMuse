/**
 * Which folder the chats column is grouped by, and where a new chat starts.
 *
 * The harness keeps the list of workspaces and their chats; the host row
 * (`workspace.ts`, the plugin's `/nanomuse/workspace`) keeps the one thing the
 * registry does not: the choice, so it survives a restart. This module is the
 * browser half of that store: one snapshot the column subscribes to, and the
 * two calls that change it. The list itself is read live from the harness
 * through the column's `useWorkspaces` hook, so a workspace added or renamed
 * anywhere shows up here without asking the host again.
 */
import { local } from './local.ts'

/** One workspace as the host reports it. */
export interface WorkspaceRow {
  id: string
  title: string
  path: string
  /** How many chats belong to it. */
  sessions: number
}

interface HostList {
  supported: boolean
  current?: string
  workspaces: WorkspaceRow[]
}

export interface WorkspaceSnapshot {
  /** The folder new chats start in; absent when the column is not grouped. */
  current?: string
  /** False when this harness has no workspace registry at all. */
  supported: boolean
  /** Ids the host knew at the last read, so a chat is never started in a folder that is gone. */
  ids: readonly string[]
}

const API = '/nanomuse/workspace'

// useSyncExternalStore compares snapshots by identity: replace the object, never mutate it.
let snapshot: WorkspaceSnapshot = { supported: true, ids: [] }
const listeners = new Set<() => void>()

export const workspaceStore = {
  getSnapshot: (): WorkspaceSnapshot => snapshot,
  subscribe: (listener: () => void): (() => void) => {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },
}

function install(list: HostList): WorkspaceSnapshot {
  const ids = Array.isArray(list.workspaces) ? list.workspaces.map((w) => w.id) : []
  // The host already drops a choice it does not know; doing it again here means a frame
  // that arrives out of order cannot leave the column grouped by a folder that is gone.
  const current = typeof list.current === 'string' && list.current && ids.includes(list.current) ? list.current : undefined
  snapshot = { supported: list.supported !== false, ...(current === undefined ? {} : { current }), ids }
  for (const listener of listeners) listener()
  return snapshot
}

/** Read the choice from the host; a workspace deleted since reads as none. */
export async function refreshWorkspace(): Promise<WorkspaceSnapshot> {
  return install(await local<HostList>(API))
}

/** Remember the folder new chats start in; `undefined` stops grouping the column. */
export async function pickWorkspace(id: string | undefined): Promise<WorkspaceSnapshot> {
  return install(await local<HostList>(`${API}/current`, { id: id ?? '' }))
}

/** The folder new chats start in, synchronously, for a click handler. */
export function currentWorkspaceId(): string | undefined {
  return snapshot.current
}
