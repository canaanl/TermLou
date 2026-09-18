package com.workspace.proot

/**
 * 管理 .bashrc 的受管段（TERMLOU_START/END）：
 * 重写时只替换受管段，保留段外用户/安装器追加的内容；
 * 无标记的用户自建文件采用追加而非覆盖；旧 V6 文件按锚点拆尾。
 */
object BashrcManager {

    const val START_MARK = "# TERMLOU_START"
    const val END_MARK = "# TERMLOU_END"

    /** 用户环境兜底文件：app 永不创建/删除/覆盖，重写 bashrc 后仍被重新 source。 */
    const val ENV_OVERLAY = ".term_lou_env"

    private const val LEGACY_MARK = "# TERMLOU_V6"
    private const val CURL_ANCHOR = "command -v curl"
    private const val ENV_HOOK = "[ -f \$HOME/.term_lou_env ] && . \$HOME/.term_lou_env"

    fun managedBlock(shellCmd: String): String {
        val cmdLine = if (shellCmd.isNotBlank()) "$shellCmd\n" else ""
        return buildString {
            append(START_MARK).append('\n')
            append("export PATH=\"\$HOME/.local/bin:\$HOME/.opencode/bin:\$PATH\"\n")
            append("alias id='id 2>/dev/null'\n")
            append("alias groups='groups 2>/dev/null'\n")
            append("for gid in \$(id -G 2>/dev/null); do\n")
            append("  grep -q \":\$gid:\" /etc/group 2>/dev/null || echo \"g\$gid:x:\$gid:\" >> /etc/group\n")
            append("done\n")
            append("apt-get clean -qq 2>/dev/null\n")
            append("rm -rf /data/* /data/.* 2>/dev/null\n")
            append("export HISTFILESIZE=100\n")
            append("export HISTSIZE=100\n")
            append("export PS1='\\[\\e[32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[0m\\]\\$ '\n")
            append("export LANG=C.UTF-8\n")
            append("alias ls='ls --color=auto'\n")
            append("alias grep='grep --color=auto'\n")
            append(
                "command -v curl >/dev/null 2>&1 && [ -f /etc/ssl/certs/ca-certificates.crt ] || " +
                    "(dpkg --configure -a 2>/dev/null; apt-get update -qq 2>/dev/null; " +
                    "apt-get install -y -qq curl ca-certificates tar 2>/dev/null; update-ca-certificates -f 2>/dev/null)\n"
            )
            append(ENV_HOOK).append('\n')
            append(cmdLine)
            append(END_MARK).append('\n')
        }
    }

    /** 合并出新文件内容：只替换受管段，段外内容保留。 */
    fun merge(existing: String, shellCmd: String): String {
        val block = managedBlock(shellCmd)
        if (existing.isBlank()) return block
        return existing.let {
            val endIdx = it.indexOf(END_MARK)
            if (endIdx >= 0) {
                block + it.substring(endIdx + END_MARK.length)
            } else if (it.startsWith(LEGACY_MARK)) {
                block + legacyTail(it, shellCmd)
            } else {
                it + (if (it.endsWith('\n')) "" else "\n") + block
            }
        }
    }

    private fun legacyTail(existing: String, shellCmd: String): String {
        if (shellCmd.isNotBlank()) {
            val at = existing.lastIndexOf(shellCmd)
            if (at >= 0) {
                return existing.substring(at + shellCmd.length)
            }
        }
        val at = existing.lastIndexOf(CURL_ANCHOR)
        val from = if (at >= 0) existing.indexOf('\n', at) else -1
        return if (from >= 0) existing.substring(from + 1) else ""
    }
}
