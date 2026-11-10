package com.workspace.proot

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 服务进程树清理器（5.12.1）。
 *
 * 与 [SessionTerminator] 的“主终端/LAN 会话”路径分开：服务由
 * `ProcessBuilder` 启动，包装进程可能先退出、后代可能 reparent，单次
 * `/proc` 扫描不足以保证收干净。这里做三件事：
 *  - 记录每个 PID 的 `/proc/<pid>/stat` starttime，避免 PID 复用误杀；
 *  - 停止时多轮扫描、补杀、等待，直到整棵树消失或超时；
 *  - 把存活 PID 返回给 manager，调用方据此返回 `stop_incomplete`，
 *    不再无条件谎报 STOPPED。
 */
@Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught")
object TermlouServiceReaper {

    const val DEFAULT_TIMEOUT_MS = 2_000L
    private const val POLL_MS = 50L
    private const val START_TIME_INDEX = 19

    data class Target(val pid: Int, val startTime: Long?)

    data class Result(val clean: Boolean, val survivors: List<Target>)

    /** 当前 root 进程及全部后代；root 已退出/僵尸时返回空。 */
    fun snapshot(rootPid: Int): List<Target> {
        if (rootPid <= 0) return emptyList()
        val root = info(rootPid) ?: return emptyList()
        if (root.state == "Z" || root.state == "X") return emptyList()
        val pids = listOf(rootPid) + SessionTerminator.collectDescendants(rootPid)
        return pids.mapNotNull { pid ->
            info(pid)?.let { Target(pid, it.startTime) }
        }
    }

    /**
     * 杀掉 [known] + root 的进程树。返回是否在 [timeoutMs] 内全部消失。
     * 会反复扫描 root 的新后代，因此服务在停止过程中 fork 的子进程也能补杀。
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun kill(
        rootPid: Int,
        known: Collection<Target>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val tracked = LinkedHashMap<Int, Long?>()
        for (t in known) tracked[t.pid] = t.startTime
        if (rootPid > 0 && !tracked.containsKey(rootPid)) {
            val root = info(rootPid)
            if (root != null) trackRoot(tracked, rootPid, root.startTime)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (rootPid > 0 && alive(rootPid, tracked[rootPid])) {
                runCatching { Os.kill(-rootPid, OsConstants.SIGKILL) }
            }
            for ((pid, start) in tracked.toList()) {
                if (alive(pid, start)) {
                    runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                }
            }
            // root 可能已死，但 known 子进程还活着；继续展开它们的后代，避免 daemon 逃逸。
            for (pid in tracked.keys.toList()) {
                if (alive(pid, tracked[pid])) {
                    for (t in snapshot(pid)) tracked.putIfAbsent(t.pid, t.startTime)
                }
            }
            tracked.entries.removeAll { (pid, start) -> !alive(pid, start) }
            if (tracked.isEmpty()) return Result(true, emptyList())
            if (System.currentTimeMillis() >= deadline) {
                return Result(false, tracked.map { Target(it.key, it.value) })
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result(false, tracked.map { Target(it.key, it.value) })
            }
        }
    }

    private data class ProcInfo(val state: String, val startTime: Long)

    /**
     * 只补充尚未记录的 root starttime；已有记录绝不覆盖。
     * 这是 PID 复用安全的核心：root 先退出、PID 被新进程复用时，
     * 新查到的 starttime 不能替换旧记录，否则会误杀新进程。
     */
    internal fun trackRoot(
        tracked: MutableMap<Int, Long?>,
        rootPid: Int,
        rootStart: Long,
    ) {
        if (!tracked.containsKey(rootPid)) tracked[rootPid] = rootStart
    }

    private fun alive(pid: Int, expectedStart: Long?): Boolean {
        val i = info(pid) ?: return false
        if (i.state == "Z" || i.state == "X") return false
        return expectedStart == null || i.startTime == expectedStart
    }

    /** stat 形如 "pid (comm) state ppid ... starttime ..."；comm 可含空格/括号。 */
    private fun info(pid: Int): ProcInfo? {
        if (pid <= 0) return null
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val rparen = stat.lastIndexOf(')')
            if (rparen < 0) return null
            val rest = stat.substring(rparen + 1).trim().split(Regex("\\s+"))
            val state = rest.getOrNull(0) ?: return null
            val start = rest.getOrNull(START_TIME_INDEX)?.toLongOrNull() ?: return null
            ProcInfo(state, start)
        } catch (_: Exception) {
            null
        }
    }
}
