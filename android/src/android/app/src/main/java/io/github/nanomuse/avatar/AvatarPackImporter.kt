package io.github.nanomuse.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.ui.avatar.AgentMood
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object AvatarPackImporter {
    private const val TAG = "AvatarPackImporter"

    data class ImportResult(
        val success: Boolean,
        val message: String,
        val importedMoods: List<AgentMood> = emptyList(),
    )

    fun importFromUri(context: Context, uri: Uri): ImportResult {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri)
            val fileName = uri.lastPathSegment?.lowercase() ?: ""

            if (mimeType == "application/zip" || mimeType == "application/x-zip-compressed" || fileName.endsWith(".zip")) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    importFromZip(context, stream)
                } ?: ImportResult(false, "无法读取文件流")
            } else {
                // Single image import
                contentResolver.openInputStream(uri)?.use { stream ->
                    val raw = BitmapFactory.decodeStream(stream) ?: return ImportResult(false, "无法解析图片内容")
                    val squareBmp = toSquare(raw, AvatarStore.STORED_PX)
                    AvatarStore.adopt(squareBmp, "Custom imported avatar", "custom", "custom")
                    ImportResult(true, "已成功导入主形象", listOf(AgentMood.IDLE))
                } ?: ImportResult(false, "无法读取图片流")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "importFromUri failed: ${e.message}")
            ImportResult(false, "导入异常: ${e.message}")
        }
    }

    fun importFromFile(context: Context, file: File): ImportResult {
        return try {
            if (!file.exists()) return ImportResult(false, "文件不存在: ${file.absolutePath}")
            if (file.name.lowercase().endsWith(".zip")) {
                file.inputStream().use { stream -> importFromZip(context, stream) }
            } else if (file.isDirectory) {
                importFromDirectory(context, file)
            } else {
                val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return ImportResult(false, "无法解析图片内容")
                val squareBmp = toSquare(raw, AvatarStore.STORED_PX)
                AvatarStore.adopt(squareBmp, "Custom imported avatar", "custom", "custom")
                ImportResult(true, "已成功导入主形象", listOf(AgentMood.IDLE))
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "importFromFile failed: ${e.message}")
            ImportResult(false, "导入异常: ${e.message}")
        }
    }

    fun importFromZip(context: Context, zipStream: InputStream): ImportResult {
        val bitmaps = mutableMapOf<String, Bitmap>()
        try {
            val zis = ZipInputStream(zipStream)
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    if (isImageExtension(name) && !name.contains("__macos") && !name.contains(".ds_store")) {
                        val bytes = zis.readBytes()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val baseName = File(name).nameWithoutExtension
                            bitmaps[baseName] = bmp
                        }
                    }
                }
                entry = zis.nextEntry
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error reading zip entries: ${e.message}")
            return ImportResult(false, "解压读取失败: ${e.message}")
        }

        if (bitmaps.isEmpty()) {
            return ImportResult(false, "ZIP 文件内未找到有效图片 (支持 PNG, JPG, WebP)")
        }

        return applyBitmaps(bitmaps)
    }

    fun importFromDirectory(context: Context, dir: File): ImportResult {
        val files = dir.listFiles() ?: return ImportResult(false, "目录为空或无法访问")
        val bitmaps = mutableMapOf<String, Bitmap>()
        for (f in files) {
            if (f.isFile && isImageExtension(f.name.lowercase())) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                if (bmp != null) {
                    bitmaps[f.nameWithoutExtension.lowercase()] = bmp
                }
            }
        }
        if (bitmaps.isEmpty()) return ImportResult(false, "目录下未找到有效图片")
        return applyBitmaps(bitmaps)
    }

    private fun applyBitmaps(bitmaps: Map<String, Bitmap>): ImportResult {
        val recognized = mutableMapOf<AgentMood, Bitmap>()
        for ((name, bmp) in bitmaps) {
            val mood = matchMood(name)
            if (mood != null) {
                recognized[mood] = toSquare(bmp, AvatarStore.STORED_PX)
            }
        }

        val baseBitmap = recognized[AgentMood.IDLE]
            ?: bitmaps.values.firstOrNull()?.let { toSquare(it, AvatarStore.STORED_PX) }
            ?: return ImportResult(false, "未能解析到任何有效立绘图像")

        AvatarStore.adopt(baseBitmap, prompt = "Custom imported avatar pack", style = "custom", model = "custom")

        val importedMoods = mutableListOf(AgentMood.IDLE)
        for ((mood, bmp) in recognized) {
            if (mood != AgentMood.IDLE) {
                AvatarStore.putMood(mood, bmp)
                importedMoods.add(mood)
            }
        }

        return ImportResult(
            success = true,
            message = "立绘导入成功：共载入 ${importedMoods.size} 个状态表情 (${importedMoods.joinToString { it.name.lowercase() }})",
            importedMoods = importedMoods,
        )
    }

    fun matchMood(name: String): AgentMood? {
        val n = name.lowercase()
        return when {
            n.contains("idle") || n.contains("base") || n.contains("normal") || n.contains("stand") || n.contains("default") -> AgentMood.IDLE
            n.contains("work") || n.contains("busy") || n.contains("type") || n.contains("focus") -> AgentMood.WORKING
            n.contains("wait") || n.contains("sleep") || n.contains("rest") || n.contains("pause") -> AgentMood.WAITING
            n.contains("happy") || n.contains("done") || n.contains("smile") || n.contains("laugh") || n.contains("cheer") -> AgentMood.HAPPY
            n.contains("error") || n.contains("fail") || n.contains("sad") || n.contains("cry") || n.contains("sweat") -> AgentMood.ERROR
            else -> null
        }
    }

    private fun isImageExtension(name: String): Boolean =
        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")

    fun toSquare(src: Bitmap, targetSize: Int): Bitmap {
        val width = src.width
        val height = src.height
        val side = minOf(width, height)
        val x = (width - side) / 2
        val y = (height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return if (side != targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        } else {
            cropped
        }
    }
}
