package com.workspace.proot

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * 1MB 总日志预算：只在进程启动时裁剪一次，不做定时轮询。
 *
 * Linux 的 /var/log 会 bind 到 filesDir/logs/linux；apt/dpkg 的已知
 * 无界日志在 prepareLinuxLogs() 里指向 /dev/null。应用日志在写入端
 * 另有单项上限，保证运行期间不会无限增长。
 */
object LogBudget {

    const val MAX_BYTES = 1024L * 1024L
    const val APPEND_CAP_BYTES = 128 * 1024
    const val VPN_LOG_CAP_BYTES = 256 * 1024

    private const val DEV_NULL = "/dev/null"

    /** 进程启动时调用一次；超过 1MB 从最旧的文件开始删/截断。 */
    fun trim(context: Context) {
        trim(context.filesDir)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    fun trim(filesDir: File) {
        val files = collectFiles(filesDir)
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            val length = file.length()
            if (length <= 0L) continue
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (!deleted) runCatching { file.writeText("") }
            total -= length
        }
    }

    /**
     * 日志目录固定在 filesDir/logs/linux，并通过 proot bind 到 /var/log，
     * 这样 Linux 日志不再占用 rootfs。
     */
    fun linuxLogDir(filesDir: File): File = File(filesDir, "logs/linux")

    /**
     * 准备 /var/log 的外部目录。reset=true 只应在主进程启动、尚未开 shell
     * 时使用：清掉上一轮已知日志，再建 /dev/null 软链。
     */
    @Suppress("LoopWithTooManyJumpStatements")
    fun prepareLinuxLogs(filesDir: File, reset: Boolean = false): File {
        val dir = linuxLogDir(filesDir)
        dir.mkdirs()
        File(dir, "apt").mkdirs()
        for (relative in SILENCED_LOGS) {
            val file = File(dir, relative)
            if (reset) runCatching { file.delete() }
            if (Files.isSymbolicLink(file.toPath())) continue
            if (file.exists()) continue
            runCatching {
                file.parentFile?.mkdirs()
                Files.createSymbolicLink(file.toPath(), File(DEV_NULL).toPath())
            }
        }
        return dir
    }

    /** 追加写但不让单文件无限增长；超过上限时保留本次写入的尾部。 */
    fun appendCapped(file: File, text: String, maxBytes: Int = APPEND_CAP_BYTES) {
        if (maxBytes <= 0) return
        runCatching {
            file.parentFile?.mkdirs()
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size >= maxBytes) {
                file.writeBytes(bytes.copyOfRange(bytes.size - maxBytes, bytes.size))
                return
            }
            if (file.length() + bytes.size > maxBytes) file.writeText("")
            file.appendBytes(bytes)
        }
    }

    /** 把已有文件截到尾部 maxBytes；用于交给 native 进程继续写的日志。 */
    fun capFile(file: File, maxBytes: Int = APPEND_CAP_BYTES) {
        if (maxBytes <= 0 || !file.isFile || file.length() <= maxBytes) return
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val length = raf.length()
                val keep = maxBytes.toLong().coerceAtMost(length)
                val bytes = ByteArray(keep.toInt())
                raf.seek(length - keep)
                raf.readFully(bytes)
                raf.setLength(0)
                raf.write(bytes)
            }
        }
    }

    private fun collectFiles(filesDir: File): List<File> {
        val files = mutableListOf<File>()
        fun add(file: File) {
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) files.add(file)
        }

        fun addTree(dir: File) {
            if (!dir.isDirectory) return
            dir.walkTopDown()
                .onEnter { it == dir || !Files.isSymbolicLink(it.toPath()) }
                .onFail { _, _ -> true }
                .forEach { add(it) }
        }

        addTree(linuxLogDir(filesDir))
        add(File(filesDir, "crash.log"))
        add(File(filesDir, "net/tun2socks.log"))
        add(File(filesDir, "net/start.log"))
        add(File(filesDir, "net/minisocks.log"))
        add(File(filesDir, "lan/error.log"))
        add(File(filesDir, ".termlou/tile.log"))
        return files.distinct()
    }

    private val SILENCED_LOGS = listOf(
        "apt/term.log",
        "apt/history.log",
        "dpkg.log",
        "alternatives.log",
        "wtmp",
        "btmp",
        "lastlog"
    )
}
