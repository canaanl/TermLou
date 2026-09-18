package com.workspace.proot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 笔记条目：文件名去后缀即 name，标签只存索引里。 */
data class NoteEntry(
    val name: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** 待办项：note 为所属笔记名（空 = 老数据无归属，仅全局可见）。 */
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = 0L,
    val note: String = ""
)

/**
 * 笔记本数据层：workspace/Notes 下纯 .txt 文件 + 隐藏索引 .termlou-notes/index.json。
 * 索引只记标签/待办/时间戳，正文本体永远是文件本身；索引缺失或损坏时按目录现状重建。
 * 无 Android 依赖，JVM 单测可直接跑。
 */
class NotesStore(notesDir: File) {
    private val root: File = notesDir
    private val meta = LinkedHashMap<String, NoteMeta>()
    private val todos = ArrayList<TodoItem>()

    private data class NoteMeta(var tags: List<String>, var createdAt: Long, var updatedAt: Long)

    /** 目录扫描 + 索引合并：目录里有但索引没有的补条目，文件没了的清条目，
     *  归属笔记已被删的待办一并剪掉，索引丢了就地重建。 */
    fun reload() {
        root.mkdirs()
        val disk = diskNames()
        val idx = readIndex()
        val nextMeta = LinkedHashMap<String, NoteMeta>()
        for (name in disk) {
            nextMeta[name] = findMeta(idx, name) ?: NoteMeta(emptyList(), fileTime(name), fileTime(name))
        }
        meta.clear()
        meta.putAll(nextMeta)
        val loaded = readTodos(idx)
        val kept = loaded.filter { it.note.isEmpty() || nextMeta.containsKey(it.note) }
        todos.clear()
        todos.addAll(kept)
        val pruned = kept.size != loaded.size
        if (idx == null || pruned || indexNames(idx) != disk) persist()
    }

    /** 按更新时间倒序，时间相同按名称。 */
    fun listNotes(): List<NoteEntry> =
        meta.map { (name, m) -> NoteEntry(name, m.tags, m.createdAt, m.updatedAt) }
            .sortedWith(compareByDescending<NoteEntry> { it.updatedAt }.thenBy { it.name })

    fun noteFile(name: String): File = File(root, "$name.$EXT")

    fun readNote(name: String): String =
        runCatching { noteFile(name).readText(Charsets.UTF_8) }.getOrDefault("")

    /** 写回已存在的笔记；文件不存在则顺手建出来。 */
    fun saveNote(name: String, text: String) {
        root.mkdirs()
        writeAtomic(noteFile(name), text)
        val now = System.currentTimeMillis()
        val m = meta[name]
        if (m == null) {
            meta[name] = NoteMeta(emptyList(), now, now)
        } else {
            m.updatedAt = now
        }
        persist()
    }

    /** 新建空白笔记：重名自动加后缀，返回最终落定的名称。 */
    fun createNote(desired: String): String {
        root.mkdirs()
        val base = sanitizeName(desired).ifEmpty { defaultName() }
        val name = uniqueName(base)
        val now = System.currentTimeMillis()
        writeAtomic(noteFile(name), "")
        meta[name] = NoteMeta(emptyList(), now, now)
        persist()
        return name
    }

    /** 重命名：返回最终名称（与原名相同则直接返回）。 */
    fun renameNote(old: String, desired: String): String {
        val base = sanitizeName(desired).ifEmpty { defaultName() }
        if (base == old) return old
        val name = uniqueName(base)
        runCatching {
            val src = noteFile(old)
            if (src.isFile) src.renameTo(noteFile(name))
        }
        val m = meta.remove(old)
        if (m != null) {
            m.updatedAt = System.currentTimeMillis()
            meta[name] = m
        }
        for (i in todos.indices) {
            if (todos[i].note == old) todos[i] = todos[i].copy(note = name)
        }
        persist()
        return name
    }

