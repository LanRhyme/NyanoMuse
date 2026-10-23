package com.openminis.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ChatWallpaperManager {
    const val PREFS_NAME = "appearance_prefs"
    const val KEY_THEME_PALETTE = "appearance_theme_palette"
    const val KEY_WALLPAPER_PATH = "appearance_wallpaper_path"
    const val KEY_WALLPAPER_DIM = "appearance_wallpaper_dim"
    const val KEY_WALLPAPER_BLUR = "appearance_wallpaper_blur"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPalette(context: Context): ThemePalette {
        val id = prefs(context).getString(KEY_THEME_PALETTE, ThemePalette.MORANDI.id)
        return ThemePalette.fromId(id)
    }

    fun setPalette(context: Context, palette: ThemePalette) {
        prefs(context).edit().putString(KEY_THEME_PALETTE, palette.id).apply()
    }

    fun getWallpaperPath(context: Context): String? {
        val path = prefs(context).getString(KEY_WALLPAPER_PATH, null) ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) path else null
    }

    fun getWallpaperDim(context: Context): Float {
        return prefs(context).getFloat(KEY_WALLPAPER_DIM, 0.25f).coerceIn(0f, 0.85f)
    }

    fun setWallpaperDim(context: Context, dim: Float) {
        prefs(context).edit().putFloat(KEY_WALLPAPER_DIM, dim.coerceIn(0f, 0.85f)).apply()
    }

    fun getWallpaperBlur(context: Context): Float {
        return prefs(context).getFloat(KEY_WALLPAPER_BLUR, 4.0f).coerceIn(0f, 25f)
    }

    fun setWallpaperBlur(context: Context, blur: Float) {
        prefs(context).edit().putFloat(KEY_WALLPAPER_BLUR, blur.coerceIn(0f, 25f)).apply()
    }

    fun saveWallpaperFromFile(context: Context, file: File): String? {
        return try {
            val wallpaperDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
            val destFile = File(wallpaperDir, "chat_wallpaper_${System.currentTimeMillis()}.jpg")

            file.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Remove old wallpapers
            wallpaperDir.listFiles()?.forEach { f ->
                if (f.absolutePath != destFile.absolutePath) {
                    f.delete()
                }
            }

            val path = destFile.absolutePath
            prefs(context).edit().putString(KEY_WALLPAPER_PATH, path).apply()
            path
        } catch (e: Exception) {
            null
        }
    }

    fun saveWallpaperFromUri(context: Context, uri: Uri): String? {
        return try {
            val wallpaperDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
            val destFile = File(wallpaperDir, "chat_wallpaper_${System.currentTimeMillis()}.jpg")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Remove old wallpapers
            wallpaperDir.listFiles()?.forEach { f ->
                if (f.absolutePath != destFile.absolutePath) {
                    f.delete()
                }
            }

            val path = destFile.absolutePath
            prefs(context).edit().putString(KEY_WALLPAPER_PATH, path).apply()
            path
        } catch (e: Exception) {
            null
        }
    }

    fun clearWallpaper(context: Context) {
        try {
            val wallpaperDir = File(context.filesDir, "wallpaper")
            wallpaperDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
        prefs(context).edit().remove(KEY_WALLPAPER_PATH).apply()
    }
}
