package io.github.nanomuse.ui.sticker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.R
import io.github.nanomuse.sticker.StickerItem
import io.github.nanomuse.sticker.StickerManager
import io.github.nanomuse.sticker.StickerPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bottom picker panel for multi-pack memes & stickers.
 * Supports groups (QQ, Dafeiyu, custom packs), searching, adding stickers, and creating groups.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPickerSheet(
    onDismiss: () -> Unit,
    onPickSticker: (StickerItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }

    val packs = remember(context, refreshKey) { StickerManager.getPacks(context) }
    var selectedPackId by remember { mutableStateOf<String?>("custom_qq") }
    var searchQuery by remember { mutableStateOf("") }
    var filterGifOnly by remember { mutableStateOf(false) }

    var showCreatePackDialog by remember { mutableStateOf(false) }
    var newPackName by remember { mutableStateOf("") }

    var stickerToDelete by remember { mutableStateOf<StickerItem?>(null) }
    var showDeletePackDialog by remember { mutableStateOf(false) }

    val currentPack = remember(packs, selectedPackId) {
        packs.firstOrNull { it.id == selectedPackId }
    }

    val stickers = remember(context, selectedPackId, refreshKey) {
        StickerManager.getStickers(selectedPackId, context)
    }

    val filtered = remember(stickers, searchQuery, filterGifOnly) {
        stickers.filter { s ->
            if (filterGifOnly && !s.isGif) return@filter false
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                s.name.lowercase().contains(q) ||
                s.seq.contains(q) ||
                s.keywords.lowercase().contains(q)
            } else {
                true
            }
        }
    }

    // Photo picker for adding new stickers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val targetPackId = selectedPackId ?: packs.firstOrNull()?.id ?: "custom_qq"
                    for (uri in uris) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val origName = uri.lastPathSegment ?: "sticker.png"
                                StickerManager.addStickerToPack(
                                    packId = targetPackId,
                                    inputStream = stream,
                                    originalName = origName,
                                    context = context,
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
                refreshKey++
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header: title, group action, filter, close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.nm_attach_stickers),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "(${filtered.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // If a custom group is selected, show a delete group icon
                    if (currentPack != null && !currentPack.isBuiltin) {
                        IconButton(
                            onClick = { showDeletePackDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete group",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filterGifOnly,
                        onClick = { filterGifOnly = !filterGifOnly },
                        label = { Text("GIF", fontSize = 12.sp) }
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Pack (Group) Tabs
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "全部" Chip
                item(key = "all_packs") {
                    FilterChip(
                        selected = selectedPackId == null,
                        onClick = { selectedPackId = null },
                        label = { Text(stringResource(R.string.nm_stickers_all), fontSize = 12.sp) }
                    )
                }

                // Pack Chips
                items(packs, key = { it.id }) { p ->
                    FilterChip(
                        selected = selectedPackId == p.id,
                        onClick = { selectedPackId = p.id },
                        label = {
                            Text(
                                text = "${p.name} (${p.count})",
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    )
                }

                // "+ 新建分组" Chip
                item(key = "create_pack") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                newPackName = ""
                                showCreatePackDialog = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.nm_stickers_add_group),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                placeholder = {
                    Text(stringResource(R.string.nm_stickers_search), fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color.Transparent,
                )
            )

            Spacer(Modifier.height(10.dp))

            // Stickers Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 72.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                // Slot 0: "添加表情" Tile
                item(key = "add_sticker_slot") {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = stringResource(R.string.nm_stickers_add_sticker),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Sticker Items
                items(filtered, key = { "${it.packId}_${it.id}" }) { s ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(
                                onClick = { onPickSticker(s) },
                                onLongClick = { stickerToDelete = s }
                            )
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(s.filePath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = s.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                            )
                            if (s.isGif) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                            RoundedCornerShape(3.dp)
                                        )
                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        "GIF",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        if (s.name.isNotBlank()) {
                            Text(
                                text = s.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog: Create New Group
    if (showCreatePackDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePackDialog = false },
            title = { Text(stringResource(R.string.nm_stickers_add_group)) },
            text = {
                OutlinedTextField(
                    value = newPackName,
                    onValueChange = { newPackName = it },
                    label = { Text(stringResource(R.string.nm_stickers_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newPackName.trim()
                        if (name.isNotEmpty()) {
                            val newPack = StickerManager.createPack(name, context)
                            if (newPack != null) {
                                selectedPackId = newPack.id
                            }
                            refreshKey++
                        }
                        showCreatePackDialog = false
                    }
                ) {
                    Text(stringResource(R.string.nm_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePackDialog = false }) {
                    Text(stringResource(R.string.nm_cancel))
                }
            }
        )
    }

    // Dialog: Delete Sticker
    stickerToDelete?.let { stk ->
        AlertDialog(
            onDismissRequest = { stickerToDelete = null },
            title = { Text(stringResource(R.string.nm_stickers_delete_confirm)) },
            text = {
                Text(stk.name.ifEmpty { stk.filename })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        StickerManager.deleteSticker(stk, context)
                        stickerToDelete = null
                        refreshKey++
                    }
                ) {
                    Text(stringResource(R.string.nm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { stickerToDelete = null }) {
                    Text(stringResource(R.string.nm_cancel))
                }
            }
        )
    }

    // Dialog: Delete Custom Pack
    if (showDeletePackDialog && currentPack != null) {
        AlertDialog(
            onDismissRequest = { showDeletePackDialog = false },
            title = { Text(stringResource(R.string.nm_stickers_delete_group_confirm)) },
            text = {
                Text(currentPack.name)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        StickerManager.deletePack(currentPack.id, context)
                        selectedPackId = null
                        showDeletePackDialog = false
                        refreshKey++
                    }
                ) {
                    Text(stringResource(R.string.nm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePackDialog = false }) {
                    Text(stringResource(R.string.nm_cancel))
                }
            }
        )
    }
}
