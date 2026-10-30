@file:Suppress("MagicNumber")

package com.workspace.proot

import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileListManagerTest {

    private lateinit var context: android.content.Context
    private lateinit var root: File
    private lateinit var child: File
    private lateinit var manager: FileListManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.filesDir, "workspace").apply { mkdirs() }
        child = File(root, "nested").apply { mkdirs() }
        manager = FileListManager(context, ThemeColors.default(true))
    }

    @Test
    fun `root has no parent but a nested folder does`() {
        manager.setCurrentDir(root)
        assertFalse(manager.canNavigateUp())

        manager.setCurrentDir(child)
        assertTrue(manager.canNavigateUp())
    }

    @Test
    fun `navigateUp moves one level and stops at the workspace root`() {
        var changes = 0
        manager.setOnDirectoryChanged { changes++ }
        manager.setCurrentDir(child)

        val list = LinearLayout(context)
        assertTrue(manager.navigateUp(list))
        assertEquals(root.absolutePath, manager.getCurrentDir().absolutePath)
        assertFalse(manager.canNavigateUp())
        assertFalse("must not climb above /workspace", manager.navigateUp(list))
        assertEquals(2, changes)
    }

    @Test
    fun `the old top back row is gone`() {
        manager.setCurrentDir(child)
        val list = LinearLayout(context)
        manager.refreshFileList(list, {}, {})

        val hasBackRow = (0 until list.childCount).any { i ->
            val text = (list.getChildAt(i) as? android.widget.TextView)?.text?.toString()
            text == context.getString(R.string.file_back)
        }
        assertFalse("the FAB is now the only way up", hasBackRow)
    }
}
