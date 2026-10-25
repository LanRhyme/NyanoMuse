package io.github.nanomuse.sticker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Sticker Pack (Group) metadata.
 */
data class StickerPack(
    val id: String,
    val name: String,
    val dir: File,
    val count: Int,
    val isBuiltin: Boolean = false,
)

/**
 * Native Custom Meme & Sticker Item.
 */
data class StickerItem(
    val id: String,
    val seq: String,
    val name: String,
    val filename: String,
    val format: String,
    val filePath: String,
    val packId: String = "default",
    val packName: String = "我的表情包",
    val keywords: String = "",
) {
    val isGif: Boolean get() = format.equals("gif", ignoreCase = true)
}

/**
 * Native Custom Meme & Sticker Manager for NyanoMuse.
 * Supports multiple packs (built-in QQ, Dafeiyu, and user-created custom packs).
 */
object StickerManager {

    private val MEME_REGEX = Regex(
        """<\s*nm:meme\s+(?:id|name)=["']?([^"'>\s/]+)["']?[^>]*/>|\[(?:表情|meme)\s*:\s*([^\]]+)\]""",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    var appContext: Context? = null

    @Volatile
    private var cachedPacks: List<StickerPack>? = null
    @Volatile
    private var cachedStickers: List<StickerItem>? = null
    private var lastScanTime: Long = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun invalidateCache() {
        cachedPacks = null
        cachedStickers = null
        lastScanTime = 0L
    }

    private fun resolveRootStickersDir(context: Context? = appContext): File? {
        val candidates = listOf(
            context?.getExternalFilesDir("stickers"),
            File("/sdcard/Android/data/top.lanrhyme.nyanomuse/files/stickers"),
            File("/sdcard/Pictures/NyanoMuse/stickers"),
            context?.let { File(it.filesDir, "stickers") },
        )
        for (c in candidates) {
            if (c != null && c.exists() && c.isDirectory) {
                return c
            }
        }
        return candidates.firstOrNull { it != null }
    }

    private fun copyAssetFolder(context: Context, assetFolder: String, destDir: File) {
        try {
            val assets = context.assets
            val files = assets.list(assetFolder) ?: return
            if (!destDir.exists()) destDir.mkdirs()
            for (f in files) {
                val subAsset = "$assetFolder/$f"
                val subFiles = assets.list(subAsset)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    copyAssetFolder(context, subAsset, File(destDir, f))
                } else {
                    val destFile = File(destDir, f)
                    if (!destFile.exists()) {
                        assets.open(subAsset).use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun ensurePacksDirectory(context: Context? = appContext): File? {
        val rootDir = resolveRootStickersDir(context) ?: return null
        if (!rootDir.exists()) rootDir.mkdirs()

        val packsDir = File(rootDir, "packs")
        if (!packsDir.exists()) packsDir.mkdirs()

        // 1. Migrate legacy single-directory stickers if present
        val legacyManifest = File(rootDir, "manifest.json")
        val customQqPack = File(packsDir, "custom_qq")
        if (legacyManifest.exists() && !customQqPack.exists()) {
            try {
                customQqPack.mkdirs()
                val jsonStr = legacyManifest.readText(Charsets.UTF_8)
                val rootJson = JSONObject(jsonStr)
                rootJson.put("id", "custom_qq")
                rootJson.put("name", "我的表情包")
                File(customQqPack, "manifest.json").writeText(rootJson.toString(2), Charsets.UTF_8)

                val arr = rootJson.optJSONArray("stickers")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val fn = item.optString("filename", "")
                        if (fn.isNotBlank()) {
                            val src = File(rootDir, fn)
                            val dst = File(customQqPack, fn)
                            if (src.exists() && !dst.exists()) {
                                src.renameTo(dst)
                            }
                        }
                    }
                }
                legacyManifest.delete()
            } catch (_: Exception) {}
        }

        // 2. Extract built-in Dafeiyu pack from assets if not present
        val dafeiyuPack = File(packsDir, "dafeiyu")
        if (!dafeiyuPack.exists() && context != null) {
            copyAssetFolder(context, "nanomuse/stickers/dafeiyu", dafeiyuPack)
        }

        return packsDir
    }

    @Synchronized
    fun getPacks(context: Context? = appContext): List<StickerPack> {
        val packsDir = ensurePacksDirectory(context) ?: return emptyList()
        val mtime = packsDir.lastModified()
        if (cachedPacks != null && mtime == lastScanTime) {
            return cachedPacks ?: emptyList()
        }

        val dirs = packsDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        val result = mutableListOf<StickerPack>()

        for (dir in dirs) {
            val manifestFile = File(dir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    val id = root.optString("id", dir.name)
                    val name = root.optString("name", dir.name)
                    val arr = root.optJSONArray("stickers")
                    val count = arr?.length() ?: 0
                    val isBuiltin = id == "custom_qq" || id == "dafeiyu"
                    result.add(
                        StickerPack(
                            id = id,
                            name = name,
                            dir = dir,
                            count = count,
                            isBuiltin = isBuiltin,
                        )
                    )
                } catch (_: Exception) {
                    result.add(StickerPack(id = dir.name, name = dir.name, dir = dir, count = 0))
                }
            } else {
                val imageFiles = dir.listFiles { f ->
                    val ext = f.extension.lowercase()
                    ext in listOf("jpg", "jpeg", "png", "gif", "webp")
                } ?: emptyArray()
                result.add(
                    StickerPack(
                        id = dir.name,
                        name = dir.name,
                        dir = dir,
                        count = imageFiles.size,
                        isBuiltin = false
                    )
                )
            }
        }

        // Sort: custom_qq first, dafeiyu second, then user packs alphabetically
        result.sortWith(Comparator { a, b ->
            when {
                a.id == "custom_qq" -> -1
                b.id == "custom_qq" -> 1
                a.id == "dafeiyu" -> -1
                b.id == "dafeiyu" -> 1
                else -> a.name.compareTo(b.name, ignoreCase = true)
            }
        })

        lastScanTime = mtime
        cachedPacks = result
        return result
    }

    @Synchronized
    fun getStickers(packId: String? = null, context: Context? = appContext): List<StickerItem> {
        val packs = getPacks(context)
        if (packs.isEmpty()) return emptyList()

        if (cachedStickers != null && packId == null) {
            return cachedStickers ?: emptyList()
        }

        val allItems = mutableListOf<StickerItem>()
        var globalSeq = 1

        for (pack in packs) {
            if (packId != null && pack.id != packId) continue

            val manifestFile = File(pack.dir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    val arr = root.optJSONArray("stickers")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val id = obj.optString("id", "")
                            val filename = obj.optString("filename", "")
                            val format = obj.optString("format", "jpg")
                            val name = obj.optString("name", "").trim()
                            val keywords = obj.optString("keywords", "")
                            val seq = String.format("%03d", globalSeq++)
                            val file = File(pack.dir, filename)

                            allItems.add(
                                StickerItem(
                                    id = id,
                                    seq = seq,
                                    name = name,
                                    filename = filename,
                                    format = format,
                                    filePath = file.absolutePath,
                                    packId = pack.id,
                                    packName = pack.name,
                                    keywords = keywords,
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            } else {
                // Folder without manifest: list raw images
                val imageFiles = pack.dir.listFiles { f ->
                    val ext = f.extension.lowercase()
                    ext in listOf("jpg", "jpeg", "png", "gif", "webp")
                }?.sortedBy { it.name } ?: emptyList()

                for (img in imageFiles) {
                    val seq = String.format("%03d", globalSeq++)
                    val stem = img.nameWithoutExtension
                    allItems.add(
                        StickerItem(
                            id = "${pack.id}_$stem",
                            seq = seq,
                            name = stem,
                            filename = img.name,
                            format = img.extension,
                            filePath = img.absolutePath,
                            packId = pack.id,
                            packName = pack.name,
                        )
                    )
                }
            }
        }

        if (packId == null) {
            cachedStickers = allItems
        }
        return allItems
    }

    fun getNamedStickers(context: Context? = appContext): List<StickerItem> {
        return getStickers(null, context).filter { it.name.isNotBlank() }
    }

    fun findSticker(query: String, context: Context? = appContext): StickerItem? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val all = getStickers(null, context)
        if (all.isEmpty()) return null

        // 1. Exact seq match ("001" or "1")
        val bySeq = all.firstOrNull {
            it.seq == q || it.seq.trimStart('0') == q.trimStart('0')
        }
        if (bySeq != null) return bySeq

        // 2. Hash ID or slug prefix match ("dfy_deyi" or "4df7c337")
        val byId = all.firstOrNull { it.id.equals(q, ignoreCase = true) || it.id.startsWith(q, ignoreCase = true) }
        if (byId != null) return byId

        // 3. Exact name match ("大肥鱼-得意" or "猫猫贴贴")
        val byNameExact = all.firstOrNull { it.name.equals(q, ignoreCase = true) }
        if (byNameExact != null) return byNameExact

        // 4. Substring name match
        val byNameSub = all.firstOrNull { it.name.contains(q, ignoreCase = true) || q.contains(it.name, ignoreCase = true) }
        if (byNameSub != null) return byNameSub

        // 5. Keywords match
        return all.firstOrNull { it.keywords.contains(q, ignoreCase = true) }
    }

    fun createPack(name: String, context: Context? = appContext): StickerPack? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val packsDir = ensurePacksDirectory(context) ?: return null

        val packId = "pack_${System.currentTimeMillis()}"
        val packDir = File(packsDir, packId)
        if (!packDir.exists()) packDir.mkdirs()

        val manifest = JSONObject().apply {
            put("id", packId)
            put("name", trimmed)
            put("count", 0)
            put("stickers", JSONArray())
        }
        File(packDir, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
        invalidateCache()
        return StickerPack(id = packId, name = trimmed, dir = packDir, count = 0)
    }

    fun addStickerToPack(
        packId: String,
        inputStream: InputStream,
        originalName: String,
        displayName: String = "",
        context: Context? = appContext,
    ): StickerItem? {
        val packsDir = ensurePacksDirectory(context) ?: return null
        val packDir = File(packsDir, packId)
        if (!packDir.exists()) packDir.mkdirs()

        val ext = originalName.substringAfterLast('.', "png").lowercase()
        val tempFile = File.createTempFile("stk_temp", ".$ext")
        val md = MessageDigest.getInstance("MD5")
        var size = 0L

        FileOutputStream(tempFile).use { out ->
            val buf = ByteArray(8192)
            var n: Int
            while (inputStream.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                size += n
            }
        }

        val hash = md.digest().joinToString("") { "%02x".format(it) }.take(8)
        val finalFilename = "sticker_${System.currentTimeMillis()}_$hash.$ext"
        val targetFile = File(packDir, finalFilename)
        tempFile.renameTo(targetFile)

        val cleanName = displayName.trim().ifEmpty { originalName.substringBeforeLast('.') }
        val manifestFile = File(packDir, "manifest.json")
        val root = if (manifestFile.exists()) {
            try { JSONObject(manifestFile.readText(Charsets.UTF_8)) }
            catch (_: Exception) { JSONObject() }
        } else {
            JSONObject()
        }

        if (!root.has("id")) root.put("id", packId)
        if (!root.has("name")) root.put("name", packDir.name)

        val arr = root.optJSONArray("stickers") ?: JSONArray().also { root.put("stickers", it) }
        val itemObj = JSONObject().apply {
            put("id", hash)
            put("filename", finalFilename)
            put("format", ext)
            put("size", size)
            put("name", cleanName)
        }
        arr.put(itemObj)
        root.put("count", arr.length())
        manifestFile.writeText(root.toString(2), Charsets.UTF_8)

        invalidateCache()
        return StickerItem(
            id = hash,
            seq = "000",
            name = cleanName,
            filename = finalFilename,
            format = ext,
            filePath = targetFile.absolutePath,
            packId = packId,
            packName = root.optString("name", packDir.name),
        )
    }

    fun deleteSticker(sticker: StickerItem, context: Context? = appContext): Boolean {
        val file = File(sticker.filePath)
        val packDir = file.parentFile ?: return false
        val manifestFile = File(packDir, "manifest.json")

        if (file.exists()) file.delete()

        if (manifestFile.exists()) {
            try {
                val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("stickers")
                if (arr != null) {
                    val newArr = JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val fn = obj.optString("filename", "")
                        if (fn != sticker.filename) {
                            newArr.put(obj)
                        }
                    }
                    root.put("stickers", newArr)
                    root.put("count", newArr.length())
                    manifestFile.writeText(root.toString(2), Charsets.UTF_8)
                }
            } catch (_: Exception) {}
        }

        invalidateCache()
        return true
    }

    fun updateStickerName(sticker: StickerItem, newName: String, context: Context? = appContext): Boolean {
        val file = File(sticker.filePath)
        val packDir = file.parentFile ?: return false
        val manifestFile = File(packDir, "manifest.json")
        if (!manifestFile.exists()) return false

        return try {
            val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("stickers") ?: return false
            val trimmed = newName.trim()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("filename", "") == sticker.filename) {
                    obj.put("name", trimmed)
                    break
                }
            }
            manifestFile.writeText(root.toString(2), Charsets.UTF_8)
            invalidateCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deletePack(packId: String, context: Context? = appContext): Boolean {
        val packs = getPacks(context)
        val pack = packs.firstOrNull { it.id == packId } ?: return false
        if (pack.isBuiltin) return false // Protect default built-in packs from accidental deletion

        val success = pack.dir.deleteRecursively()
        invalidateCache()
        return success
    }

    fun hasMemeTag(text: String): Boolean {
        return text.contains("<nm:meme", ignoreCase = true) ||
               text.contains("[表情:", ignoreCase = true) ||
               text.contains("[meme:", ignoreCase = true)
    }

    fun findMemeMatch(text: String): MatchResult? {
        return MEME_REGEX.find(text)
    }

    fun promptParagraph(context: Context? = appContext): String? {
        if (context != null) init(context)
        val packs = getPacks(context)
        if (packs.isEmpty()) return null

        val catalogLines = mutableListOf<String>()
        var totalCount = 0

        for (pack in packs) {
            val stickers = getStickers(pack.id, context).filter { it.name.isNotBlank() }
            if (stickers.isNotEmpty()) {
                totalCount += stickers.size
                val items = stickers.take(30).joinToString(" ") { "[${it.seq}:${it.name}]" }
                catalogLines.add("  【${pack.name}】: $items")
            }
        }

        if (totalCount == 0) return null

        val catalogText = catalogLines.joinToString("\n")
        return """
            [Mobile Meme & Custom Sticker Guidelines / 表情包发送规范]
            - 触发场景：当聊天氛围轻松、幽默互动、调侃、卖萌或吐槽适合发表情时，可以在多气泡切分处单独发送表情标签 `<nm:meme id="序号或名称"/>`（单次回合最多发 1 张表情包，可单独成气泡或接在文字气泡后）。
            - 范例：
              哈哈确实！<nm:bubble/><nm:meme id="001"/>
              太真实了<nm:bubble/><nm:meme id="大肥鱼-得意"/><nm:bubble/>我明天也这么干
            - 可选表情分组清单：
            $catalogText
            - 边界例外：仅在真正契合语境时自然使用，写代码、长篇技术解答、严肃讨论中严禁使用。严禁使用清单之外的序号或名称。
        """.trimIndent()
    }
}
