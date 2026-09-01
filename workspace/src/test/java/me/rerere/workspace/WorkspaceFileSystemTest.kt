package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class WorkspaceFileSystemTest {

    private lateinit var testRoot: File
    private lateinit var fileSystem: WorkspaceFileSystem

    @Before
    fun setUp() {
        testRoot = File(System.getProperty("java.io.tmpdir"), "workspace_test_${System.currentTimeMillis()}")
        testRoot.mkdirs()
        fileSystem = WorkspaceFileSystem()
    }

    @Test
    fun `list should return files sorted by directory then name`() {
        // Create test files
        File(testRoot, "b_file.txt").writeText("content")
        File(testRoot, "a_file.txt").writeText("content")
        val subDir = File(testRoot, "subdir")
        subDir.mkdirs()
        File(subDir, "c_file.txt").writeText("content")

        val entries = fileSystem.list(testRoot)

        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("subdir", entries[0].name)
        assertEquals("a_file.txt", entries[1].name)
        assertEquals("b_file.txt", entries[2].name)
    }

    @Test
    fun `list should filter out hidden files starting with l2s`() {
        File(testRoot, ".l2s.config").writeText("config")
        File(testRoot, "normal.txt").writeText("content")

        val entries = fileSystem.list(testRoot)

        assertEquals(1, entries.size)
        assertEquals("normal.txt", entries[0].name)
    }

    @Test
    fun `readText should read file content`() {
        val file = File(testRoot, "test.txt")
        file.writeText("Hello, World!")

        val content = fileSystem.readText(testRoot, "test.txt")

        assertEquals("Hello, World!", content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `readText should throw for non existent file`() {
        fileSystem.readText(testRoot, "nonexistent.txt")
    }

    @Test
    fun `writeText should create file with content`() {
        val entry = fileSystem.writeText(testRoot, "new_file.txt", "New content")

        assertTrue(File(testRoot, "new_file.txt").exists())
        assertEquals("New content", File(testRoot, "new_file.txt").readText())
        assertEquals("new_file.txt", entry.name)
        assertFalse(entry.isDirectory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writeText should throw when overwrite is false and file exists`() {
        File(testRoot, "existing.txt").writeText("old content")
        fileSystem.writeText(testRoot, "existing.txt", "new content", overwrite = false)
    }

    @Test
    fun `delete should remove file`() {
        val file = File(testRoot, "to_delete.txt")
        file.writeText("content")

        val result = fileSystem.delete(testRoot, "to_delete.txt")

        assertTrue(result)
        assertFalse(file.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `delete should throw when trying to delete root`() {
        fileSystem.delete(testRoot, ".")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `move should throw when trying to move root`() {
        fileSystem.move(testRoot, ".", "target")
    }

    @Test
    fun `move should rename file`() {
        File(testRoot, "source.txt").writeText("content")

        val entry = fileSystem.move(testRoot, "source.txt", "target.txt")

        assertFalse(File(testRoot, "source.txt").exists())
        assertTrue(File(testRoot, "target.txt").exists())
        assertEquals("target.txt", entry.name)
    }

    @Test
    fun `glob should find matching files`() {
        File(testRoot, "file1.txt").writeText("content")
        File(testRoot, "file2.txt").writeText("content")
        File(testRoot, "other.md").writeText("content")

        val entries = fileSystem.glob(testRoot, "*.txt")

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.name.endsWith(".txt") })
    }

    @Test
    fun `grep should find matching text in files`() {
        File(testRoot, "file1.txt").writeText("Hello World")
        File(testRoot, "file2.txt").writeText("Goodbye World")

        val matches = fileSystem.grep(testRoot, "World")

        assertEquals(2, matches.size)
        assertTrue(matches.all { it.text.contains("World") })
    }

    @Test
    fun `WorkspaceConfig should have correct defaults`() {
        val config = WorkspaceConfig()

        assertEquals(512 * 1024L, config.maxReadBytes)
        assertEquals(2 * 1024 * 1024L, config.maxWriteBytes)
        assertEquals(500, config.maxListEntries)
        assertEquals(100, config.maxSearchResults)
    }
}
