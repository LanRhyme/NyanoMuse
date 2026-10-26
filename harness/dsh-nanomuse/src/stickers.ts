/**
 * The person's stickers (表情包) on this computer: the packs under
 * `$DSH_HOME/nanomuse/stickers/packs/<packId>/`, each a `manifest.json` and its pictures,
 * the same layout the phone uses so a pack means the same thing on both. One pack ships
 * with the app (大肥鱼, the whale-girl chibi set under `assets/stickers/`); the rest are the
 * person's own, added from the composer's sticker panel or a folder.
 *
 * The browser half reads the catalogue over `/nanomuse/stickers/list`, adds and edits
 * through the other routes, and draws the pictures from `/nanomuse/stickers/img/<pack>/<file>`
 * (only a file the pack's manifest names is ever served, so no path leaves the pack).
 * The model is told the numbered catalogue and writes `<nm:meme id="…"/>` in its reply
 * (`sticker-index.ts` holds both the numbering and that paragraph).
 */
import { createHash } from 'node:crypto'
import { createReadStream, existsSync } from 'node:fs'
import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { basename, join } from 'node:path'
import { Service, type Context } from '@deepseek-ai/cordis'
import { mountGuarded } from './admit.ts'
import { dshHome, json, message, sameOrigin, send } from './cloud.ts'
import { packageAssetsDir } from './profile.ts'
import { comparePacks, flattenPacks, memePrompt, memeUrl, STICKERS_PREFIX, type StickerCatalogue, type StickerItemView, type StickerPackView } from './sticker-index.ts'

export { STICKERS_PREFIX }

/** The biggest picture taken, and the formats it may be in. */
export const STICKER_MAX_BYTES = 8 * 1024 * 1024
const STICKER_TYPES: Record<string, string> = {
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
}
/** The pack that ships with the app. */
const BUILTIN_PACKS = ['dafeiyu']
/** A pack id and a file name are made of these; nothing else ever reaches a path. */
const SAFE = /^[A-Za-z0-9._-]{1,80}$/

interface ManifestSticker {
  id?: unknown
  filename?: unknown
  format?: unknown
  name?: unknown
  keywords?: unknown
  caption?: unknown
  size?: unknown
}

interface Manifest {
  id?: unknown
  name?: unknown
  description?: unknown
  count?: unknown
  stickers?: unknown
}

/** One pack as it sits on disk. */
interface PackOnDisk {
  id: string
  dir: string
  name: string
  description: string
  builtin: boolean
  stickers: Array<{ id: string; filename: string; format: string; name: string; keywords: string }>
}

declare module '@deepseek-ai/cordis' {
  interface Context {
    nanomuseStickers: NanomuseStickers
  }
}

export default class NanomuseStickers extends Service {
  private ready: Promise<void> | undefined
  /** The catalogue last read, and what it was read from (the packs folder's mtime). */
  private cached: { at: number; catalogue: StickerCatalogue } | undefined
  /** The prompt paragraph of the catalogue last read, for the synchronous prompt assembly. */
  private promptText: string | null = null

  constructor(ctx: Context) {
    super(ctx, 'nanomuseStickers')
  }

  async [Service.init](): Promise<void> {
    this.ready = this.prepare().catch((error: unknown) => {
      this.ctx.logger.warn('nanomuse stickers: prepare failed: %s', message(error))
    })
    await this.ready
    await this.catalogue().catch(() => undefined)
    mountGuarded(this.ctx, STICKERS_PREFIX, this.handle, 'nanomuse stickers: api')
  }

  // ---- the store -----------------------------------------------------------------------

  get root(): string {
    return join(dshHome(), 'nanomuse', 'stickers')
  }

  get packsDir(): string {
    return join(this.root, 'packs')
  }

  /** The folders exist, and the shipped pack is copied in the first time. */
  private async prepare(): Promise<void> {
    await mkdir(this.packsDir, { recursive: true })
    for (const id of BUILTIN_PACKS) {
      const dest = join(this.packsDir, id)
      if (existsSync(join(dest, 'manifest.json'))) continue
      const src = join(packageAssetsDir(import.meta.url), 'stickers', id)
      if (!existsSync(src)) continue
      try {
        await cp(src, dest, { recursive: true })
      } catch (error: unknown) {
        this.ctx.logger.warn('nanomuse stickers: could not copy the %s pack: %s', id, message(error))
      }
    }
  }

