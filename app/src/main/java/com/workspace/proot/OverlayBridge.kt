package com.workspace.proot

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    /** 当前屏归属 + 结算闸门：换屏同步指针，同一请求只结算一次（见 EntryOwnership） */
    private val ownership = EntryOwnership<Entry>()
    private var running = false
    private var pollTask: Runnable? = null
    private var reqObserver: FileObserver? = null
    private var refs = AtomicInteger(0)
    private var idleRunnable: Runnable? = null
    private var sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS
    private var closingSession = false
    private var activeOverlay: ScriptDialogOverlay? = null

    private class Entry(val request: ScriptDialogSpec.Request, val file: File)

    internal fun activeOverlayForTest(): ScriptDialogOverlay? = activeOverlay

    internal fun sessionTimeoutMsForTest(): Long = sessionTimeoutMs

    fun acquire(context: Context, termlouBase: File) {
        if (ctx == null) {
            ctx = context.applicationContext
            reqDir = File(termlouBase, "req")
            resDir = File(termlouBase, "res")
        }
        refs.incrementAndGet()
        if (!running) start()
    }

    fun release() {
        if (refs.get() > 0) refs.decrementAndGet()
        maybeStop()
    }

    /**
     * 有活动窗口/队列时不能停：命令进程结束只是 release 一个引用，
     * 不能把用户正在看的浮窗一起销毁。真正消失后由 onDismissed 再调用这里。
     */
    @Suppress("ComplexCondition")
    private fun maybeStop() {
        if (refs.get() <= 0 && running && activeOverlay == null &&
            queue.isEmpty() && pendingParses.isEmpty()
        ) {
            stop()
        }
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
        cancelIdleTimer()
        closingSession = true
        val closing = activeOverlay
        activeOverlay = null
        closing?.dismiss(immediate = true)
        // 收尾前给仍在等待的请求写明确结果，避免 bash 侧空等
        ownership.current?.let {
            resolve(it, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
        }
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            resolve(entry, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
        }
        queue.clear()
        ownership.reset()
        closingSession = false
    }

    private fun pollOnce() {
        val files = reqDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val currentName = ownership.current?.file?.name
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
    @Suppress("CyclomaticComplexMethod")
    private fun submitParse(file: File) {
        val p = parser ?: return
        runCatching { p.execute {
            val text = runCatching { file.readText() }.getOrNull()
            val obj = text?.let { runCatching { MiniJson.parse(it) }.getOrNull() }
            if (obj != null && obj.optString("op", "") == "canvas") {
                val patch = runCatching { CanvasPatch.parse(obj) }.getOrNull()
                mainHandler.post {
                    pendingParses.remove(file.name)
                    if (running && !closingSession) {
                        val overlay = activeOverlay
                        if (patch != null && overlay?.isAlive() == true) {
                            if (overlay.applyCanvasPatch(patch.key, patch)) {
                                // 程序化 patch 也算“还有更新在推进”，重置空闲计时；
                                // 停止更新后仍会按会话超时关闭。
                                resetIdleTimer()
                            }
                        }
                    }
                    runCatching { file.delete() }
                }
                return@execute
            }
            val parsed = obj?.let { runCatching { ScriptDialogSpec.parseRequest(it) }.getOrNull() }
            mainHandler.post {
                if (!running) return@post
                pendingParses.remove(file.name)
                if (parsed == null) {
                    if (file.exists()) {
                        mainHandler.postDelayed({ scheduleRetry(file) }, PARSE_RETRY_MS)
                    }
                    return@post
                }
                if (file.name == ownership.current?.file?.name) return@post
                if (queue.any { it.file.name == file.name }) return@post
                queue.addLast(Entry(parsed, file))
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
        // 最终关闭动画期间，不复活旧窗口；新请求排队，等窗口真正移除后开新会话。
        if (closingSession && activeOverlay != null) return
        while (queue.isNotEmpty()) {
            val entry = queue.first()
            if (!Settings.canDrawOverlays(c)) {
                queue.removeFirst()
                resolve(entry, ScriptDialogSpec.Result("error", error = ScriptDialogSpec.ERROR_PERMISSION))
                continue
            }
            // 显式关闭：销毁整个会话，清空队列。
            if (entry.request.op == "close") {
                queue.removeFirst()
                val prev = ownership.current
                ownership.clearCurrent()
                resolve(entry, ScriptDialogSpec.Result("close"))
                closeSession(prev, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
                return
            }
            queue.removeFirst()
            // 归属同步：旧屏被顶掉时拿到明确 dismiss，而不是挂到无响应。
            val superseded = ownership.takeOver(entry)
            if (superseded != null) {
                resolve(superseded, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
            }
            // 单例原位更新：有窗则改内容，无窗才新建。
            val current = activeOverlay
            if (current != null && current.isAlive()) {
                configureSessionTimeout(entry.request, first = false)
                val updated = current.updateContent(entry.request) { result ->
                    handleEntryResult(entry, result)
                }
                if (!updated) {
                    closeSession(entry, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
                    return
                }
                resetIdleTimer()
                return
            }
            // 无窗：新建会话（首建按 --anim）。
            activeOverlay = null
            closingSession = false
            configureSessionTimeout(entry.request, first = true)
            val overlay = ScriptDialogOverlay(c, entry.request) { result ->
                handleEntryResult(entry, result)
            }
            activeOverlay = overlay
            overlay.onUserActivity = { resetIdleTimer() }
            overlay.onSessionClose = { closeSession(ownership.current, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS)) }
            overlay.onDismissed = {
                if (activeOverlay === overlay) {
                    activeOverlay = null
                    closingSession = false
                    maybeStop()
                    if (running) showNext()
                }
            }
            overlay.show()
            resetIdleTimer()
            return
        }
    }

    /**
     * 一屏结果结算。
     * close=false / keep 只保持窗口并重置空闲计时；其余最终结果销毁整个会话。
     */
    private fun handleEntryResult(entry: Entry, result: ScriptDialogSpec.Result) {
        if (ownership.isResolved(entry)) return
        resolve(entry, result)
        if (!ScriptDialogSpec.shouldDismiss(entry.request, result)) {
            ownership.finish(entry)
            resetIdleTimer()
            showNext()
        } else {
            ownership.finish(entry)
            closeSession(null, null)
        }
    }

    /**
     * 销毁整个弹窗会话：取消空闲计时、结算当前/队列、淡出窗口、清空归属。
     * activeOverlay 由 onDismissed 真正移除后再清空；期间新请求排队等新会话。
     */
    private fun closeSession(current: Entry?, currentResult: ScriptDialogSpec.Result?) {
        cancelIdleTimer()
        closingSession = true
        if (current != null && !ownership.isResolved(current)) {
            ownership.finish(current)
            resolve(current, currentResult ?: ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
        }
        val pending = queue.toList()
        queue.clear()
        for (e in pending) {
            resolve(e, ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
        }
        ownership.clearCurrent()
        ownership.reset()
        val overlay = activeOverlay
        if (overlay == null) {
            closingSession = false
            maybeStop()
        } else {
            overlay.dismiss()
        }
    }

    private fun onIdleTimeout() {
        idleRunnable = null
        if (!running || activeOverlay == null || closingSession) return
        val entry = ownership.current
        val values = activeOverlay?.captureValues() ?: emptyMap()
        val extras = if (entry != null) mapOf("__reqId" to entry.request.id) else emptyMap()
        closeSession(
            entry,
            ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_TIMEOUT, values + extras)
        )
    }

    private fun configureSessionTimeout(request: ScriptDialogSpec.Request, first: Boolean) {
        if (request.timeoutExplicit) {
            sessionTimeoutMs = (request.timeoutSec.coerceIn(
                ScriptDialogSpec.MIN_TIMEOUT_SEC,
                ScriptDialogSpec.MAX_TIMEOUT_SEC
            ) * 1000.0).toLong()
        } else if (first) {
            sessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS
        }
    }

    @Suppress("ComplexCondition")
    private fun resetIdleTimer() {
        cancelIdleTimer()
        val overlay = activeOverlay
        if (!running || overlay == null || !overlay.isAlive() || closingSession) return
        val runnable = Runnable { onIdleTimeout() }
        idleRunnable = runnable
        mainHandler.postDelayed(runnable, sessionTimeoutMs)
    }

    private fun cancelIdleTimer() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun resolve(entry: Entry, result: ScriptDialogSpec.Result) {
        // 归属闸门：同一请求只结算一次。陈旧回调（换屏前绑定的按钮/返回/外部点击）重复触发
        // 时不再覆盖 res 文件，也不会把已收工的旧等待方再叫醒一次。
        if (ownership.isResolved(entry)) return
        val written = runCatching {
            resDir.mkdirs()
            val out = File(resDir, entry.file.name)
            val tmp = File(resDir, "${entry.file.name}.tmp")
            tmp.writeText(ScriptDialogSpec.resultToJsonString(result))
            if (out.exists() && !out.delete()) return@runCatching false
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return@runCatching false
            }
            true
        }.getOrElse { false }
        if (written) {
            ownership.markResolved(entry)
            runCatching { entry.file.delete() }
        }
    }

    private const val FALLBACK_POLL_INTERVAL_MS = 1000L
    private const val EVENT_SETTLE_MS = 5L
    private const val PARSE_RETRY_MS = 10L
    private const val DEFAULT_SESSION_TIMEOUT_MS = 30_000L
}