package com.openminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ExecutionModeTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val memoryStore = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        memoryStore.clear()
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences("nyanomuse_execution_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String>(1)
            memoryStore[key] = value
            editor
        }
        `when`(prefs.getString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.nullable(String::class.java))).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val def = invocation.getArgument<String?>(1)
            (memoryStore[key] as? String) ?: def
        }
    }

    @Test
    fun testModePersistenceAndSwitching() {
        // Set ROOT mode
        ExecutionMode.setMode(context, ExecutionMode.ROOT)
        assertEquals(ExecutionMode.ROOT, ExecutionMode.getMode(context))
        assertEquals(ExecutionMode.ROOT, ExecutionMode.currentMode(context))

        // Set HOST_SHELL mode
        ExecutionMode.setMode(context, ExecutionMode.HOST_SHELL)
        assertEquals(ExecutionMode.HOST_SHELL, ExecutionMode.getMode(context))

        // Set PROOT_SANDBOX mode
        ExecutionMode.setMode(context, ExecutionMode.PROOT_SANDBOX)
        assertEquals(ExecutionMode.PROOT_SANDBOX, ExecutionMode.getMode(context))
    }

    @Test
    fun testModeTitlesAndDescriptions() {
        assertNotNull(ExecutionMode.ROOT.title)
        assertNotNull(ExecutionMode.ROOT.desc)
        assertEquals("root", ExecutionMode.ROOT.key)

        assertNotNull(ExecutionMode.HOST_SHELL.title)
        assertNotNull(ExecutionMode.HOST_SHELL.desc)
        assertEquals("host_shell", ExecutionMode.HOST_SHELL.key)

        assertNotNull(ExecutionMode.PROOT_SANDBOX.title)
        assertNotNull(ExecutionMode.PROOT_SANDBOX.desc)
        assertEquals("proot", ExecutionMode.PROOT_SANDBOX.key)
    }
}
