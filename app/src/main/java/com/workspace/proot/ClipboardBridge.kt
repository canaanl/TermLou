package com.workspace.proot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * 进程级单例：监听 termlou 目录（filesDir/.termlou/clipboard）req，把文本写入系统剪贴板。
 * 仅开放写入（set/clear），读不受限于此桥；写在任意场景都允许，无需窗口焦点。
 * 协议：sh 原子写 req/<id>.json {id, op:"set"|"clear", text?} -> 桥主线程 setPrimaryClip -> 原子写 res/<id>.json {ok:true}。
 */
object ClipboardBridge {

    private var ctx: Context? = null
    private lateinit var reqDir: File
    private lateinit var resDir: File
    private val mainHandler = Handler(Looper.getMainLooper())
    private var parser: java.util.concurrent.ExecutorService? = null
    private val pendingParses = mutableSetOf<String>()
    private var running = false
    private var pollTask: Runnable? = null
    private var reqObserver: FileObserver? = null
    private var refs = 0

    fun acquire(context: Context, termlouBase: File) {
        if (ctx == null) {
            ctx = context.applicationContext
            reqDir = File(termlouBase, "clipboard/req")
            resDir = File(termlouBase, "clipboard/res")
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
        parser = Executors.newSingleThreadExecutor()
        reqDir.listFiles()?.forEach { runCatching { it.delete() } }
        resDir.listFiles()?.forEach { runCatching { it.delete() } }
        pollTask = object : Runnable {
            override fun run() {
                if (!running) return
                pollOnce()
                mainHandler.postDelayed(this, FALLBACK_POLL_MS)
            }
        }
        mainHandler.post(pollTask!!)
        @Suppress("DEPRECATION")
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
    }

    private fun pollOnce() {
        val files = reqDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            if (pendingParses.contains(f.name)) continue
            pendingParses.add(f.name)
            submitParse(f)
        }
    }

    private fun submitParse(file: File) {
        val p = parser ?: return
        runCatching {
            p.execute {
                val text = runCatching { file.readText() }.getOrNull()
                val parsed = text?.let { runCatching { MiniJson.parse(it) }.getOrNull() }
                mainHandler.post {
                    if (!running) return@post
                    pendingParses.remove(file.name)
                    if (parsed == null) {
                        if (file.exists()) {
                            mainHandler.postDelayed({ scheduleRetry(file) }, PARSE_RETRY_MS)
                        }
                        return@post
                    }
                    handleRequest(file, parsed)
                }
            }
        }.onFailure {
            pendingParses.remove(file.name)
        }
    }

    private fun scheduleRetry(file: File) {
        if (!running || !file.exists()) return
        if (pendingParses.contains(file.name)) return
        pendingParses.add(file.name)
        submitParse(file)
    }

    private fun handleRequest(file: File, obj: MiniJson.Obj) {
        val op = obj.optString("op", "set")
        val c = ctx ?: run {
            resolve(file, false, "no_context")
            return
        }
        mainHandler.post {
            try {
                val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                when (op) {
                    "clear" -> {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                        // 兼容旧版：部分系统 clear 需额外 clearPrimaryClip
                        runCatching { cm.clearPrimaryClip() }
                    }
                    else -> {
                        var t = obj.optString("text", "")
                        // 400KB 截断（按字符近似，Binder 1MB 限制）
                        if (t.length > 409600) t = t.substring(0, 409600)
                        cm.setPrimaryClip(ClipData.newPlainText("termlou", t))
                    }
                }
                resolve(file, true, null)
            } catch (e: Exception) {
                resolve(file, false, e.message ?: "clipboard_error")
            }
        }
    }

    private fun resolve(file: File, ok: Boolean, error: String?) {
        runCatching {
            resDir.mkdirs()
            val out = File(resDir, file.name)
            val tmp = File(resDir, "${file.name}.tmp")
            val json = if (ok) {
                MiniJson.Obj().put("ok", true).toString()
            } else {
                MiniJson.Obj().put("ok", false).put("error", error ?: "unknown").toString()
            }
            tmp.writeText(json)
            tmp.renameTo(out)
            file.delete()
        }
    }

    private const val FALLBACK_POLL_MS = 1000L
    private const val EVENT_SETTLE_MS = 5L
    private const val PARSE_RETRY_MS = 10L
}
