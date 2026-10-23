/**
 * This computer's hands for the other devices: what a phone or another computer
 * of the same account may call here over the hub — `shell`, `files`,
 * `file.get`, `file.put`, `open`, `screen` (`docs/hub.md`, "Actions"). `info`
 * and `notify` are the service's, since they read its state.
 *
 * The contract judges a command on the device that *asked*, before it is sent
 * — the phone's ShellGuard, a runtime's Sentinel, this bundle's approval card
 * — so nothing here asks again; the switch that decides whether this computer
 * takes calls at all is *remote control* in the service. Everything runs as the
 * signed-in user. A port of the runtime's `nanomuse/hub/actions.py`, limits and
 * shapes alike, so a caller cannot tell the two apart.
 */
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readdir, readFile, rename, rm, stat, lstat, writeFile } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, extname, isAbsolute, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { HubError } from './hub.ts'
import { cliFor, which } from './coding-readers.ts'

/** Characters of stdout or stderr a `shell` result carries. */
export const OUTPUT_LIMIT = 200_000
/** Bytes a file may have to travel base64 inside one hub frame. */
export const FILE_LIMIT = 8 * 1024 * 1024
export const SHELL_TIMEOUT_MAX_S = 900
/** Entries a `files` listing stops at. */
export const ENTRY_LIMIT = 2000

/** The actions this module answers, in the order the runtime announces them. */
export const REMOTE_ACTIONS = ['shell', 'files', 'file.get', 'file.put', 'open', 'screen', 'image.generate'] as const
export type RemoteAction = (typeof REMOTE_ACTIONS)[number]

export type Args = Record<string, unknown>
export type Result = Record<string, unknown>

/** Something to run the platform's tools with; swapped in tests. */
export interface Platform {
  platform: NodeJS.Platform
  /** Runs a program; resolves with its exit code, or rejects when it cannot start. */
  run(file: string, args: string[], timeoutMs: number): Promise<number>
  env: NodeJS.ProcessEnv
}

const MIME: Record<string, string> = {
  '.txt': 'text/plain', '.md': 'text/markdown', '.json': 'application/json', '.html': 'text/html', '.htm': 'text/html',
  '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript', '.ts': 'text/plain', '.py': 'text/x-python',
  '.csv': 'text/csv', '.xml': 'application/xml', '.yaml': 'application/yaml', '.yml': 'application/yaml', '.toml': 'application/toml',
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.gz': 'application/gzip', '.tar': 'application/x-tar',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.svg': 'image/svg+xml',
  '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.mp4': 'video/mp4', '.webm': 'video/webm',
}

/** The one place a path from another device becomes a path here: `~`, env vars, relative to home. */
export function expand(path: string | undefined, home = homedir()): string {
  let p = (path ?? '').trim() || '~'
  if (p === '~' || p.startsWith('~/') || p.startsWith('~\\')) p = join(home, p.slice(1))
  p = p.replace(/\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?/g, (whole, name: string) => process.env[name] ?? whole)
  p = p.replace(/%([A-Za-z_][A-Za-z0-9_]*)%/g, (whole, name: string) => process.env[name] ?? whole)
  return resolve(isAbsolute(p) ? p : join(home, p))
}

function clip(text: string): string {
  if (text.length <= OUTPUT_LIMIT) return text
  return `${text.slice(0, OUTPUT_LIMIT)}\n… [${text.length - OUTPUT_LIMIT} more characters not shown]`
}

function shellCommand(command: string, platform: NodeJS.Platform, env: NodeJS.ProcessEnv): { file: string; args: string[] } {
  if (platform === 'win32') return { file: env.COMSPEC || 'cmd.exe', args: ['/d', '/s', '/c', command] }
  return { file: env.SHELL || '/bin/sh', args: ['-c', command] }
}

