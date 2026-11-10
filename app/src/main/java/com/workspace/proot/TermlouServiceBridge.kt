package com.workspace.proot

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 服务命令的独立文件 IPC（5.12.0）。
 *
 * 与 [OverlayBridge] 分开，避免把服务命令混进浮窗/超时/结果仲裁：
 *
 * ```
 * /termlou/service/req/<id>.json   ->  TermlouServiceManager
 * /termlou/service/res/<id>.json   <-  {ok, service | services | error}
 * ```
 *
 * 由 MainActivity、TermlouCommandRunner、LanShareService、TermlouBackgroundService
 * 分别 acquire/release；引用计数归零才停轮询。
 */
@Suppress("TooGenericExceptionCaught")
object TermlouServiceBridge {

    private const val TAG = "TermlouServiceIpc"
    private const val POLL_MS = 200L

    private val lock = Any()
    private var context: Context? = null
    private var reqDir: File? = null
    private var resDir: File? = null
    private var refs = 0
    private var running = false
    private var thread: Thread? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private val pending = mutableSetOf<String>()

    fun acquire(context: Context) {
        synchronized(lock) {
            this.context = context.applicationContext
            reqDir = TermlouDirs.serviceReq(context)
            resDir = TermlouDirs.serviceRes(context)
            refs++
            if (!running) startLocked()
        }
    }

    fun release() {
        synchronized(lock) {
            if (refs > 0) refs--
            if (refs == 0) stopLocked()
        }
    }

    private fun startLocked() {
        val req = reqDir ?: return
        val res = resDir ?: return
        req.mkdirs()
        res.mkdirs()
        // 上一轮进程被杀留下的请求/响应不能在新进程里被当成新命令。
        runCatching { req.listFiles()?.forEach { it.delete() } }
        runCatching { res.listFiles()?.forEach { it.delete() } }
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "term-lou-service-ipc").apply { isDaemon = true }
        }
        running = true
        thread = Thread({ loop() }, "term-lou-service-ipc-poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopLocked() {
        running = false
        thread?.interrupt()
        thread = null
        // 已提交的请求让它们写完响应；这里只停止“继续收新请求”。
        runCatching { executor?.shutdown() }
        executor = null
        synchronized(pending) { pending.clear() }
    }

    private fun loop() {
        while (running) {
            try {
                pollOnce()
            } catch (e: Exception) {
                Log.w(TAG, "poll failed", e)
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun pollOnce() {
        val dir = reqDir ?: return
        val exec = executor ?: return
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (file in files) {
            val added = synchronized(pending) {
                if (pending.contains(file.name)) false else {
                    pending.add(file.name)
                    true
                }
            }
            if (!added) continue
            exec.execute {
                try {
                    handle(file)
                } finally {
                    synchronized(pending) { pending.remove(file.name) }
                }
            }
        }
    }

    private fun handle(file: File) {
        val resDir = this.resDir ?: return
        val ctx = context ?: return
        val response = try {
            val request = TermlouServiceProtocol.parse(MiniJson.parse(file.readText()))
            if (request == null) {
                TermlouServiceProtocol.error("bad_request")
            } else {
                TermlouServiceManager.handle(ctx, request)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle ${file.name} failed", e)
            TermlouServiceProtocol.error("bad_request")
        }
        runCatching {
            resDir.mkdirs()
            val out = File(resDir, file.name)
            val tmp = File(resDir, "${file.name}.tmp")
            tmp.writeText(response.toString())
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                out.writeText(response.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "write response ${file.name} failed", it) }
        runCatching { file.delete() }
    }
}
