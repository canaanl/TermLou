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
            invalidateSnapshot() // 库被重建＝缓存地基作废
        }
        return backend
    }

    /** 目录扫描 + 库合并：新文件补条目、mtime 变了的重灌（外部改过的 txt 下次进页面即可搜）、
     *  文件没了的清条目，归属笔记已被删的待办一并剪掉。
     *  库损坏 / 旧结构（user_version ≠ v2）时删库，按目录 txt 头部全量重建。 */
    fun reload() {
        invalidateSnapshot()
        root.mkdirs()
        PinyinDict.ensureLoaded()
        val disk = diskNames()
        val rows = db().query(NotesSql.Q_SYNC_ROWS, types = NotesSql.T_NAME_MTIME)
            .associate { (it[0] as String) to (it[1] as Long) }
        val added = disk - rows.keys
        val changed = disk.filter { it !in added && rows.getValue(it) != fileTime(it) }
        val removed = rows.keys - disk
        db().transaction {
            for (name in added) ingestNote(name, fresh = true)
            for (name in changed) ingestNote(name, fresh = false)
            for (name in removed) {
                db().execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
                db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
                db().execArgs(NotesSql.W_NGRAM_DELETE, listOf(name))
                db().execArgs(NotesSql.W_DELETE_NOTE, listOf(name))
            }
            db().exec(NotesSql.W_PRUNE_ORPHAN_TODOS)
        }
    }

    /** 单篇入库（新增 / 外部改动重灌共用，须在事务内）：头部恢复元数据，
     *  正文小写镜像进 notes，拼音双剖面 + 首字母 + bigram 物化进索引表。
     *  新增笔记的索引行必然不存在，跳过 fts/ngram DELETE——那是 name 全表扫描，
     *  批量入库时会退化成 O(库²)（万条实测 187s 的元凶）。 */
    private fun ingestNote(name: String, fresh: Boolean) {
        val f = noteFile(name)
        val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
        val (head, body) = parseHead(raw)
        val mtime = f.lastModified()
        val created = head?.created ?: mtime
        val updated = head?.updated ?: mtime
        val tags = head?.tags.orEmpty()
        db().execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
        if (!fresh) {
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db().execArgs(NotesSql.W_NGRAM_DELETE, listOf(name))
        }
        val args = listOf(name, NotesSql.encodeTags(tags), created, updated, NotesSql.bodyKey(body), mtime)
        if (fresh) db().execArgs(NotesSql.W_INSERT_NOTE, args)
        else db().execArgs(NotesSql.W_UPSERT_NOTE, args)
        for (t in head?.todos.orEmpty()) {
            db().execArgs(
                NotesSql.W_INSERT_TODO,
                listOf(t.id, t.text, if (t.done) 1L else 0L, t.createdAt, name)
            )
        }
        insertSearchRow(name, body)
    }

    /** 写搜索索引行（fts + bigram），与正文镜像同事务增删，保证永不脱节。 */
    private fun insertSearchRow(name: String, text: String) {
        val src = text.take(NoteMatcher.MAX_BODY_CHARS)
        db().execArgs(
            NotesSql.W_FTS_INSERT,
            listOf(
                name, text,
                PinyinDict.primaryPy(src), PinyinDict.secondaryPy(src), PinyinDict.initials(src)
            )
        )
        db().execArgs(NotesSql.W_NGRAM_INSERT, listOf(name, NotesSql.bigrams(src)))
    }

    /** 按更新时间倒序，时间相同按名称。走列表快照（全量条目+可搜索字段一次建好，
     *  连打复用）；任何写路径与库自愈都会失效，读到的永远是库的最新状态。 */
    fun listNotes(): List<NoteEntry> = snapshot().entries

    /** 列表快照：搜索/列表/标签统计的地基，避免每次查询都重查 1 万行并重拼字段文本。 */
    private fun snapshot(): SearchSnapshot {
        cachedSnapshot?.let { return it }
        val entries = db().query(NotesSql.Q_LIST_NOTES, types = NotesSql.T_NOTE_ROW)
            .map { NotesSql.noteEntry(it) }
        val todoTexts = todos().filter { it.note.isNotEmpty() }.groupBy({ it.note }, { it.text })
        val fields = entries.map { e ->
            FieldTexts(e.name, e.tags.joinToString(" "), todoTexts[e.name].orEmpty().joinToString(" "))
        }
        return SearchSnapshot(entries, fields).also { cachedSnapshot = it }
    }

    private fun invalidateSnapshot() {
        cachedSnapshot = null
    }

    @Volatile
    private var cachedSnapshot: SearchSnapshot? = null

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

    /**
     * 笔记列表全字段模糊搜索：与「对每条笔记跑 noteScore」逐条等价（对拍测试锁定），
     * 但全程不读 .txt——正文与拼音/首字母剖面在写入与入库时已物化进库。
     * 两路召回合并成超集：① 标题/标签/待办逐词内存精确打分取并集；
     * ② 正文库内召回——同词多分支 UNION 去重、跨词 INTERSECT 求交，只回传最终交集。
     * 候选＝SQL 交集 ∪ 内存并集（数学上可证是真候选的超集，最终精排精确过滤，
     * 结果与全量逐条打分完全一致）；排序层语义零改动
     * （标题＞标签＞正文＞容错＞拼音＞首字母，BM25 只召回不排序）。
     * 空查询返回全量列表（与 listNotes 同序）。
     */
    fun searchNotesFull(raw: String): List<NoteEntry> {
        val terms = NoteMatcher.terms(raw)
        if (terms.isEmpty()) return listNotes()
        val snap = snapshot()
        val entries = snap.entries
        if (entries.isEmpty()) return emptyList()
        // ① 内存层：标题/标签/待办不查库，逐词精确打分（与精排同一内核），跨词取并
        val memHits = HashSet<String>()
        for (term in terms) {
            for (f in snap.fields) {
                if (NoteMatcher.termScore(term, f.name, "", f.tags, f.todos) >= 0) memHits.add(f.name)
            }
        }
        // ② 正文召回：同词分支 UNION、跨词 INTERSECT，交集在库内算完、只回传结果行
        val recall = recallIntersect(terms)
        if (recall.isEmpty() && memHits.isEmpty()) return emptyList()
        val hitNames = HashSet<String>(recall)
        hitNames.addAll(memHits)
        // ③ 批量取候选正文 → 原打分内核精排：超集里的假阳性在此被精确滤掉，排序不变
        val bodies = fetchBodies(hitNames)
        val scored = ArrayList<Pair<NoteEntry, Int>>(hitNames.size)
        for ((e, f) in entries.zip(snap.fields)) {
            if (e.name !in hitNames) continue
            val s = NoteMatcher.noteScore(e.name, bodies[e.name].orEmpty(), terms, f.tags, f.todos)
            if (s >= 0) scored.add(e to s)
        }
        return scored.sortedBy { it.second }.map { it.first }
    }

    /** 多词正文召回：每个词的分支在库内 UNION（同词多通道去重），词与词 INTERSECT（AND），
     *  交集由 SQLite 算完才回传——多词查询不再把每词几千行都物化进内存再求交。 */
    private fun recallIntersect(terms: List<String>): Set<String> {
        val groups = ArrayList<String>(terms.size)
        val args = ArrayList<String>()
        for (term in terms) {
            val branches = recallBranches(term)
            if (branches.isEmpty()) continue // 防御：某词无召回通道＝正文必不命中，只靠内存层兜底
            groups.add(branches.joinToString(separator = " UNION ", prefix = "(", postfix = ")") { it.first })
            for (b in branches) args.add(b.second)
        }
        if (groups.isEmpty()) return emptySet()
        // SQLite 语句不能以 "(" 开头：单词直接跑（剥壳），多词用 FROM 子查询承载各组再 INTERSECT
        val sql = if (groups.size == 1) groups[0].removeSurrounding("(", ")")
        else groups.joinToString(" INTERSECT ") { "SELECT name FROM $it" }
        return db().query(sql, args, NotesSql.T_NAME).mapTo(HashSet()) { it[0] as String }
    }

    /** 单词正文召回分支（各返回 SELECT name，供 UNION 拼接）：产出「正文精排可能命中」的超集，
     *  候选再过 NoteMatcher 精排。
     *  字面 ≥3 字走 trigram 短语（同表拼音/首字母列顺带覆盖拉丁全拼、首字母）、
     *  2 字走 bigram 表、1 字与纯标点走库内 LIKE 扫描；汉字词补读音组合 LIKE
     *  （主/副读音双剖面，覆盖同音字与多音字读音差）与 bigram OR 容错召回（≤2 编辑必留 ≥2 连字）。 */
    private fun recallBranches(term: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val len = term.length
        val hasHan = term.any { NoteMatcher.isHan(it) }
        when {
            len >= 3 && hasToken(term) -> out.add(NotesSql.Q_FTS_SEARCH to NotesSql.escapeMatch(term))
            len == 2 && hasToken(term) -> out.add(NotesSql.Q_NGRAM_MATCH to NotesSql.escapeMatch(term))
            len >= 1 -> out.add(NotesSql.Q_BODY_LIKE to "%$term%")
        }
        if (PinyinDict.loaded && (hasHan || len < 3)) {
            if (hasHan) {
                // 汉字词 → 枚举读音组合（多音字主/副读音全覆盖，非汉字原样拼接）
                for (combo in pinyinCombos(term)) {
                    out.add(NotesSql.qFtsLike("body_py") to "%$combo%")
                    out.add(NotesSql.qFtsLike("body_py2") to "%$combo%")
                }
            } else {
                out.add(NotesSql.qFtsLike("body_py") to "%$term%")
                out.add(NotesSql.qFtsLike("body_py2") to "%$term%")
                if (len >= 2) out.add(NotesSql.qFtsLike("body_ini") to "%$term%")
            }
        }
        if (hasHan && len in NoteMatcher.MIN_FUZZY_TERM..NoteMatcher.MAX_FUZZY_TERM) {
            val bigrams = (0 until len - 1).map { term.substring(it, it + 2) }
                .distinct().filter { hasToken(it) }
            if (bigrams.size > 1) {
                out.add(
                    NotesSql.Q_NGRAM_MATCH to
                        bigrams.joinToString(" OR ") { NotesSql.escapeMatch(it) }
                )
            }
        }
        return out
    }

    /** 词里有实词字符才发 MATCH（unicode61 剥标点，纯标点短语会成空查询）。 */
    private fun hasToken(s: String): Boolean = s.any { it.isLetterOrDigit() }

    /** 汉字词的读音组合（截面枚举，主读音优先，超 32 截断；有字查不到读音 → 空，与打分层同进退）。 */
    private fun pinyinCombos(term: String): List<String> {
        var combos = listOf("")
        for (ch in term) {
            val options: Array<String> = if (NoteMatcher.isHan(ch)) {
                PinyinDict.readings[ch] ?: return emptyList()
            } else {
                arrayOf(ch.toString())
            }
            val next = ArrayList<String>((combos.size * options.size).coerceAtMost(MAX_PINYIN_COMBOS))
            for (c in combos) {
                for (o in options) {
                    if (next.size >= MAX_PINYIN_COMBOS) break
                    next.add(c + o)
                }
                if (next.size >= MAX_PINYIN_COMBOS) break
            }
            combos = next
        }
        return combos
    }

    /** 候选正文按名批量点查（分 500 一块走主键 IN，不碰磁盘 .txt）。 */
    private fun fetchBodies(names: Set<String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(names.size)
        val all = names.toList()
        var i = 0
        while (i < all.size) {
            val chunk = all.subList(i, (i + 500).coerceAtMost(all.size))
            db().query(NotesSql.qBodiesIn(chunk.size), chunk, NotesSql.T_NAME_BODY)
                .forEach { out[it[0] as String] = it[1] as String }
            i += chunk.size
        }
        return out
    }

    fun noteFile(name: String): File = File(root, "$name.$EXT")

    /** 读正文：剥掉头部块，UI 永远看不到元数据。 */
    fun readNote(name: String): String {
        val raw = runCatching { noteFile(name).readText(Charsets.UTF_8) }.getOrDefault("")
        return parseHead(raw).second
    }

    /** 写回已存在的笔记；文件不存在则顺手建出来。头部与正文单点写透，搜索物化列同事务刷新。 */
    fun saveNote(name: String, text: String) {
        root.mkdirs()
        PinyinDict.ensureLoaded()
        val now = System.currentTimeMillis()
        val row = db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
        val created = (row?.get(2) as? Long) ?: now
        val tags = row?.let { NotesSql.decodeTags(it[1] as String) }.orEmpty()
        val todos = if (row == null) emptyList() else todosOf(name)
        writeAtomic(noteFile(name), buildHead(created, now, tags, todos) + text)
        val mtime = noteFile(name).lastModified()
        db().transaction {
            db().execArgs(
                NotesSql.W_UPSERT_NOTE,
                listOf(name, NotesSql.encodeTags(tags), created, now, NotesSql.bodyKey(text), mtime)
            )
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db().execArgs(NotesSql.W_NGRAM_DELETE, listOf(name))
            insertSearchRow(name, text)
        }
        invalidateSnapshot()
    }

    /** 新建空白笔记：重名自动加后缀，返回最终落定的名称。 */
    fun createNote(desired: String): String {
        root.mkdirs()
        val base = sanitizeName(desired).ifEmpty { defaultName() }
        val name = uniqueName(base)
        val now = System.currentTimeMillis()
        writeAtomic(noteFile(name), buildHead(now, now, emptyList(), emptyList()))
        val mtime = noteFile(name).lastModified()
        db().transaction {
            db().execArgs(
                NotesSql.W_INSERT_NOTE,
                listOf(name, NotesSql.encodeTags(emptyList()), now, now, "", mtime)
            )
            insertSearchRow(name, "")
        }
        invalidateSnapshot()
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
        PinyinDict.ensureLoaded()
        db().transaction {
            db().execArgs(NotesSql.W_RENAME_NOTE, listOf(name, now, old))
            db().execArgs(NotesSql.W_REMAP_TODOS, listOf(name, old))
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(old))
            db().execArgs(NotesSql.W_NGRAM_DELETE, listOf(old))
            insertSearchRow(name, readNote(name))
        }
        refreshHeader(name)
        return name
    }

    fun deleteNote(name: String) {
        invalidateSnapshot()
        runCatching { noteFile(name).delete() }
        db().transaction {
            db().execArgs(NotesSql.W_DELETE_NOTE_TODOS, listOf(name))
            db().execArgs(NotesSql.W_FTS_DELETE, listOf(name))
            db().execArgs(NotesSql.W_NGRAM_DELETE, listOf(name))
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
        invalidateSnapshot()
        runCatching { backend.close() }
    }

    /** 按库现状重写该笔记的头部块（无归属待办没有文件可写，直接跳过）。 */
    private fun refreshHeader(name: String) {
        invalidateSnapshot() // 标签/待办/改名等一切走头部刷新的写路径，顺带失效列表快照
        if (name.isEmpty()) return
        val row = db().query(NotesSql.Q_NOTE_ROW, listOf(name), NotesSql.T_NOTE_ROW).firstOrNull()
            ?: return
        val tags = NotesSql.decodeTags(row[1] as String)
        val created = row[2] as Long
        val updated = row[3] as Long
        writeAtomic(noteFile(name), buildHead(created, updated, tags, todosOf(name)) + readNote(name))
        // 头部重写会改文件 mtime：同步登记，reload 才不会把自己写的文件当成外部改动重灌
        db().execArgs(NotesSql.W_TOUCH_NOTE, listOf(noteFile(name).lastModified(), name))
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
                val ver = first.query(NotesSql.Q_USER_VERSION, types = NotesSql.T_COUNT)
                    .firstOrNull()?.get(0) as? Long ?: 0L
                if (ver != NotesSql.SCHEMA_VERSION.toLong()) {
                    // 老结构（v1 等）：走删库重建通道，按 txt 头部全量恢复
                    throw IllegalStateException("notes schema v$ver != v${NotesSql.SCHEMA_VERSION}")
                }
                NotesSql.schema().forEach { first.exec(it) }
                first.query(NotesSql.Q_TODO_ALL_COUNT, types = NotesSql.T_COUNT)
                true
            }.getOrDefault(false)
            if (ok) return first
            runCatching { first.close() }
        }
        // 库损坏 / 结构过旧：删库（含 journal），按目录现状重建
        dbFile.delete()
        File(dbFile.parentFile, "${dbFile.name}-journal").delete()
        val backend = openDb(dbFile)
        NotesSql.schema().forEach { backend.exec(it) }
        backend.exec(NotesSql.W_SET_USER_VERSION)
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

    private class FieldTexts(val name: String, val tags: String, val todos: String)

    /** 列表快照：全量条目 + 逐篇可搜索字段文本（标题/标签/待办），搜索连打复用。 */
    private class SearchSnapshot(val entries: List<NoteEntry>, val fields: List<FieldTexts>)

    companion object {
        const val EXT = "txt"
        private const val HEAD_OPEN = "---notes-meta"
        private const val HEAD_CLOSE = "---"
        private const val FIRST_SUFFIX = 2
        private const val MAX_SUFFIX_TRIES = 9999
        private const val TMP_SUFFIX = ".tmp"
        private const val MAX_PINYIN_COMBOS = 32

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