/** `shell {command, cwd?, timeout?}` → `{exit_code, stdout, stderr, timed_out, duration_ms}`. */
export async function shell(args: Args, platform: NodeJS.Platform = process.platform, env: NodeJS.ProcessEnv = process.env): Promise<Result> {
  const command = typeof args.command === 'string' ? args.command : ''
  if (!command.trim()) throw new HubError('usage', 'a command is required')
  const requested = Number(args.timeout)
  const timeoutS = Math.max(1, Math.min(Number.isFinite(requested) && requested > 0 ? requested : 120, SHELL_TIMEOUT_MAX_S))
  const cwd = typeof args.cwd === 'string' && args.cwd.trim() ? expand(args.cwd) : undefined
  const started = Date.now()
  const { file, args: argv } = shellCommand(command, platform, env)
  return new Promise<Result>((done) => {
    let child
    try {
      child = spawn(file, argv, { cwd, stdio: ['ignore', 'pipe', 'pipe'], detached: platform !== 'win32', env, windowsHide: true })
    } catch (error: unknown) {
      done({ exit_code: 127, stdout: '', stderr: message(error), timed_out: false, duration_ms: 0 })
      return
    }
    const out: Buffer[] = []
    const err: Buffer[] = []
    let timedOut = false
    let settled = false
    const finish = (code: number | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      done({
        exit_code: timedOut ? 124 : (code ?? 1),
        stdout: clip(Buffer.concat(out).toString('utf8')),
        stderr: clip(Buffer.concat(err).toString('utf8')),
        timed_out: timedOut,
        duration_ms: Date.now() - started,
      })
    }
    const timer = setTimeout(() => {
      timedOut = true
      killTree(child.pid, platform)
      // give the shell a moment to flush, then answer with what arrived
      setTimeout(() => finish(124), 5000).unref()
    }, timeoutS * 1000)
    child.stdout?.on('data', (chunk: Buffer) => out.push(chunk))
    child.stderr?.on('data', (chunk: Buffer) => err.push(chunk))
    child.on('error', (error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      done({ exit_code: 127, stdout: '', stderr: error.message, timed_out: false, duration_ms: Date.now() - started })
    })
    child.on('close', (code) => finish(code))
  })
}

/** Stop the shell and everything it started, so a timed-out command cannot linger. */
function killTree(pid: number | undefined, platform: NodeJS.Platform): void {
  if (!pid) return
  if (platform === 'win32') {
    spawn('taskkill', ['/F', '/T', '/PID', String(pid)], { stdio: 'ignore', windowsHide: true }).on('error', () => undefined)
    return
  }
  try {
    process.kill(-pid, 'SIGKILL')
  } catch {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {
      // already gone
    }
  }
}

interface Entry {
  name: string
  type: 'dir' | 'file' | 'link'
  size: number
  mtime: number
}

