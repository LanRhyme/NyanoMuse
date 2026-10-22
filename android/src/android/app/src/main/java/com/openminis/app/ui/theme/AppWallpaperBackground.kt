package com.openminis.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/** Provides whether a custom app wallpaper is currently configured and present */
val LocalHasAppWallpaper = compositionLocalOf { false }

/**
 * Reusable wallpaper background wrapper for any screen or navigation root.
 * When a custom wallpaper is configured in Appearance settings, renders the wallpaper
 * with user's blur & dim parameters.
 */
@Composable
fun AppWallpaperBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var wallpaperPath by remember { mutableStateOf(ChatWallpaperManager.getWallpaperPath(context)) }
    var wallpaperBlur by remember { mutableFloatStateOf(ChatWallpaperManager.getWallpaperBlur(context)) }
    var wallpaperDim by remember { mutableFloatStateOf(ChatWallpaperManager.getWallpaperDim(context)) }

    val prefs = remember { context.getSharedPreferences(ChatWallpaperManager.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ChatWallpaperManager.KEY_WALLPAPER_PATH ||
                key == ChatWallpaperManager.KEY_WALLPAPER_BLUR ||
                key == ChatWallpaperManager.KEY_WALLPAPER_DIM
            ) {
                wallpaperPath = ChatWallpaperManager.getWallpaperPath(context)
                wallpaperBlur = ChatWallpaperManager.getWallpaperBlur(context)
                wallpaperDim = ChatWallpaperManager.getWallpaperDim(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val wallpaperFile = remember(wallpaperPath) { wallpaperPath?.let { File(it) }?.takeIf { it.exists() } }
    val hasWallpaper = wallpaperFile != null

    Box(modifier = modifier.fillMaxSize().background(ChatColors.background)) {
        if (wallpaperFile != null) {
            AsyncImage(
                model = wallpaperFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .let { if (wallpaperBlur > 0f) it.blur(wallpaperBlur.dp) else it },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = wallpaperDim)),
            )
        }
        CompositionLocalProvider(LocalHasAppWallpaper provides hasWallpaper) {
            content()
        }
    }
}
