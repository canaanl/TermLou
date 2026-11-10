package com.workspace.proot

import java.io.File

/**
 * 解析 ProcessBuilder 子进程的宿主 PID（5.12.2）。
 *
 * Android 的 `java.lang.Process` 没有公开的 `pid()`（`api-versions.xml`
 * 里没有），反射可能失败，也可能拿到 -1；之前 service 的 pid 因此恒为 -1，
 * stop 的进程树清理拿不到 root，空树永远 clean。
 *
 * 解析顺序：
 *  1. 服务启动时由宿主 `/system/bin/sh` 在 exec PRoot 前写下的 pidfile
 *     （同一个 PID 被 exec 保留，最可靠）；
 *  2. 反射 `pid()` / 私有 `pid` 字段，只接受 > 0 的值；
 *  3. 扫描 `/proc`，只找 `ppid == 本 App pid` 的直接子进程，
 *     用该服务唯一的临时目录匹配 `cmdline`（-b <tempDir>:/tmp）
 *     或 `environ`（PROOT_TMP_DIR=<tempDir>）。
 *
 * 不依赖 hidden API，也不需要额外的 JNI。
 */
@Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught")
object ServiceProcessPidResolver {

    private const val RETRIES = 50
    private const val RETRY_MS = 10L

    fun resolve(process: Process, tempDir: File, pidFile: File? = null): Int? {
        readPidFile(pidFile)?.let { return it }
        directPid(process)?.let { return it }
        val selfPid = android.os.Process.myPid()
        repeat(RETRIES) {
            readPidFile(pidFile)?.let { return it }
            findChildPid(selfPid, tempDir, File("/proc"))?.let { return it }
            directPid(process)?.let { return it }
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    /** 读取宿主 shell 写下的 pidfile；只接受正整数。 */
    internal fun readPidFile(file: File?): Int? {
        if (file == null) return null
        return try {
            file.readText().trim().toIntOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从给定 procRoot 查找直接子进程；抽出来便于 JVM 用 fake /proc 回归。
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun findChildPid(selfPid: Int, tempDir: File, procRoot: File): Int? {
        val path = tempDir.absolutePath
        val envNeedle = "PROOT_TMP_DIR=$path"
        for (dir in procRoot.listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            if (readPpid(File(dir, "stat")) != selfPid) continue
            val cmdline = readText(File(dir, "cmdline"))
            if (cmdline != null && cmdline.contains(path)) return pid
            val environ = readText(File(dir, "environ"))
            if (environ != null && environ.contains(envNeedle)) return pid
        }
        return null
    }

    /**
     * 反射取 PID；必须是 > 0。Android 上 `pid()` 可能抛
     * UnsupportedOperationException，也可能返回 -1（`toHandle()` 不支持），
     * 绝不能把 -1 当有效 PID。
     */
    internal fun directPid(process: Process): Int? {
        runCatching {
            val method = Process::class.java.getMethod("pid")
            val raw = when (val value = method.invoke(process)) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }
            positivePid(raw)?.let { return it }
        }
        runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            positivePid(field.getInt(process).toLong())?.let { return it }
        }
        return null
    }

    /** 0 与 -1 都不是有效 PID；-1 在 Android 上表示“不可用”。 */
    internal fun positivePid(value: Long?): Int? = value?.takeIf { it > 0 }?.toInt()

    private fun readPpid(stat: File): Int? {
        return try {
            val text = stat.readText()
            val rparen = text.lastIndexOf(')')
            if (rparen < 0) return null
            text.substring(rparen + 1).trim().split(Regex("\\s+"))
                .getOrNull(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun readText(file: File): String? =
        try {
            file.readBytes().toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
}