/** `files {path?}` → `{path, entries:[{name,type,size,mtime}]}`; folders first, then by name. */
export async function files(args: Args): Promise<Result> {
  const path = expand(typeof args.path === 'string' ? args.path : undefined)
  let info
  try {
    info = await stat(path)
  } catch {
    throw new HubError('not_found', `${path} does not exist`)
  }
  if (info.isFile()) {
    return { path, entries: [{ name: basename(path), type: 'file', size: info.size, mtime: Math.floor(info.mtimeMs / 1000) }] }
  }
  let names: string[]
  try {
    names = await readdir(path)
  } catch {
    throw new HubError('forbidden', `cannot read ${path}`)
  }
  const entries: Entry[] = []
  for (const name of names) {
    try {
      const st = await lstat(join(path, name))
      entries.push({
        name,
        type: st.isSymbolicLink() ? 'link' : st.isDirectory() ? 'dir' : 'file',
        size: st.size,
        mtime: Math.floor(st.mtimeMs / 1000),
      })
    } catch {
      continue
    }
  }
  entries.sort((a, b) => Number(a.type !== 'dir') - Number(b.type !== 'dir') || a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
  return { path, entries: entries.slice(0, ENTRY_LIMIT) }
}

/** `file.get {path}` → `{path, name, bytes, mime, data}` (base64). */
export async function fileGet(args: Args): Promise<Result> {
  const path = expand(typeof args.path === 'string' ? args.path : undefined)
  let info
  try {
    info = await stat(path)
  } catch {
    info = undefined
  }
  if (!info?.isFile()) throw new HubError('not_found', `${path} is not a file`)
  if (info.size > FILE_LIMIT) throw new HubError('too_large', `${basename(path)} is ${info.size} bytes; the limit over the hub is ${FILE_LIMIT}`)
  const data = await readFile(path)
  return { path, name: basename(path), bytes: data.length, mime: MIME[extname(path).toLowerCase()] ?? 'application/octet-stream', data: data.toString('base64') }
}

/** `file.put {path, data, force?}` → `{path, bytes}`; written beside, then moved into place. */
export async function filePut(args: Args): Promise<Result> {
  if (typeof args.path !== 'string' || !args.path.trim()) throw new HubError('usage', 'a path is required')
  const path = expand(args.path)
  const raw = Buffer.from(typeof args.data === 'string' ? args.data : '', 'base64')
  let info
  try {
    info = await stat(path)
  } catch {
    info = undefined
  }
  if (info?.isDirectory()) throw new HubError('is_dir', `${path} is a folder`)
  if (info && !args.force) throw new HubError('exists', `${path} already exists; force replaces it`)
  await mkdir(dirname(path), { recursive: true })
  // written next to the target and renamed into place, so a reader never sees half a file; a
  // write or rename that fails takes its part file with it
  const part = join(dirname(path), `${basename(path)}.nanomuse-part`)
  try {
    await writeFile(part, raw)
    await rename(part, path)
  } catch (error) {
    await rm(part, { force: true }).catch(() => undefined)
    throw error
  }
  return { path, bytes: raw.length }
}

/**
 * Windows opens a URL or a file through `Start-Process`, the target a PowerShell literal in an
 * encoded command: nothing between here and the shell's API reads it, so a `%NAME%` in a URL
 * stays as typed (cmd's `start` expanded it to the variable), as do `&`, `^` and quotes.
 */
export function windowsOpener(target: string): { file: string; args: string[] } {
  const script = `Start-Process -FilePath '${target.replace(/'/g, "''")}'`
  return { file: 'powershell.exe', args: ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')] }
}

/** `open {url}` → `{ok, url}`: a URL in the browser, a path with whatever opens it here. */
export async function open(args: Args, platform: Platform = nodePlatform()): Promise<Result> {
  let url = typeof args.url === 'string' ? args.url.trim() : ''
  if (!url) throw new HubError('usage', 'a URL or a path is required')
  let local: string | undefined
  if (!url.includes('://')) {
    const path = expand(url)
    try {
      await stat(path)
    } catch {
      throw new HubError('not_found', `${path} does not exist`)
    }
    local = path
    url = pathToFileURL(path).href
  }
  let ok = false
  try {
    if (platform.platform === 'darwin') ok = (await platform.run('open', [url], 20_000)) === 0
    else if (platform.platform === 'win32') {
      const opener = windowsOpener(local ?? url)
      ok = (await platform.run(opener.file, opener.args, 20_000)) === 0
    } else ok = (await platform.run('xdg-open', [url], 20_000)) === 0
  } catch {
    ok = false
  }
  return { ok, url }
}

/** `screen` → `{mime, bytes, width, height, data}`: a still of the whole screen, by the platform's own tool. */
export async function screen(platform: Platform = nodePlatform()): Promise<Result> {
  const dir = await mkdtemp(join(tmpdir(), 'nanomuse-screen-'))
  const file = join(dir, 'screen.png')
  try {
    const taken = await capture(file, platform)
    if (!taken) throw new HubError('no_screen', 'this computer cannot take a screenshot (no display, or no tool for it)')
    let data: Buffer
    try {
      data = await readFile(file)
    } catch {
      throw new HubError('no_screen', 'this computer cannot take a screenshot (no display, or no tool for it)')
    }
    const size = pngSize(data)
    return { mime: 'image/png', bytes: data.length, width: size.width, height: size.height, data: data.toString('base64') }
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => undefined)
  }
}

/** Tries the platform's screenshot tools in turn; true when one wrote the file. */
async function capture(file: string, platform: Platform): Promise<boolean> {
  const attempts: [string, string[]][] =
    platform.platform === 'darwin'
      ? [['screencapture', ['-x', '-t', 'png', file]]]
      : platform.platform === 'win32'
        ? [[
            'powershell',
            ['-NoProfile', '-NonInteractive', '-Command',
              'Add-Type -AssemblyName System.Windows.Forms,System.Drawing; ' +
                '$b=[System.Windows.Forms.SystemInformation]::VirtualScreen; ' +
                '$bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; ' +
                '$g=[System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Left,$b.Top,0,0,$bmp.Size); ' +
                `$bmp.Save('${file.replace(/'/g, "''")}',[System.Drawing.Imaging.ImageFormat]::Png)`],
          ]]
        : [
            ['gnome-screenshot', ['-f', file]],
            ['spectacle', ['-b', '-n', '-o', file]],
            ['grim', [file]],
            ['scrot', ['-o', file]],
            ['import', ['-window', 'root', file]],
          ]
  if (platform.platform !== 'win32' && platform.platform !== 'darwin' && !platform.env.DISPLAY && !platform.env.WAYLAND_DISPLAY) return false
  for (const [tool, args] of attempts) {
    try {
      if ((await platform.run(tool, args, 20_000)) !== 0) continue
    } catch {
      continue
    }
    try {
      if ((await stat(file)).size > 0) return true
    } catch {
      continue
    }
  }
  return false
}

/** Width and height from a PNG's IHDR chunk; zeros when the bytes are not a PNG. */
export function pngSize(data: Buffer): { width: number; height: number } {
  if (data.length < 24 || data.toString('latin1', 1, 4) !== 'PNG') return { width: 0, height: 0 }
  return { width: data.readUInt32BE(16), height: data.readUInt32BE(20) }
}

/**
 * Uses Antigravity CLI to generate an image from a prompt, returning the base64-encoded image and metadata.
 */
export async function imageGenerate(args: Args, env: NodeJS.ProcessEnv = process.env): Promise<Result> {
  const prompt = typeof args.prompt === 'string' ? args.prompt.trim() : ''
  if (!prompt) {
    throw new HubError('usage', 'prompt is required')
  }
  const aspectRatio = typeof args.aspect_ratio === 'string' ? args.aspect_ratio.trim() : '1:1'
  let imageName = typeof args.image_name === 'string' ? args.image_name.trim() : ''
  if (!imageName) {
    const slug = prompt.slice(0, 30).toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^_+|_+$/g, '') || 'generated_image'
    imageName = `${slug}_${Date.now()}`
  }

  const agy = cliFor('antigravity', env) ?? which('antigravity', env) ?? 'agy'
  const taskDesc = `Call generate_image tool with Prompt=${JSON.stringify(prompt)} and ImageName=${JSON.stringify(imageName)} and AspectRatio=${JSON.stringify(aspectRatio)}`
  const argv = [
    '--output-format',
    'json',
    '--dangerously-skip-permissions',
    '--print',
    taskDesc,
  ]

  const timeoutMs = 120_000
  const { stdout, stderr, timedOut, code } = await new Promise<{ stdout: string; stderr: string; timedOut: boolean; code: number }>((done) => {
    let child: ReturnType<typeof spawn>
    try {
      child = spawn(agy, argv, { stdio: ['ignore', 'pipe', 'pipe'], env, windowsHide: true })
    } catch (err: unknown) {
      done({ stdout: '', stderr: message(err), timedOut: false, code: 127 })
      return
    }
    const out: Buffer[] = []
    const err: Buffer[] = []
    let isTimedOut = false
    let settled = false
    const finish = (exitCode: number | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      done({
        stdout: Buffer.concat(out).toString('utf8'),
        stderr: Buffer.concat(err).toString('utf8'),
        timedOut: isTimedOut,
        code: isTimedOut ? 124 : (exitCode ?? 1),
      })
    }
    const timer = setTimeout(() => {
      isTimedOut = true
      killTree(child.pid, process.platform)
      setTimeout(() => finish(124), 2000).unref()
    }, timeoutMs)
    child.stdout?.on('data', (c: Buffer) => out.push(c))
    child.stderr?.on('data', (c: Buffer) => err.push(c))
    child.on('error', (e) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      done({ stdout: '', stderr: e.message, timedOut: false, code: 127 })
    })
    child.on('close', (c) => finish(c))
  })

  if (timedOut) {
    throw new HubError('timeout', 'Image generation timed out after 120 seconds')
  }
  if (code !== 0 && !stdout.trim()) {
    throw new HubError('execution_error', stderr.trim() || 'Antigravity CLI failed')
  }

  let data: Record<string, unknown> = {}
  try {
    data = JSON.parse(stdout)
  } catch {
    const lines = stdout.split('\n').map((l) => l.trim()).filter((l) => l.startsWith('{'))
    if (lines.length > 0) {
      try {
        data = JSON.parse(lines[lines.length - 1] ?? '{}')
      } catch {
        // ignore
      }
    }
  }

  const responseText = String(data.response || '')
  const cid = String(data.conversation_id || '')

  let imgPath = ''
  const m = responseText.match(/file:\/\/([^\s)]+\.(?:jpg|jpeg|png|webp))/i)
  if (m && m[1]) {
    imgPath = m[1]
  }

  if (!imgPath || !(await stat(imgPath).then((s) => s.isFile()).catch(() => false))) {
    if (cid) {
      const bases = [
        join(homedir(), '.gemini', 'antigravity-cli'),
        join(homedir(), '.gemini', 'antigravity'),
      ]
      for (const base of bases) {
        const bdir = join(base, 'brain', cid)
        try {
          const files = await readdir(bdir)
          const valid: { path: string; mtime: number }[] = []
          for (const f of files) {
            if (/\.(jpe?g|png|webp)$/i.test(f)) {
              const full = join(bdir, f)
              const s = await stat(full).catch(() => null)
              if (s && s.isFile()) {
                valid.push({ path: full, mtime: s.mtimeMs })
              }
            }
          }
          if (valid.length > 0 && valid[0]) {
            valid.sort((a, b) => b.mtime - a.mtime)
            imgPath = valid[0]?.path ?? ''
            break
          }
        } catch {
          // directory may not exist
        }
      }
    }
  }

  if (!imgPath || !(await stat(imgPath).then((s) => s.isFile()).catch(() => false))) {
    throw new HubError('image_not_found', `Image output not found: ${responseText.slice(0, 200)}`)
  }

  const descM = responseText.match(/\*\*Description\*\*:\s*([^\n]+)/)
  const description = descM?.[1]?.trim() ?? ''

  const rawBytes = await readFile(imgPath)
  const b64Data = rawBytes.toString('base64')
  const ext = extname(imgPath).toLowerCase()
  const mimeType = MIME[ext] || 'image/jpeg'

  return {
    ok: true,
    path: imgPath,
    filename: basename(imgPath),
    mime_type: mimeType,
    size: rawBytes.length,
    data: b64Data,
    prompt,
    aspect_ratio: aspectRatio,
    description,
  }
}

