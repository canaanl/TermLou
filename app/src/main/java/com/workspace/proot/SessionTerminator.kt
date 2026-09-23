package com.workspace.proot

import java.io.File

/**
 * 会话进程树统一拆除（主终端与 LAN 会话共用，替代两套强弱不一的清理路径）。
 *
 * termux_pty 子进程已 setsid（pgid == pid），但 proot 再起的 shell/job 可能自建进程组 ——
 * 先趁父进程还在时从 /proc 收集后代，再整组补刀、逐个补刀，
 * 避免只杀直接 pid 留下一堆孤儿 shell 占着 CPU 和挂载。
 */
object SessionTerminator {
    private const val MAX_BFS_DEPTH = 32

    /** 杀掉整棵会话进程树（进程组 + 直接 pid + /proc 后代）。 */
    fun killTree(pid: Int) {
        if (pid <= 0) return
        val descendants = collectDescendants(pid)
        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }   // 进程组
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }    // 直接 pid
        for (d in descendants) runCatching { android.system.Os.kill(d, android.system.OsConstants.SIGKILL) } // 后代
    }

    /**
     * 杀树并在后台收割直接子进程（waitpid），避免反复开杀会话留下僵尸。
     * 收割会阻塞到子进程真正退出，放独立线程不拖慢调用方的关闭流程；
     * 进程被 SIGKILL 后立即退出，线程存活时间通常是毫秒级。
     * 子进程在 killTree 前不会被任何人收割（pid 直到本线程 waitpid 才释放），无 pid 复用误杀窗口。
     */
    fun terminate(pid: Int) {
        if (pid <= 0) return
        killTree(pid)
        Thread({ runCatching { TunSpawner.waitPid(pid) } }, "session-reaper-$pid").apply {
            isDaemon = true
        }.start()
    }

    /** 从 /proc 按 ppid 关系收集 root 的全部后代（BFS，深度封顶）。 */
    private fun collectDescendants(root: Int): List<Int> {
        val result = ArrayList<Int>()
        try {
            val children = HashMap<Int, MutableList<Int>>()
            for (d in File("/proc").listFiles() ?: emptyArray()) {
                val name = d.name
                if (name.isEmpty() || !name.all { it in '0'..'9' }) continue
                val p = name.toIntOrNull() ?: continue
                // stat 形如 "pid (comm) state ppid ..."，comm 可含空格/括号，取最后一个 ')' 之后
                val stat = try { File(d, "stat").readText() } catch (_: Exception) { continue }
                val rparen = stat.lastIndexOf(')')
                if (rparen < 0 || rparen + 1 >= stat.length) continue
                val rest = stat.substring(rparen + 1).trim().split(Regex("\\s+"))
                if (rest.size < 2) continue
                val ppid = rest[1].toIntOrNull() ?: continue
                children.getOrPut(ppid) { ArrayList() }.add(p)
            }
            val queue = ArrayDeque<Int>()
            queue.add(root)
            var queueDepth = 0
            while (queue.isNotEmpty() && queueDepth++ < MAX_BFS_DEPTH) {
                val cur = queue.removeFirst()
                for (c in children[cur].orEmpty()) {
                    if (c != root && c !in result) {
                        result.add(c)
                        queue.add(c)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
