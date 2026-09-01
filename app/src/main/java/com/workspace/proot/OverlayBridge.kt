package com.workspace.proot

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.concurrent.Executors

/**
 * 进程级单例：监听 termlou 目录（filesDir/.termlou）req，队列化弹出浮窗，并把结果写回 res。
 * MainActivity 与 TermlouCommandRunner 通过 acquire()/release() 引用计数共享一个轮询循环，
 * 避免重复弹窗；当前浮窗超时后自动 dismiss 并返回 timeout 结果。
 *
 * 性能设计：
 *  - bash 侧原子写（tmp+rename）后事件驱动立即触发，沉降仅为兜底（5ms）；
 *  - 文件读取 + JSON 解析落在单线程后台执行器，主线程只做入队与 View 编排；
 *  - 队列连续弹窗（翻页）走快速动画，避免动画叠加拖慢体感。
 */
object OverlayBridge {

    private var ctx: Context? = null
    private lateinit var reqDir: File
    private lateinit var resDir: File
    private val mainHandler = Handler(Looper.getMainLooper())
    private var parser: java.util.concurrent.ExecutorService? = null
    private val pendingParses = mutableSetOf<String>()
    private val queue = ArrayDeque<Entry>()
    private var current: Entry? = null
    private var running = false
    private var pollTask: Runnable? = null
    private var reqObserver: FileObserver? = null
    private var refs = 0
    private var timeoutRunnable: Runnable? = null
    private var activeOverlay: ScriptDialogOverlay? = null

    private class Entry(val request: ScriptDialogSpec.Request, val file: File, val enqueuedAt: Long)

    fun acquire(context: Context, termlouBase: File) {
        if (ctx == null) {
            ctx = context.applicationContext
            reqDir = File(termlouBase, "req")
            resDir = File(termlouBase, "res")
        }
        refs++
        if (!running) start()
    }

    fun release() {
        if (refs > 0) refs--
        if (refs <= 0 && running) stop()
    }

