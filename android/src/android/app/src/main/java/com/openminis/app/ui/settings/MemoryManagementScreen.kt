package com.openminis.app.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.MinisTextButton
import io.github.nanomuse.memory.MemoryEntity
import io.github.nanomuse.ui.home.MuseTones
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modernized Settings-level Memory Management Screen.
 * Backed by LocalMemoryDb (structured cards, CJK instant search, hot/cold filtering, category filtering)
 * with a collapsible bottom drawer for underlying GLOBAL.md and daily logs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var globalMemoryOn by remember {
        mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    var memories by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var activeCount by remember { mutableStateOf(0) }
    var archivedCount by remember { mutableStateOf(0) }
    var files by remember { mutableStateOf<List<MemoryRepository.MemoryFileInfo>>(emptyList()) }

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") } // all, active, archived
    var categoryFilter by remember { mutableStateOf("all") } // all, preference, habit, device, profile, custom

    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteConfirmMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleteFileName by remember { mutableStateOf<String?>(null) }
    var legacyExpanded by remember { mutableStateOf(false) }

    fun refresh() {
        memories = memoryRepository.getAllStructuredMemories(includeArchived = true)
        activeCount = memoryRepository.activeStructuredCount()
        archivedCount = memoryRepository.archivedStructuredCount()
        files = memoryRepository.listAllFiles()
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    // Filter memories based on search query, status, and category
    val filteredMemories = remember(memories, searchQuery, statusFilter, categoryFilter) {
        val baseList = if (searchQuery.isNotBlank()) {
            val matchIds = memoryRepository.searchStructuredMemories(searchQuery, limit = 100, includeArchived = true)
                .map { it.item.id }
                .toSet()
            memories.filter { it.id in matchIds }
        } else {
            memories
        }

        baseList.filter { item ->
            if (statusFilter == "active" && item.isArchived) return@filter false
            if (statusFilter == "archived" && !item.isArchived) return@filter false
            if (categoryFilter != "all" && item.category != categoryFilter) return@filter false
            true
        }.sortedWith(compareByDescending<MemoryEntity> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    SettingsScaffold(title = stringResource(R.string.memory_title), onBack = onBack) {
        // 1. Global toggle
        SettingsSection(
            header = stringResource(R.string.settings_memory_global_header),
            footer = stringResource(R.string.settings_memory_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_global_enabled_title),
                subtitle = stringResource(R.string.settings_memory_global_enabled_subtitle),
                checked = globalMemoryOn,
                onCheckedChange = { newValue ->
                    globalMemoryOn = newValue
                    MemoryGlobalPrefs.setGlobalEnabled(context, newValue)
                },
                showDivider = false,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Dashboard summary cards (Active / Archived / Total)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MemoryStatCard(
                modifier = Modifier.weight(1f),
                count = activeCount,
                label = stringResource(R.string.memory_filter_active),
                tint = if (MuseTones.isDark) Color(0xFFA4B4D6) else Color(0xFF52678F)
            )
            MemoryStatCard(
                modifier = Modifier.weight(1f),
                count = archivedCount,
                label = stringResource(R.string.memory_filter_archived),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MemoryStatCard(
                modifier = Modifier.weight(1f),
                count = activeCount + archivedCount,
                label = stringResource(R.string.memory_filter_all),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Search Bar & Add Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.memory_search_placeholder), fontSize = 13.5.sp) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MuseTones.surface,
                    unfocusedContainerColor = MuseTones.surface,
                    focusedBorderColor = MuseTones.action,
                    unfocusedBorderColor = MuseTones.hairline,
                )
            )

            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MuseTones.surface)
                    .border(1.dp, MuseTones.hairline, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.memory_add), tint = MuseTones.action)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. Status Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = statusFilter == "all",
                onClick = { statusFilter = "all" },
                label = { Text(stringResource(R.string.memory_filter_all), fontSize = 12.sp) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MuseTones.fill,
                    containerColor = MuseTones.surface,
                )
            )
            FilterChip(
                selected = statusFilter == "active",
                onClick = { statusFilter = "active" },
                label = { Text("${stringResource(R.string.memory_filter_active)} ($activeCount)", fontSize = 12.sp) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MuseTones.fill,
                    containerColor = MuseTones.surface,
                )
            )
            FilterChip(
                selected = statusFilter == "archived",
                onClick = { statusFilter = "archived" },
                label = { Text("${stringResource(R.string.memory_filter_archived)} ($archivedCount)", fontSize = 12.sp) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MuseTones.fill,
                    containerColor = MuseTones.surface,
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 5. Category Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val categories = listOf("all", "preference", "habit", "device", "profile", "custom")
            categories.forEach { cat ->
                val label = when (cat) {
                    "preference" -> stringResource(R.string.memory_cat_preference)
                    "habit" -> stringResource(R.string.memory_cat_habit)
                    "device" -> stringResource(R.string.memory_cat_device)
                    "profile" -> stringResource(R.string.memory_cat_profile)
                    "custom" -> stringResource(R.string.memory_cat_custom)
                    else -> stringResource(R.string.memory_cat_all)
                }
                FilterChip(
                    selected = categoryFilter == cat,
                    onClick = { categoryFilter = cat },
                    label = { Text(label, fontSize = 12.sp) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MuseTones.fill,
                        containerColor = MuseTones.surface,
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 6. Memory Cards Stream
        if (filteredMemories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isNotBlank()) stringResource(R.string.memory_search_no_results) else stringResource(R.string.memory_empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredMemories.forEach { mem ->
                    MemoryCardItem(
                        memory = mem,
                        onTogglePin = {
                            memoryRepository.togglePin(mem.id, !mem.isPinned)
                            refresh()
                        },
                        onToggleArchive = {
                            memoryRepository.toggleArchive(mem.id, !mem.isArchived)
                            refresh()
                        },
                        onEdit = { editingMemory = mem },
                        onDelete = { deleteConfirmMemory = mem }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 7. Collapsible Underlying Files Section (GLOBAL.md & Daily Logs)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MuseTones.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MuseTones.hairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { legacyExpanded = !legacyExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.memory_section_underlying_files),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.memory_underlying_files_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (legacyExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = legacyExpanded) {
                    Column {
                        HorizontalDivider(color = MuseTones.hairline)
                        if (files.isEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_empty_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            files.forEachIndexed { index, file ->
                                MemoryFileRow(
                                    file = file,
                                    onClick = { onFileClick(file.name, file.isGlobal) },
                                    onDelete = if (!file.isGlobal) { { deleteFileName = file.name } } else null,
                                )
                                if (index < files.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        color = MuseTones.hairline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Dialog: Add new memory
    if (showAddDialog) {
        EditMemoryDialog(
            initialContent = "",
            initialCategory = "preference",
            title = stringResource(R.string.memory_add),
            onDismiss = { showAddDialog = false },
            onSave = { content, category ->
                memoryRepository.upsertStructuredMemory(
                    content = content,
                    category = category,
                    source = "manual"
                )
                showAddDialog = false
                refresh()
            }
        )
    }

    // Dialog: Edit existing memory
    editingMemory?.let { target ->
        EditMemoryDialog(
            initialContent = target.content,
            initialCategory = target.category,
            title = stringResource(R.string.memory_edit),
            onDismiss = { editingMemory = null },
            onSave = { content, category ->
                memoryRepository.upsertStructuredMemory(
                    id = target.id,
                    content = content,
                    category = category,
                    source = target.source,
                    isPinned = target.isPinned,
                    isArchived = target.isArchived
                )
                editingMemory = null
                refresh()
            }
        )
    }

    // Dialog: Confirm structured memory delete
    deleteConfirmMemory?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmMemory = null },
            title = { Text(stringResource(R.string.memory_delete_structured_title)) },
            text = { Text(stringResource(R.string.memory_delete_structured_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    memoryRepository.deleteStructuredMemory(target.id)
                    deleteConfirmMemory = null
                    refresh()
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteConfirmMemory = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Dialog: Confirm legacy file delete
    if (deleteFileName != null) {
        AlertDialog(
            onDismissRequest = { deleteFileName = null },
            title = { Text(stringResource(R.string.memory_delete_confirm_title, deleteFileName ?: "")) },
            text = { Text(stringResource(R.string.memory_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteFileName?.let {
                        memoryRepository.deleteFile(it)
                        files = memoryRepository.listAllFiles()
                    }
                    deleteFileName = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteFileName = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryStatCard(
    count: Int,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MuseTones.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MuseTones.hairline)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tint
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MemoryCardItem(
    memory: MemoryEntity,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDark = MuseTones.isDark
    val (catBg, catText) = when (memory.category) {
        "habit" -> if (isDark) Color(0xFF1E3027) to Color(0xFF92CBB0) else Color(0xFFEBF4EF) to Color(0xFF437A5F)
        "device" -> if (isDark) Color(0xFF33271D) to Color(0xFFDEB892) else Color(0xFFF7F2EB) to Color(0xFF8C633A)
        "profile" -> if (isDark) Color(0xFF331E26) to Color(0xFFDCA3B2) else Color(0xFFF8EEF1) to Color(0xFF8E5466)
        "custom" -> if (isDark) Color(0xFF282035) to Color(0xFFC4B5DE) else Color(0xFFF2EEF8) to Color(0xFF6F5986)
        else -> if (isDark) Color(0xFF232B3A) to Color(0xFFA4B4D6) else Color(0xFFEEF2F8) to Color(0xFF52678F)
    }

    val catName = when (memory.category) {
        "habit" -> stringResource(R.string.memory_cat_habit)
        "device" -> stringResource(R.string.memory_cat_device)
        "profile" -> stringResource(R.string.memory_cat_profile)
        "custom" -> stringResource(R.string.memory_cat_custom)
        else -> stringResource(R.string.memory_cat_preference)
    }

    val sourceLabel = when (memory.source) {
        "person", "manual" -> stringResource(R.string.memory_source_manual)
        "import" -> stringResource(R.string.memory_source_import)
        else -> stringResource(R.string.memory_source_agent)
    }

    val dateStr = remember(memory.updatedAt) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        fmt.format(Date(memory.updatedAt))
    }

    val borderColor = if (memory.isPinned) MuseTones.action else MuseTones.hairline
    val borderWidth = if (memory.isPinned) 1.5.dp else 1.dp
    val alpha = if (memory.isArchived) 0.72f else 1f

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MuseTones.surface.copy(alpha = alpha),
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Category pill, Source pill, Pin badge, Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(catBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(text = catName, color = catText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MuseTones.fill)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = sourceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp)
                }

                if (memory.isPinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(Icons.Outlined.PushPin, contentDescription = null, tint = MuseTones.action, modifier = Modifier.size(12.dp))
                        Text(text = stringResource(R.string.memory_pin), color = MuseTones.action, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = if (memory.isPinned) stringResource(R.string.memory_unpin) else stringResource(R.string.memory_pin),
                        tint = if (memory.isPinned) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(onClick = onToggleArchive, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = if (memory.isArchived) stringResource(R.string.memory_restore) else stringResource(R.string.memory_archive),
                        tint = if (memory.isArchived) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.memory_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditMemoryDialog(
    initialContent: String,
    initialCategory: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (content: String, category: String) -> Unit,
) {
    var content by remember { mutableStateOf(initialContent) }
    var category by remember { mutableStateOf(initialCategory) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Category chips selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val categories = listOf("preference", "habit", "device", "profile", "custom")
                    categories.forEach { cat ->
                        val label = when (cat) {
                            "habit" -> stringResource(R.string.memory_cat_habit)
                            "device" -> stringResource(R.string.memory_cat_device)
                            "profile" -> stringResource(R.string.memory_cat_profile)
                            "custom" -> stringResource(R.string.memory_cat_custom)
                            else -> stringResource(R.string.memory_cat_preference)
                        }
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(label, fontSize = 11.5.sp) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MuseTones.fill,
                                containerColor = MuseTones.surface,
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text(stringResource(R.string.memory_add_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MuseTones.action,
                        unfocusedBorderColor = MuseTones.hairline,
                    )
                )
            }
        },
        confirmButton = {
            MinisTextButton(
                onClick = { if (content.isNotBlank()) onSave(content.trim(), category) },
                enabled = content.isNotBlank()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun MemoryFileRow(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (file.preview.isNotBlank()) {
                Text(
                    file.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Full-page memory file editor, matching iOS MemoryFileEditView.
 * Monospaced text. The Save button stays ALWAYS visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val savedToastText = stringResource(R.string.memory_save_toast)

    LaunchedEffect(fileName) {
        content = memoryRepository.readFile(fileName)
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            io.github.nanomuse.ui.muse.MuseTopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    MinisTextButton(onClick = {
                        try {
                            memoryRepository.saveFile(fileName, content)
                            saveError = null
                            android.widget.Toast.makeText(
                                context,
                                savedToastText,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            saveError = e.message
                        }
                    }) {
                        Text(stringResource(R.string.common_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            com.openminis.app.ui.components.DialogTextField(
                value = content,
                onValueChange = { content = it },
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                placeholder = stringResource(R.string.memory_editor_placeholder),
                modifier = Modifier.weight(1f),
            )

            if (isGlobal) {
                Text(
                    stringResource(R.string.memory_global_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (saveError != null) {
                Text(
                    saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

