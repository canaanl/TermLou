package com.workspace.proot

import com.workspace.proot.NotesDbBackend.Col
import java.sql.DriverManager
import java.sql.Types

/** JVM 单测后端：sqlite-jdbc 跑同一套 SQL（含 FTS5 trigram），行为与设备端对齐。 */
internal class JdbcNotesDb(path: String) : NotesDbBackend {

    private val conn = run {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path")
    }

    init {
        try {
            exec("PRAGMA journal_mode=DELETE")
            exec("PRAGMA busy_timeout=5000")
        } catch (e: Exception) {
            runCatching { conn.close() }
            throw e
        }
    }

    @Synchronized
    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    @Synchronized
    override fun execArgs(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v -> setArg(ps, i + 1, v) }
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun query(sql: String, args: List<Any?>, types: List<Col>): List<List<Any?>> {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v -> setArg(ps, i + 1, v) }
            ps.executeQuery().use { rs ->
                val out = ArrayList<List<Any?>>()
                while (rs.next()) {
                    out.add(types.mapIndexed { c, t ->
                        if (t == Col.INT) rs.getLong(c + 1) else rs.getString(c + 1)
                    })
                }
                return out
            }
        }
    }

    @Synchronized
    override fun transaction(block: () -> Unit) {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            block()
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = prev
        }
    }

    @Synchronized
    override fun close() {
        runCatching { conn.close() }
    }

    private fun setArg(ps: java.sql.PreparedStatement, index: Int, v: Any?) {
        when (v) {
            null -> ps.setNull(index, Types.NULL)
            is String -> ps.setString(index, v)
            is Long -> ps.setLong(index, v)
            is Int -> ps.setInt(index, v)
            is Double -> ps.setDouble(index, v)
            is Boolean -> ps.setBoolean(index, v)
            else -> throw IllegalArgumentException("unsupported bind type ${v::class}")
        }
    }
}
