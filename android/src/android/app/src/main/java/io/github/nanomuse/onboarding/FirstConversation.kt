package io.github.nanomuse.onboarding

import android.content.Context
import com.openminis.app.R
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.repository.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The first conversation, the way Muse does it: no form. After the three setup cards the user
 * lands in the chat, the app speaks first (a scripted opening, zero tokens), asks what to call
 * them, and then — with the model doing the talking — asks what they would like to call it. A
 * name chooser card appears under that question; the pick is written to SOUL.md on the spot,
 * so the header changes before the model has even replied.
 *
 * The model, not a regex, decides what the user meant. Its reply carries a small
 * `nanomuse-naming` block when something happened — the form of address was given (with two
 * name suggestions for the agent, in the user's language), or the agent was named — and the
 * app moves the [Phase] on that. A reply without the block means the user talked about
 * something else: the model helps with it and steers back, and the phase stays where it is.
 *
 * Everything the app shows on its own behalf here is virtual: the opening and the card live in
 * the view model's message list only, never in the database and never in the model's history.
 * The model learns what happened through a short system-prompt addendum for the current phase.
 */
enum class Phase {
    /** Never started; eligible on the next fresh draft if the name is still the default. */
    NONE,

    /** The opening is on screen; the user is answering "what should I call you?". */
    ASK_USER_NAME,

    /** The model asked for its name; the chooser card is showing. */
    ASK_AGENT_NAME,

    /** The name was just picked from the chooser; the model's next reply is its first as itself. */
    NAMED,

    /** Over — either completed or the user talked past it. */
    DONE,
}

data class NamingCardState(val suggestions: List<String>, val chosen: String? = null)

/** What the model told the app in a `nanomuse-naming` block. */
data class NamingBlock(
    /** The block had a `user_address` key (null value = the user wants no form of address). */
    val addressGiven: Boolean,
    val userAddress: String?,
    /** Names the model suggests for itself, already cleaned. */
    val suggestions: List<String>,
    /** The name the user gave the agent, if this block says so. */
    val agentName: String?,
)

class FirstConversation(
    private val context: Context,
    private val memoryRepository: MemoryRepository?,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var phase: Phase
        get() = prefs.getString(KEY_PHASE, null)?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: Phase.DONE
        private set(value) {
            prefs.edit().putString(KEY_PHASE, value.name).apply()
        }

    /** The session (draft or real id) the conversation is bound to. */
    var sessionId: String?
        get() = prefs.getString(KEY_SESSION, null)
        private set(value) {
            prefs.edit().putString(KEY_SESSION, value).apply()
        }

    private val _namingCard = MutableStateFlow<NamingCardState?>(null)
    val namingCard: StateFlow<NamingCardState?> = _namingCard.asStateFlow()

    /** Whether the intro belongs in [sessionId]'s transcript — disabled in NyanoMuse. */
    fun shouldShowIntro(currentSessionId: String, hasOtherSessions: Boolean): Boolean = false

    /** Bind to [currentSessionId] */
    fun start(currentSessionId: String) {
        sessionId = currentSessionId
        phase = Phase.DONE
    }

    /** The draft became a real session: keep following it. */
    fun rebind(fromDraft: String, toReal: String) {
        if (sessionId == fromDraft) sessionId = toReal
    }

    fun isBoundTo(currentSessionId: String): Boolean = false

    /** The three opening paragraphs, in the device language — disabled in NyanoMuse. */
    fun intro(): List<String> = emptyList()

    /**
     * The model's reply just finished.
     */
    fun afterTurn(assistantText: String?): Boolean = false

    /** A chip was tapped: the name is saved at once; the model's next reply is its first as itself. */
    fun pick(name: String) {
        if (phase != Phase.ASK_AGENT_NAME) return
        applyName(name)
        phase = Phase.NAMED
    }

    /** The user moved on to something the app handles itself (an avatar change): drop the chooser. */
    fun dismissChooser() {
        if (phase == Phase.ASK_AGENT_NAME) {
            phase = Phase.DONE
            _namingCard.value = null
        }
    }

    private fun applyName(name: String) {
        val cur = SoulStore.load(context) ?: com.openminis.app.agent.SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        SoulStore.save(context, cur.copy(metadata = cur.metadata.copy(name = name)))
        _namingCard.value = (_namingCard.value ?: NamingCardState(currentSuggestions())).copy(chosen = name)
    }

    /** The system-prompt addendum for the current phase; disabled in NyanoMuse. */
    fun promptAddendum(): String? = null

    private fun saveAddress(address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        val repo = memoryRepository ?: return
        runCatching {
            val current = repo.loadGlobalMd()
            val line = "- Call them: $address"
            val updated = when {
                current.lines().any { it.trim().startsWith("- Call them:") } ->
                    current.lines().joinToString("\n") { if (it.trim().startsWith("- Call them:")) line else it }
                current.isBlank() -> "## About the user\n$line\n"
                else -> current.trimEnd() + "\n\n## About the user\n$line\n"
            }
            repo.saveGlobalMd(updated)
        }
    }

    private fun saveSuggestions(names: List<String>) {
        prefs.edit().putString(KEY_SUGGESTIONS, names.joinToString("\n")).apply()
    }

    /** The model's suggestions when it gave some; otherwise two from the bundled pool, in the device language. */
    private fun currentSuggestions(): List<String> {
        prefs.getString(KEY_SUGGESTIONS, null)?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { return it }
        val pool = context.resources.getStringArray(R.array.nm_name_suggestions).toList()
        val seed = (sessionId ?: "").hashCode()
        return pool.shuffled(java.util.Random(seed.toLong())).take(2)
    }

    companion object {
        const val BLOCK = "nanomuse-naming"
        private const val PREFS = "nanomuse"
        private const val KEY_PHASE = "first_conversation.phase"
        private const val KEY_SESSION = "first_conversation.session"
        private const val KEY_ADDRESS = "first_conversation.address"
        private const val KEY_SUGGESTIONS = "first_conversation.suggestions"
        private const val TAKEN_NAMES = "Siri, Alexa, Cortana, Jarvis, Muse, Gemini, Copilot, 小爱, 小度, 小艺, 天猫精灵, 豆包, 文心, 通义, 阿福"
        private const val MAX_NAME = 16

        /**
         * The opening the app spoke in [sessionId] on its own behalf, as one text — for the
         * conversation sync, which has no row for it. Null when [sessionId] is not the first
         * conversation (or it never started).
         */
        fun introOf(context: Context, sessionId: String): String? = null

        private val blockRegex = Regex("```$BLOCK[ \\t]*\\r?\\n([\\s\\S]*?)```")
        private val QUOTES = Regex("^[\\s\"'“”‘’「」『』]+|[\\s\"'“”‘’「」『』。，、！!？?.]+$")

        /** The last `nanomuse-naming` block in [text], or null when there is none or it is not JSON. */
        fun parseBlock(text: String?): NamingBlock? {
            if (text.isNullOrEmpty() || !text.contains("```$BLOCK")) return null
            val json = blockRegex.findAll(text).lastOrNull()?.groupValues?.get(1) ?: return null
            val o = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null
            val suggestions = o.optJSONArray("suggest")?.let { arr ->
                (0 until arr.length()).mapNotNull { cleanName(arr.optString(it)) }.distinct().take(3)
            }.orEmpty()
            return NamingBlock(
                addressGiven = o.has("user_address"),
                userAddress = if (o.isNull("user_address")) null else cleanName(o.optString("user_address")),
                suggestions = suggestions,
                agentName = if (o.isNull("agent_name")) null else cleanName(o.optString("agent_name")),
            )
        }

        /** A usable name: one line, quotes and trailing punctuation gone, not absurdly long. */
        fun cleanName(raw: String?): String? {
            val t = raw?.replace(QUOTES, "")?.trim() ?: return null
            if (t.isEmpty() || t.contains('\n') || t.length > MAX_NAME) return null
            return t
        }
    }
}
