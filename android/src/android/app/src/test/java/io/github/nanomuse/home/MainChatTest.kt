package io.github.nanomuse.home

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class MainChatTest {

    private class PrefsContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MapPrefs>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) { MapPrefs() }
        override fun getApplicationContext(): Context = this
    }

    private class MapPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }

    private lateinit var context: PrefsContext
    private lateinit var dao: ChatDao
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        context = PrefsContext()
        dao = Mockito.mock(ChatDao::class.java)
        repo = ChatRepository(dao)
        MainChat.reset()
    }

    private fun session(id: String, updatedAt: Long): ChatSessionEntity = ChatSessionEntity(
        id = id,
        title = "Chat $id",
        modelId = "m1",
        createdAt = updatedAt - 1000,
        updatedAt = updatedAt,
    )

    @Test
    fun `last active session persists and is retrieved`() {
        MainChat.setLastActive(context, "session-123")
        assertEquals("session-123", MainChat.getLastActive(context))

        // Draft ids are ignored
        MainChat.setLastActive(context, "__new__draft-1")
        assertEquals("session-123", MainChat.getLastActive(context))
    }

    @Test
    fun `onPromoted updates last promoted draft and active session`() {
        MainChat.onPromoted(context, "__new__draft-1", "real-session-1")
        assertEquals("real-session-1", MainChat.getLastActive(context))
        assertEquals("__new__draft-1" to "real-session-1", MainChat.lastPromotedDraft.value)
    }

    @Test
    fun `resolveLaunchSession returns new chat when mode is 2`() = runBlocking {
        context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("launch_session", 2)
            .apply()

        val sid = MainChat.resolveLaunchSession(context, repo)
        assertTrue(sid.startsWith("__new__"))
    }

    @Test
    fun `resolveLaunchSession returns designated main chat when mode is 3`() = runBlocking {
        context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("launch_session", 3)
            .apply()

        MainChat.set(context, "pinned-main")
        val sMain = session("pinned-main", 1000L)
        Mockito.`when`(dao.getSession("pinned-main")).thenReturn(sMain)

        val sid = MainChat.resolveLaunchSession(context, repo)
        assertEquals("pinned-main", sid)
    }

    @Test
    fun `resolveLaunchSession lands on main chat on cold launch`() = runBlocking {
        val s1 = session("s1", 2000L)
        val s2 = session("s2", 5000L)
        Mockito.`when`(dao.listSessions()).thenReturn(listOf(s2, s1))
        Mockito.`when`(dao.getSession("s2")).thenReturn(s2)
        Mockito.`when`(dao.getSession("s1")).thenReturn(s1)

        val sid = MainChat.resolveLaunchSession(context, repo)
        // Cold launch with no recent activity lands on Main Chat (fresh draft)
        assertTrue(sid.startsWith("__new__"))
    }

    @Test
    fun `resolveLaunchSession prefers lastActiveSession when user was recently viewing it`() = runBlocking {
        val s1 = session("s1", 2000L)
        val s2 = session("s2", 3000L)
        Mockito.`when`(dao.listSessions()).thenReturn(listOf(s2, s1))
        Mockito.`when`(dao.getSession("s1")).thenReturn(s1)
        Mockito.`when`(dao.getSession("s2")).thenReturn(s2)

        // User actively opened s1 just now
        MainChat.setLastActive(context, "s1")

        val sid = MainChat.resolveLaunchSession(context, repo)
        assertEquals("s1", sid)
    }

    @Test
    fun `resolveLaunchSession lands on main chat if last active session is stale`() = runBlocking {
        val s1 = session("s1", 1000L)
        Mockito.`when`(dao.listSessions()).thenReturn(listOf(s1))
        Mockito.`when`(dao.getSession("s1")).thenReturn(s1)

        // User had last opened s1 2 hours ago (stale > 15m)
        context.getSharedPreferences("nanomuse", Context.MODE_PRIVATE)
            .edit()
            .putString("last_active.session", "s1")
            .putLong("last_active.time", System.currentTimeMillis() - 2 * 3600 * 1000L)
            .apply()

        val sid = MainChat.resolveLaunchSession(context, repo)
        assertTrue(sid.startsWith("__new__"))
    }

    @Test
    fun `resolve never hijacks side chats from listSessions`() = runBlocking {
        val s1 = session("side-1", 1000L)
        val s2 = session("side-2", 2000L)
        Mockito.`when`(dao.listSessions()).thenReturn(listOf(s2, s1))

        val resolved = MainChat.resolve(context, repo)
        assertTrue(resolved.startsWith("__new__"))
        // KEY_SESSION in prefs must remain unset/unpolluted
        val persisted = MainChat.persisted(context)
        org.junit.Assert.assertNull(persisted)
    }
}
