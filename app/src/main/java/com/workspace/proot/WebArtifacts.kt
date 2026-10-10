package com.workspace.proot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 浏览器的工作区产物（5.9.0 建立；5.9.37 删掉截图后只剩自发现与 cookie）。
 *
 * ```
 * /workspace/web/web.env        端口 + 令牌（**仅服务运行时存在**，供 agent 自发现）
 * /workspace/web/cookies.txt    人可读 cookie 清单
 * /workspace/web/cookies.json   给 agent 的结构化版
 * ```
 *
 * 物理位置 = `filesDir/workspace/web`；Linux 侧是 `/workspace/web/...`（不是 `~`，见 WORKSPACE_MOUNT）。
 * 纯文件操作，不碰安卓 UI，可单测。
 *
 * ⚠ **自发现（`web.env`）是这个功能的地基，不许动。** 5.9.37 删截图时，
 * 截图落盘与它同在这个文件里（见下面的历史），一并删掉时手滑就会把令牌也删了 ——
 * agent 连不上，什么都做不了。
 */
object WebArtifacts {

    private const val PREFS = "term-lou-web"

    /** 产物目录的物理位置（Android 侧；Linux 侧见 [WebProtocol.WEB_DIR]）。 */
    fun webRoot(context: Context): File = File(context.filesDir, "workspace/web")

    fun envFile(context: Context): File = File(webRoot(context), "web.env")

    /**
     * 把物理路径翻译成 Linux 侧**真正能打开**的路径。
     *
     * ⚠ 5.9.5 修：此前返回 `$WEB_DIR/shots/a.png`，但 Linux 的 `~` 是 rootfs 里的
     * `/root`，**不是**工作区 —— agent 拿这个路径去 cat 必然失败。
     * 工作区挂在 [WebProtocol.WORKSPACE_MOUNT]，所以正确答案是 `/workspace/web/...`。
     */
    fun linuxPath(context: Context, file: File): String {
        val root = File(context.filesDir, "workspace").absolutePath
        val rel = file.absolutePath.removePrefix(root).trimStart('/', '\\')
        return WebProtocol.WORKSPACE_MOUNT + "/" + rel
    }

    /** 服务启动时写 env（agent 自发现用）；关闭时删掉（服务不在就别留陈端口）。 */
    fun writeEnv(context: Context, port: Int, token: String) {
        runCatching {
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
        }
    }

    fun clearEnv(context: Context) {
        runCatching { envFile(context).delete() }
    }

    /** cookie 导出：人可读 txt + 结构化 json。 */
    fun writeCookies(context: Context, url: String, pairs: List<Pair<String, String>>) {
        runCatching {
            val root = webRoot(context)
            root.mkdirs()
            File(root, "cookies.txt").writeText(
                buildString {
                    appendLine("# TermLou 浏览器 · cookie 导出")
                    appendLine("# url: $url")
                    appendLine("# 生成时刻的会话 cookie；会话结束即失效（无痕）")
                    for ((k, v) in pairs) appendLine("$k=$v")
                },
                Charsets.UTF_8
            )
            val arr = JSONArray()
            for ((k, v) in pairs) arr.put(JSONObject().put("name", k).put("value", v))
            File(root, "cookies.json").writeText(
                JSONObject().put("url", url).put("cookies", arr).toString(),
                Charsets.UTF_8
            )
        }
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