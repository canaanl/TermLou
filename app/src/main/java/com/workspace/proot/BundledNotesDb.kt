package com.workspace.proot

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.workspace.proot.NotesDbBackend.Col

/** 设备端后端：bundled SQLite（含 FTS5），journal=DELETE 保证目录无 -wal/-shm 残留。 */
class BundledNotesDb(path: String) : NotesDbBackend {

    private val conn = BundledSQLiteDriver().open(path)

    init {
        try {
            conn.execSQL("PRAGMA journal_mode=DELETE")
            conn.execSQL("PRAGMA busy_timeout=5000")
            // 搜索/批量入库调优：页缓存 16MB（默认仅 2MB，万条扫描/建索引全靠它命中内存）、
            // 临时表走内存（bigram OR 等 MATCH 会建临时结构，默认落盘）
            conn.execSQL("PRAGMA cache_size=-16384")
            conn.execSQL("PRAGMA temp_store=MEMORY")
        } catch (e: Exception) {
            runCatching { conn.close() }
            throw e
        }
    }

    @Synchronized
    override fun exec(sql: String) {
        conn.execSQL(sql)
    }

    @Synchronized
    override fun execArgs(sql: String, args: List<Any?>) {
        conn.prepare(sql).use { st ->
            args.forEachIndexed { i, v -> bind(st, i + 1, v) }
            st.step()
        }
    }

    @Synchronized
    override fun query(sql: String, args: List<Any?>, types: List<Col>): List<List<Any?>> {
        conn.prepare(sql).use { st ->
            args.forEachIndexed { i, v -> bind(st, i + 1, v) }
            val out = ArrayList<List<Any?>>()
            while (st.step()) {
                out.add(types.mapIndexed { c, t ->
                    if (st.isNull(c)) null
                    else if (t == Col.INT) st.getLong(c)
                    else st.getText(c)
                })
            }
            return out
        }
    }

    @Synchronized
    override fun transaction(block: () -> Unit) {
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            block()
            conn.execSQL("COMMIT")
        } catch (e: Exception) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw e
        }
    }

    @Synchronized
    override fun close() {
        runCatching { conn.close() }
    }

    private fun bind(st: SQLiteStatement, index: Int, v: Any?) {
        when (v) {
            null -> st.bindNull(index)
            is String -> st.bindText(index, v)
            is Long -> st.bindLong(index, v)
            is Int -> st.bindLong(index, v.toLong())
            is Double -> st.bindDouble(index, v)
            is Boolean -> st.bindLong(index, if (v) 1L else 0L)
            else -> throw IllegalArgumentException("unsupported bind type ${v::class}")
        }
    }
}