  /** Every pack, read from disk: the shipped one first, then the person's by name. */
  async packs(): Promise<PackOnDisk[]> {
    const dirs = await readdir(this.packsDir, { withFileTypes: true }).catch(() => [])
    const packs: PackOnDisk[] = []
    for (const entry of dirs) {
      if (!entry.isDirectory() || !SAFE.test(entry.name)) continue
      const dir = join(this.packsDir, entry.name)
      const pack = await this.readPack(dir, entry.name)
      if (pack) packs.push(pack)
    }
    packs.sort(comparePacks)
    return packs
  }

  private async readPack(dir: string, folder: string): Promise<PackOnDisk | undefined> {
    const manifestPath = join(dir, 'manifest.json')
    let manifest: Manifest = {}
    if (existsSync(manifestPath)) {
      try {
        manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as Manifest
      } catch {
        manifest = {}
      }
    } else {
      // a folder dropped in by hand: every picture in it, named by its file
      const files = await readdir(dir).catch(() => [])
      const stickers = files
        .filter((file) => STICKER_TYPES[ext(file)] !== undefined)
        .sort()
        .map((file) => ({ id: `${folder}_${stem(file)}`, filename: file, format: ext(file).slice(1), name: stem(file), keywords: '' }))
      if (stickers.length === 0) return undefined
      return { id: folder, dir, name: folder, description: '', builtin: false, stickers }
    }
    const id = typeof manifest.id === 'string' && SAFE.test(manifest.id) ? manifest.id : folder
    const list = Array.isArray(manifest.stickers) ? (manifest.stickers as ManifestSticker[]) : []
    const stickers: PackOnDisk['stickers'] = []
    for (const item of list) {
      const filename = typeof item.filename === 'string' ? basename(item.filename) : ''
      if (!filename || !SAFE.test(filename) || !existsSync(join(dir, filename))) continue
      const format = typeof item.format === 'string' && item.format ? item.format : ext(filename).slice(1)
      stickers.push({
        id: typeof item.id === 'string' && item.id ? item.id : filename,
        filename,
        format,
        name: typeof item.name === 'string' ? item.name.trim() : '',
        keywords: typeof item.keywords === 'string' ? item.keywords : '',
      })
    }
    // a pack the person just made is empty on purpose: it stays, and the picker offers it
    return {
      id,
      dir,
      name: typeof manifest.name === 'string' && manifest.name.trim() ? manifest.name.trim() : folder,
      description: typeof manifest.description === 'string' ? manifest.description : '',
      builtin: BUILTIN_PACKS.includes(id),
      stickers,
    }
  }

  /** The catalogue the browser draws and the model reads, with the numbers assigned. */
  async catalogue(): Promise<StickerCatalogue> {
    await this.ready
    const at = await stat(this.packsDir).then((s) => s.mtimeMs).catch(() => 0)
    if (this.cached && this.cached.at === at) return this.cached.catalogue
    const packs: StickerPackView[] = []
    for (const pack of await this.packs()) {
      const items: StickerItemView[] = pack.stickers.map((sticker) => ({
        id: sticker.id,
        seq: '',
        name: sticker.name,
        filename: sticker.filename,
        format: sticker.format,
        packId: pack.id,
        packName: pack.name,
        keywords: sticker.keywords,
        url: memeUrl(pack.id, sticker.filename),
      }))
      packs.push({ id: pack.id, name: pack.name, description: pack.description, count: items.length, builtin: pack.builtin, items })
    }
    const catalogue = flattenPacks(packs)
    this.cached = { at, catalogue }
    this.promptText = memePrompt(catalogue)
    return catalogue
  }

  /** The pack with that id, from the catalogue's view. */
  async packOf(id: string): Promise<PackOnDisk | undefined> {
    return (await this.packs()).find((pack) => pack.id === id)
  }

