package com.workspace.proot

/**
 * TermLou 主程序是否处于“已打开”状态（5.12.4）。
 *
 * 后台服务的唯一存活条件：**TermLou 主程序打开着**。
 * 前台、后台、按 Home 都算打开；按返回键退出、从最近任务划掉、进程死亡算关闭。
 *
 * 这里不看 PRoot、也不看终端 shell 是否 `exit`——主程序打开着就保持服务。
 * 默认 false：磁贴/服务入口在从未打开过主程序时也能拉起服务，
 * 但 60 秒检查会发现主程序没开而停掉。
 */
object MainAppPresence {

    @Volatile
    private var opened = false

    fun onMainOpened() {
        opened = true
    }

    fun onMainClosed() {
        opened = false
    }

    fun isOpen(): Boolean = opened

    fun reset() {
        opened = false
    }
}
