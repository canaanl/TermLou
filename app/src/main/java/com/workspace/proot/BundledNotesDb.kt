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
