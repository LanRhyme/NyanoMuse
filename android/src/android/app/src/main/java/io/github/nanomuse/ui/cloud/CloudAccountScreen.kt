package io.github.nanomuse.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import io.github.nanomuse.ui.muse.setPlainText
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.nanomuse.cloud.ConfigSync
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import io.github.nanomuse.cloud.AllowanceSignal
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.cloud.RelayAddress
import io.github.nanomuse.sysfiles.SystemFiles
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRow
import io.github.nanomuse.ui.muse.MuseRowDivider
import io.github.nanomuse.ui.muse.MuseSectionLabel
import io.github.nanomuse.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * Settings → nanoMuse Cloud: the account. Who is signed in (the hint, never the number), the
 * password, the devices holding a key, what was used of the allowance and what is left, what was used
 * by kind (chat, pictures, video, calls) and by model, the account's own history, the provider's
 * pages, and the ways out — this phone, everywhere, or the account itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenProvider: (instanceId: String) -> Unit,
    onOpenModelGroups: () -> Unit,
    onOpenDataControls: () -> Unit = {},
    /** Devices → "Ask this device": back to the chat with "@<name> " typed (contract C7, rule 8). */
    onAskDevice: ((name: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(NanoMuseCloud.account(context)) }
    var signedIn by remember { mutableStateOf(NanoMuseCloud.isSignedIn(context)) }
    var sessions by remember { mutableStateOf<List<NanoMuseCloud.Session>>(emptyList()) }
    var events by remember { mutableStateOf<List<NanoMuseCloud.Event>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var showToday by remember { mutableStateOf(true) }
    var passwordDialog by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var changeServer by remember { mutableStateOf(false) }
    // the sign-out sheet's question, off every time it opens (contract C12)
    var keep by remember(confirm, changeServer) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        scope.launch {
            try {
                account = NanoMuseCloud.refresh(context)
                signedIn = NanoMuseCloud.isSignedIn(context)
                if (signedIn) {
                    sessions = runCatching { NanoMuseCloud.sessions(context) }.getOrDefault(sessions)
                    events = runCatching { NanoMuseCloud.events(context) }.getOrDefault(events)
                }
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
            }
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { if (signedIn) refresh() }

    fun leave() {
        account = null
        signedIn = false
        sessions = emptyList()
        events = emptyList()
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.nm_cloud_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            if (!signedIn) {
                MuseCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.nm_cloud_not_signed_in), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.nm_cloud_not_signed_in_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (NanoMuseCloud.signInEnded(context)) {
                            // the relay refused the key; the account's data waits for its return (contract C12)
                            Text(
                                stringResource(R.string.nm_cloud_sign_in_ended),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(title = stringResource(R.string.nm_cloud_sign_in), onClick = onSignIn, titleColor = MuseTones.action)
                    val base = NanoMuseCloud.baseUrl(context)
                    if (!RelayAddress.isDefault(base)) {
                        // pointed at someone's own relay: say which, so a sign-in that fails is understood
                        MuseRowDivider(inset = 16.dp)
                        MuseRow(title = stringResource(R.string.nm_cloud_server_row), value = RelayAddress.display(base), chevron = false, onClick = onSignIn)
                    }
                }
            } else {
                val a = account

                // -- who ---------------------------------------------------------------------
                MuseCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(MuseTones.fill),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (a?.hint?.firstOrNull { it.isLetterOrDigit() } ?: 'n').uppercaseChar().toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MuseTones.action,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(text = a?.hint ?: stringResource(R.string.nm_cloud_title), style = MaterialTheme.typography.titleMedium)
                            val channel = when (a?.channel) {
                                "phone" -> stringResource(R.string.nm_cloud_channel_phone)
                                "email" -> stringResource(R.string.nm_cloud_channel_email)
                                else -> ""
                            }
                            val since = a?.createdAt?.takeIf { it > 0 }?.let {
                                stringResource(R.string.nm_cloud_since, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)))
                            }
                            Text(
                                text = listOfNotNull(channel.takeIf { it.isNotEmpty() }, since).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_password),
                        icon = Icons.Outlined.Password,
                        value = stringResource(if (a?.hasPassword == true) R.string.nm_cloud_password_set else R.string.nm_cloud_password_unset),
                        onClick = { passwordDialog = true },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_devices_signed_in),
                        icon = Icons.Outlined.PhoneAndroid,
                        value = (if (sessions.isNotEmpty()) sessions.size else a?.sessions ?: 0).toString(),
                        chevron = false,
                        onClick = { refresh() },
                    )
                    MuseRowDivider()
                    // the relay the key belongs to (0.1.38: people who run their own); changing it means signing out first
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_server_row),
                        icon = Icons.Outlined.Storage,
                        value = RelayAddress.display(NanoMuseCloud.baseUrl(context)),
                        chevron = false,
                        trailing = {
                            TextButton(onClick = { changeServer = true }) {
                                Text(stringResource(R.string.nm_cloud_server_change), color = MuseTones.action, fontSize = 13.sp)
                            }
                        },
                        onClick = { changeServer = true },
                    )
                    if (a?.accountId?.isNotEmpty() == true) {
                        Text(
                            text = stringResource(R.string.nm_cloud_account_id, a.accountId.take(8)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 10.dp),
                        )
                    }
                }
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MuseTones.action,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }

                // -- data controls: the switch lives under Settings; here, how it stands ---------
                if (a != null) {
                    MuseSectionLabel(stringResource(R.string.nm_data_title))
                    MuseCard {
                        MuseRow(
                            title = stringResource(R.string.nm_data_improve),
                            icon = Icons.Outlined.Storage,
                            value = stringResource(if (a.contribute) R.string.nm_data_status_on else R.string.nm_data_status_off) +
                                if (a.samples > 0) " · " + stringResource(R.string.nm_data_turns, a.samples) else "",
                            onClick = onOpenDataControls,
                        )
                    }
                }

                // -- E2EE Config Sync --------------------------------------------------------
                if (signedIn) {
                    val hasKey = ConfigSync.hasKey(context)
                    val syncing by ConfigSync.syncing.collectAsState()
                    val lastSyncAt by ConfigSync.lastSyncTime.collectAsState()
                    var showPwDialog by remember { mutableStateOf(false) }

                    MuseSectionLabel("端到端加密多端同步")
                    MuseCard {
                        MuseRow(
                            title = "同步状态",
                            icon = Icons.Outlined.Tune,
                            value = if (hasKey) "已开启端到端加密" else "未解锁密钥 (点击配置)",
                            onClick = {
                                if (!hasKey) showPwDialog = true
                                else ConfigSync.pullSoon(context)
                            },
                        )
                        MuseRowDivider()
                        MuseRow(
                            title = if (syncing) "正在同步中..." else "立即同步配置与模型",
                            icon = Icons.Outlined.Refresh,
                            value = if (lastSyncAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSyncAt)) else "未同步",
                            onClick = {
                                if (!hasKey) showPwDialog = true
                                else {
                                    ConfigSync.pullSoon(context)
                                    ConfigSync.pushDebounced(context)
                                }
                            },
                        )
                        MuseRowDivider()
                        MuseRow(
                            title = "设置 / 重置同步密码",
                            icon = Icons.Outlined.Password,
                            value = if (hasKey) "重新派生加密密钥" else "输入密码以启用",
                            onClick = { showPwDialog = true },
                        )
                    }

                    if (showPwDialog) {
                        var pwText by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showPwDialog = false },
                            title = { Text("端到端同步加密密码") },
                            text = {
                                Column {
                                    Text(
                                        text = "多端提供商、模型配置与人设采用端到端加密 (AES-256-GCM + PBKDF2)。服务器无法窥探您的 API 密钥与凭据。\n请输入自建服务的账号密码派生解密密钥：",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = pwText,
                                        onValueChange = { pwText = it },
                                        label = { Text("密码") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (pwText.isNotBlank()) {
                                            val accId = NanoMuseCloud.account(context)?.accountId.orEmpty()
                                            ConfigSync.setEncryptionPassword(context, pwText, accId)
                                            showPwDialog = false
                                        }
                                    },
                                    enabled = pwText.isNotBlank(),
                                ) {
                                    Text("保存并同步", color = MuseTones.action)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPwDialog = false }) {
                                    Text("取消")
                                }
                            },
                        )
                    }
                }

                // -- sign-ins ---------------------------------------------------------------
                if (sessions.isNotEmpty()) {
                    MuseSectionLabel(stringResource(R.string.nm_cloud_devices_signed_in))
                    MuseCard {
                        sessions.forEachIndexed { i, s ->
                            if (i > 0) MuseRowDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (s.device.contains("web", ignoreCase = true)) Icons.Outlined.Language
                                    else if (s.device.contains("mac", true) || s.device.contains("windows", true) || s.device.contains("linux", true)) Icons.Outlined.Computer
                                    else Icons.Outlined.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    val deviceName = s.device.ifBlank { stringResource(R.string.nm_cloud_device_unknown) }
                                    val thisOne = if (s.current) "  ·  " + stringResource(R.string.nm_cloud_this_device) else ""
                                    Text(
                                        text = deviceName + thisOne,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = listOf(
                                            stringResource(if (s.via == "password") R.string.nm_cloud_via_password else R.string.nm_cloud_via_code),
                                            stringResource(
                                                R.string.nm_cloud_last_used,
                                                SystemFiles.relative(context, (if (s.lastUsedAt > 0) s.lastUsedAt else s.createdAt) * 1000),
                                            ),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!s.current) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                try {
                                                    NanoMuseCloud.revokeSession(context, s.prefix)
                                                    sessions = sessions.filterNot { it.prefix == s.prefix }
                                                } catch (e: Exception) {
                                                    error = NanoMuseCloud.describe(context, e)
                                                }
                                                busy = false
                                            }
                                        },
                                    ) { Text(stringResource(R.string.nm_cloud_sign_out), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }

                // -- history ----------------------------------------------------------------
                if (events.isNotEmpty()) {
                    MuseSectionLabel(stringResource(R.string.nm_cloud_activity))
                    MuseCard {
                        events.take(12).forEachIndexed { i, e ->
                            if (i > 0) MuseRowDivider(inset = 16.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = eventLabel(e.kind),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (e.kind == "sign_in.failed" || e.kind == "budget.refused" || e.kind == "upstream.error") {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (e.detail.isNotBlank() && !e.kind.startsWith("password")) {
                                        Text(
                                            text = e.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    text = SystemFiles.relative(context, e.ts * 1000),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                MuseGap()
                CommunityNoticeCard()
                MuseGap()
                MuseCard {
                    Text(
                        text = stringResource(R.string.nm_cloud_how_it_works),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_open_provider),
                        icon = Icons.Outlined.Tune,
                        onClick = { NanoMuseCloud.instance(context)?.let { onOpenProvider(it.id) } },
                    )
                    MuseRowDivider()
                    MuseRow(title = stringResource(R.string.settings_model_groups), onClick = onOpenModelGroups)
                }
                MuseGap()
                DevicesSection(onAsk = onAskDevice?.let { ask -> { d -> ask(d.name) } })

                // -- the ways out -----------------------------------------------------------
                MuseGap()
                MuseCard {
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_sign_out_here),
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        chevron = false,
                        onClick = { confirm = Confirm.SIGN_OUT },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_sign_out_everywhere),
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        chevron = false,
                        onClick = { confirm = Confirm.SIGN_OUT_ALL },
                    )
                    MuseRowDivider()
                    MuseRow(
                        title = stringResource(R.string.nm_cloud_delete_account),
                        icon = Icons.Outlined.DeleteOutline,
                        chevron = false,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirm = Confirm.DELETE },
                    )
                }
                Text(
                    text = stringResource(R.string.nm_cloud_sign_out_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        when (which) {
                            Confirm.SIGN_OUT -> R.string.nm_cloud_sign_out_here
                            Confirm.SIGN_OUT_ALL -> R.string.nm_cloud_sign_out_everywhere
                            Confirm.DELETE -> R.string.nm_cloud_delete_account
                        },
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            when (which) {
                                Confirm.SIGN_OUT -> R.string.nm_cloud_sign_out_confirm
                                Confirm.SIGN_OUT_ALL -> R.string.nm_cloud_sign_out_everywhere_confirm
                                Confirm.DELETE -> R.string.nm_cloud_delete_account_confirm
                            },
                        ),
                    )
                    // contract C12: a sign-out takes the account's data off the phone unless asked not to
                    if (which != Confirm.DELETE) KeepChatsRow(keep) { keep = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                when (which) {
                                    Confirm.SIGN_OUT -> { NanoMuseCloud.signOut(context, keep); leave() }
                                    Confirm.SIGN_OUT_ALL -> { NanoMuseCloud.signOutEverywhere(context, includingThis = true, keep = keep); leave() }
                                    Confirm.DELETE -> { NanoMuseCloud.deleteAccount(context); leave() }
                                }
                            } catch (e: Exception) {
                                error = NanoMuseCloud.describe(context, e)
                            }
                            busy = false
                            confirm = null
                        }
                    },
                ) {
                    Text(
                        stringResource(if (which == Confirm.DELETE) R.string.delete else R.string.nm_cloud_sign_out),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (changeServer) {
        // the key belongs to one relay: out of this one first, then the sign-in screen with its server form
        AlertDialog(
            onDismissRequest = { changeServer = false },
            title = { Text(stringResource(R.string.nm_cloud_server_change)) },
            text = {
                Column {
                    Text(stringResource(R.string.nm_cloud_server_change_confirm))
                    KeepChatsRow(keep) { keep = it } // a sign-out like any other (C12)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                NanoMuseCloud.signOut(context, keep)
                                leave()
                                changeServer = false
                                onSignIn()
                            } catch (e: Exception) {
                                error = NanoMuseCloud.describe(context, e)
                                changeServer = false
                            }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.nm_cloud_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { changeServer = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (passwordDialog) {
        PasswordDialog(
            hasPassword = account?.hasPassword == true,
            onDismiss = { passwordDialog = false },
            onSaved = { removed ->
                passwordDialog = false
                account = account?.copy(hasPassword = !removed)
                notice = context.getString(if (removed) R.string.nm_cloud_password_removed else R.string.nm_cloud_password_saved)
                refresh()
            },
        )
    }
}

private enum class Confirm { SIGN_OUT, SIGN_OUT_ALL, DELETE }

/**
 * *Keep this account's chats on this device* — the one question a sign-out asks (contract C12).
 * Off by default: the account's chats, memory, feed, goals and face leave the phone with it.
 */
@Composable
private fun KeepChatsRow(keep: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!keep) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.nm_cloud_keep_chats), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.nm_cloud_keep_chats_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = keep, onCheckedChange = onChange)
    }
}

/** Set, change or remove the password; the relay decides whether the current one is needed. */
@Composable
private fun PasswordDialog(hasPassword: Boolean, onDismiss: () -> Unit, onSaved: (removed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MuseTones.action,
        cursorColor = MuseTones.action,
        focusedLabelColor = MuseTones.action,
    )
    val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()

    fun save(remove: Boolean) {
        if (busy) return
        if (!remove) {
            if (next.length < 8) { error = context.getString(R.string.nm_cloud_err_password_short); return }
            if (next != again) { error = context.getString(R.string.nm_cloud_password_mismatch); return }
        }
        error = null
        busy = true
        scope.launch {
            try {
                NanoMuseCloud.setPassword(context, if (remove) "" else next, current.takeIf { it.isNotEmpty() })
                onSaved(remove)
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (hasPassword) R.string.nm_cloud_password_title_change else R.string.nm_cloud_password_title_set)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.nm_cloud_password_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Spacer(Modifier.height(12.dp))
                if (hasPassword) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it },
                        label = { Text(stringResource(R.string.nm_cloud_password_current)) },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = { Text(stringResource(R.string.nm_cloud_password_new)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(
                                if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (show) R.string.nm_a11y_hide_password else R.string.nm_a11y_show_password),
                                tint = muted,
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it },
                    label = { Text(stringResource(R.string.nm_cloud_password_confirm)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (hasPassword) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { save(remove = true) }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.nm_cloud_password_remove), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(remove = false) }, enabled = !busy && next.isNotEmpty()) {
                Text(stringResource(R.string.save), color = MuseTones.action)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** One line of the usage table: a glyph, the kind, requests · tokens, and what it cost. */
@Composable
private fun UsageLine(icon: ImageVector?, title: String, detail: String, amount: String, compact: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = amount,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One half of a two-way switch inside a card (today / all time). */
@Composable
private fun SegmentTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MuseTones.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "chat" -> Icons.Outlined.ChatBubbleOutline
    "image" -> Icons.Outlined.Image
    "video" -> Icons.Outlined.Videocam
    "realtime" -> Icons.Outlined.Phone
    else -> Icons.Outlined.Tune
}

@Composable
private fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "chat" -> R.string.nm_usage_kind_chat
        "image" -> R.string.nm_usage_kind_image
        "video" -> R.string.nm_usage_kind_video
        "realtime" -> R.string.nm_usage_kind_realtime
        else -> R.string.nm_usage_kind_other
    },
)

@Composable
private fun eventLabel(kind: String): String = when (kind) {
    "account.created" -> stringResource(R.string.nm_cloud_ev_account_created)
    "contribute.on" -> stringResource(R.string.nm_cloud_ev_contribute_on)
    "contribute.off" -> stringResource(R.string.nm_cloud_ev_contribute_off)
    "contribute.deleted" -> stringResource(R.string.nm_cloud_ev_contribute_deleted)
    "contribute.default" -> stringResource(R.string.nm_cloud_ev_contribute_default)
    "invite.accepted" -> stringResource(R.string.nm_cloud_ev_invite_accepted)
    "invite.used" -> stringResource(R.string.nm_cloud_ev_invite_used)
    "credit.granted" -> stringResource(R.string.nm_cloud_ev_credit_granted)
    "pool.set" -> stringResource(R.string.nm_cloud_ev_pool_set)
    "sign_in.code" -> stringResource(R.string.nm_cloud_ev_sign_in_code)
    "sign_in.password" -> stringResource(R.string.nm_cloud_ev_sign_in_password)
    "sign_in.failed" -> stringResource(R.string.nm_cloud_ev_sign_in_failed)
    "password.set" -> stringResource(R.string.nm_cloud_ev_password_set)
    "password.changed" -> stringResource(R.string.nm_cloud_ev_password_changed)
    "password.cleared" -> stringResource(R.string.nm_cloud_ev_password_cleared)
    "sign_out" -> stringResource(R.string.nm_cloud_ev_sign_out)
    "sign_out.all" -> stringResource(R.string.nm_cloud_ev_sign_out_all)
    "budget.refused" -> stringResource(R.string.nm_cloud_ev_budget_refused)
    "upstream.error" -> stringResource(R.string.nm_cloud_ev_upstream_error)
    else -> kind
}

/** ¥ / $ amounts: whole numbers above a hundred, cents otherwise, fen-fractions for the tiny ones. */
internal fun money(v: Double): String = when {
    v >= 100 -> String.format(java.util.Locale.US, "%.0f", v)
    v >= 1 -> String.format(java.util.Locale.US, "%.2f", v)
    v > 0 && v < 0.01 -> String.format(java.util.Locale.US, "%.4f", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
}

/**
 * Invite a friend: the account's code and link (each new sign-up with it adds to the pool),
 * how many came and what they added.
 */
@Composable
private fun InviteCard(a: NanoMuseCloud.Account) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    var copied by remember { mutableStateOf(false) }
    val link = a.inviteUrl.ifBlank {
        val b = NanoMuseCloud.baseUrl(context).ifBlank { "" }
        if (b.isNotBlank()) "$b/web/?invite=" + a.inviteCode else ""
    }
    MuseCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.nm_cloud_invite_why, money(a.inviteBonusCny)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nm_cloud_invite_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.inviteCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                }
                TextButton(onClick = { clipboard.setPlainText("nanoMuse", a.inviteCode); copied = true }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (copied) R.string.nm_cloud_invite_copied else R.string.nm_cloud_invite_copy))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val text = context.getString(R.string.nm_cloud_invite_share_text, a.inviteCode, link)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.nm_cloud_invite_share)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nm_cloud_invite_share), fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            val facts = buildList {
                add(stringResource(R.string.nm_cloud_invite_count, a.invites))
                if (a.inviteEarnedCny > 0) add(stringResource(R.string.nm_cloud_invite_earned, money(a.inviteEarnedCny)))
            }
            Text(
                text = facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The notice on the site, with the whole story: who pays, what is kept, how to help. */
const val NOTICE_URL = "https://github.com/LanRhyme/NyanoMuse"

/**
 * The community notice: nanoMuse is free, open source and non-profit; who pays; what the relay
 * keeps; and the invitation to file issues and pull requests — with the repository one tap
 * away. The title opens the same notice on the site. Shown on the account page, the sign-in
 * screen and (in short) the first-run screen.
 */
@Composable
fun CommunityNoticeCard(inset: Dp = 16.dp) {
    val context = LocalContext.current
    MuseCard(inset = inset) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().clickable { openExternalUrl(context, NOTICE_URL) },
            ) {
                Icon(Icons.Outlined.Code, contentDescription = null, tint = MuseTones.action, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nm_cloud_notice_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(14.dp))
            }
            Text(
                stringResource(R.string.nm_cloud_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.nm_cloud_notice_closing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = { openExternalUrl(context, "https://github.com/nano-muse/nanoMuse") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nm_notice_github))
                }
                TextButton(onClick = { openExternalUrl(context, "https://github.com/nano-muse/nanoMuse/issues/new/choose") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nm_notice_issue))
                }
            }
        }
    }
}