    fun deleteNote(name: String) {
        runCatching { noteFile(name).delete() }
        meta.remove(name)
        todos.removeAll { it.note == name }
        persist()
    }

    fun tagsOf(name: String): List<String> = meta[name]?.tags.orEmpty()

    /** 挂标签：存进索引（去 "#" 前缀、去空、去重、保序追加）。 */
    fun attachTags(name: String, tags: List<String>) {
        val clean = tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        val m = meta[name] ?: return
        m.tags = (m.tags + clean).distinct()
        m.updatedAt = System.currentTimeMillis()
        persist()
    }

    fun detachTag(name: String, tag: String) {
        val m = meta[name] ?: return
        if (!m.tags.contains(tag)) return
        m.tags = m.tags - tag
        m.updatedAt = System.currentTimeMillis()
        persist()
    }

    /** 全部标签及挂载篇数，按篇数倒序。 */
    fun allTags(): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (entry in listNotes()) {
            for (t in entry.tags) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.toList().sortedByDescending { it.second }
    }

    fun notesWithTag(tag: String): List<NoteEntry> = listNotes().filter { it.tags.contains(tag) }

    /** 未完成在前，其次按创建时间。 */
    fun todos(): List<TodoItem> = todos.sortedWith(compareBy<TodoItem> { it.done }.thenBy { it.createdAt })

    /** 某篇笔记的待办，同全局排序。 */
    fun todosOf(note: String): List<TodoItem> =
        todos().filter { it.note == note }

