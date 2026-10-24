package io.github.nanomuse.tools

import com.openminis.app.tools.NativeSystemTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSystemToolsTest {

    @Test
    fun testMakeDefinitionsContainsAllTools() {
        val defs = NativeSystemTools.makeDefinitions()
        val names = defs.map { it.name }.toSet()

        assertTrue(names.contains(NativeSystemTools.TOOL_DEVICE_INFO))
        assertTrue(names.contains(NativeSystemTools.TOOL_FOREGROUND_APP))
        assertTrue(names.contains(NativeSystemTools.TOOL_DEVICE_CONTROL))
        assertTrue(names.contains(NativeSystemTools.TOOL_APP_MANAGE))
        assertTrue(names.contains(NativeSystemTools.TOOL_SCREEN_INTERACT))
        assertTrue(names.contains(NativeSystemTools.TOOL_APP_LAUNCH))
        assertTrue(names.contains(NativeSystemTools.TOOL_SYSTEM_NOTIFY))
        assertTrue(names.contains(NativeSystemTools.TOOL_SCREEN_CAPTURE))
        assertTrue(names.contains(NativeSystemTools.TOOL_IMAGE_GENERATE))
    }

    @Test
    fun testDeviceControlDefinition() {
        val def = NativeSystemTools.deviceControlDefinition()
        assertEquals(NativeSystemTools.TOOL_DEVICE_CONTROL, def.name)
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.required.contains("action"))
        assertNotNull(def.parameters["action"])
        assertNotNull(def.parameters["sub_action"])
        assertNotNull(def.parameters["stream"])
        assertNotNull(def.parameters["value"])
        assertNotNull(def.parameters["text"])
        assertNotNull(def.parameters["settings_page"])
        assertNotNull(def.parameters["url"])
        assertNotNull(def.parameters["phone_number"])
    }

    @Test
    fun testAppManageDefinition() {
        val def = NativeSystemTools.appManageDefinition()
        assertEquals(NativeSystemTools.TOOL_APP_MANAGE, def.name)
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.required.contains("action"))
        assertNotNull(def.parameters["query"])
        assertNotNull(def.parameters["package_name"])
        assertNotNull(def.parameters["user_apps_only"])
        assertNotNull(def.parameters["limit"])
    }

    @Test
    fun testScreenInteractDefinition() {
        val def = NativeSystemTools.screenInteractDefinition()
        assertEquals(NativeSystemTools.TOOL_SCREEN_INTERACT, def.name)
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.required.contains("action"))
        assertNotNull(def.parameters["coordinate_x"])
        assertNotNull(def.parameters["coordinate_y"])
        assertNotNull(def.parameters["direction"])
        assertNotNull(def.parameters["text"])
        assertNotNull(def.parameters["element_text"])
        assertNotNull(def.parameters["resource_id"])
        assertNotNull(def.parameters["key"])
    }

    @Test
    fun testReadOnlyClassification() {
        assertTrue(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_DEVICE_INFO))
        assertTrue(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_FOREGROUND_APP))
        assertTrue(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_SCREEN_CAPTURE))
        assertFalse(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_DEVICE_CONTROL))
        assertFalse(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_APP_MANAGE))
        assertFalse(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_SCREEN_INTERACT))
        assertFalse(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_APP_LAUNCH))
        assertFalse(NativeSystemTools.isReadOnly(NativeSystemTools.TOOL_SYSTEM_NOTIFY))
    }
}