    private fun start() {
        if (running) return
        running = true
        reqDir.mkdirs()
        resDir.mkdirs()
        // 新建后台解析线程池（stop 时会被 shutdown；下次启动重建，避免用已死线程池 execute 抛异常闪退）
        parser = Executors.newSingleThreadExecutor()
        // 启动即清理残留请求与结果（上次进程被杀的孤儿）
        reqDir.listFiles()?.forEach { runCatching { it.delete() } }
        resDir.listFiles()?.forEach { runCatching { it.delete() } }
        // 兼容旧版本：删除已废弃的磁贴日志
        runCatching { File(reqDir.parentFile, "tile.log").delete() }
        pollTask = object : Runnable {
            override fun run() {
                if (!running) return
                pollOnce()
                mainHandler.postDelayed(this, FALLBACK_POLL_INTERVAL_MS)
            }
        }
        mainHandler.post(pollTask!!)
        // 事件驱动：req 文件一到立刻触发（tmp+rename 原子写 → MOVED_TO 即内容完整）
        reqObserver = object : FileObserver(
            reqDir.absolutePath,
            FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.MODIFY
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (!running) return
                mainHandler.postDelayed({ pollOnce() }, EVENT_SETTLE_MS)
            }
        }.apply { startWatching() }
    }

    private fun stop() {
        running = false
        reqObserver?.stopWatching()
        reqObserver = null
        pollTask?.let { mainHandler.removeCallbacks(it) }
        pollTask = null
        parser?.let {
            it.shutdownNow()
            runCatching { it.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        parser = null
        pendingParses.clear()
        cancelTimeout()
        activeOverlay?.dismiss()
        activeOverlay = null
        current = null
        queue.clear()
    }

    private fun pollOnce() {
        val files = reqDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val currentName = current?.file?.name
        for (f in files) {
            if (f.name == currentName) continue
            if (queue.any { it.file.name == f.name }) continue
            if (pendingParses.contains(f.name)) continue
            pendingParses.add(f.name)
            submitParse(f)
        }
        showNext()
    }

    /** 后台读文件 + 解析，结果回主线程入队；解析失败（半写残留）短暂延迟后重试。 */
    private fun submitParse(file: File) {
        val p = parser ?: return
        runCatching { p.execute {
            val text = runCatching { file.readText() }.getOrNull()
            val parsed = text?.let { runCatching { ScriptDialogSpec.parseRequest(it) }.getOrNull() }
            mainHandler.post {
                if (!running) return@post
                pendingParses.remove(file.name)
                if (parsed == null) {
                    if (file.exists()) {
                        mainHandler.postDelayed({ scheduleRetry(file) }, PARSE_RETRY_MS)
                    }
                    return@post
                }
                if (file.name == current?.file?.name) return@post
                if (queue.any { it.file.name == file.name }) return@post
                queue.addLast(Entry(parsed, file, System.currentTimeMillis()))
                showNext()
            }
        } }.onFailure {
            pendingParses.remove(file.name)
        }
    }

    private fun scheduleRetry(file: File) {
        if (!running || !file.exists()) return
        if (pendingParses.contains(file.name)) return
        pendingParses.add(file.name)
        submitParse(file)
    }

    private fun showNext() {
        if (!running) return
        val c = ctx ?: return
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            val timeoutMs = (entry.request.timeoutSec * 1000.0).toLong()
            if (System.currentTimeMillis() - entry.enqueuedAt > timeoutMs) {
                resolve(entry, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_TIMEOUT, mapOf("__reqId" to entry.request.id)))
                continue
            }
            if (!Settings.canDrawOverlays(c)) {
                resolve(entry, ScriptDialogSpec.Result("error", error = ScriptDialogSpec.ERROR_PERMISSION))
                continue
            }
            // 显式关闭
            if (entry.request.op == "close") {
                cancelTimeout()
                val prev = current
                activeOverlay?.dismiss()
                activeOverlay = null
                current = null
                // 关闭者自身回 close，之前等待的 current 也回 dismiss
                resolve(entry, ScriptDialogSpec.Result("close"))
                if (prev != null) {
                    resolve(prev, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
                }
                continue
            }
            // 单例原位更新：有窗则改内容，无窗则新建
            if (activeOverlay?.isAlive() == true) {
                cancelTimeout()
                current = entry
                activeOverlay?.updateContent(entry.request) { result ->
                    cancelTimeout()
                    resolve(entry, result)
                    if (!ScriptDialogSpec.shouldDismiss(entry.request, result)) {
                        // 非关窗（按钮 close=false）：保持窗口等待下一次更新
                        current = null
                        showNext()
                    } else {
                        activeOverlay?.dismiss()
                        activeOverlay = null
                        current = null
                        showNext()
                    }
                }
                val runnable = Runnable {
                    timeoutRunnable = null
                    val values = activeOverlay?.captureValues() ?: emptyMap()
                    activeOverlay?.dismiss()
                    activeOverlay = null
                    if (current === entry) {
                        current = null
                        resolve(entry, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_TIMEOUT, values + ("__reqId" to entry.request.id)))
                        showNext()
                    }
                }
                timeoutRunnable = runnable
                mainHandler.postDelayed(runnable, timeoutMs)
                return
            }
            // 无窗：新建（首建按 --anim）
            current = entry
            val overlay = ScriptDialogOverlay(c, entry.request) { result ->
                cancelTimeout()
                resolve(entry, result)
                if (!ScriptDialogSpec.shouldDismiss(entry.request, result)) {
                    // 非关窗（按钮 close=false）：保持窗口等待下一次更新
                    current = null
                    showNext()
                } else {
                    activeOverlay = null
                    current = null
                    showNext()
                }
            }
            activeOverlay = overlay
            val runnable = Runnable {
                timeoutRunnable = null
                val values = activeOverlay?.captureValues() ?: emptyMap()
                activeOverlay?.dismiss()
                activeOverlay = null
                if (current === entry) {
                    current = null
                    resolve(entry, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_TIMEOUT, values + ("__reqId" to entry.request.id)))
                    showNext()
                }
            }
            timeoutRunnable = runnable
            mainHandler.postDelayed(runnable, timeoutMs)
            overlay.show()
            return
        }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun resolve(entry: Entry, result: ScriptDialogSpec.Result) {
        runCatching {
            resDir.mkdirs()
            val out = File(resDir, entry.file.name)
            val tmp = File(resDir, "${entry.file.name}.tmp")
            tmp.writeText(ScriptDialogSpec.resultToJsonString(result))
            tmp.renameTo(out)
            entry.file.delete()
        }
    }

    private const val FALLBACK_POLL_INTERVAL_MS = 1000L
    private const val EVENT_SETTLE_MS = 5L
    private const val PARSE_RETRY_MS = 10L
}