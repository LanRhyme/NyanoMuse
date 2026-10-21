package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import com.openminis.app.data.repository.MemoryRepository

/**
 * nanoMuse: the memory screen is ours (the structured store, the hot and cold lists, the
 * categories, and the search over what the list leaves out). This is the call into it; the
 * screen itself lives in `io.github.nanomuse.ui.settings`.
 */

@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
) {
    io.github.nanomuse.ui.settings.MemoryManagementScreenContent(memoryRepository, onBack, onFileClick)
}

@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    io.github.nanomuse.ui.settings.MemoryFileEditScreenContent(fileName, isGlobal, memoryRepository, onBack)
}
