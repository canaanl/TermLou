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
    private val openDb: (File) -> NotesDbBackend = { BundledNotesDb(it.absolutePath) }
) {
    private val root: File = notesDir
    private val dbFile: File = File(root, NotesSql.DB_FILE)
    private var backend: NotesDbBackend

    init {
        root.mkdirs()
        backend = openOrRebuild(openDb)
    }

    /** 连接自愈：库文件被外部删除（删库/端掉整个目录）时，旧连接悬空会报只读；
     * 每次用库前判文件在否，没了就关旧连接重建空库，再走正常 reload 语义。 */
    private fun db(): NotesDbBackend {
        if (!dbFile.isFile) {
            runCatching { backend.close() }
            root.mkdirs()
            backend = openOrRebuild(openDb)
        }
        return backend
    }

    /** 目录扫描 + 库合并：目录里有但库里没有的补条目，文件没了的清条目，
     *  归属笔记已被删的待办一并剪掉。库损坏时删库按目录现状重建。 */
    fun reload() {
        root.mkdirs()
        val disk = diskNames()
        val rows = db().query(NotesSql.Q_NOTE_NAMES, types = NotesSql.T_NAME)
            .map { it[0] as String }.toSet()
        db().transaction {
            for (name in disk - rows) {
                val raw = runCatching { noteFile(name).readText(Charsets.UTF_8) }.getOrDefault("")
                val (head, body) = parseHead(raw)
                val created = head?.created ?: fileTime(name)
                val updated = head?.updated ?: fileTime(name)
                val tags = head?.tags.orEmpty()
                db().execArgs(NotesSql.W_INSERT_NOTE, listOf(name, NotesSql.encodeTags(tags), created, updated))
                for (t in head?.todos.orEmpty()) {
                    db().execArgs(
                        NotesSql.W_INSERT_TODO,
                        listOf(t.id, t.text, if (t.done) 1L else 0L, t.createdAt, name)
                    )
                }
                db().execArgs(NotesSql.W_FTS_INSERT, listOf(name, body))
            }
            for (name in rows - disk) {
                db().execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
                db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
                db().execArgs(NotesSql.W_DELETE_NOTE, listOf(name))
            }
            db().exec(NotesSql.W_PRUNE_ORPHAN_TODOS)
        }
    }

    /** 按更新时间倒序，时间相同按名称。 */
    fun listNotes(): List<NoteEntry> =
        db().query(NotesSql.Q_LIST_NOTES, types = NotesSql.T_NOTE_ROW).map { NotesSql.noteEntry(it) }

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
        return db().query(NotesSql.Q_FTS_SEARCH, listOf(NotesSql.escapeMatch(q)), NotesSql.T_NAME)
            .mapNotNull { row ->
                val name = row[0] as String
                db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW)
                    .firstOrNull()?.let { NotesSql.noteEntry(it) }
            }
    }

    fun noteFile(name: String): File = File(root, "$name.$EXT")

    /** 读正文：剥掉头部块，UI 永远看不到元数据。 */
    fun readNote(name: String): String {
        val raw = runCatching { noteFile(name).readText(Charsets.UTF_8) }.getOrDefault("")
        return parseHead(raw).second
    }

    /** 写回已存在的笔记；文件不存在则顺手建出来。头部与正文单点写透。 */
    fun saveNote(name: String, text: String) {
        root.mkdirs()
        val now = System.currentTimeMillis()
        val row = db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
        val created = (row?.get(2) as? Long) ?: now
        val tags = row?.let { NotesSql.decodeTags(it[1] as String) }.orEmpty()
        val todos = if (row == null) emptyList() else todosOf(name)
        writeAtomic(noteFile(name), buildHead(created, now, tags, todos) + text)
        db().transaction {
            db().execArgs(NotesSql.W_UPSERT_NOTE, listOf(name, NotesSql.encodeTags(tags), created, now))
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db().execArgs(NotesSql.W_FTS_INSERT, listOf(name, text))
        }
    }

    /** 新建空白笔记：重名自动加后缀，返回最终落定的名称。 */
    fun createNote(desired: String): String {
        root.mkdirs()
        val base = sanitizeName(desired).ifEmpty { defaultName() }
        val name = uniqueName(base)
        val now = System.currentTimeMillis()
        writeAtomic(noteFile(name), buildHead(now, now, emptyList(), emptyList()))
        db().transaction {
            db().execArgs(NotesSql.W_INSERT_NOTE, listOf(name, NotesSql.encodeTags(emptyList()), now, now))
            db().execArgs(NotesSql.W_FTS_INSERT, listOf(name, ""))
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
        db().transaction {
            db().execArgs(NotesSql.W_RENAME_NOTE, listOf(name, now, old))
            db().execArgs(NotesSql.W_REMAP_TODOS, listOf(name, old))
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(old))
            db().execArgs(NotesSql.W_FTS_INSERT, listOf(name, readNote(name)))
        }
        refreshHeader(name)
        return name
    }

    fun deleteNote(name: String) {
        runCatching { noteFile(name).delete() }
        db().transaction {
            db().execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db().execArgs(NotesSql.W_DELETE_NOTE, listOf(name))
        }
    }

    fun tagsOf(name: String): List<String> =
        db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW)
            .firstOrNull()?.let { NotesSql.decodeTags(it[1] as String) }.orEmpty()

    /** 挂标签：存进库（去 "#" 前缀、去空、去重、保序追加）。 */
    fun attachTags(name: String, tags: List<String>) {
        val clean = tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        val row = db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
            ?: return
        val merged = (NotesSql.decodeTags(row[1] as String) + clean).distinct()
        val now = System.currentTimeMillis()
        db().execArgs(NotesSql.W_SET_TAGS, listOf(NotesSql.encodeTags(merged), now, name))
        refreshHeader(name)
    }

    fun detachTag(name: String, tag: String) {
        val row = db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
            ?: return
        val tags = NotesSql.decodeTags(row[1] as String)
        if (!tags.contains(tag)) return
        db().execArgs(
            NotesSql.W_SET_TAGS,
            listOf(NotesSql.encodeTags(tags - tag), System.currentTimeMillis(), name)
        )
        refreshHeader(name)
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
        db().query(NotesSql.Q_TODOS, types = NotesSql.T_TODO_ROW).map { NotesSql.todoItem(it) }

    /** 某篇笔记的待办，同全局排序。 */
    fun todosOf(note: String): List<TodoItem> =
        db().query(NotesSql.Q_TODOS_OF, listOf(note), NotesSql.T_TODO_ROW).map { NotesSql.todoItem(it) }

    /** 空内容返回 null，不落盘。 */
    fun addTodo(text: String, note: String = ""): TodoItem? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val item = TodoItem(UUID.randomUUID().toString(), clean, false, System.currentTimeMillis(), note)
        db().execArgs(
            NotesSql.W_INSERT_TODO,
            listOf(item.id, item.text, if (item.done) 1L else 0L, item.createdAt, item.note)
        )
        refreshHeader(note)
        return item
    }

    fun setTodoDone(id: String, done: Boolean) {
        db().execArgs(NotesSql.W_SET_TODO_DONE, listOf(if (done) 1L else 0L, id))
        refreshHeader(todoNote(id))
    }

    /** 空内容忽略，不改不动。 */
    fun setTodoText(id: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        db().execArgs(NotesSql.W_SET_TODO_TEXT, listOf(clean, id))
        refreshHeader(todoNote(id))
    }

    fun deleteTodo(id: String) {
        val note = todoNote(id)
        db().execArgs(NotesSql.W_DELETE_TODO, listOf(id))
        refreshHeader(note)
    }

    /** 已完成数, 未完成数。 */
    fun todoCounts(): Pair<Int, Int> {
        val done = (db().query(NotesSql.Q_TODO_DONE_COUNT, types = NotesSql.T_COUNT)
            .firstOrNull()?.get(0) as? Long) ?: 0L
        val all = (db().query(NotesSql.Q_TODO_ALL_COUNT, types = NotesSql.T_COUNT)
            .firstOrNull()?.get(0) as? Long) ?: 0L
        return done.toInt() to (all - done).toInt()
    }

    /** 默认名：当前时间戳。 */
    fun defaultName(): String = STAMP_FMT.format(Date())

    fun close() {
        runCatching { backend.close() }
    }

    /** 按库现状重写该笔记的头部块（无归属待办没有文件可写，直接跳过）。 */
    private fun refreshHeader(name: String) {
        if (name.isEmpty()) return
        val row = db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
            ?: return
        val tags = NotesSql.decodeTags(row[1] as String)
        val created = row[2] as Long
        val updated = row[3] as Long
        writeAtomic(noteFile(name), buildHead(created, updated, tags, todosOf(name)) + readNote(name))
    }

    private fun todoNote(id: String): String =
        db().query(NotesSql.Q_TODO_NOTE, listOf(id), NotesSql.T_NAME)
            .firstOrNull()?.get(0) as? String ?: ""

    /** 头部块：文件开头的 ---notes-meta 围栏。无头/坏头整篇当正文，老文件天然兼容。 */
    private data class NoteHead(
        val created: Long,
        val updated: Long,
        val tags: List<String>,
        val todos: List<TodoItem>
    )

    private fun buildHead(created: Long, updated: Long, tags: List<String>, todos: List<TodoItem>): String =
        buildString {
            append(HEAD_OPEN).append('\n')
            append("created: ").append(created).append('\n')
            append("updated: ").append(updated).append('\n')
            append("tags: ").append(tags.joinToString(", ")).append('\n')
            for (t in todos) {
                append("todo: ").append(t.id).append(" | ").append(if (t.done) 1 else 0)
                    .append(" | ").append(t.createdAt).append(" | ").append(escHead(t.text)).append('\n')
            }
            append(HEAD_CLOSE).append('\n').append('\n')
        }

    private fun parseHead(text: String): Pair<NoteHead?, String> {
        if (!text.startsWith(HEAD_OPEN + "\n")) return null to text
        val close = text.indexOf("\n$HEAD_CLOSE\n")
        if (close < 0) return null to text
        val lines = text.substring(HEAD_OPEN.length + 1, close).split('\n')
        var created: Long? = null
        var updated: Long? = null
        val tags = ArrayList<String>()
        val todos = ArrayList<TodoItem>()
        for (line in lines) {
            when {
                line.startsWith("created: ") -> created = line.removePrefix("created: ").trim().toLongOrNull()
                line.startsWith("updated: ") -> updated = line.removePrefix("updated: ").trim().toLongOrNull()
                line.startsWith("tags: ") -> tags.addAll(
                    line.removePrefix("tags: ").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                )
                line.startsWith("todo: ") -> parseHeadTodo(line.removePrefix("todo: "))?.let { todos.add(it) }
            }
        }
        val c = created ?: return null to text
        val u = updated ?: return null to text
        val body = text.substring(close + HEAD_CLOSE.length + 2).removePrefix("\n")
        return NoteHead(c, u, tags.distinct(), todos) to body
    }

    private fun parseHeadTodo(raw: String): TodoItem? {
        val parts = raw.split(" | ", limit = 4)
        if (parts.size != 4) return null
        val created = parts[2].trim().toLongOrNull() ?: return null
        val text = unescHead(parts[3]).trim()
        if (text.isEmpty()) return null
        return TodoItem(parts[0].trim(), text, parts[1].trim() == "1", created)
    }

    private fun escHead(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescHead(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
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

    private fun dbHas(name: String): Boolean =
        db().query(NotesSql.Q_HAS_NOTE, listOf(name), NotesSql.T_ONE).isNotEmpty()

    private fun diskNames(): Set<String> =
        root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() == EXT }
            .map { it.nameWithoutExtension }
            .toSet()

    private fun fileTime(name: String): Long =
        runCatching { noteFile(name).lastModified() }.getOrDefault(System.currentTimeMillis())

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

    companion object {
        const val EXT = "txt"
        private const val HEAD_OPEN = "---notes-meta"
        private const val HEAD_CLOSE = "---"
        private const val FIRST_SUFFIX = 2
        private const val MAX_SUFFIX_TRIES = 9999
        private const val TMP_SUFFIX = ".tmp"

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
