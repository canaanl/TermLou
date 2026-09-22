package com.workspace.proot

import com.workspace.proot.NotesDbBackend.Col
import java.util.Locale

/**
 * 笔记库 SQL：表结构 + 语句 + 行映射 + 标签编解码 + 搜索物化列。
 * 纯函数无驱动依赖，JVM 可直接测。
 *
 * 表：
 * notes(name 主键, tags_json 保序数组, created_at, updated_at, body 小写正文镜像, file_mtime 外部改动检测)
 * todos(id 主键, text, done 0/1, created_at, note 归属)
 * notes_fts(name, body, body_py, body_py2, body_ini, trigram)：正文全文索引＋拼音双剖面＋首字母，
 *   ≥3 字短语 MATCH 与 ≥3 字 LIKE 都能走 trigram 加速。
 * notes_ngram(name, bg, unicode61)：正文 bigram（空格分隔）表，2 字短语与容错召回（bigram OR）。
 * 正文 .txt 仍在磁盘原位，DB 只存元数据 + 搜索物化列；查询期零 .txt 读盘。
 * schema 版本经 PRAGMA user_version 标记，不一致时由 NotesStore 删库按 txt 头部重建。
 */
internal object NotesSql {

    const val DB_FILE = "notes.db"
    const val SCHEMA_VERSION = 2

    fun schema(): List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS notes (" +
            "name TEXT PRIMARY KEY, " +
            "tags_json TEXT NOT NULL DEFAULT '[]', " +
            "created_at INTEGER NOT NULL DEFAULT 0, " +
            "updated_at INTEGER NOT NULL DEFAULT 0, " +
            "body TEXT NOT NULL DEFAULT '', " +
            "file_mtime INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS todos (" +
            "id TEXT PRIMARY KEY, " +
            "text TEXT NOT NULL, " +
            "done INTEGER NOT NULL DEFAULT 0, " +
            "created_at INTEGER NOT NULL DEFAULT 0, " +
            "note TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX IF NOT EXISTS idx_todos_note ON todos(note)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING " +
            "fts5(name, body, body_py, body_py2, body_ini, tokenize='trigram')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS notes_ngram USING " +
            "fts5(name, bg, tokenize='unicode61')"
    )

    const val Q_USER_VERSION = "PRAGMA user_version"
    const val W_SET_USER_VERSION = "PRAGMA user_version = $SCHEMA_VERSION"

    const val Q_NOTE_NAMES = "SELECT name FROM notes"
    const val Q_SYNC_ROWS = "SELECT name, file_mtime FROM notes"
    const val Q_HAS_NOTE = "SELECT 1 FROM notes WHERE name = ? LIMIT 1"
    const val Q_NOTE_ROW = "SELECT name, tags_json, created_at, updated_at FROM notes WHERE name = ?"
    const val Q_LIST_NOTES =
        "SELECT name, tags_json, created_at, updated_at FROM notes ORDER BY updated_at DESC, name ASC"
    const val Q_TODOS =
        "SELECT id, text, done, created_at, note FROM todos ORDER BY done ASC, created_at ASC, rowid ASC"
    const val Q_TODOS_OF =
        "SELECT id, text, done, created_at, note FROM todos WHERE note = ? " +
            "ORDER BY done ASC, created_at ASC, rowid ASC"
    const val Q_TODO_DONE_COUNT = "SELECT COUNT(*) FROM todos WHERE done <> 0"
    const val Q_TODO_ALL_COUNT = "SELECT COUNT(*) FROM todos"
    const val Q_TODO_NOTE = "SELECT note FROM todos WHERE id = ?"
    const val Q_FTS_SEARCH = "SELECT name FROM notes_fts WHERE notes_fts MATCH ?"
    const val Q_NGRAM_MATCH = "SELECT name FROM notes_ngram WHERE notes_ngram MATCH ?"
    const val Q_BODY_LIKE = "SELECT name FROM notes WHERE body LIKE ?"

    /** FTS 列 LIKE（body_py/body_py2/body_ini，≥3 字字面量走 trigram 加速）。 */
    fun qFtsLike(col: String): String = "SELECT name FROM notes_fts WHERE $col LIKE ?"

    /** 候选正文按名批量点查（IN 分块由调用方控制，B-tree 主键查找不碰磁盘 .txt）。 */
    fun qBodiesIn(count: Int): String =
        "SELECT name, body FROM notes WHERE name IN (" + List(count) { "?" }.joinToString(",") + ")"

