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
 * ~/web/web.env        端口 + 令牌（**仅服务运行时存在**，供 agent 自发现）
 * ~/web/shots/         截图目录（shot-0001.png 这样递增，保留最近 [MAX_SHOTS] 张）
 * ~/web/cookies.txt    人可读 cookie 清单
 * ~/web/cookies.json   给 agent 的结构化版
 * ```
 *
 * 物理路径 = `filesDir/workspace/web`（Linux 侧 HOME 就是工作区根，故 Linux 看到的是 `~/web/...`）。
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

    /** `~/web` 的物理目录。 */
    fun webRoot(context: Context): File = File(context.filesDir, "workspace/web")

    fun shotsDir(context: Context): File = File(webRoot(context), "shots")

    fun envFile(context: Context): File = File(webRoot(context), "web.env")

    /**
     * 把物理路径翻译成 Linux 侧看到的路径：Linux 的 `~` 就是工作区根，
     * 所以 `filesDir/workspace/web/shots/a.png` 对 agent 来说是 `~/web/shots/a.png`。
     */
    fun linuxPath(context: Context, file: File): String {
        val root = File(context.filesDir, "workspace").absolutePath
        val rel = file.absolutePath.removePrefix(root).trimStart('/', '\\')
        return "~/$rel"
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
                    appendLine("#   source ~/web/web.env")
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
        name.removePrefix(SHOT_PREFIX).removeSuffix(SHOT_SUFFIX).toIntOrNull() ?: 0
    } ?: 0

    /** 截图文件名（4 位序号，可排序 = 可按时间排序）。 */
    fun shotFileName(seq: Int): String = "$SHOT_PREFIX%04d$SHOT_SUFFIX".format(seq)

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
     * 「清除缓存」按钮：删截图与 cookie 文件，**保留 web.env**（服务还开着时 agent 要用）。
     * 返回删除的文件数（用于状态提示）。
     */
    fun clearArtifacts(context: Context): Int {
        var removed = 0
        runCatching {
            shotsDir(context).listFiles()?.forEach { if (it.delete()) removed++ }
            listOf("cookies.txt", "cookies.json").forEach { name ->
                if (File(webRoot(context), name).delete()) removed++
            }
        }
        return removed
    }
}