  private async writeManifest(pack: PackOnDisk): Promise<void> {
    const body = {
      id: pack.id,
      name: pack.name,
      description: pack.description,
      count: pack.stickers.length,
      stickers: pack.stickers.map((sticker) => ({
        id: sticker.id,
        filename: sticker.filename,
        format: sticker.format,
        name: sticker.name,
        keywords: sticker.keywords,
      })),
    }
    await writeFile(join(pack.dir, 'manifest.json'), JSON.stringify(body, null, 2), 'utf8')
    this.cached = undefined
  }

  /** A new pack of the person's, empty. */
  async createPack(name: string): Promise<PackOnDisk> {
    const clean = name.trim().slice(0, 40)
    if (!clean) throw new Error('a pack needs a name')
    const id = `pack_${Date.now().toString(36)}`
    const dir = join(this.packsDir, id)
    await mkdir(dir, { recursive: true })
    const pack: PackOnDisk = { id, dir, name: clean, description: '', builtin: false, stickers: [] }
    await this.writeManifest(pack)
    return pack
  }

  /** A sticker taken from a data URL (`data:image/png;base64,…`). */
  async addSticker(packId: string, dataUrl: string, name: string, filename: string): Promise<StickerItemView> {
    await this.ready
    const pack = await this.packOf(packId)
    if (!pack) throw new Error('no such pack')
    const match = /^data:([a-z]+)\/([a-z0-9.+-]+);base64,(.*)$/i.exec(dataUrl ?? '')
    if (!match) throw new Error('not an image')
    const bytes = Buffer.from(match[3] ?? '', 'base64')
    if (bytes.length === 0) throw new Error('empty picture')
    if (bytes.length > STICKER_MAX_BYTES) throw new Error('too large')
    const subtype = (match[2] ?? '').toLowerCase().replace('jpg', 'jpeg')
    const format = subtype === 'jpeg' ? 'jpg' : subtype
    if (!STICKER_TYPES[`.${format}`]) throw new Error('unsupported format')
    const hash = createHash('md5').update(bytes).digest('hex').slice(0, 8)
    const stemName = (filename ?? '').replace(/\.[^.]*$/, '').replace(/[^A-Za-z0-9._-]/g, '').slice(0, 40)
    const stored = `sticker_${Date.now().toString(36)}_${hash}.${format}`
    await writeFile(join(pack.dir, stored), bytes)
    const display = (name ?? '').trim().slice(0, 40) || stemName || '表情'
    pack.stickers.push({ id: hash, filename: stored, format, name: display, keywords: '' })
    await this.writeManifest(pack)
    const items = (await this.catalogue()).items
    const added = items.find((item) => item.packId === packId && item.filename === stored)
    if (!added) throw new Error('the sticker was written but the catalogue did not read it back')
    return added
  }

  /** A sticker removed from its pack; the picture goes with it. */
  async removeSticker(packId: string, key: string): Promise<void> {
    const pack = await this.packOf(packId)
    if (!pack) throw new Error('no such pack')
    const sticker = pack.stickers.find((item) => item.id === key || item.filename === key)
    if (!sticker) throw new Error('no such sticker')
    pack.stickers = pack.stickers.filter((item) => item !== sticker)
    await rm(join(pack.dir, sticker.filename), { force: true })
    await this.writeManifest(pack)
  }

  async renameSticker(packId: string, key: string, name: string): Promise<void> {
    const pack = await this.packOf(packId)
    if (!pack) throw new Error('no such pack')
    const sticker = pack.stickers.find((item) => item.id === key || item.filename === key)
    if (!sticker) throw new Error('no such sticker')
    const clean = name.trim().slice(0, 40)
    if (!clean) throw new Error('a sticker needs a name')
    sticker.name = clean
    await this.writeManifest(pack)
  }

  async renamePack(packId: string, name: string): Promise<void> {
    const pack = await this.packOf(packId)
    if (!pack) throw new Error('no such pack')
    const clean = name.trim().slice(0, 40)
    if (!clean) throw new Error('a pack needs a name')
    pack.name = clean
    await this.writeManifest(pack)
  }

