package io.github.nanomuse.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.cloud.RelayAddress
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val ROUTE_CLOUD_SIGN_IN = "nanomuse/cloud/sign-in"
const val ROUTE_CLOUD_ACCOUNT = "nanomuse/cloud/account"

/**
 * Sign in to a self-hosted NyanoMuse Relay: enter server address, username and password.
 * Connects and authenticates without requiring third-party SMS or phone number services.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialBase = NanoMuseCloud.baseUrl(context)
    var serverUrl by remember { mutableStateOf(if (RelayAddress.isDefault(initialBase)) "" else initialBase) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var checking by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var serverStatus by remember { mutableStateOf<String?>(null) }
    var serverBad by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun problemLine(p: RelayAddress.Problem): String = when (p) {
        RelayAddress.Problem.EMPTY, RelayAddress.Problem.NOT_A_URL -> context.getString(R.string.nm_cloud_server_bad_url)
        RelayAddress.Problem.HTTP_PUBLIC -> context.getString(R.string.nm_cloud_server_https)
    }

    fun checkServer(then: ((String) -> Unit)? = null) {
        if (checking) return
        val raw = serverUrl.trim()
        if (raw.isEmpty()) {
            serverStatus = context.getString(R.string.nm_cloud_server_bad_url)
            serverBad = true
            return
        }
        val problem = RelayAddress.problem(raw)
        if (problem != null) {
            serverStatus = problemLine(problem)
            serverBad = true
            return
        }
        val url = RelayAddress.normalize(raw) ?: return
        checking = true
        serverStatus = null
        scope.launch {
            try {
                val h = NanoMuseCloud.checkRelay(url)
                serverStatus = context.getString(R.string.nm_cloud_server_ok, RelayAddress.display(url), h.version)
                serverBad = false
                NanoMuseCloud.setBaseUrl(context, url)
                then?.invoke(url)
            } catch (e: Exception) {
                serverStatus = context.getString(R.string.nm_cloud_server_unreachable, RelayAddress.display(url))
                serverBad = true
            }
            checking = false
        }
    }

    fun login() {
        if (verifying || identifier.isBlank() || password.isEmpty()) return
        error = null
        verifying = true
        scope.launch {
            try {
                val raw = serverUrl.trim()
                if (raw.isNotEmpty()) {
                    val url = RelayAddress.normalize(raw)
                    if (url != null) {
                        NanoMuseCloud.setBaseUrl(context, url)
                    }
                }
                if (NanoMuseCloud.baseUrl(context).isBlank()) {
                    error = context.getString(R.string.nm_cloud_server_url) + " 不能为空"
                    verifying = false
                    return@launch
                }
                NanoMuseCloud.login(context, identifier.trim(), password)
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
                verifying = false
            }
        }
    }

    val canSubmit = identifier.isNotBlank() && password.isNotEmpty() && !verifying && !checking

    Scaffold(
        containerColor = MuseTones.surface,
        topBar = {
            MuseTopAppBar(
                title = {},
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            io.github.nanomuse.ui.muse.NmBrandMark(size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.nm_cloud_signin_title),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.nm_cloud_signin_subtitle),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = muted,
                textAlign = TextAlign.Center,
            )
            if (NanoMuseCloud.signInEnded(context)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.nm_cloud_sign_in_ended),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MuseTones.fill, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            // 1. Server Address Field
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; serverStatus = null },
                label = { Text(stringResource(R.string.nm_cloud_server_url)) },
                placeholder = { Text("https://your-server.com 或 192.168.x.x:8000") },
                singleLine = true,
                enabled = !verifying && !checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                trailingIcon = {
                    TextButton(
                        onClick = { checkServer() },
                        enabled = !checking && !verifying && serverUrl.isNotBlank(),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MuseTones.action)
                        } else {
                            Text(stringResource(R.string.nm_cloud_server_check), color = MuseTones.action, fontSize = 13.sp)
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MuseTones.action,
                    cursorColor = MuseTones.action,
                    focusedLabelColor = MuseTones.action,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            serverStatus?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (serverBad) MaterialTheme.colorScheme.error else MuseTones.action,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // 2. Identifier (Username / Account) Field
            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it; error = null },
                label = { Text(stringResource(R.string.nm_cloud_identifier)) },
                placeholder = { Text(stringResource(R.string.nm_cloud_identifier_hint)) },
                singleLine = true,
                enabled = !verifying,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MuseTones.action,
                    cursorColor = MuseTones.action,
                    focusedLabelColor = MuseTones.action,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            // 3. Password Field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text(stringResource(R.string.nm_cloud_password)) },
                singleLine = true,
                enabled = !verifying,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { login() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (showPassword) R.string.nm_a11y_hide_password else R.string.nm_a11y_show_password),
                            tint = muted,
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MuseTones.action,
                    cursorColor = MuseTones.action,
                    focusedLabelColor = MuseTones.action,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))

            // 4. Connect & Login Button
            Button(
                onClick = { login() },
                enabled = canSubmit,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (verifying) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        text = stringResource(R.string.nm_cloud_verify),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "所有数据仅保存在你的自建服务与本地设备中，绝不上传任何第三方云端",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
