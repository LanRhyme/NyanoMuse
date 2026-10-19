package io.github.nanomuse.cloud

import android.content.Context
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.hands.Hands
import io.github.nanomuse.hub.Hub
import io.github.nanomuse.models.ModelSlots
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * End-to-End Encrypted (E2EE) Provider, Model and Identity Configuration Sync.
 * Excludes themes and wallpapers (strictly device-local).
 */
object ConfigSync {
    private const val TAG = "ConfigSync"
    private const val PREFS = "nanomuse_config_sync"
    private const val KEY_REV = "rev"
    private const val KEY_SYNC_KEY = "sync_key"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val PUSH_DELAY_MS = 1500L

    private val jsonMediaType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nm-config-sync").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var pendingPush: ScheduledFuture<*>? = null
    @Volatile private var applying = false

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val app = context.applicationContext
        _lastSyncTime.value = prefs(app).getLong(KEY_LAST_SYNC_AT, 0L)
        pullSoon(app)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    pullSoon(app)
                }
            }
        )
    }

    fun hasKey(context: Context): Boolean = !prefs(context).getString(KEY_SYNC_KEY, null).isNullOrBlank()

    fun setEncryptionPassword(context: Context, password: String, accountId: String) {
        if (password.isBlank() || accountId.isBlank()) return
        val key = deriveKey(password, accountId)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_SYNC_KEY, keyB64).apply()
        AppLogger.info(TAG, "E2EE sync key initialized for account $accountId")
        pullSoon(context)
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
        _lastSyncTime.value = 0L
    }

    private fun getSyncKey(context: Context): ByteArray? {
        val b64 = prefs(context).getString(KEY_SYNC_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
    }

    fun deriveKey(password: String, accountId: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), accountId.toByteArray(StandardCharsets.UTF_8), 100_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun encrypt(payload: JSONObject, key: ByteArray): Triple<String, String, String> {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val full = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val ciphertextBytes = full.copyOfRange(0, full.size - 16)
        val tagBytes = full.copyOfRange(full.size - 16, full.size)
        return Triple(
            Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(tagBytes, Base64.NO_WRAP)
        )
    }

    fun decrypt(ciphertextB64: String, ivB64: String, tagB64: String, key: ByteArray): JSONObject {
        val ct = Base64.decode(ciphertextB64, Base64.NO_WRAP)
        val tag = Base64.decode(tagB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val full = ByteArray(ct.size + tag.size)
        System.arraycopy(ct, 0, full, 0, ct.size)
        System.arraycopy(tag, 0, full, ct.size, tag.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(full)
        return JSONObject(String(plain, StandardCharsets.UTF_8))
    }

    fun onFrame(context: Context, frame: JSONObject) {
        val rev = frame.optInt("rev", 0)
        val fromDevice = frame.optString("device")
        if (fromDevice == Hub.deviceId(context)) return
        AppLogger.info(TAG, "received config frame rev=$rev from $fromDevice")
        pullSoon(context)
    }

    fun pullSoon(context: Context) {
        worker.execute {
            pullBlocking(context.applicationContext)
        }
    }

    fun pushDebounced(context: Context) {
        if (applying) return
        val app = context.applicationContext
        synchronized(this) {
            pendingPush?.cancel(false)
            pendingPush = worker.schedule({
                pushBlocking(app)
            }, PUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun backupLocalConfig(context: Context) {
        try {
            val dir = File(context.filesDir, "backups/configs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "config_${System.currentTimeMillis()}.json")
            val local = gatherLocalConfig(context)
            file.writeText(local.toString(2), Charsets.UTF_8)
            val list = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            if (list.size > 10) {
                for (old in list.drop(10)) old.delete()
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "backupLocalConfig failed: ${e.message}")
        }
    }

    private fun repo(context: Context): ProviderRepository? =
        (context.applicationContext as? MinisApp)?.providerRepositoryOrNull

    fun gatherLocalConfig(context: Context): JSONObject {
        val repo = repo(context)
        val instances = repo?.config?.value?.instances?.filter {
            it.isEnabled && it.id != NanoMuseCloud.instance(context)?.id
        } ?: emptyList()

        val providersArr = JSONArray()
        for (inst in instances) {
            val pObj = JSONObject()
            pObj.put("id", inst.id)
            pObj.put("label", inst.label)
            pObj.put("providerType", inst.providerType.name)
            pObj.put("protocol", if (inst.useResponsesAPI) "openai-responses" else "openai")
            pObj.put("baseURL", inst.customBaseURL ?: "")
            pObj.put("appendV1Suffix", inst.appendV1Suffix)
            pObj.put("useResponsesAPI", inst.useResponsesAPI)
            inst.customUserAgent?.let { pObj.put("customUserAgent", it) }
            val apiKey = repo?.loadApiKey(inst.id) ?: ""
            pObj.put("apiKey", apiKey)

            val modelsArr = JSONArray()
            val entries = repo?.config?.value?.modelEntries?.filter { it.providerInstanceId == inst.id } ?: emptyList()
            for (e in entries) {
                modelsArr.put(JSONObject().apply {
                    put("id", e.model.id)
                    put("name", e.model.displayName ?: e.model.id)
                    put("vision", e.model.hasImageInput)
                    put("kind", if (io.github.nanomuse.avatar.ImageGen.looksLikeImageModel(e.model.id)) "image"
                        else if (io.github.nanomuse.media.VideoGen.looksLikeVideoModel(e.model.id)) "video"
                        else "chat")
                })
            }
            pObj.put("models", modelsArr)
            pObj.put("updated_at", System.currentTimeMillis())
            providersArr.put(pObj)
        }

        val slotsObj = JSONObject()
        ModelSlots.current(context, ModelSlots.Slot.CHAT)?.let { v ->
            slotsObj.put("chat", JSONObject().put("providerId", v.instance.id).put("modelId", v.modelId).put("updated_at", System.currentTimeMillis()))
        }
        ModelSlots.current(context, ModelSlots.Slot.HANDS)?.let { v ->
            slotsObj.put("hands", JSONObject().put("providerId", v.instance.id).put("modelId", v.modelId).put("updated_at", System.currentTimeMillis()))
        }
        ModelSlots.current(context, ModelSlots.Slot.IMAGE)?.let { v ->
            slotsObj.put("image", JSONObject().put("providerId", v.instance.id).put("modelId", v.modelId).put("updated_at", System.currentTimeMillis()))
        }
        ModelSlots.current(context, ModelSlots.Slot.VIDEO)?.let { v ->
            slotsObj.put("video", JSONObject().put("providerId", v.instance.id).put("modelId", v.modelId).put("updated_at", System.currentTimeMillis()))
        }

        val soulFile = SoulStore.fileLocation(context)
        val soulText = if (soulFile.exists()) soulFile.readText(Charsets.UTF_8) else ""
        val parsed = SoulMDParser.parse(soulText)
        val soulObj = JSONObject().apply {
            put("name", parsed.metadata.name)
            put("style", parsed.metadata.style)
            put("lang", parsed.metadata.lang)
            put("icon", parsed.metadata.icon)
            put("body", soulText)
            put("updated_at", System.currentTimeMillis())
        }

        return JSONObject().apply {
            put("version", 1)
            put("updated_at", System.currentTimeMillis())
            put("device", Hub.deviceId(context))
            put("providers", providersArr)
            put("slots", slotsObj)
            put("soul", soulObj)
        }
    }

    fun applyRemoteConfig(context: Context, remote: JSONObject) {
        applying = true
        try {
            backupLocalConfig(context)
            val repo = repo(context) ?: return
            val existing = repo.config.value.instances.toMutableList()
            val remoteProviders = remote.optJSONArray("providers") ?: JSONArray()

            for (i in 0 until remoteProviders.length()) {
                val p = remoteProviders.getJSONObject(i)
                val label = p.optString("label")
                val providerTypeStr = p.optString("providerType", "openAI")
                val providerType = when (providerTypeStr.lowercase()) {
                    "anthropic" -> ProviderType.anthropic
                    "gemini" -> ProviderType.gemini
                    "openrouter" -> ProviderType.openRouter
                    "xai" -> ProviderType.xAI
                    "kimicode" -> ProviderType.kimiCode
                    "openairesponses" -> ProviderType.openAIResponses
                    else -> ProviderType.openAI
                }
                val baseURL = p.optString("baseURL").ifEmpty { null }
                val appendV1 = p.optBoolean("appendV1Suffix", true)
                val useResponses = p.optBoolean("useResponsesAPI", false)
                val userAgent = p.optString("customUserAgent").ifEmpty { null }
                val apiKey = p.optString("apiKey")

                var target = existing.firstOrNull { it.label == label }
                    ?: existing.firstOrNull { it.customBaseURL != null && it.customBaseURL == baseURL }
                    ?: existing.firstOrNull { it.providerType == providerType && it.id != NanoMuseCloud.instance(context)?.id }

                if (target != null) {
                    val updated = target.copy(
                        label = label,
                        customBaseURL = baseURL,
                        appendV1Suffix = appendV1,
                        useResponsesAPI = useResponses,
                        customUserAgent = userAgent,
                    )
                    repo.updateInstance(updated)
                    if (apiKey.isNotBlank()) {
                        repo.saveApiKey(updated.id, apiKey)
                    }
                    target = updated
                } else {
                    val fresh = ProviderInstance(
                        id = UUID.randomUUID().toString(),
                        label = label.ifBlank { providerType.name },
                        providerType = providerType,
                        credentialType = ProviderCredential.apiKey,
                        customBaseURL = baseURL,
                        appendV1Suffix = appendV1,
                        useResponsesAPI = useResponses,
                        customUserAgent = userAgent,
                    )
                    repo.addInstance(fresh)
                    if (apiKey.isNotBlank()) {
                        repo.saveApiKey(fresh.id, apiKey)
                    }
                    target = fresh
                }

                // Sync models via replaceEntries
                val modelsArr = p.optJSONArray("models")
                if (modelsArr != null && target != null) {
                    val modelList = mutableListOf<LLMModel>()
                    for (mIdx in 0 until modelsArr.length()) {
                        val m = modelsArr.getJSONObject(mIdx)
                        val modelId = m.optString("id")
                        val modelName = m.optString("name", modelId)
                        val vision = m.optBoolean("vision", false)
                        modelList.add(
                            LLMModel(
                                id = modelId,
                                displayName = modelName,
                                provider = target.label,
                                inputModalities = if (vision) listOf("text", "image") else listOf("text"),
                                outputModalities = listOf("text"),
                            )
                        )
                    }
                    if (modelList.isNotEmpty()) {
                        repo.replaceEntries(target.id, modelList)
                    }
                }
            }

            // Sync slots
            val slots = remote.optJSONObject("slots")
            if (slots != null) {
                slots.optJSONObject("chat")?.let { cSlot ->
                    val mId = cSlot.optString("modelId")
                    if (mId.isNotBlank()) {
                        val entry = repo.config.value.modelEntries.firstOrNull { it.model.id == mId && !it.isHidden }
                        if (entry != null) {
                            ModelSlots.followPick(context, entry.id)
                        }
                    }
                }
                slots.optJSONObject("hands")?.let { hSlot ->
                    val mId = hSlot.optString("modelId")
                    if (mId.isNotBlank()) {
                        val entry = repo.config.value.modelEntries.firstOrNull { it.model.id == mId && it.model.hasImageInput }
                        if (entry != null) {
                            Hands.setModelEntryId(context, entry.id)
                        }
                    }
                }
            }

            // Sync SOUL
            val soul = remote.optJSONObject("soul")
            if (soul != null) {
                val body = soul.optString("body")
                if (body.isNotBlank()) {
                    val soulFile = SoulStore.fileLocation(context)
                    soulFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    soulFile.writeText(body, Charsets.UTF_8)
                    SoulStore.refreshCache(context)
                }
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "applyRemoteConfig failed: ${e.message}")
        } finally {
            applying = false
        }
    }

    fun pullBlocking(context: Context): Boolean {
        if (!NanoMuseCloud.isSignedIn(context)) return false
        val key = getSyncKey(context) ?: return false
        val token = NanoMuseCloud.apiKey(context) ?: return false
        val baseUrl = NanoMuseCloud.baseUrl(context).trimEnd('/')
        if (baseUrl.isBlank()) return false

        _syncing.value = true
        try {
            val req = Request.Builder()
                .url("$baseUrl/v1/sync/config")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "nanoMuse-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            val resp = http.newCall(req).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    AppLogger.warning(TAG, "pull config HTTP error: ${r.code}")
                    return false
                }
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) return false
                val obj = JSONObject(body)
                val rev = obj.optInt("rev", 0)
                val ct = obj.optString("ciphertext")
                val iv = obj.optString("iv")
                val tag = obj.optString("tag")

                val localRev = prefs(context).getInt(KEY_REV, 0)
                if (rev == 0 || ct.isBlank()) {
                    // Nothing remote yet: push our config if we have any
                    val local = gatherLocalConfig(context)
                    if (local.optJSONArray("providers")?.length() ?: 0 > 0) {
                        pushBlocking(context)
                    }
                    return true
                }

                if (rev > localRev) {
                    val remote = decrypt(ct, iv, tag, key)
                    applyRemoteConfig(context, remote)
                    val now = System.currentTimeMillis()
                    prefs(context).edit().putInt(KEY_REV, rev).putLong(KEY_LAST_SYNC_AT, now).apply()
                    _lastSyncTime.value = now
                    AppLogger.info(TAG, "pulled and applied config rev $rev")
                }
                return true
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "pullBlocking error: ${e.message}")
            return false
        } finally {
            _syncing.value = false
        }
    }

    fun pushBlocking(context: Context): Boolean {
        if (applying || !NanoMuseCloud.isSignedIn(context)) return false
        val key = getSyncKey(context) ?: return false
        val token = NanoMuseCloud.apiKey(context) ?: return false
        val baseUrl = NanoMuseCloud.baseUrl(context).trimEnd('/')
        if (baseUrl.isBlank()) return false

        _syncing.value = true
        try {
            val local = gatherLocalConfig(context)
            val (ct, iv, tag) = encrypt(local, key)
            val clientRev = prefs(context).getInt(KEY_REV, 0)

            val body = JSONObject().apply {
                put("device", Hub.deviceId(context))
                put("rev", clientRev)
                put("ciphertext", ct)
                put("iv", iv)
                put("tag", tag)
            }

            val req = Request.Builder()
                .url("$baseUrl/v1/sync/config")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "nanoMuse-Android/${BuildConfig.VERSION_NAME}")
                .put(body.toString().toRequestBody(jsonMediaType))
                .build()

            val resp = http.newCall(req).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    AppLogger.warning(TAG, "push config HTTP error: ${r.code}")
                    return false
                }
                val resObj = JSONObject(r.body?.string().orEmpty())
                val newRev = resObj.optInt("rev", clientRev + 1)
                val now = System.currentTimeMillis()
                prefs(context).edit().putInt(KEY_REV, newRev).putLong(KEY_LAST_SYNC_AT, now).apply()
                _lastSyncTime.value = now
                AppLogger.info(TAG, "pushed config rev $newRev successfully")
                return true
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "pushBlocking error: ${e.message}")
            return false
        } finally {
            _syncing.value = false
        }
    }
}
