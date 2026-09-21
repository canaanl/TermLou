package com.workspace.proot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 笔记条目：文件名去后缀即 name，标签只存库里。 */
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
 * 笔记本数据层：workspace/Notes 下纯 .txt 正文 + 同目录 notes.db（元数据 + FTS5 全文索引）。
 * 正文 .txt 原位保留；标签/待办/时间戳进库；notes_fts 做 trigram 全文索引（中英子串可搜）。
 * 公开 API 与旧版（.txt + index.json）完全一致，调用方零改动。
 * 无 Android 依赖（后端可注入），JVM 单测可直接跑。
 */
class NotesStore(
    notesDir: File,
    openDb: (File) -> NotesDbBackend = { BundledNotesDb(it.absolutePath) }
) {
    private val root: File = notesDir
    private val dbFile: File = File(root, NotesSql.DB_FILE)
    private val db: NotesDbBackend

    init {
        root.mkdirs()
        db = openOrRebuild(openDb)
        migrateIfNeeded()
    }

    /** 目录扫描 + 库合并：目录里有但库里没有的补条目，文件没了的清条目，
     *  归属笔记已被删的待办一并剪掉。库损坏时删库按目录现状重建。 */
    fun reload() {
        root.mkdirs()
        val disk = diskNames()
        val rows = db.query(NotesSql.Q_NOTE_NAMES, types = NotesSql.T_NAME)
            .map { it[0] as String }.toSet()
        db.transaction {
            for (name in disk - rows) {
                val t = fileTime(name)
                db.execArgs(NotesSql.W_INSERT_NOTE, listOf(name, NotesSql.encodeTags(emptyList()), t, t))
                db.execArgs(NotesSql.W_FTS_INSERT, listOf(name, readNote(name)))
            }
            for (name in rows - disk) {
                db.execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
                db.execArgs(NotesSql.W_FTS_DELETE, listOf(name))
                db.execArgs(NotesSql.W_DELETE_NOTE, listOf(name))
            }
            db.exec(NotesSql.W_PRUNE_ORPHAN_TODOS)
        }
    }

    /** 按更新时间倒序，时间相同按名称。 */
    fun listNotes(): List<NoteEntry> =
        db.query(NotesSql.Q_LIST_NOTES, types = NotesSql.T_NOTE_ROW).map { NotesSql.noteEntry(it) }

    /** 全文检索：3 字及以上走 FTS5 trigram；3 字以下 trigram 索引查不到，
     * 退化为标题+正文子串扫描（中文单字可搜）。UI 暂未接线，行为不变。 */
    fun searchNotes(raw: String): List<NoteEntry> {
        val q = raw.trim()
        if (q.isEmpty()) return emptyList()
        if (q.length < 3) {
            val folded = q.lowercase()
            return listNotes().filter { e ->
                e.name.lowercase().contains(folded) || readNote(e.name).lowercase().contains(folded)
            }
        }
        return db.query(NotesSql.Q_FTS_SEARCH, listOf(NotesSql.escapeMatch(q)), NotesSql.T_NAME)
            .mapNotNull { row ->
                val name = row[0] as String
                db.query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW)
                    .firstOrNull()?.let { NotesSql.noteEntry(it) }
            }
    }

    fun noteFile(name: String): File = File(root, "$name.$EXT")

    fun readNote(name: String): String =
        runCatching { noteFile(name).readText(Charsets.UTF_8) }.getOrDefault("")

    /** 写回已存在的笔记；文件不存在则顺手建出来。 */
    fun saveNote(name: String, text: String) {
        root.mkdirs()
        writeAtomic(noteFile(name), text)
        val now = System.currentTimeMillis()
        db.transaction {
            db.execArgs(NotesSql.W_UPSERT_NOTE, listOf(name, NotesSql.encodeTags(emptyList()), now, now))
            db.execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db.execArgs(NotesSql.W_FTS_INSERT, listOf(name, text))
        }
    }

    /** 新建空白笔记：重名自动加后缀，返回最终落定的名称。 */
    fun createNote(desired: String): String {
        root.mkdirs()
        val base = sanitizeName(desired).ifEmpty { defaultName() }
        val name = uniqueName(base)
        val now = System.currentTimeMillis()
        writeAtomic(noteFile(name), "")
        db.transaction {
            db.execArgs(NotesSql.W_INSERT_NOTE, listOf(name, NotesSql.encodeTags(emptyList()), now, now))
            db.execArgs(NotesSql.W_FTS_INSERT, listOf(name, ""))
        }
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
        val now = System.currentTimeMillis()
        db.transaction {
            db.execArgs(NotesSql.W_RENAME_NOTE, listOf(name, now, old))
            db.execArgs(NotesSql.W_REMAP_TODOS, listOf(name, old))
            db.execArgs(NotesSql.W_FTS_DELETE, listOf(old))
            db.execArgs(NotesSql.W_FTS_INSERT, listOf(name, readNote(name)))
        }
        return name
    }

    fun deleteNote(name: String) {
        runCatching { noteFile(name).delete() }
        db.transaction {
            db.execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
            db.execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db.execArgs(NotesSql.W_DELETE_NOTE, listOf(name))
        }
    }

    fun tagsOf(name: String): List<String> =
        db.query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW)
            .firstOrNull()?.let { NotesSql.decodeTags(it[1] as String) }.orEmpty()

    /** 挂标签：存进库（去 "#" 前缀、去空、去重、保序追加）。 */
    fun attachTags(name: String, tags: List<String>) {
        val clean = tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        val row = db.query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
            ?: return
        val merged = (NotesSql.decodeTags(row[1] as String) + clean).distinct()
        db.execArgs(
            NotesSql.W_SET_TAGS,
            listOf(NotesSql.encodeTags(merged), System.currentTimeMillis(), name)
        )
    }

    fun detachTag(name: String, tag: String) {
        val row = db.query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
            ?: return
        val tags = NotesSql.decodeTags(row[1] as String)
        if (!tags.contains(tag)) return
        db.execArgs(
            NotesSql.W_SET_TAGS,
            listOf(NotesSql.encodeTags(tags - tag), System.currentTimeMillis(), name)
        )
    }

    /** 全部标签及挂载篇数，按篇数倒序（并列保持列表序，与旧版一致）。 */
    fun allTags(): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (entry in listNotes()) {
            for (t in entry.tags) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.toList().sortedByDescending { it.second }
    }

    fun notesWithTag(tag: String): List<NoteEntry> = listNotes().filter { it.tags.contains(tag) }

    /** 未完成在前，其次按创建时间（并列按入库序，与旧版一致）。 */
    fun todos(): List<TodoItem> =
        db.query(NotesSql.Q_TODOS, types = NotesSql.T_TODO_ROW).map { NotesSql.todoItem(it) }

    /** 某篇笔记的待办，同全局排序。 */
    fun todosOf(note: String): List<TodoItem> =
        db.query(NotesSql.Q_TODOS_OF, listOf(note), NotesSql.T_TODO_ROW).map { NotesSql.todoItem(it) }

    /** 空内容返回 null，不落盘。 */
    fun addTodo(text: String, note: String = ""): TodoItem? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val item = TodoItem(UUID.randomUUID().toString(), clean, false, System.currentTimeMillis(), note)
        db.execArgs(
            NotesSql.W_INSERT_TODO,
            listOf(item.id, item.text, if (item.done) 1L else 0L, item.createdAt, item.note)
        )
        return item
    }

    fun setTodoDone(id: String, done: Boolean) {
        db.execArgs(NotesSql.W_SET_TODO_DONE, listOf(if (done) 1L else 0L, id))
    }

    /** 空内容忽略，不改不动。 */
    fun setTodoText(id: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        db.execArgs(NotesSql.W_SET_TODO_TEXT, listOf(clean, id))
    }

    fun deleteTodo(id: String) {
        db.execArgs(NotesSql.W_DELETE_TODO, listOf(id))
    }

    /** 已完成数, 未完成数。 */
    fun todoCounts(): Pair<Int, Int> {
        val done = (db.query(NotesSql.Q_TODO_DONE_COUNT, types = NotesSql.T_COUNT)
            .firstOrNull()?.get(0) as? Long) ?: 0L
        val all = (db.query(NotesSql.Q_TODO_ALL_COUNT, types = NotesSql.T_COUNT)
            .firstOrNull()?.get(0) as? Long) ?: 0L
        return done.toInt() to (all - done).toInt()
    }

    /** 默认名：当前时间戳。 */
    fun defaultName(): String = STAMP_FMT.format(Date())

    fun close() {
        runCatching { db.close() }
    }

    private fun openOrRebuild(openDb: (File) -> NotesDbBackend): NotesDbBackend {
        val first = runCatching { openDb(dbFile) }.getOrNull()
        if (first != null) {
            val ok = runCatching {
                NotesSql.schema().forEach { first.exec(it) }
                first.query(NotesSql.Q_TODO_ALL_COUNT, types = NotesSql.T_COUNT)
                true
            }.getOrDefault(false)
            if (ok) return first
            runCatching { first.close() }
        }
        dbFile.delete()
        File(dbFile.parentFile, "${dbFile.name}-journal").delete()
        val backend = openDb(dbFile)
        NotesSql.schema().forEach { backend.exec(it) }
        return backend
    }

    /** 旧版 index.json 一次性事务导入（标签/时间戳/待办全保留），成功后改名 .bak 留作回滚。 */
    private fun migrateIfNeeded() {
        val idx = indexFile()
        if (!idx.isFile) return
        val count = (db.query(NotesSql.Q_TODO_ALL_COUNT, types = NotesSql.T_COUNT)
            .firstOrNull()?.get(0) as? Long ?: 0L) +
            (db.query(NotesSql.Q_NOTE_NAMES, types = NotesSql.T_NAME).size)
        if (count == 0L) {
            val doc = readIndex() ?: return
            val now = System.currentTimeMillis()
            db.transaction {
                val arr = doc.optArr(KEY_NOTES)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getObj(i) ?: continue
                        val n = o.optString(KEY_NAME, "")
                        if (n.isEmpty()) continue
                        val tags = readTags(o)
                        db.execArgs(
                            NotesSql.W_INSERT_NOTE,
                            listOf(n, NotesSql.encodeTags(tags), numOf(o, KEY_CREATED, now), numOf(o, KEY_UPDATED, now))
                        )
                        db.execArgs(NotesSql.W_FTS_INSERT, listOf(n, readNote(n)))
                    }
                }
                for (t in readTodos(doc)) {
                    db.execArgs(
                        NotesSql.W_INSERT_TODO,
                        listOf(t.id, t.text, if (t.done) 1L else 0L, t.createdAt, t.note)
                    )
                }
            }
        }
        runCatching { idx.renameTo(File(idx.parentFile, INDEX_BAK)) }
    }

    private fun dbHas(name: String): Boolean =
        db.query(NotesSql.Q_HAS_NOTE, listOf(name), NotesSql.T_ONE).isNotEmpty()

    private fun diskNames(): Set<String> =
        root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() == EXT }
            .map { it.nameWithoutExtension }
            .toSet()

    private fun fileTime(name: String): Long =
        runCatching { noteFile(name).lastModified() }.getOrDefault(System.currentTimeMillis())

    private fun indexFile(): File = File(File(root, INDEX_DIR), INDEX_FILE)

    private fun uniqueName(base: String): String {
        if (!noteFile(base).exists() && !dbHas(base)) return base
        return suffixedName(base)
    }

    private fun suffixedName(base: String): String {
        var n = FIRST_SUFFIX
        while (n <= MAX_SUFFIX_TRIES) {
            val cand = "$base($n)"
            if (!noteFile(cand).exists() && !dbHas(cand)) return cand
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

    companion object {
        const val EXT = "txt"
        const val INDEX_DIR = ".termlou-notes"
        private const val INDEX_FILE = "index.json"
        private const val INDEX_BAK = "index.json.migrated-bak"
        private const val FIRST_SUFFIX = 2
        private const val MAX_SUFFIX_TRIES = 9999
        private const val TMP_SUFFIX = ".tmp"
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
