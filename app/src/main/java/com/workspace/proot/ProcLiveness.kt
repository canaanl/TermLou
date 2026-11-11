package com.workspace.proot

import java.io.File

/**
 * `/proc` 进程存活判定（5.12.3）。
 *
 * 主 PRoot 监控与服务进程树清理共用这一份实现，避免两套判断行为不一致：
 *  - `stat` 形如 `pid (comm) state ppid ... starttime ...`，`comm` 可含空格/括号，
 *    因此只取最后一个 `)` 之后的内容；
 *  - `starttime` 用来防 PID 复用：PID 相同但 starttime 不同，说明已经是另一个进程；
 *  - 僵尸（`Z`）/已退出（`X`）/读不到 `/proc` 一律视为不存活（失败即关闭，绝不赌它活着）。
 */
@Suppress("MagicNumber", "TooGenericExceptionCaught", "ReturnCount")
object ProcLiveness {

    private const val START_TIME_INDEX = 19

    data class Info(val state: String, val startTime: Long)

    /** 读取进程信息；进程不存在或 `/proc` 不可读时返回 null。 */
    fun info(pid: Int, procRoot: File = File("/proc")): Info? {
        if (pid <= 0) return null
        return try {
            val stat = File(procRoot, "$pid/stat").readText()
            val rparen = stat.lastIndexOf(')')
            if (rparen < 0) return null
            val rest = stat.substring(rparen + 1).trim().split(Regex("\\s+"))
            val state = rest.getOrNull(0) ?: return null
            val start = rest.getOrNull(START_TIME_INDEX)?.toLongOrNull() ?: return null
            Info(state, start)
        } catch (_: Exception) {
            null
        }
    }

    /** 进程是否仍存活；[expectedStart] 非空时还必须与记录一致。 */
    fun isAlive(pid: Int, expectedStart: Long?, procRoot: File = File("/proc")): Boolean {
        val i = info(pid, procRoot) ?: return false
        if (i.state == "Z" || i.state == "X") return false
        return expectedStart == null || i.startTime == expectedStart
    }

    /** 进程启动时刻（`stat` 第 22 个字段）；不存在时返回 null。 */
    fun startTime(pid: Int, procRoot: File = File("/proc")): Long? = info(pid, procRoot)?.startTime
}
