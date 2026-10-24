package io.github.nanomuse.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.theme.ChatColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import java.text.DateFormat
import java.util.Date

/**
 * Muse's chats drawer: the agent's name, the "main chat" row, then side chats by topic, with
 * search, settings and "new" along the bottom. The archive glyph beside the section title opens
 * the full OpenMinis session list (folders, groups, search, bulk actions) — nothing upstream is
 * lost, it just moved one tap away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SideChatDrawer(
    agentName: String,
    chatRepository: ChatRepository,
    mainSessionId: String?,
    currentSessionId: String?,
    onOpenMain: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onAllChats: () -> Unit,
    onSettings: () -> Unit,
    onSetMain: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onSystemFiles: (() -> Unit)? = null,
    onDevices: (() -> Unit)? = null,
    onCoding: (() -> Unit)? = null,
    selectedTab: HomeTab? = null,
    onSelectTab: ((HomeTab) -> Unit)? = null,
) {
    val sessions by chatRepository.observeSessions().collectAsState(initial = emptyList())
    // nanoMuse: the account's other devices, for the row under the main chat
    val hubConnected by io.github.nanomuse.hub.Hub.connected.collectAsState()
    val hubDevices by io.github.nanomuse.hub.Hub.devices.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val othersOnline = remember(hubDevices) { hubDevices.count { it.online && it.kind != "web" && it.id != io.github.nanomuse.hub.Hub.deviceId(context) } }
    // nanoMuse: computers whose runtime can show and steer coding agents (Cursor, Codex, Claude Code)
    val codingComputers = remember(hubDevices) {
        hubDevices.count { it.online && it.actions.contains("coding.sessions") && it.id != io.github.nanomuse.hub.Hub.deviceId(context) }
    }
    // nanoMuse: a chat from another device of the account is the same chat here (contract C8) — no
    // badge per chat; the turns written elsewhere carry "From Pixel 8" in the bubble instead
    val coroutineScope = rememberCoroutineScope()
    var sessionToRename by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var query by remember { mutableStateOf("") }
    val allChats = remember(sessions, query, mainSessionId) {
        sessions
            .filter { it.id != mainSessionId }
            .filter { query.isBlank() || (it.title ?: "").contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<ChatSessionEntity> { it.pinnedAt ?: 0L }.thenByDescending { it.updatedAt })
    }

    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val drawerBg = if (hazeState != null) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else ChatColors.background
    Column(
        modifier = modifier
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        style = HazeStyle(
                            backgroundColor = drawerBg,
                            blurRadius = 22.dp,
                            noiseFactor = 0.05f,
                            tints = emptyList(),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .background(drawerBg)
            .drawBehind {
                drawLine(
                    color = outlineColor,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // nanoMuse: Muse's drawer voice: the agent name big and plain, rows in
        // quiet pills, everything monochrome.
        val nmDark = ChatColors.isDark
        val nmRowSelected = if (nmDark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.06f)
        val nmInk = ChatColors.primaryText
        val nmMuted = ChatColors.secondaryText
        Text(
            text = agentName,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = nmInk,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        // 5-Tab Horizontal Navigation Capsule (Chat, Feed, Ideas, Goals, Library)
        if (onSelectTab != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (nmDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f))
                    .border(
                        width = 0.6.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeTab.entries.forEach { tabItem ->
                    val isSelected = selectedTab == tabItem
                    val tabBg by animateColorAsState(
                        targetValue = if (isSelected) {
                            if (nmDark) MaterialTheme.colorScheme.primaryContainer else Color.Black
                        } else Color.Transparent,
                        label = "drawerTabBg",
                    )
                    val tabFg by animateColorAsState(
                        targetValue = if (isSelected) {
                            if (nmDark) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                        } else nmMuted,
                        label = "drawerTabFg",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(tabBg)
                            .clickable { onSelectTab(tabItem) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isSelected) tabItem.selectedIcon else tabItem.icon,
                            contentDescription = stringResource(tabItem.label),
                            tint = tabFg,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // Prominent "+ 新建会话" action button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 2.dp)
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ChatColors.link.copy(alpha = 0.12f))
                .border(0.8.dp, ChatColors.link.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .clickable(onClick = onNewChat)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = ChatColors.link, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.nm_drawer_new_chat),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.link,
            )
        }
        Spacer(Modifier.height(4.dp))

        // nanoMuse: 主聊天 (Main Chat) 专属常驻入口
        val isMainSelected = currentSessionId != null &&
            (currentSessionId == mainSessionId || (mainSessionId != null && io.github.nanomuse.home.MainChat.isMain(context, currentSessionId)))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 2.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isMainSelected) ChatColors.link.copy(alpha = 0.12f)
                    else if (nmDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f)
                )
                .border(
                    width = if (isMainSelected) 0.8.dp else 0.5.dp,
                    color = if (isMainSelected) ChatColors.link.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(onClick = onOpenMain)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Outlined.Home,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isMainSelected) ChatColors.link else nmInk,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nm_drawer_main_chat),
                    fontSize = 15.sp,
                    fontWeight = if (isMainSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isMainSelected) ChatColors.link else nmInk,
                )
                Text(
                    text = agentName,
                    fontSize = 12.sp,
                    color = nmMuted,
                )
            }
            if (isMainSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ChatColors.link)
                )
            }
        }
        Spacer(Modifier.height(2.dp))

        // nanoMuse: Devices
        if (onDevices != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onDevices)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Outlined.Devices, contentDescription = null, modifier = Modifier.size(20.dp), tint = nmInk)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.nm_devices_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = nmInk,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        !hubConnected -> stringResource(R.string.nm_devices_off)
                        othersOnline == 0 -> stringResource(R.string.nm_hub_service_alone_short)
                        else -> pluralStringResource(R.plurals.nm_hub_service_devices, othersOnline, othersOnline)
                    },
                    fontSize = 13.sp,
                    color = nmMuted,
                )
            }
        }

        // nanoMuse: Coding
        if (onCoding != null && codingComputers > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onCoding)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(20.dp), tint = nmInk)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.nm_coding_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = nmInk,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = codingComputers.toString(),
                    fontSize = 13.sp,
                    color = nmMuted,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Search pill moved above session list
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (nmDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = nmMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.nm_drawer_search),
                        color = nmMuted,
                        fontSize = 13.5.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = nmInk, fontSize = 13.5.sp),
                    cursorBrush = SolidColor(nmInk),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = nmMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 22.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.nm_drawer_all_chats).uppercase(),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = nmMuted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAllChats, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = stringResource(R.string.nm_drawer_all_chats),
                    tint = nmMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (allChats.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = nmMuted,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.nm_drawer_empty_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nmInk,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.nm_drawer_empty_body),
                    fontSize = 13.sp,
                    color = nmMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                items(allChats, key = { it.id }) { session ->
                    val isSelected = session.id == currentSessionId || (currentSessionId == null && session.id == mainSessionId)
                    SideChatRow(
                        session = session,
                        selected = isSelected,
                        selectedFill = nmRowSelected,
                        ink = nmInk,
                        muted = nmMuted,
                        onClick = { onOpenSession(session.id) },
                        onTogglePin = {
                            coroutineScope.launch {
                                val newPin = if (session.pinnedAt != null) null else System.currentTimeMillis()
                                chatRepository.dao.updatePinnedAt(session.id, newPin)
                            }
                        },
                        onRename = {
                            renameText = session.title ?: ""
                            sessionToRename = session
                        },
                        onDelete = {
                            sessionToDelete = session
                        },
                    )
                }
            }
        }

        // Clean Bottom Toolbar: Settings & System Files
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.nm_drawer_settings),
                    tint = nmInk,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.nm_drawer_settings),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = nmInk,
                )
            }

            Spacer(Modifier.weight(1f))

            if (onSystemFiles != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSystemFiles)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = stringResource(R.string.nm_sysfiles_title),
                        tint = nmMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.nm_sysfiles_title),
                        fontSize = 13.5.sp,
                        color = nmMuted,
                    )
                }
            }
        }
    }

    if (sessionToRename != null) {
        val s = sessionToRename!!
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text(stringResource(R.string.nm_drawer_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            coroutineScope.launch {
                                chatRepository.updateSessionTitle(s.id, trimmed)
                            }
                        }
                        sessionToRename = null
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (sessionToDelete != null) {
        val s = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text(stringResource(R.string.nm_drawer_delete)) },
            text = { Text(stringResource(R.string.nm_drawer_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            chatRepository.deleteSession(s.id)
                            if (currentSessionId == s.id) {
                                onNewChat()
                            }
                        }
                        sessionToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.nm_drawer_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SideChatRow(
    session: ChatSessionEntity,
    selected: Boolean,
    selectedFill: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 1.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) selectedFill else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            if (session.pinnedAt != null) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = ChatColors.link,
                    modifier = Modifier.size(15.dp).padding(end = 2.dp),
                )
                Spacer(Modifier.size(4.dp))
            }
            if (session.category == "archived") {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = null,
                    tint = ChatColors.link.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp).padding(end = 3.dp),
                )
                Spacer(Modifier.size(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nm_drawer_untitled),
                    fontSize = 15.sp,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected || session.pinnedAt != null) FontWeight.Medium else FontWeight.Normal,
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = relativeDay(androidx.compose.ui.platform.LocalContext.current, session.updatedAt),
                fontSize = 12.sp,
                color = muted,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (session.pinnedAt != null) stringResource(R.string.nm_drawer_unpin) else stringResource(R.string.nm_drawer_pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { menu = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nm_drawer_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nm_drawer_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

internal fun relativeDay(context: android.content.Context, ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    return when {
        diff < 60_000L -> context.getString(R.string.nm_time_just_now)
        android.text.format.DateUtils.isToday(ms) -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
        diff < 7L * 24 * 3600_000L -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(ms))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}
