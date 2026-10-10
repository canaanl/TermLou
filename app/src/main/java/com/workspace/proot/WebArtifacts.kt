package com.workspace.proot

import android.content.Context
import java.io.File

/**
 * 浏览器的产物目录（5.9.0 建立；5.9.38 起**只有自发现这一个文件**）。
 *
 * ```
 * /workspace/web/web.env    端口 + 令牌（**仅服务运行时存在**，供 agent 自发现）
 * ```
 *
 * 物理位置 = `filesDir/workspace/web`；Linux 侧是 `/workspace/web/...`（不是 `~`，见 WORKSPACE_MOUNT）。
 * 纯文件操作，不碰安卓 UI，可单测。
 *
 * ## 这个功能不保留 cookie、不留缓存（5.9.38）
 *
 * - **不再导出 cookie**：cookie 是登录态，用户明确要求"都删，不保留"，
 *   于是 `cookies` 指令、导出函数、以及只服务于它的 [linuxPath] 全部删掉。
 * - 网页自己的存储（cookie 库 / localStorage / 缓存）在 `app_webview/`（app 私有目录），
 *   **安卓不允许把它指定到工作区** —— 那一块靠"每次开关都清空"来保证不留痕，
 *   见 `WebAutomationService.destroySession`。
 * - 所以这个目录在服务运行时**只有 `web.env` 一个文件**，关闭服务时被删掉；
 *   设置里的「清除缓存」按钮清的是这个目录（主要用来清强杀后残留的旧 `web.env`）。
 *
 * ⚠ **`web.env` 是这个功能的地基，不许动。** 5.9.37 删截图、5.9.38 删 cookie 时，
 * 它们都跟它写在同一个文件里，一并删掉时手滑就会把令牌也删了 ——
 * 那样 agent 连不上，什么都做不了。
 */
object WebArtifacts {

    private const val PREFS = "term-lou-web"

    /** 产物目录的物理位置（Android 侧；Linux 侧见 [WebProtocol.WEB_DIR]）。 */
    fun webRoot(context: Context): File = File(context.filesDir, "workspace/web")

    fun envFile(context: Context): File = File(webRoot(context), "web.env")

    /**
     * 服务启动时写 env（agent 自发现用）；关闭时删掉（服务不在就别留陈端口）。
     *
     * @return **真写进去了吗**（5.9.38 起上报）。从前是 `runCatching` 一声不响，
     *   而 agent 的自发现全靠这个文件 —— 写失败的表现只是"agent 连不上"，
     *   两边都看不到原因。现在失败会在 `diag` 的 `env_written` 里留痕。
     */
    fun writeEnv(context: Context, port: Int, token: String): Boolean = runCatching {
        webRoot(context).mkdirs()
        envFile(context).writeText(
            buildString {
                appendLine("# TermLou 浏览器 · browser")
                appendLine("PORT=$port")
                appendLine("TOKEN=$token")
                appendLine("# 用法 usage:")
                appendLine("#   source ${WebProtocol.WEB_DIR}/web.env")
                appendLine("#   curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"ping\"}' http://127.0.0.1:\$PORT/op")
            },
            Charsets.UTF_8
        )
        true
    }.getOrDefault(false)

    fun clearEnv(context: Context) {
        runCatching { envFile(context).delete() }
    }

    /**
     * 「清除缓存」按钮：**清空 [webRoot] 下的全部内容**，递归删，目录本身保留。
     *
     * 5.9.6 改：此前只删三样白名单 —— 以后多写一种产物忘了加进来就永远删不掉，
     * 而且强杀进程后残留的 `web.env`（陈旧端口与令牌）这个按钮够不着。
     * 现在是"除目录本身外全删"。
     *
     * **能删的前提是服务已停**：按钮在运行中是置灰的（见
     * [WebAutomationController.refreshRow]），所以这里不需要为在跑的会话保留任何东西。
     *
     * 返回删掉的**文件**数（目录不计入），用于状态提示。
     */
    fun clearArtifacts(context: Context): Int {
        val root = webRoot(context)
        if (!root.isDirectory) return 0
        var removed = 0
        runCatching { removed = deleteContents(root) }
        runCatching { root.mkdirs() }
        return removed
    }

    /**
     * 递归删掉 [dir] 下的全部内容，[dir] **自己保留**。返回删掉的文件数。
     *
     * 每一项都过一遍 [isDisposable]：空串、`.`、`..` 永远不删 ——
     * 这是防止把目录自己删掉的那道锁，测试专门盯着它。
     */
    internal fun deleteContents(dir: File): Int {
        var removed = 0
        dir.listFiles()?.forEach { child ->
            val rel = child.relativeTo(dir).path
            if (!isDisposable(rel)) return@forEach
            if (child.isDirectory) {
                removed += deleteContents(child)
                // 子目录删不掉不算失败：文件已经清掉了，目录留着无妨
                runCatching { child.delete() }
            } else if (child.delete()) {
                removed++
            }
        }
        return removed
    }

    /**
     * 这个相对路径要不要删。
     *
     * ⚠ `.` 与 `..` 是 [File.relativeTo] 在路径没落在 [dir] 里时可能给出的结果，
     * 拿它们去删就是把目录自己删了 —— 所以显式挡掉。
     */
    fun isDisposable(relPath: String): Boolean {
        val p = relPath.replace('\\', '/').trim('/')
        return p.isNotEmpty() && p != "." && p != ".."
    }
}