/** Runs one of this module's actions by its hub name. */
export function run(action: RemoteAction, args: Args, platform: Platform = nodePlatform()): Promise<Result> {
  switch (action) {
    case 'shell':
      return shell(args, platform.platform, platform.env)
    case 'files':
      return files(args)
    case 'file.get':
      return fileGet(args)
    case 'file.put':
      return filePut(args)
    case 'open':
      return open(args, platform)
    case 'screen':
      return screen(platform)
    case 'image.generate':
      return imageGenerate(args, platform.env)
  }
}

/** The real platform: `process.platform`, `child_process.spawn`. */
export function nodePlatform(): Platform {
  return {
    platform: process.platform,
    env: process.env,
    run: (file, args, timeoutMs) =>
      new Promise<number>((done, fail) => {
        const child = spawn(file, args, { stdio: 'ignore', windowsHide: true })
        const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs)
        child.on('error', (error) => {
          clearTimeout(timer)
          fail(error)
        })
        child.on('close', (code) => {
          clearTimeout(timer)
          done(code ?? 1)
        })
      }),
  }
}

/** A brief line for the audit and the toast: what the other device asked for. */
export function brief(action: string, args: Args): string {
  const text = (value: unknown, max = 80) => (typeof value === 'string' ? value.replace(/\s+/g, ' ').trim().slice(0, max) : '')
  switch (action) {
    case 'shell':
      return text(args.command)
    case 'files':
    case 'file.get':
    case 'file.put':
      return text(args.path) || '~'
    case 'open':
      return text(args.url)
    case 'notify':
      return text(args.text)
    case 'image.generate':
      return text(args.prompt)
    default:
      return ''
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
