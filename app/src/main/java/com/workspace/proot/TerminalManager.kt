package com.workspace.proot

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.workspace.RootfsPatcher
import java.io.File

class TerminalManager(
    private val context: Context,
    private val lxRoot: File,
    private val wsFiles: File,
    private val wsTmp: File
) {
    var session: TerminalSession? = null
        private set

    var ctrlMode = false
        private set

    fun setSession(session: TerminalSession?) {
        this.session = session
    }

    internal fun setupEnvironment(shellCmd: String) {
        wsTmp.mkdirs()
        wsTmp.listFiles()?.forEach { it.deleteRecursively() }
        wsFiles.mkdirs()

        val bashrc = File(lxRoot, "root/.bashrc")
        val bashrcState = File(lxRoot, "root/.term_lou_state")
        val savedCmd = if (bashrcState.exists()) bashrcState.readText().trim() else ""
        val marker = "# TERMLOU_V6"
        val needWrite = !bashrc.exists() || !bashrc.readText().contains(marker) || savedCmd != shellCmd
        if (needWrite) {
            val cmdLine = if (shellCmd.isNotBlank()) "$shellCmd\n" else ""
            val content = marker + "\n" +
                "export PATH=\"\$HOME/.local/bin:\$PATH\"\n" +
                "alias id='id 2>/dev/null'\nalias groups='groups 2>/dev/null'\n" +
                "for gid in \$(id -G 2>/dev/null); do\n" +
                "  grep -q \":\$gid:\" /etc/group 2>/dev/null || echo \"g\$gid:x:\$gid:\" >> /etc/group\n" +
                "done\n" +
                "apt-get clean -qq 2>/dev/null\n" +
                "rm -rf /data/* /data/.* 2>/dev/null\n" +
                "export HISTFILESIZE=100\n" +
                "export HISTSIZE=100\n" +
                "export PS1='\\[\\e[32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[0m\\]\\\\$ '\n" +
                "export LANG=C.UTF-8\n" +
                "alias ls='ls --color=auto'\n" +
                "alias grep='grep --color=auto'\n" +
                "command -v curl >/dev/null 2>&1 && [ -f /etc/ssl/certs/ca-certificates.crt ] || (dpkg --configure -a 2>/dev/null; apt-get update -qq 2>/dev/null; apt-get install -y -qq curl ca-certificates tar 2>/dev/null; update-ca-certificates -f 2>/dev/null)\n" +
                cmdLine
            bashrc.writeText(content)
            bashrcState.writeText(shellCmd)
        }

        File(lxRoot, "tmp").let {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        File(lxRoot, "data").let {
            if (it.exists() && !isSymlink(it)) it.deleteRecursively()
            it.mkdirs()
        }

        setupWrappers()
        setupAptSources()
        setupGroupFile()
    }

    internal fun setupWrappers() {
        val groupsWrapperId = File(lxRoot, "usr/local/bin/id")
        if (!groupsWrapperId.exists()) {
            groupsWrapperId.parentFile?.mkdirs()
            groupsWrapperId.writeText("#!/bin/bash\nexec /usr/bin/id \"\$@\" 2>/dev/null\n")
            groupsWrapperId.setExecutable(true)
        }
        val groupsWrapperGrp = File(lxRoot, "usr/local/bin/groups")
        if (!groupsWrapperGrp.exists()) {
            groupsWrapperGrp.parentFile?.mkdirs()
            groupsWrapperGrp.writeText("#!/bin/bash\nexec /usr/bin/groups \"\$@\" 2>/dev/null\n")
            groupsWrapperGrp.setExecutable(true)
        }

        // 每次启动无条件重写，确保 APK 升级后 rootfs 里的 wrapper 同步更新（不能依赖 exists 守卫）
        val termlouUi = File(lxRoot, "usr/local/bin/termlou-ui")
        termlouUi.parentFile?.mkdirs()
        val script = context.resources.openRawResource(R.raw.termlou_ui)
            .bufferedReader().use { it.readText() }
        termlouUi.writeText(script)
        termlouUi.setExecutable(true)

        val termlouClipboard = File(lxRoot, "usr/local/bin/termlou-clipboard")
        termlouClipboard.parentFile?.mkdirs()
        val clipScript = context.resources.openRawResource(R.raw.termlou_clipboard)
            .bufferedReader().use { it.readText() }
        termlouClipboard.writeText(clipScript)
        termlouClipboard.setExecutable(true)

        val profileD = File(lxRoot, "etc/profile.d/00-fix-groups.sh")
        if (!profileD.exists()) {
            profileD.parentFile?.mkdirs()
            profileD.writeText(
                "# Fix groups v2\nalias id='id 2>/dev/null'\nalias groups='groups 2>/dev/null'\n" +
                "for gid in \$(id -G 2>/dev/null); do\n  grep -q \":\$gid:\" /etc/group 2>/dev/null || echo \"g\$gid:x:\$gid:\" >> /etc/group\ndone\n"
            )
            profileD.setExecutable(true)
        }
    }

    private fun setupAptSources() {
        val codename = resolveDistroCodename(lxRoot)
        val aptSources = File(lxRoot, "etc/apt/sources.list")
        if (!aptSources.exists() || aptSources.readText().isBlank()) {
            aptSources.parentFile?.mkdirs()
            aptSources.writeText(
                "deb http://deb.debian.org/debian $codename main\n" +
                "deb http://deb.debian.org/debian $codename-updates main\n" +
                "deb http://deb.debian.org/debian-security $codename-security main\n"
            )
        }
    }

    private fun setupGroupFile() {
        val groupFile = File(lxRoot, "etc/group")
        if (!groupFile.exists()) {
            groupFile.parentFile?.mkdirs()
            groupFile.writeText(
                "root:x:0:\ndaemon:x:1:\nbin:x:2:\nsys:x:3:\nadm:x:4:\ntty:x:5:\ndisk:x:6:\nlp:x:7:\nmail:x:8:\nnews:x:9:\nuucp:x:10:\nman:x:12:\nproxy:x:13:\nkmem:x:15:\ndialout:x:20:\nfax:x:21:\nvoice:x:22:\ncdrom:x:24:\nfloppy:x:25:\ntape:x:26:\nsudo:x:27:\nauditor:x:28:\nvideo:x:44:\nsaslauth:x:45:\nplugdev:x:46:\ngames:x:50:\ngopher:x:51:\nusers:x:100:\nnogroup:x:65534:\ninet:x:3003:\nnet_bt_admin:x:3005:\nnet_bt:x:3006:\nnet_bw_stats:x:3009:\nnet_bw_acct:x:3010:\neverybody:x:9997:\n"
            )
        }
    }

    internal fun buildProotArgs(shellPath: String): List<String> {
        TermlouDirs.base(context).mkdirs()
        val args = mutableListOf(
            "--root-id", "--link2symlink", "--kill-on-exit",
            "-r", lxRoot.absolutePath,
            "-w", "/workspace",
            "-b", "${wsFiles.absolutePath}:/workspace",
            "-b", "${wsTmp.absolutePath}:/tmp",
            "-b", "${TermlouDirs.base(context).absolutePath}:/termlou",
        )
        for (p in listOf("/dev", "/proc", "/sys", "/etc/hosts")) {
            if (File(p).exists()) args += listOf("-b", p)
        }
        args += shellPath
        return args
    }

    internal fun buildProotEnv(loader: File): Array<String> {
        return arrayOf(
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${wsTmp.absolutePath}",
            "TMPDIR=/tmp",
            "BUN_INSTALL_CACHE_DIR=/tmp/bun-cache",
            "HOME=/root",
            "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )
    }

    fun runInProot(command: String, timeoutSec: Long): String {
        TermlouDirs.base(context).mkdirs()
        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")
        val shell = findShellInRootfs() ?: throw Exception(context.getString(R.string.shell_not_found))

        val args = mutableListOf(
            "--root-id", "--link2symlink",
            "-r", lxRoot.absolutePath,
            "-w", "/workspace",
            "-b", "${wsFiles.absolutePath}:/workspace",
            "-b", "${wsTmp.absolutePath}:/tmp",
            "-b", "${TermlouDirs.base(context).absolutePath}:/termlou",
        )
        for (p in listOf("/dev", "/proc", "/sys", "/etc/hosts")) {
            if (File(p).exists()) args += listOf("-b", p)
        }
        args += shell
        args += "-c"
        args += command

        val pb = ProcessBuilder(listOf(prootBin.absolutePath) + args)
        pb.directory(wsFiles)
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to wsTmp.absolutePath,
            "TMPDIR" to "/tmp",
            "HOME" to "/root",
            "PATH" to "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8"
        ))
        pb.redirectErrorStream(true)

        val process = pb.start()
        process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
        return process.inputStream.bufferedReader().readText().trim()
    }

    internal fun findShellInRootfs(): String? {
        for (s in listOf("/usr/bin/bash", "/usr/bin/dash", "/usr/bin/sh", "/bin/sh")) {
            if (File(lxRoot, s.removePrefix("/")).exists()) return s
        }
        return null
    }

    private fun isSymlink(file: File) = java.nio.file.Files.isSymbolicLink(file.toPath())

    fun toggleCtrlMode(): Boolean {
        ctrlMode = !ctrlMode
        return ctrlMode
    }

    fun setCtrlMode(armed: Boolean) {
        ctrlMode = armed
    }

    fun handleCtrlKey(codePoint: Int): Boolean {
        if (!ctrlMode) return false
        val ctrlCode = when (codePoint) {
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            '['.code -> 27
            '\\'.code -> 28
            ']'.code -> 29
            '^'.code -> 30
            '_'.code -> 31
            else -> return false
        }
        ctrlMode = false
        val s = session
        if (s != null) {
            val data = byteArrayOf(ctrlCode.toByte())
            s.write(data, 0, data.size)
        }
        return true
    }

    fun writeToSession(text: String, scope: CoroutineScope) {
        val s = session ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = text.toByteArray()
                s.write(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.e("TerminalManager", "write failed", e)
            }
        }
    }

    fun syncDnsToRootfs() {
        val servers = NetworkUtils.getDnsServers(context).ifEmpty {
            listOf("114.114.114.114", "223.5.5.5")
        }
        RootfsPatcher().ensureRootfsDns(File(lxRoot, "etc"), servers)
    }

    fun destroy() {
        session?.finishIfRunning()
    }
}