  /** A pack of the person's removed with its pictures; a shipped pack stays. */
  async removePack(packId: string): Promise<void> {
    const pack = await this.packOf(packId)
    if (!pack) throw new Error('no such pack')
    if (pack.builtin) throw new Error('the shipped pack cannot be removed')
    await rm(pack.dir, { recursive: true, force: true })
    this.cached = undefined
  }

  // ---- the routes ----------------------------------------------------------------------

  private readonly handle = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1')
    const route = url.pathname.slice(STICKERS_PREFIX.length) || '/'
    if (!sameOrigin(req)) return send(res, 403, { error: { code: 'forbidden', message: 'Same-origin requests only' } })
    try {
      if (req.method === 'GET' && route === '/list') return send(res, 200, await this.catalogue())
      if (req.method === 'GET' && route.startsWith('/img/')) return await this.serveImage(route.slice('/img/'.length), res)
      if (req.method === 'POST' && route === '/add') {
        const body = await json(req, STICKER_MAX_BYTES + 64 * 1024)
        const packId = String(body['packId'] ?? '')
        const data = String(body['data'] ?? '')
        const name = String(body['name'] ?? '')
        const filename = String(body['filename'] ?? '')
        return send(res, 200, await this.addSticker(packId, data, name, filename))
      }
      if (req.method === 'POST' && route === '/remove') {
        const body = await json(req)
        await this.removeSticker(String(body['packId'] ?? ''), String(body['id'] ?? ''))
        return send(res, 200, { ok: true })
      }
      if (req.method === 'POST' && route === '/rename') {
        const body = await json(req)
        await this.renameSticker(String(body['packId'] ?? ''), String(body['id'] ?? ''), String(body['name'] ?? ''))
        return send(res, 200, { ok: true })
      }
      if (req.method === 'POST' && route === '/pack') {
        const body = await json(req)
        if (body['remove'] === true) {
          await this.removePack(String(body['id'] ?? ''))
          return send(res, 200, { ok: true })
        }
        if (typeof body['name'] === 'string' && typeof body['id'] === 'string' && body['id']) {
          await this.renamePack(String(body['id']), body['name'])
          return send(res, 200, { ok: true })
        }
        const pack = await this.createPack(String(body['name'] ?? ''))
        return send(res, 200, { id: pack.id, name: pack.name })
      }
      return send(res, 404, { error: { code: 'not_found', message: 'No such route' } })
    } catch (error: unknown) {
      return send(res, 400, { error: { code: 'bad_request', message: message(error) } })
    }
  }

  /** One picture, only when the pack's manifest names it. */
  private async serveImage(rest: string, res: ServerResponse): Promise<void> {
    const [packId = '', filename = ''] = rest.split('/').map((part) => decodeURIComponent(part))
    if (!SAFE.test(packId) || !SAFE.test(filename)) return send(res, 404)
    const pack = await this.packOf(packId)
    const sticker = pack?.stickers.find((item) => item.filename === filename)
    if (!pack || !sticker) return send(res, 404)
    const path = join(pack.dir, sticker.filename)
    const info = await stat(path).catch(() => undefined)
    if (!info) return send(res, 404)
    const type = STICKER_TYPES[ext(filename)] ?? 'application/octet-stream'
    res.writeHead(200, { 'content-type': type, 'content-length': info.size, 'cache-control': 'public, max-age=86400' })
    createReadStream(path).pipe(res)
  }

  /** The prompt paragraph for the model, or null when there is nothing to send. */
  async promptParagraph(): Promise<string | null> {
    return memePrompt(await this.catalogue())
  }

  /** The same paragraph, synchronously, from the catalogue last read (the prompt assembly). */
  paragraph(): string | null {
    return this.promptText
  }
}

function ext(file: string): string {
  const at = file.lastIndexOf('.')
  return at < 0 ? '' : file.slice(at).toLowerCase()
}

function stem(file: string): string {
  const at = file.lastIndexOf('.')
  return at <= 0 ? file : file.slice(0, at)
}
