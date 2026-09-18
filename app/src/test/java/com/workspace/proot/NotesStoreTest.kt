package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NotesStoreTest {

    private lateinit var dir: File
    private lateinit var store: NotesStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("notes-test").toFile()
        store = NotesStore(dir)
        store.reload()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun names(): List<String> = store.listNotes().map { it.name }

    private fun indexFile(): File = File(File(dir, NotesStore.INDEX_DIR), "index.json")

    @Test
    fun `empty dir lists nothing and writes fresh index`() {
        assertTrue(store.listNotes().isEmpty())
        assertTrue(indexFile().isFile)
    }

    @Test
    fun `blank name falls back to timestamp`() {
        val name = store.createNote("   ")
        assertTrue(name.matches(Regex("\\d{8}_\\d{6}")))
        assertTrue(File(dir, "$name.${NotesStore.EXT}").isFile)
    }

    @Test
    fun `duplicate names get numeric suffix`() {
        assertEquals("a", store.createNote("a"))
        assertEquals("a(2)", store.createNote("a"))
        assertEquals("a(3)", store.createNote("a"))
    }

    @Test
    fun `save and read round-trip`() {
        store.createNote("m")
        store.saveNote("m", "hello")
        assertEquals("hello", store.readNote("m"))
        assertEquals(listOf("m"), names())
    }

    @Test
    fun `rename moves file and keeps tags`() {
        store.createNote("old")
        store.attachTags("old", listOf("t"))
        assertEquals("new", store.renameNote("old", "new"))
        assertEquals(listOf("t"), store.tagsOf("new"))
        assertFalse(File(dir, "old.${NotesStore.EXT}").exists())
        assertTrue(File(dir, "new.${NotesStore.EXT}").exists())
    }

    @Test
    fun `delete removes file and meta`() {
        store.createNote("gone")
        store.deleteNote("gone")
        assertTrue(names().isEmpty())
        assertFalse(File(dir, "gone.${NotesStore.EXT}").exists())
    }

    @Test
    fun `scan picks up external txt and ignores other suffixes`() {
        File(dir, "ext.${NotesStore.EXT}").writeText("x")
        File(dir, "skip.md").writeText("y")
        store.reload()
        assertEquals(listOf("ext"), names())
    }

    @Test
    fun `deleted index rebuilds listing but drops tags and todos`() {
        store.createNote("k")
        store.attachTags("k", listOf("tag"))
        store.addTodo("t1")
        assertTrue(indexFile().delete())
        store.reload()
        assertEquals(listOf("k"), names())
        assertTrue(store.tagsOf("k").isEmpty())
        assertTrue(store.todos().isEmpty())
        assertTrue(indexFile().isFile)
    }

    @Test
    fun `corrupt index rebuilds from disk`() {
        store.createNote("k")
        indexFile().writeText("{oops")
        store.reload()
        assertEquals(listOf("k"), names())
    }

    @Test
    fun `todos full lifecycle`() {
        val item = store.addTodo("  buy milk  ")
        assertEquals("buy milk", item?.text)
        store.setTodoDone(item!!.id, true)
        assertEquals(1 to 0, store.todoCounts())
        store.setTodoText(item.id, "buy bread")
        assertEquals("buy bread", store.todos().single().text)
        store.deleteTodo(item.id)
        assertEquals(0 to 0, store.todoCounts())
    }

    @Test
    fun `blank todo returns null and blank edit is ignored`() {
        assertNull(store.addTodo("   "))
        val item = store.addTodo("keep")!!
        store.setTodoText(item.id, "   ")
        assertEquals("keep", store.todos().single().text)
    }

    @Test
    fun `todos carry note attribution and filter by note`() {
        store.addTodo("global")
        store.addTodo("mine", "n1")
        assertEquals(listOf("mine"), store.todosOf("n1").map { it.text })
        assertEquals(2, store.todos().size)
    }

    @Test
    fun `todo attribution survives reload`() {
        store.addTodo("mine", "n1")
        val reloaded = NotesStore(dir)
        reloaded.reload()
        assertEquals("n1", reloaded.todos().single().note)
    }

    @Test
    fun `rename remaps todo attribution`() {
        store.createNote("a")
        store.addTodo("x", "a")
        store.renameNote("a", "b")
        assertEquals(listOf("x"), store.todosOf("b").map { it.text })
        assertTrue(store.todosOf("a").isEmpty())
    }

    @Test
    fun `delete cascades note todos`() {
        store.createNote("a")
        store.addTodo("x", "a")
        store.addTodo("global")
        store.deleteNote("a")
        assertEquals(listOf("global"), store.todos().map { it.text })
    }

    @Test
    fun `tags attach detach counts and filter`() {
        store.createNote("a")
        store.createNote("b")
        store.attachTags("a", listOf("w", "x"))
        store.attachTags("b", listOf("w"))
        assertEquals(listOf("w" to 2, "x" to 1), store.allTags())
        store.detachTag("a", "w")
        assertEquals(setOf("w" to 1, "x" to 1), store.allTags().toSet())
        assertEquals(listOf("a"), store.notesWithTag("x").map { it.name })
    }

    @Test
    fun `tag input is sanitized`() {
        store.createNote("n")
        store.attachTags("n", listOf("#a", "  ", "a", "#b"))
        assertEquals(listOf("a", "b"), store.tagsOf("n"))
    }

    @Test
    fun `sanitizeName strips separators and txt suffix`() {
        assertEquals("a_b", NotesStore.sanitizeName("a/b.${NotesStore.EXT}"))
        assertEquals("plain", NotesStore.sanitizeName("  plain  "))
        assertEquals("", NotesStore.sanitizeName("   "))
    }
}