    const val W_UPSERT_NOTE =
        "INSERT INTO notes(name, tags_json, created_at, updated_at, body, file_mtime) " +
            "VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(name) DO UPDATE SET " +
            "tags_json = excluded.tags_json, created_at = excluded.created_at, " +
            "updated_at = excluded.updated_at, body = excluded.body, file_mtime = excluded.file_mtime"
    const val W_INSERT_NOTE =
        "INSERT INTO notes(name, tags_json, created_at, updated_at, body, file_mtime) " +
            "VALUES(?, ?, ?, ?, ?, ?)"
    const val W_RENAME_NOTE = "UPDATE notes SET name = ?, updated_at = ? WHERE name = ?"
    const val W_DELETE_NOTE = "DELETE FROM notes WHERE name = ?"
    const val W_SET_TAGS = "UPDATE notes SET tags_json = ?, updated_at = ? WHERE name = ?"
    const val W_TOUCH_NOTE = "UPDATE notes SET file_mtime = ? WHERE name = ?"
    const val W_FTS_DELETE = "DELETE FROM notes_fts WHERE name = ?"
    const val W_FTS_INSERT =
        "INSERT INTO notes_fts(name, body, body_py, body_py2, body_ini) VALUES(?, ?, ?, ?, ?)"
    const val W_NGRAM_DELETE = "DELETE FROM notes_ngram WHERE name = ?"
    const val W_NGRAM_INSERT = "INSERT INTO notes_ngram(name, bg) VALUES(?, ?)"
    const val W_REMAP_TODOS = "UPDATE todos SET note = ? WHERE note = ?"
    const val W_DELETE_NOTE_TODOS = "DELETE FROM todos WHERE note = ?"
    const val W_PRUNE_ORPHAN_TODOS =
        "DELETE FROM todos WHERE note <> '' AND note NOT IN (SELECT name FROM notes)"
    const val W_INSERT_TODO =
        "INSERT INTO todos(id, text, done, created_at, note) VALUES(?, ?, ?, ?, ?)"
    const val W_SET_TODO_DONE = "UPDATE todos SET done = ? WHERE id = ?"
    const val W_SET_TODO_TEXT = "UPDATE todos SET text = ? WHERE id = ?"
    const val W_DELETE_TODO = "DELETE FROM todos WHERE id = ?"

    val T_NOTE_ROW = listOf(Col.TEXT, Col.TEXT, Col.INT, Col.INT)
    val T_TODO_ROW = listOf(Col.TEXT, Col.TEXT, Col.INT, Col.INT, Col.TEXT)
    val T_NAME = listOf(Col.TEXT)
    val T_NAME_MTIME = listOf(Col.TEXT, Col.INT)
    val T_NAME_BODY = listOf(Col.TEXT, Col.TEXT)
    val T_COUNT = listOf(Col.INT)
    val T_ONE = listOf(Col.INT)

    fun noteEntry(row: List<Any?>): NoteEntry = NoteEntry(
        name = row[0] as String,
        tags = decodeTags(row[1] as String),
        createdAt = row[2] as Long,
        updatedAt = row[3] as Long
    )

    fun todoItem(row: List<Any?>): TodoItem = TodoItem(
        id = row[0] as String,
        text = row[1] as String,
        done = (row[2] as Long) != 0L,
        createdAt = row[3] as Long,
        note = row[4] as String
    )

    /** 标签保序数组 ↔ JSON：复用 MiniJson（顶层包一层 object 绕开只认 object 的限制）。 */
    fun encodeTags(tags: List<String>): String =
        MiniJson.Arr().apply { tags.forEach { put(it) } }.toString()

    fun decodeTags(json: String): List<String> {
        if (json == "[]") return emptyList() // 绝大多数笔记无标签：绕开 MiniJson 解析
        val arr = runCatching { MiniJson.parse("{\"t\":$json}").optArr("t") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.getString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.distinct()
    }

    /** FTS5 MATCH 转义：包双引号做短语查询，内嵌引号双写。 */
    fun escapeMatch(raw: String): String = "\"" + raw.replace("\"", "\"\"") + "\""

    /** 正文入库镜像（notes.body）：小写化，配合小写查询词直接 LIKE。 */
    fun bodyKey(text: String): String = text.lowercase(Locale.ROOT)

    /** 正文 bigram 串（空格分隔，unicode61 切成 2 字一 token）：2 字查询与容错召回的索引底料。 */
    fun bigrams(text: String): String {
        if (text.length < 2) return ""
        val sb = StringBuilder(text.length * 2)
        for (i in 0 until text.length - 1) {
            if (i > 0) sb.append(' ')
            sb.append(text[i]).append(text[i + 1])
        }
        return sb.toString()
    }
}
