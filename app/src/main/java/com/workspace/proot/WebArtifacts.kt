package com.workspace.proot

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 无头浏览器的产物目录（5.9.0）：落在 Linux 可见的工作区里，用户用 app 的文件页就能翻到。
 *
 * ```
 * /workspace/web/web.env        端口 + 令牌（**仅服务运行时存在**，供 agent 自发现）
 * /workspace/web/shots/         截图目录（shot-0001.png 这样递增，保留最近 [MAX_SHOTS] 张）
 * /workspace/web/cookies.txt    人可读 cookie 清单
 * /workspace/web/cookies.json   给 agent 的结构化版
 * ```
 *
 * 物理位置 = `filesDir/workspace/web`；Linux 侧是 `/workspace/web/...`（不是 `~`，见 WORKSPACE_MOUNT）。
 * 纯文件操作 + 序号逻辑，不碰安卓 UI，可单测。
 */
object WebArtifacts {

    /** 截图保留张数上限：避免长期使用把工作区撑大。 */
    const val MAX_SHOTS = 50

    private const val PREFS = "term-lou-web"
    private const val SHOT_PREFIX = "shot-"
    private const val SHOT_SUFFIX = ".png"
    private const val SHOT_SEQ_RESET_MS = 1_000L
    private val shotLock = Any()

    @Volatile private var lastShotSeq = 0
    @Volatile private var lastShotAt = 0L

    /** 产物目录的物理位置（Android 侧；Linux 侧见 [WebProtocol.WEB_DIR]）。 */
    fun webRoot(context: Context): File = File(context.filesDir, "workspace/web")

    fun shotsDir(context: Context): File = File(webRoot(context), "shots")

    fun envFile(context: Context): File = File(webRoot(context), "web.env")

    /**
     * 把物理路径翻译成 Linux 侧**真正能打开**的路径。
     * ⚠ 5.9.5 修：此前返回 `$WEB_DIR/shots/a.png`，但 Linux 的 `~` 是 rootfs 里的
     * `/root`，**不是**工作区 —— agent 拿这个路径去 cat 必然失败。
     * 工作区挂在 [WebProtocol.WORKSPACE_MOUNT]，所以正确答案是 `/workspace/web/shots/a.png`。
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
                    appendLine("# TermLou 无头浏览器 · headless browser")
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

    /**
     * 截图落盘（`shot-0001.png` 递增），超过上限删最旧的。
     *
     * 序号**从目录里已有的文件续**，不靠进程内计数器——否则进程一重启就又从 1 开始，
     * 新截图会把上次的旧图直接覆盖掉。
     */
    fun saveShot(context: Context, bitmap: Bitmap): File? = runCatching {
        val dir = shotsDir(context)
        dir.mkdirs()
        val existing = dir.listFiles { f -> f.name.startsWith(SHOT_PREFIX) && f.name.endsWith(SHOT_SUFFIX) }
            ?.map { it.name }
            ?: emptyList()
        val seq = nextShotSeq(highestShotSeq(existing) + 1)
        val file = File(dir, shotFileName(seq))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        trimShots(dir)
        file
    }.getOrNull()

    /** 进程内序号：跨并发指令递增（同时刻也不会撞名）。 */
    private fun nextShotSeq(candidate: Int): Int = synchronized(shotLock) {
        val now = System.currentTimeMillis()
        if (now - lastShotAt > SHOT_SEQ_RESET_MS) {
            lastShotSeq = candidate
        } else {
            lastShotSeq = maxOf(lastShotSeq + 1, candidate)
        }
        lastShotAt = now
        lastShotSeq
    }

    /** 已有文件名里最大的序号；没有就 0。 */
    fun highestShotSeq(names: List<String>): Int = names.maxOfOrNull { name ->
        // ⚠ 5.9.30：**只取前导数字**，不能整串解析。
        // 整页截图一屏一张，名字带屏号：`shot-0007-2.png`。
        // 去掉前缀后缀剩 `0007-2`，`toIntOrNull()` 直接 null → 序号永远算成 0 →
        // 新截图会覆盖旧截图。那是**静默丢数据**，所以这里改成取前导数字。
        name.removePrefix(SHOT_PREFIX).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    } ?: 0

    /** 截图文件名（4 位序号，可排序 = 可按时间排序）。 */
    fun shotFileName(seq: Int): String = "$SHOT_PREFIX%04d$SHOT_SUFFIX".format(seq)

