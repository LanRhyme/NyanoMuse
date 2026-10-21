package com.openminis.app.notification

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class BackgroundTaskNotifierTest {

    private class PrefsContext(val nm: NotificationManager) : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MapPrefs>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) { MapPrefs() }
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = if (name == Context.NOTIFICATION_SERVICE) nm else null
        override fun getSystemServiceName(serviceClass: Class<*>): String? =
            if (serviceClass == NotificationManager::class.java) Context.NOTIFICATION_SERVICE else null
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

    private lateinit var nm: NotificationManager
    private lateinit var context: PrefsContext
    private lateinit var dao: ChatDao
    private lateinit var repo: ChatRepository
    private lateinit var bgRepo: BackgroundSettingsRepository
    private var isForeground = false

    @Before
    fun setUp() {
        nm = Mockito.mock(NotificationManager::class.java)
        context = PrefsContext(nm)
        dao = Mockito.mock(ChatDao::class.java)
        repo = ChatRepository(dao)
        bgRepo = BackgroundSettingsRepository(context)
        isForeground = false
        ChatViewModelStore.setActiveSession(null)
    }

    @Test
    fun `notifyTaskCompleted does nothing when task notifications are disabled`() {
        bgRepo.setTaskNotificationsEnabled(false)
        val notifier = BackgroundTaskNotifier(context, repo, bgRepo, isAppForeground = { isForeground })

        notifier.notifyTaskCompleted("s1")
        Mockito.verifyNoInteractions(dao)
    }

    @Test
    fun `notifyTaskCompleted suppresses status notification when actively viewing current session in foreground`() {
        bgRepo.setTaskNotificationsEnabled(true)
        isForeground = true
        ChatViewModelStore.setActiveSession("s1")

        val notifier = BackgroundTaskNotifier(context, repo, bgRepo, isAppForeground = { isForeground })

        notifier.notifyTaskCompleted("s1")
        Mockito.verifyNoInteractions(dao)
    }

    @Test
    fun `notifyTaskCompleted proceeds when user is in foreground but on another screen`() {
        runBlocking {
            bgRepo.setTaskNotificationsEnabled(true)
            isForeground = true
            ChatViewModelStore.setActiveSession("s_other")

            val s1 = ChatSessionEntity("s1", "Test Chat", "m1", 1000L, 2000L)
            Mockito.`when`(dao.getSession("s1")).thenReturn(s1)

            val notifier = BackgroundTaskNotifier(context, repo, bgRepo, isAppForeground = { isForeground })
            notifier.notifyTaskCompleted("s1")

            // Wait brief tick for coroutine
            Thread.sleep(100)
            Mockito.verify(dao).getSession("s1")
        }
    }

    @Test
    fun `cancelNotification cancels notification with matching session hash`() {
        val notifier = BackgroundTaskNotifier(context, repo, bgRepo, isAppForeground = { isForeground })
        notifier.cancelNotification("s_to_cancel")

        Mockito.verify(nm).cancel("s_to_cancel".hashCode())
    }
}
