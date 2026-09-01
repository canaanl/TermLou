package com.workspace.proot

/**
 * 原生 tun2socks 启动器：通过 JNI fork() + dup2(fd→3) + execve，
 * 把 VpnService 的 TUN fd 可靠传递给子进程（Android posix_spawn 会丢弃非标准 fd，
 * ProcessBuilder 无法传 fd，故走 fork/exec）。
 */
object TunSpawner {

    init {
        System.loadLibrary("termux")
    }

    external fun spawnTun2Socks(executable: String, args: Array<String>, tunFd: Int, logPath: String): Int

    external fun waitPid(pid: Int): Int

    external fun killPid(pid: Int): Boolean
}