    /** 整页截图里第 `index` 屏（1 起）的文件名：`shot-0007-1.png`、`-2.png`… */
    fun shotScreenFileName(seq: Int, index: Int): String =
        "$SHOT_PREFIX%04d-%d$SHOT_SUFFIX".format(seq, index.coerceAtLeast(1))

    /**
     * 一次落盘多屏（5.9.30 整页截图）。
     *
     * **共用一个基准序号**，屏号跟在后面 —— 于是 `shot-0007-1/2/3.png`
     * 一眼就是同一次整页截图的第三屏，而不是三次无关的截图。
     *
     * @param bitmaps 顺序必须是从上到下
     * @return 成功落盘的文件；**长度必须等于入参**（半落盘视为失败，由调用方回收）
     */
    fun saveShotScreens(context: Context, bitmaps: List<Bitmap>): List<File> {
        if (bitmaps.isEmpty()) return emptyList()
        val dir = shotsDir(context)
        dir.mkdirs()
        val existing = dir.listFiles { f -> f.name.startsWith(SHOT_PREFIX) && f.name.endsWith(SHOT_SUFFIX) }
            ?.map { it.name }
            ?: emptyList()
        val base = nextShotSeq(highestShotSeq(existing) + 1)
        val files = mutableListOf<File>()
        bitmaps.forEachIndexed { i, bmp ->
            val f = File(dir, shotScreenFileName(base, i + 1))
            val ok = runCatching {
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            }.getOrDefault(false)
            if (!ok) {
                // 已经写出去的那几张要删掉 —— 留下半套比全失败更难解释
                files.forEach { runCatching { it.delete() } }
                return emptyList()
            }
            files.add(f)
        }
        trimShots(dir)
        return files
    }

    /**
     * 纯逻辑：给定已有的截图文件名，返回**要删掉的最旧的那些**（超出 [max] 时）。
     * 抽出来是为了能单测"只保留最近 N 张"这条规则。
     */
    fun oldestToDelete(names: List<String>, max: Int = MAX_SHOTS): List<String> {
        val keep = max.coerceAtLeast(0)
        return names.sorted().take((names.size - keep).coerceAtLeast(0))
    }

    /** 只保留最近 [MAX_SHOTS] 张（按文件名排序 = 按时间排序）。 */
    private fun trimShots(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("shot-") && f.name.endsWith(".png") }
            ?: return
        oldestToDelete(files.map { it.name }).forEach { File(dir, it).delete() }
    }

    /** cookie 导出：人可读 txt + 结构化 json。 */
    fun writeCookies(context: Context, url: String, pairs: List<Pair<String, String>>) {
        runCatching {
            val root = webRoot(context)
            root.mkdirs()
            File(root, "cookies.txt").writeText(
                buildString {
                    appendLine("# TermLou 无头浏览器 · cookie 导出")
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
     * 5.9.6 改：此前只删 `shots/` + `cookies.txt` + `cookies.json` 三样白名单 ——
     * 以后多写一种产物忘了加进来就永远删不掉，而且强杀进程后残留的 `web.env`
     * （陈旧端口与令牌）这个按钮够不着。现在是"除目录本身外全删"。
     *
     * **能删的前提是服务已停**：按钮在运行中是置灰的（见
     * [WebAutomationController.refreshRow]），所以这里不需要为在跑的会话保留任何东西，
     * 也不会和正在写的 `shot` 撞上。
     *
     * 返回删掉的**文件**数（目录不计入），用于状态提示。
     */
    fun clearArtifacts(context: Context): Int {
        val root = webRoot(context)
        if (!root.isDirectory) return 0
        var removed = 0
        runCatching { removed = deleteContents(root) }
        // shots/ 留着空目录：文件页里少一次目录闪烁，服务下次写截图时也少一次 mkdirs
        runCatching { shotsDir(context).mkdirs() }
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
     * ⚠ `.` 与 `..` 是 `File.relativeTo` 在路径没落在 [dir] 里时可能给出的结果，
     * 拿它们去删就是把目录自己删了 —— 所以显式挡掉。
     */
    fun isDisposable(relPath: String): Boolean {
        val p = relPath.replace('\\', '/').trim('/')
        return p.isNotEmpty() && p != "." && p != ".."
    }
}
