package com.workspace.proot

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsManagerTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var manager: SettingsManager

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        manager = SettingsManager(prefs)
    }

    @Test
    fun `load reads fontSizeIndex from prefs`() {
        every { prefs.getInt("fontSizeIndex", 2) } returns 3
        manager.load()
        assertEquals(3, manager.fontSizeIndex)
        assertEquals(34, manager.fontSizeSp)
    }

    @Test
    fun `load reads shellCmd from prefs`() {
        every { prefs.getString("shellCmd", "") } returns "/bin/zsh"
        manager.load()
        assertEquals("/bin/zsh", manager.shellCmd)
    }

    @Test
    fun `load reads keepAlive from prefs`() {
        every { prefs.getBoolean("keepAlive", false) } returns true
        manager.load()
        assertTrue(manager.keepAlive)
    }

    @Test
    fun `setFontSizeIndex updates fontSizeSp`() {
        manager.setFontSizeIndex(4)
        assertEquals(4, manager.fontSizeIndex)
        assertEquals(40, manager.fontSizeSp)
        verify { editor.putInt("fontSizeIndex", 4) }
    }

    @Test
    fun `setShellCmd updates shellCmd`() {
        manager.setShellCmd("/bin/bash")
        assertEquals("/bin/bash", manager.shellCmd)
        verify { editor.putString("shellCmd", "/bin/bash") }
    }

    @Test
    fun `setKeepAlive updates keepAlive`() {
        manager.setKeepAlive(true)
        assertTrue(manager.keepAlive)
        verify { editor.putBoolean("keepAlive", true) }
    }

    @Test
    fun `fontSizes list has correct values`() {
        assertEquals(listOf(18, 22, 28, 34, 40), manager.fontSizes)
    }

    @Test
    fun `fontNames list has correct values`() {
        assertEquals(listOf("极小", "小", "中等", "大", "极大"), manager.fontNames)
    }
}
