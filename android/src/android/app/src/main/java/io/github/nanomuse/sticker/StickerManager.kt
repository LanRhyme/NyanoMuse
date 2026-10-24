package io.github.nanomuse.sticker

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Native Custom Meme & Sticker Manager for NyanoMuse.
 * Manages local sticker assets, catalog prompt injection, and tag extraction.
 */
data class StickerItem(
    val id: String,
    val seq: String,
    val name: String,
    val filename: String,
    val format: String,
    val filePath: String,
) {
    val isGif: Boolean get() = format.equals("gif", ignoreCase = true)
}

object StickerManager {

    private val MEME_REGEX = Regex(
        """<\s*nm:meme\s+(?:id|name)=["']?([^"'>\s/]+)["']?[^>]*/>|\[(?:表情|meme)\s*:\s*([^\]]+)\]""",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    var appContext: Context? = null

    @Volatile
    private var cachedStickers: List<StickerItem>? = null
    private var lastMtime: Long = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun resolveStickersDir(context: Context? = appContext): File? {
        val candidates = listOf(
            context?.getExternalFilesDir("stickers"),
            File("/sdcard/Android/data/top.lanrhyme.nyanomuse/files/stickers"),
            File("/sdcard/Pictures/NyanoMuse/stickers"),
            context?.let { File(it.filesDir, "stickers") },
        )
        for (c in candidates) {
            if (c != null && c.isDirectory && File(c, "manifest.json").exists()) {
                return c
            }
        }
        return candidates.firstOrNull { it != null && it.exists() }
    }

    fun getStickers(context: Context? = appContext): List<StickerItem> {
        val dir = resolveStickersDir(context) ?: return emptyList()
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return emptyList()

        val mtime = manifestFile.lastModified()
        if (cachedStickers != null && mtime == lastMtime) {
            return cachedStickers ?: emptyList()
        }

        return try {
            val jsonStr = manifestFile.readText(Charsets.UTF_8)
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("stickers") ?: return emptyList()
            val list = mutableListOf<StickerItem>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                val filename = obj.optString("filename", "")
                val format = obj.optString("format", "jpg")
                val name = obj.optString("name", "").trim()
                val seq = String.format("%03d", i + 1)
                val file = File(dir, filename)

                list.add(
                    StickerItem(
                        id = id,
                        seq = seq,
                        name = name,
                        filename = filename,
                        format = format,
                        filePath = file.absolutePath,
                    )
                )
            }
            lastMtime = mtime
            cachedStickers = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getNamedStickers(context: Context? = appContext): List<StickerItem> {
        return getStickers(context).filter { it.name.isNotBlank() }
    }

    fun findSticker(query: String, context: Context? = appContext): StickerItem? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val all = getStickers(context)
        if (all.isEmpty()) return null

        // 1. Exact seq match ("001" or "1")
        val bySeq = all.firstOrNull {
            it.seq == q || it.seq.trimStart('0') == q.trimStart('0')
        }
        if (bySeq != null) return bySeq

        // 2. Hash ID prefix match
        val byId = all.firstOrNull { it.id.startsWith(q, ignoreCase = true) }
        if (byId != null) return byId

        // 3. Exact name match
        val byNameExact = all.firstOrNull { it.name.equals(q, ignoreCase = true) }
        if (byNameExact != null) return byNameExact

        // 4. Substring name match
        return all.firstOrNull { it.name.contains(q, ignoreCase = true) }
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
        val named = getNamedStickers(context)
        if (named.isEmpty()) return null

        val catalog = named.joinToString(" ") { "[${it.seq}:${it.name}]" }
        return """
            [Mobile Meme & Custom Sticker Guidelines / 表情包发送规范]
            - 触发场景：当聊天氛围轻松、幽默互动、调侃、卖萌或吐槽适合发表情时，可以在多气泡切分处单独发送表情标签 `<nm:meme id="序号"/>`（单次回合最多发 1 张表情包，可单独成气泡或接在文字气泡后）。
            - 范例：
              哈哈确实！<nm:bubble/><nm:meme id="001"/>
              太真实了<nm:bubble/><nm:meme id="005"/><nm:bubble/>我明天也这么干
            - 可选表情清单（序号:名称）：
              $catalog
            - 边界例外：仅在真正契合语境时自然使用，写代码、长篇技术解答、严肃讨论中严禁使用。严禁使用清单之外的序号。
        """.trimIndent()
    }
}
