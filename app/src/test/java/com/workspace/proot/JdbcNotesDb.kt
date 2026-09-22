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

    /** 预编译语句缓存：同 SQL 只 prepare 一次、复用重绑定——万条批量入库与高频搜索不再重复解析 SQL。 */
    private val stmts = java.util.concurrent.ConcurrentHashMap<String, java.sql.PreparedStatement>()

    init {
        try {
            exec("PRAGMA journal_mode=DELETE")
            exec("PRAGMA busy_timeout=5000")
            exec("PRAGMA cache_size=-16384")
            exec("PRAGMA temp_store=MEMORY")
        } catch (e: Exception) {
            runCatching { conn.close() }
            throw e
        }
    }

    private fun prep(sql: String): java.sql.PreparedStatement =
        stmts.computeIfAbsent(sql) { conn.prepareStatement(it) }

    @Synchronized
    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    @Synchronized
    override fun execArgs(sql: String, args: List<Any?>) {
        val ps = prep(sql)
        ps.clearParameters()
        args.forEachIndexed { i, v -> setArg(ps, i + 1, v) }
        ps.executeUpdate()
    }

    @Synchronized
    override fun query(sql: String, args: List<Any?>, types: List<Col>): List<List<Any?>> {
        val ps = prep(sql)
        ps.clearParameters()
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
        stmts.values.forEach { runCatching { it.close() } }
        stmts.clear()
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
