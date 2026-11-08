package com.workspace.proot

/**
 * 进程收割 JNI（通用；5.11.0 从原 `TunSpawner` 拆出）。
 *
 * 只保留 `waitpid` / `kill` 两个基础操作，给 [SessionTerminator] 与会话
 * 进程树清理使用。VPN 抓包栈删除后，这里不再承担"启动 tun2socks"的职责。
 */
object ProcessJni {

    init {
        System.loadLibrary("termux")
    }

    external fun waitPid(pid: Int): Int

    external fun killPid(pid: Int): Boolean
}
