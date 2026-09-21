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
        store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        store.reload()
    }

    @After
    fun tearDown() {
        runCatching { store.close() }
        dir.deleteRecursively()
    }

    private fun names(): List<String> = store.listNotes().map { it.name }

    private fun dbFile(): File = File(dir, NotesSql.DB_FILE)

    @Test
    fun `empty dir lists nothing and writes fresh db`() {
        assertTrue(store.listNotes().isEmpty())
        assertTrue(dbFile().isFile)
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
    fun `deleted db rebuilds listing but drops tags and todos`() {
        store.createNote("k")
        store.attachTags("k", listOf("tag"))
        store.addTodo("t1")
        runCatching { store.close() }
        assertTrue(dbFile().delete())
        store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        store.reload()
        assertEquals(listOf("k"), names())
        assertTrue(store.tagsOf("k").isEmpty())
        assertTrue(store.todos().isEmpty())
        assertTrue(dbFile().isFile)
    }

    @Test
    fun `corrupt db rebuilds from disk`() {
        store.createNote("k")
        runCatching { store.close() }
        dbFile().writeText("{oops")
        store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
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
        store.createNote("n1")
        store.addTodo("mine", "n1")
        val reloaded = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        reloaded.reload()
        assertEquals("n1", reloaded.todos().single().note)
        runCatching { reloaded.close() }
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
    fun `external note deletion prunes its attributed todos and self-heals`() {
        store.createNote("a")
        store.addTodo("x", "a")
        store.addTodo("global")
        store.noteFile("a").delete()
        val reloaded = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        reloaded.reload()
        assertEquals(listOf("global"), reloaded.todos().map { it.text })
        runCatching { reloaded.close() }
        val again = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        again.reload()
        assertEquals(listOf("global"), again.todos().map { it.text })
        runCatching { again.close() }
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

    @Test
    fun `fts finds chinese substring in body`() {
        store.createNote("cn")
        store.saveNote("cn", "明天去超市买牛奶和面包")
        assertEquals(listOf("cn"), store.searchNotes("超市").map { it.name })
        assertEquals(listOf("cn"), store.searchNotes("牛奶和面").map { it.name })
    }

    @Test
    fun `fts finds note by name`() {
        store.createNote("旅行计划")
        assertEquals(listOf("旅行计划"), store.searchNotes("旅行").map { it.name })
    }

    @Test
    fun `fts follows updates deletes and renames`() {
        store.createNote("a")
        store.saveNote("a", "香蕉苹果")
        assertEquals(listOf("a"), store.searchNotes("香蕉").map { it.name })
        store.saveNote("a", "只剩橙子")
        assertTrue(store.searchNotes("香蕉").isEmpty())
        assertEquals(listOf("a"), store.searchNotes("橙子").map { it.name })
        assertEquals("b", store.renameNote("a", "b"))
        assertEquals(listOf("b"), store.searchNotes("橙子").map { it.name })
        store.deleteNote("b")
        assertTrue(store.searchNotes("橙子").isEmpty())
    }

    @Test
    fun `fts blank query returns empty`() {
        store.createNote("a")
        store.saveNote("a", "hello world")
        assertTrue(store.searchNotes("   ").isEmpty())
    }

    @Test
    fun `fts quote in query does not break`() {
        store.createNote("a")
        store.saveNote("a", "say \"hi\" loudly")
        assertEquals(listOf("a"), store.searchNotes("\"hi\"").map { it.name })
    }

    @Test
    fun `legacy index json migrates into db and is renamed to bak`() {
        runCatching { store.close() }
        File(dir, "k.${NotesStore.EXT}").writeText("body text")
        val idxDir = File(dir, NotesStore.INDEX_DIR).apply { mkdirs() }
        File(idxDir, "index.json").writeText(
            "{\"version\":2," +
                "\"notes\":[{\"name\":\"k\",\"tags\":[\"t\"],\"createdAt\":1000,\"updatedAt\":2000}]," +
                "\"todos\":[{\"id\":\"x\",\"text\":\"job\",\"done\":false,\"createdAt\":3000,\"note\":\"k\"}]}"
        )
        assertTrue(dbFile().delete())
        store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        assertEquals(listOf("t"), store.tagsOf("k"))
        assertEquals(listOf("job"), store.todos().map { it.text })
        assertEquals("k", store.todos().single().note)
        assertEquals("body text", store.readNote("k"))
        assertFalse(File(idxDir, "index.json").exists())
        assertTrue(File(idxDir, "index.json.migrated-bak").isFile)
        assertTrue(dbFile().isFile)
    }
}