    /** 空内容返回 null，不落盘。 */
    fun addTodo(text: String, note: String = ""): TodoItem? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val item = TodoItem(UUID.randomUUID().toString(), clean, false, System.currentTimeMillis(), note)
        todos.add(item)
        persist()
        return item
    }

    fun setTodoDone(id: String, done: Boolean) {
        val i = todos.indexOfFirst { it.id == id }
        if (i < 0) return
        todos[i] = todos[i].copy(done = done)
        persist()
    }

    /** 空内容忽略，不改不动。 */
    fun setTodoText(id: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val i = todos.indexOfFirst { it.id == id }
        if (i < 0) return
        todos[i] = todos[i].copy(text = clean)
        persist()
    }

    fun deleteTodo(id: String) {
        if (todos.removeAll { it.id == id }) persist()
    }

    /** 已完成数, 未完成数。 */
    fun todoCounts(): Pair<Int, Int> {
        val done = todos.count { it.done }
        return done to (todos.size - done)
    }

    /** 默认名：当前时间戳。 */
    fun defaultName(): String = STAMP_FMT.format(Date())

    private fun diskNames(): Set<String> =
        root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() == EXT }
            .map { it.nameWithoutExtension }
            .toSet()

    private fun fileTime(name: String): Long =
        runCatching { noteFile(name).lastModified() }.getOrDefault(System.currentTimeMillis())

    private fun indexFile(): File = File(File(root, INDEX_DIR), INDEX_FILE)

    private fun uniqueName(base: String): String {
        if (!noteFile(base).exists() && !meta.containsKey(base)) return base
        return suffixedName(base)
    }

    private fun suffixedName(base: String): String {
        var n = FIRST_SUFFIX
        while (n <= MAX_SUFFIX_TRIES) {
            val cand = "$base($n)"
            if (!noteFile(cand).exists() && !meta.containsKey(cand)) return cand
            n++
        }
        return "$base-${System.currentTimeMillis()}"
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}$TMP_SUFFIX")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readIndex(): MiniJson.Obj? =
        runCatching {
            val f = indexFile()
            if (!f.isFile) return null
            MiniJson.parse(f.readText(Charsets.UTF_8))
        }.getOrNull()

    private fun indexNames(idx: MiniJson.Obj?): Set<String> {
        val arr = idx?.optArr(KEY_NOTES) ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            arr.getObj(i)?.optString(KEY_NAME, "")?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    private fun findMeta(idx: MiniJson.Obj?, name: String): NoteMeta? {
        val arr = idx?.optArr(KEY_NOTES) ?: return null
        var found: NoteMeta? = null
        var i = 0
        while (i < arr.length() && found == null) {
            val o = arr.getObj(i)
            if (o != null && o.optString(KEY_NAME, "") == name) {
                found = NoteMeta(readTags(o), numOf(o, KEY_CREATED, 0L), numOf(o, KEY_UPDATED, 0L))
            }
            i++
        }
        return found
    }

    private fun readTags(o: MiniJson.Obj): List<String> {
        val arr = o.optArr(KEY_TAGS) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.getString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.distinct()
    }

    private fun readTodos(idx: MiniJson.Obj?): List<TodoItem> {
        val arr = idx?.optArr(KEY_TODOS) ?: return emptyList()
        val out = ArrayList<TodoItem>()
        for (i in 0 until arr.length()) {
            todoOf(arr.getObj(i))?.let { out.add(it) }
        }
        return out
    }

    private fun todoOf(o: MiniJson.Obj?): TodoItem? {
        val text = o?.optString(KEY_TEXT, "").orEmpty().trim()
        return if (o == null || text.isEmpty()) {
            null
        } else {
            TodoItem(
                o.optString(KEY_ID, UUID.randomUUID().toString()),
                text,
                o.optBoolean(KEY_DONE, false),
                numOf(o, KEY_CREATED, 0L),
                o.optString(KEY_NOTE, "")
            )
        }
    }

    private fun numOf(o: MiniJson.Obj, key: String, default: Long): Long =
        (o.raw(key) as? Number)?.toLong() ?: default

    private fun persist() {
        val notesArr = MiniJson.Arr()
        for ((name, m) in meta) {
            val tagsArr = MiniJson.Arr()
            for (t in m.tags) tagsArr.put(t)
            notesArr.put(
                MiniJson.Obj()
                    .put(KEY_NAME, name)
                    .put(KEY_TAGS, tagsArr)
                    .put(KEY_CREATED, m.createdAt)
                    .put(KEY_UPDATED, m.updatedAt)
            )
        }
        val todosArr = MiniJson.Arr()
        for (t in todos) {
            todosArr.put(
                MiniJson.Obj()
                    .put(KEY_ID, t.id)
                    .put(KEY_TEXT, t.text)
                    .put(KEY_DONE, t.done)
                    .put(KEY_CREATED, t.createdAt)
                    .put(KEY_NOTE, t.note)
            )
        }
        val doc = MiniJson.Obj()
            .put(KEY_VERSION, INDEX_VERSION)
            .put(KEY_NOTES, notesArr)
            .put(KEY_TODOS, todosArr)
        writeAtomic(indexFile(), doc.toString())
    }

    companion object {
        const val EXT = "txt"
        const val INDEX_DIR = ".termlou-notes"
        private const val INDEX_FILE = "index.json"
        private const val INDEX_VERSION = 2
        private const val FIRST_SUFFIX = 2
        private const val MAX_SUFFIX_TRIES = 9999
        private const val TMP_SUFFIX = ".tmp"
        private const val KEY_VERSION = "version"
        private const val KEY_NOTES = "notes"
        private const val KEY_TODOS = "todos"
        private const val KEY_NAME = "name"
        private const val KEY_TAGS = "tags"
        private const val KEY_TEXT = "text"
        private const val KEY_ID = "id"
        private const val KEY_DONE = "done"
        private const val KEY_NOTE = "note"
        private const val KEY_CREATED = "createdAt"
        private const val KEY_UPDATED = "updatedAt"

        private val STAMP_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /** 去路径分隔符、去 .txt 后缀；空则返回空串由调用方填时间戳。 */
        fun sanitizeName(raw: String): String {
            var s = raw.trim().replace('/', '_').replace('\\', '_')
            if (s.length > EXT.length + 1 && s.endsWith(".$EXT", ignoreCase = true)) {
                s = s.dropLast(EXT.length + 1)
            }
            return s.trim()
        }
    }
}
