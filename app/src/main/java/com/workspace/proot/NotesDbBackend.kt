package com.workspace.proot

/**
 * 笔记库最小后端接口：SQL 与驱动分离，同一套语句双实现。
 * 设备端走 bundled SQLite（含 FTS5），JVM 单测走 sqlite-jdbc。
 * 所有方法调用方都在主线程，设备实现再加 synchronized 兜底。
 */
interface NotesDbBackend : AutoCloseable {

    enum class Col { TEXT, INT }

    fun exec(sql: String)

    fun execArgs(sql: String, args: List<Any?>)

    fun query(sql: String, args: List<Any?> = emptyList(), types: List<Col>): List<List<Any?>>

    fun transaction(block: () -> Unit)

    override fun close()
}
