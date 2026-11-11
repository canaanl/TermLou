package com.workspace.proot

import java.io.File

/**
 * 主 PRoot 的唯一事实源（5.12.3）。
 *
 * 只回答一个问题：**主 PRoot 现在还活着吗**。这里不保存任何“服务属于哪个会话”
 * 的关联——服务是独立运行单元，主 PRoot 只是“允许它继续活着”的条件。
 *
 * 身份由宿主 PID + `/proc/<pid>/stat` 的 starttime 组成：
 *  - 主终端创建时记录；
 *  - 主终端结束时清除（旧会话的迟到回调不会误清新主 PRoot）；
 *  - 存活判断直接看 `/proc`，不依赖 `TerminalSession.isRunning`（那是 PTY 状态，
 *    PRoot 已死但后代仍占着 PTY 时会误报存活）。
 */
@Suppress("TooManyFunctions")
object MainProotTracker {

    private const val START_TIME_RETRIES = 20
    private const val START_TIME_RETRY_MS = 10L

    private val lock = Any()
    private var pid = 0
    private var startTime: Long? = null

    /**
     * 主 PRoot 启动：记录宿主 PID 与 starttime。
     *
     * 这里运行在主线程，不能阻塞等待。先立即登记 PID；`/proc` 若尚未就绪，
     * 由后台线程短暂重试补上 starttime（PID 不变时才写入）。
     * 补不上时存活判断退化为“存在且非僵尸”，仍然失败关闭，只是少了 PID 复用保护。
     */
    fun onStarted(sessionPid: Int) {
        if (sessionPid <= 0) return
        val start = ProcLiveness.startTime(sessionPid)
        record(sessionPid, start)
        if (start != null) return
        Thread({
            var filled: Long? = null
            var retry = 0
            while (filled == null && retry < START_TIME_RETRIES) {
                retry++
                try {
                    Thread.sleep(START_TIME_RETRY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                filled = ProcLiveness.startTime(sessionPid)
            }
            if (filled != null) fillStartTime(sessionPid, filled)
        }, "term-lou-main-proot").apply { isDaemon = true }.start()
    }

    /** 只在 PID 未变且尚未记录时补 starttime，避免覆盖或误伤新主 PRoot。 */
    private fun fillStartTime(expectedPid: Int, start: Long) {
        synchronized(lock) {
            if (pid == expectedPid && startTime == null) startTime = start
        }
    }

    /**
     * 主 PRoot 结束。
     *
     * [sessionPid] > 0 时只清理同一 PID 的记录，避免旧会话的迟到回调误清新主 PRoot；
     * [sessionPid] <= 0（PTY 退出回调里 PID 已被清成 -1）时，仅当已记录的主 PRoot
     * 确实不存活才清理，同样不会误伤刚启动的新主 PRoot。
     */
    fun onFinished(sessionPid: Int) {
        onFinished(sessionPid, File("/proc"))
    }

    /** 测试用：指定 procRoot 判定是否误清。 */
    internal fun onFinished(sessionPid: Int, procRoot: File) {
        synchronized(lock) {
            if (sessionPid > 0) {
                if (pid != sessionPid) return
            } else if (isAliveLocked(procRoot)) {
                return
            }
            clearLocked()
        }
    }

    fun currentPid(): Int = synchronized(lock) { pid }

    fun isAlive(): Boolean = synchronized(lock) { isAliveLocked() }

    /** 测试用：指定 procRoot 判定。 */
    internal fun isAlive(procRoot: File): Boolean = synchronized(lock) { isAliveLocked(procRoot) }

    /** 测试用：直接记录身份，避免依赖真实进程。 */
    internal fun record(pid: Int, startTime: Long?) {
        synchronized(lock) {
            this.pid = pid
            this.startTime = startTime
        }
    }

    fun reset() {
        synchronized(lock) { clearLocked() }
    }

    private fun isAliveLocked(procRoot: File = File("/proc")): Boolean {
        val p = pid
        if (p <= 0) return false
        return ProcLiveness.isAlive(p, startTime, procRoot)
    }

    private fun clearLocked() {
        pid = 0
        startTime = null
    }
}
