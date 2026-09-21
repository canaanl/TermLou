package com.workspace.proot

import com.workspace.proot.NotesDbBackend.Col

/**
 * 笔记库 SQL：表结构 + 语句 + 行映射 + 标签编解码。
 * 纯函数无驱动依赖，JVM 可直接测。
 *
 * 表：
 * notes(name 主键, tags_json 保序数组, created_at, updated_at)
 * todos(id 主键, text, done 0/1, created_at, note 归属)
 * notes_fts(name, body, trigram 分词)：正文全文索引，中英子串可搜。
 * 正文 .txt 仍在磁盘原位，DB 只存元数据 + FTS 镜像。
 */
internal object NotesSql {

    const val DB_FILE = "notes.db"
    const val SCHEMA_VERSION = 1

    fun schema(): List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS notes (" +
            "name TEXT PRIMARY KEY, " +
            "tags_json TEXT NOT NULL DEFAULT '[]', " +
            "created_at INTEGER NOT NULL DEFAULT 0, " +
            "updated_at INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS todos (" +
            "id TEXT PRIMARY KEY, " +
            "text TEXT NOT NULL, " +
            "done INTEGER NOT NULL DEFAULT 0, " +
            "created_at INTEGER NOT NULL DEFAULT 0, " +
            "note TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX IF NOT EXISTS idx_todos_note ON todos(note)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(name, body, tokenize='trigram')"
    )

    const val Q_NOTE_NAMES = "SELECT name FROM notes"
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

    const val W_UPSERT_NOTE =
        "INSERT INTO notes(name, tags_json, created_at, updated_at) VALUES(?, ?, ?, ?) " +
            "ON CONFLICT(name) DO UPDATE SET updated_at = excluded.updated_at"
    const val W_INSERT_NOTE =
        "INSERT INTO notes(name, tags_json, created_at, updated_at) VALUES(?, ?, ?, ?)"
    const val W_RENAME_NOTE = "UPDATE notes SET name = ?, updated_at = ? WHERE name = ?"
    const val W_DELETE_NOTE = "DELETE FROM notes WHERE name = ?"
    const val W_SET_TAGS = "UPDATE notes SET tags_json = ?, updated_at = ? WHERE name = ?"
    const val W_FTS_DELETE = "DELETE FROM notes_fts WHERE name = ?"
    const val W_FTS_INSERT = "INSERT INTO notes_fts(name, body) VALUES(?, ?)"
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
}
