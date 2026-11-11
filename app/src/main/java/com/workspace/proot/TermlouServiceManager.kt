package com.workspace.proot

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Linux 后台服务托管（5.12.3）。
 *
 * 服务是**独立运行单元**，不隶属任何终端会话，只有三条规则：
 *  1. 可以没有主 PRoot 就启动（磁贴直接拉起，不需要先打开主程序）；
 *  2. 每 60 秒检查一次主 PRoot（[MainProotTracker]），不在就停掉全部服务——
 *     包括“从来没有主 PRoot”的情况，服务最多活到下一次检查；
 *  3. 主 PRoot 退出、TermLou 主程序关闭时立即停掉全部服务。
 *
 * 因此这里不存在会话 token / 绑定关系，生命周期只有
 * `STARTING -> RUNNING -> STOPPING -> STOPPED / FAILED`。
 *
 * 与一次性 [TermlouCommandRunner] 分开：服务由 [TermlouBackgroundService] 前台承载
 * 独立 PRoot；普通磁贴命令、`runInProot()` 的行为不变。
 */
@Suppress("TooManyFunctions", "LargeClass")
object TermlouServiceManager {

    private const val TAG = "TermlouService"
    private const val MAX_ACTIVE_SERVICES = 4
    private const val MONITOR_MS = 60_000L
    private const val LOG_CAP_BYTES = 256 * 1024
    private const val STARTUP_GRACE_MS = 500L
    private const val LOG_READ_CHARS = 4096
    private const val EXIT_REAP_TIMEOUT_MS = 1_000L
    private const val SIGTERM_WAIT_MS = 750L
    private const val APP_CLOSE_JOIN_MS = 1_200L
    private const val APP_CLOSE_VERIFY_MS = 500L

    private val lock = Any()
    private var appContext: Context? = null
    private var stateLoaded = false
    private var hostRequested = false
    private var hostEpoch = 0L

    private val services = LinkedHashMap<String, Managed>()
    private var monitor: ScheduledExecutorService? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "term-lou-service-mgr").apply { isDaemon = true }
    }

    @Suppress("LongParameterList")
    private class Managed(
        val name: String,
        val argv: List<String>,
        val command: String,
        val startedAt: Long,
        var state: TermlouServiceProtocol.State,
        var process: Process?,
        var pid: Int?,
        var exitCode: Int?,
        var error: String?,
        val logFile: File,
        val tempDir: File,
        var knownTargets: List<TermlouServiceReaper.Target> = emptyList(),
    )

    private val ACTIVE = setOf(
        TermlouServiceProtocol.State.STARTING,
        TermlouServiceProtocol.State.RUNNING,
        TermlouServiceProtocol.State.STOPPING,
    )

    fun attach(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                loadStateLocked()
            }
        }
    }

    // ---------- 主 PRoot 生命周期 ----------

    /**
     * 主 PRoot 启动。只登记身份，不触碰任何服务：服务不因主 PRoot 出现而改变状态，
     * 也不会被重新“绑定”。
     */
    fun onMainSessionStarted(context: Context, session: TerminalSession) {
        attach(context)
        MainProotTracker.onStarted(session.pid)
    }

    /**
     * 主 PRoot 退出：主 PRoot 关掉说明不再需要后台，立即停掉全部服务，
     * 不等 60 秒监控。ThemeSwap 换色重建不会走到这里（同一会话被恢复）。
     */
    fun onMainSessionFinished(context: Context, session: TerminalSession) {
        attach(context)
        MainProotTracker.onFinished(session.pid)
        io.execute {
            synchronized(lock) {
                if (MainProotTracker.isAlive()) return@synchronized
                stopAllLocked("main_proot_exited")
            }
        }
    }

    /**
     * TermLou 主程序关闭（返回键 finish / 从最近任务划掉）：
     * Android 不会因为 Activity 结束就回收 PRoot 子进程，必须显式杀干净。
     *
     * 在独立线程执行并限时等待：关闭 App 后进程可能很快被杀，完全异步的清理来不及完成，
     * 残留的后台服务正是要消除的问题；但也不能长时间阻塞主线程。
     */
    fun onAppClosed(context: Context) {
        attach(context)
        MainProotTracker.reset()
        val worker = Thread({
            synchronized(lock) { stopAllLocked("app_closed", graceful = false) }
        }, "term-lou-app-close").apply { isDaemon = true }
        worker.start()
        // 只等一小段，避免 ANR；清理线程是 daemon，App 进程被回收前会继续跑完。
        runCatching { worker.join(APP_CLOSE_JOIN_MS) }
    }

    // ---------- 命令入口 ----------

    fun handle(context: Context, request: TermlouServiceProtocol.Request): MiniJson.Obj {
        attach(context)
        return when (request.action) {
            TermlouServiceProtocol.ACTION_START -> startServiceLocked(context, request)
            TermlouServiceProtocol.ACTION_STOP -> stopServiceLocked(request.name)
            TermlouServiceProtocol.ACTION_STATUS -> statusLocked(request.name)
            TermlouServiceProtocol.ACTION_LIST -> listLocked()
            TermlouServiceProtocol.ACTION_RM -> removeServiceLocked(request.name)
            TermlouServiceProtocol.ACTION_CLEAN -> cleanServicesLocked()
            else -> TermlouServiceProtocol.error("unknown action")
        }
    }

    /**
     * 启动服务。这里**不检查主 PRoot**：无主 PRoot 也能启动，能活多久由 60 秒监控决定。
     */
    private fun startServiceLocked(context: Context, request: TermlouServiceProtocol.Request): MiniJson.Obj =
        synchronized(lock) {
            val existing = services[request.name]
            if (existing != null && existing.state in ACTIVE) {
                return@synchronized TermlouServiceProtocol.error("exists", snapshot(existing))
            }
            if (existing != null && (existing.process != null || existing.knownTargets.isNotEmpty())) {
                return@synchronized TermlouServiceProtocol.error("stop_incomplete", snapshot(existing))
            }
            if (activeCountLocked() >= MAX_ACTIVE_SERVICES) {
                return@synchronized TermlouServiceProtocol.error("too_many_services")
            }
            // 已 STOPPED/FAILED 的同名记录允许重新创建；先清理旧记录。
            if (existing != null) services.remove(existing.name)

            val root = TermlouDirs.base(context)
            val serviceDir = File(root, "services").apply { mkdirs() }
            val tempRoot = File(context.filesDir, "workspace/tmp").apply { mkdirs() }
            val tempDir = File(tempRoot, "svc-${request.name}-${System.currentTimeMillis()}").apply { mkdirs() }
            val log = File(serviceDir, "${request.name}.log")
            val s = Managed(
                name = request.name,
                argv = request.argv,
                command = request.argv.joinToString(" "),
                startedAt = System.currentTimeMillis(),
                state = TermlouServiceProtocol.State.STARTING,
                process = null,
                pid = null,
                exitCode = null,
                error = null,
                logFile = log,
                tempDir = tempDir,
            )
            services[s.name] = s

            if (!ensureHostLocked(context)) {
                services.remove(s.name)
                tempDir.deleteRecursively()
                return@synchronized TermlouServiceProtocol.error("cannot_start_background_service")
            }

            launchServiceLocked(context, s)
        }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun launchServiceLocked(context: Context, s: Managed): MiniJson.Obj {
        return try {
            appendLog(s, "--- service ${s.name} start at ${System.currentTimeMillis()} ---\n")
            appendLog(s, "command: ${s.command}\n")
            val tm = TerminalManager(
                context,
                File(context.filesDir, "workspace/linux"),
                File(context.filesDir, "workspace"),
                s.tempDir,
            )
            tm.setupWrappers()
            val pidFile = File(s.tempDir, "host.pid")
            val process = tm.startBackground(s.argv, pidFile)
            s.process = process
            val pid = ServiceProcessPidResolver.resolve(process, s.tempDir, pidFile)
            s.pid = pid
            appendLog(s, "pid: ${pid ?: -1}\n")
            if (pid == null) {
                return failStartPidUnavailable(context, s, process)
            }
            drainOutput(s, process)

            // 启动宽限：catch command-not-found / .profile 阻塞解除后立刻退出等情况。
            val early = awaitStartupLocked(context, s, process)
            if (early != null) return early

            s.knownTargets = TermlouServiceReaper.snapshot(pid)
            s.state = TermlouServiceProtocol.State.RUNNING
            ensureMonitorLocked()
            writeStateLocked()
            refreshHostNotificationLocked(context)
            TermlouServiceProtocol.ok(snapshot(s))
        } catch (e: Exception) {
            Log.e(TAG, "start ${s.name} failed", e)
            s.state = TermlouServiceProtocol.State.FAILED
            s.error = e.message ?: e.javaClass.simpleName
            s.process = null
            s.pid = null
            appendLog(s, "--- service ${s.name} start failed: ${s.error} ---\n")
            s.tempDir.deleteRecursively()
            writeStateLocked()
            refreshHostNotificationLocked(context)
            stopHostIfIdleLocked()
            TermlouServiceProtocol.error("start_failed: ${s.error}", snapshot(s))
        }
    }

    /**
     * 拿不到真实 PID 时拒绝继续托管：没有 PID 就无法验证/清理进程树，
     * 宁可启动失败，也不能留下一条 stop 永远收不干净的记录。
     */
    private fun failStartPidUnavailable(
        context: Context,
        s: Managed,
        process: Process,
    ): MiniJson.Obj {
        s.state = TermlouServiceProtocol.State.FAILED
        s.error = "start_failed: pid_unavailable"
        appendLog(s, "--- service ${s.name} start failed: pid_unavailable ---\n")
        runCatching { process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        runCatching { process.inputStream.close() }
        s.process = null
        s.pid = null
        s.tempDir.deleteRecursively()
        writeStateLocked()
        refreshHostNotificationLocked(context)
        stopHostIfIdleLocked()
        return TermlouServiceProtocol.error("start_failed: pid_unavailable", snapshot(s))
    }

    /** 启动宽限期检查；提前退出返回错误 JSON，存活返回 null。 */
    private fun awaitStartupLocked(
        context: Context,
        s: Managed,
        process: Process,
    ): MiniJson.Obj? {
        try {
            Thread.sleep(STARTUP_GRACE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (process.isAlive) return null
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        s.process = null
        s.pid = null
        s.exitCode = code
        s.state = TermlouServiceProtocol.State.FAILED
        s.error = "exited during startup (code $code)"
        appendLog(s, "--- service ${s.name} exited during startup code=$code ---\n")
        s.tempDir.deleteRecursively()
        writeStateLocked()
        refreshHostNotificationLocked(context)
        stopHostIfIdleLocked()
        return TermlouServiceProtocol.error(
            "start_failed: exited during startup (code $code)",
            snapshot(s),
        )
    }

    private fun stopServiceLocked(name: String): MiniJson.Obj = synchronized(lock) {
        val s = services[name] ?: return@synchronized TermlouServiceProtocol.error("not_found")
        if (s.state !in ACTIVE && s.knownTargets.isEmpty()) {
            return@synchronized TermlouServiceProtocol.ok(snapshot(s))
        }
        s.state = TermlouServiceProtocol.State.STOPPING
        val clean = stopProcessLocked(s, "stopped")
        if (clean) {
            s.state = TermlouServiceProtocol.State.STOPPED
            s.error = "stopped"
            writeStateLocked()
            refreshHostNotificationLocked(context = appContext)
            stopHostIfIdleLocked()
            TermlouServiceProtocol.ok(snapshot(s))
        } else {
            s.state = TermlouServiceProtocol.State.STOPPING
            val err = s.error?.takeIf { it == "pid_unavailable" } ?: "stop_incomplete"
            s.error = err
            writeStateLocked()
            refreshHostNotificationLocked(context = appContext)
            TermlouServiceProtocol.error(err, snapshot(s))
        }
    }

    private fun removeServiceLocked(name: String): MiniJson.Obj = synchronized(lock) {
        val s = services[name] ?: return@synchronized TermlouServiceProtocol.error("not_found")
        if (s.state in ACTIVE || s.process != null || s.knownTargets.isNotEmpty()) {
            return@synchronized TermlouServiceProtocol.error("active", snapshot(s))
        }
        services.remove(name)
        runCatching { s.logFile.delete() }
        runCatching { s.tempDir.deleteRecursively() }
        writeStateLocked()
        refreshHostNotificationLocked(context = appContext)
        stopHostIfIdleLocked()
        TermlouServiceProtocol.okRemoved(name)
    }

    private fun cleanServicesLocked(): MiniJson.Obj = synchronized(lock) {
        val targets = services.values.filter { s ->
            s.state !in ACTIVE && s.process == null && s.knownTargets.isEmpty()
        }
        for (s in targets) {
            services.remove(s.name)
            runCatching { s.logFile.delete() }
            runCatching { s.tempDir.deleteRecursively() }
        }
        if (targets.isNotEmpty()) {
            writeStateLocked()
            refreshHostNotificationLocked(context = appContext)
            stopHostIfIdleLocked()
        }
        TermlouServiceProtocol.okRemovedCount(targets.size)
    }

    private fun statusLocked(name: String): MiniJson.Obj = synchronized(lock) {
        val s = services[name] ?: return@synchronized TermlouServiceProtocol.error("not_found")
        TermlouServiceProtocol.ok(snapshot(s))
    }

    private fun listLocked(): MiniJson.Obj = synchronized(lock) {
        val list = services.values.sortedBy { it.startedAt }.map(::snapshot)
        TermlouServiceProtocol.okServices(list)
    }

    // ---------- 主机服务 ----------

    fun onHostStarted(context: Context, epoch: Long) {
        attach(context)
        synchronized(lock) {
            // 旧代宿主（epoch 落后）可能还会收到 onStartCommand，不能把状态抬回 true。
            if (epoch == hostEpoch || epoch < 0) hostRequested = true
            refreshHostNotificationLocked(context)
        }
    }

    fun onHostDestroyed(epoch: Long) {
        // 只有“当前这一代”宿主销毁才清状态/停服务。
        // 若销毁回调之前已经为下一个服务请求了新宿主（hostEpoch 变了），
        // 旧宿主的 onDestroy 不能误清新请求，也不能误杀刚启动的服务。
        val current = synchronized(lock) {
            if (epoch >= 0 && epoch != hostEpoch) return
            hostRequested = false
            hostEpoch
        }
        io.execute {
            synchronized(lock) {
                if (hostEpoch != current) return@execute
                stopAllLocked("host service destroyed")
            }
        }
    }

    fun hasActiveServices(): Boolean = synchronized(lock) { activeCountLocked() > 0 }

    fun activeServiceCount(): Int = synchronized(lock) { activeCountLocked() }

    private fun ensureHostLocked(context: Context): Boolean {
        if (hostRequested) return true
        return runCatching {
            hostEpoch++
            ContextCompat.startForegroundService(
                context,
                Intent(context, TermlouBackgroundService::class.java)
                    .putExtra(TermlouBackgroundService.EXTRA_HOST_EPOCH, hostEpoch),
            )
            hostRequested = true
            true
        }.onFailure { Log.e(TAG, "start background host failed", it) }.getOrDefault(false)
    }

    private fun stopHostIfIdleLocked() {
        if (activeCountLocked() > 0 || !hostRequested) return
        val ctx = appContext ?: return
        hostRequested = false
        runCatching { ctx.stopService(Intent(ctx, TermlouBackgroundService::class.java)) }
    }

    private fun refreshHostNotificationLocked(context: Context?) {
        if (context == null) return
        TermlouBackgroundService.refresh(context, activeCountLocked())
    }

    // ---------- 进程 ----------

    private fun appendLog(s: Managed, text: String) {
        LogBudget.appendCapped(s.logFile, text, LOG_CAP_BYTES)
    }

    private fun drainOutput(s: Managed, process: Process) {
        Thread({
            var code = -1
            try {
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(LOG_READ_CHARS)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) appendLog(s, String(buf, 0, n))
                    }
                }
            } catch (_: Exception) {
            }
            code = runCatching { process.waitFor() }.getOrDefault(-1)
            appendLog(s, "\n--- service ${s.name} exited code=$code ---\n")
            onProcessExit(s, process, code)
        }, "term-lou-svc-${s.name}").apply { isDaemon = true }.start()
    }

    private fun onProcessExit(s: Managed, process: Process, code: Int) {
        synchronized(lock) {
            if (s.process !== process) return
            s.process = null
            s.pid = null
            s.exitCode = code

            // 包装进程退出后 PID 可能立即被复用；只能用退出前记录的 knownTargets，且不传 root PID。
            val reaped = TermlouServiceReaper.kill(0, s.knownTargets, EXIT_REAP_TIMEOUT_MS)
            s.knownTargets = reaped.survivors
            if (!reaped.clean) {
                s.state = TermlouServiceProtocol.State.STOPPING
                s.error = "exit $code; stop_incomplete"
            } else if (s.state != TermlouServiceProtocol.State.STOPPING) {
                if (code == 0) {
                    s.state = TermlouServiceProtocol.State.STOPPED
                    s.error = "exited"
                } else {
                    s.state = TermlouServiceProtocol.State.FAILED
                    s.error = "exit $code"
                }
            } else {
                s.state = TermlouServiceProtocol.State.STOPPED
            }
            if (reaped.clean) runCatching { s.tempDir.deleteRecursively() }
            writeStateLocked()
            refreshHostNotificationLocked(appContext)
            stopMonitorIfIdleLocked()
            stopHostIfIdleLocked()
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun stopProcessLocked(s: Managed, reason: String, graceful: Boolean = true): Boolean {
        val process = s.process
        val pid = s.pid
        s.process = null
        s.pid = null

        // 先趁 root 还活着抓一次树，再优雅退出；否则停止过程中新 fork 的子进程会漏掉。
        // 注意：包装进程已经退出时绝不能再 snapshot(pid)，PID 可能已被复用。
        val known = s.knownTargets +
            if (pid != null && process?.isAlive == true) TermlouServiceReaper.snapshot(pid) else emptyList()

        // 先给一次优雅退出的机会，再进入强制清理。
        if (process != null) {
            runCatching { process.destroy() }
            if (graceful) {
                runCatching { process.waitFor(SIGTERM_WAIT_MS, TimeUnit.MILLISECONDS) }
            }
        }
        if (pid == null && s.knownTargets.isEmpty()) {
            // 没有 PID 就没有可验证的进程树；绝不能返回 clean 假装 STOPPED。
            if (process != null) {
                runCatching { if (process.isAlive) process.destroyForcibly() }
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                runCatching { process.inputStream.close() }
            }
            s.knownTargets = emptyList()
            s.exitCode = process?.let { runCatching { it.exitValue() }.getOrNull() }
            s.error = "pid_unavailable"
            appendLog(s, "\n--- service ${s.name} stop: $reason clean=false reason=pid_unavailable ---\n")
            Log.w(TAG, "service ${s.name} stop: pid unavailable, tree not verified")
            return false
        }
        // 包装进程已经退出时传 0，禁止 reaper 再用旧 PID 查 /proc（PID 可能已复用）。
        val rootPid = if (process?.isAlive == true) pid ?: 0 else 0
        // 关闭 App 时跳过优雅等待，验证窗口也收紧：SIGKILL 已发出，进程组由 --kill-on-exit 兜底。
        val verifyMs = if (graceful) TermlouServiceReaper.DEFAULT_TIMEOUT_MS else APP_CLOSE_VERIFY_MS
        val reaped = TermlouServiceReaper.kill(rootPid, known, verifyMs)

        if (process != null) {
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            runCatching { process.inputStream.close() }
        }
        // reaper 判定 clean 后，Process 对象仍存活说明 /proc 可能不可读；不能报 STOPPED。
        val clean = reaped.clean && process?.isAlive != true
        val survivors = if (clean) emptyList() else reaped.survivors.ifEmpty { known }
        s.knownTargets = survivors
        s.exitCode = process?.let { runCatching { it.exitValue() }.getOrNull() }
        appendLog(s, "\n--- service ${s.name} stop: $reason clean=$clean ---\n")
        if (clean) {
            runCatching { s.tempDir.deleteRecursively() }
            s.error = reason
        } else {
            appendLog(s, "survivors: ${survivors.joinToString(",") { it.pid.toString() }}\n")
            s.error = "stop_incomplete"
        }
        Log.i(TAG, "service ${s.name} stop: $reason clean=$clean")
        return clean
    }

    /** 停止全部活动服务（主 PRoot 消失、App 关闭、宿主销毁的统一入口）。 */
    private fun stopAllLocked(reason: String, graceful: Boolean = true) {
        for (s in services.values) {
            if (s.state in ACTIVE || s.knownTargets.isNotEmpty()) {
                s.state = TermlouServiceProtocol.State.STOPPING
                val clean = stopProcessLocked(s, reason, graceful)
                if (clean) {
                    s.state = TermlouServiceProtocol.State.STOPPED
                    s.error = reason
                } else {
                    s.state = TermlouServiceProtocol.State.STOPPING
                    s.error = s.error?.takeIf { it == "pid_unavailable" } ?: "stop_incomplete"
                }
            }
        }
        writeStateLocked()
        refreshHostNotificationLocked(appContext)
        stopMonitorIfIdleLocked()
        stopHostIfIdleLocked()
    }

    // ---------- 监控（60 秒） ----------

    private fun ensureMonitorLocked() {
        if (monitor != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "term-lou-service-monitor").apply { isDaemon = true }
        }
        exec.scheduleWithFixedDelay({ monitorTick() }, MONITOR_MS, MONITOR_MS, TimeUnit.MILLISECONDS)
        monitor = exec
    }

    private fun stopMonitorIfIdleLocked() {
        if (activeCountLocked() > 0) return
        runCatching { monitor?.shutdownNow() }
        monitor = null
    }

    /**
     * 每 60 秒检查一次：主 PRoot 不在就停掉全部服务。
     *
     * 这里**不因为“没有主 PRoot 身份”而提前返回**——从来没有主 PRoot 时同样要停，
     * 否则磁贴拉起的服务会永远活着。整个 tick 包在 try/catch 里，
     * 单次异常不会让 `scheduleWithFixedDelay` 永久停摆。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun monitorTick() {
        try {
            val idle = synchronized(lock) {
                // 定期刷新后代快照，保证停止时能补杀中途 fork 的子进程。
                for (s in services.values) {
                    val pid = s.pid
                    if (pid != null && s.process?.isAlive == true) {
                        s.knownTargets = (s.knownTargets + TermlouServiceReaper.snapshot(pid))
                            .distinctBy { it.pid to it.startTime }
                    }
                }
                activeCountLocked() == 0
            }
            if (idle) {
                synchronized(lock) { stopMonitorIfIdleLocked() }
            } else if (!MainProotTracker.isAlive()) {
                // 进入 IO 线程后再确认一次，避免与刚启动的主 PRoot 竞争。
                io.execute {
                    synchronized(lock) {
                        if (activeCountLocked() == 0) return@synchronized
                        if (MainProotTracker.isAlive()) return@synchronized
                        Log.i(TAG, "main PRoot missing, stopping all services")
                        stopAllLocked("main_proot_missing")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "monitor tick failed", e)
        }
    }

    // ---------- 快照与持久化 ----------

    private fun activeCountLocked(): Int = services.values.count { it.state in ACTIVE }

    private fun snapshot(s: Managed): TermlouServiceProtocol.ServiceSnapshot =
        TermlouServiceProtocol.ServiceSnapshot(
            name = s.name,
            command = s.command,
            state = s.state,
            startedAt = s.startedAt,
            alive = s.process?.isAlive == true,
            exitCode = s.exitCode,
            error = s.error,
            logPath = "/termlou/services/${s.name}.log",
            pid = s.pid,
        )

    private fun stateFile(context: Context): File = File(TermlouDirs.base(context), "services.json")

    private fun writeStateLocked() {
        val ctx = appContext ?: return
        runCatching {
            val arr = MiniJson.Arr()
            for (s in services.values.sortedBy { it.startedAt }) {
                arr.put(
                    MiniJson.Obj()
                        .put("name", s.name)
                        .put("command", s.command)
                        .put("state", s.state.name)
                        .put("started_at", s.startedAt)
                        .put("exit_code", s.exitCode ?: -1)
                        .put("error", s.error ?: ""),
                )
            }
            stateFile(ctx).parentFile?.mkdirs()
            val out = stateFile(ctx)
            val tmp = File(out.parentFile, "${out.name}.tmp")
            tmp.writeText(MiniJson.Obj().put("services", arr).toString())
            if (!tmp.renameTo(out)) {
                out.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "write service state failed", it) }
    }

    @Suppress("ReturnCount")
    private fun loadStateLocked() {
        if (stateLoaded) return
        stateLoaded = true
        val ctx = appContext ?: return
        runCatching {
            val f = stateFile(ctx)
            if (!f.isFile) return
            val root = MiniJson.parse(f.readText())
            val arr = root.optArr("services") ?: return
            var converted = false
            for (i in 0 until arr.length()) {
                val obj = arr.getObj(i) ?: continue
                val name = obj.optString("name", "")
                if (TermlouServiceProtocol.validName(name)) {
                    val stateText = obj.optString("state", "FAILED")
                    val state = runCatching { TermlouServiceProtocol.State.valueOf(stateText) }
                        .getOrDefault(TermlouServiceProtocol.State.FAILED)
                    val wasActive = state in ACTIVE
                    if (wasActive) converted = true
                    val startedAt = (obj.raw("started_at") as? Number)?.toLong() ?: 0L
                    val exitCode = (obj.raw("exit_code") as? Number)?.toInt()?.takeIf { it != -1 }
                    services[name] = Managed(
                        name = name,
                        argv = emptyList(),
                        command = obj.optString("command", ""),
                        startedAt = startedAt,
                        state = if (wasActive) TermlouServiceProtocol.State.FAILED else state,
                        process = null,
                        pid = null,
                        exitCode = exitCode,
                        error = if (wasActive) "app process restarted" else obj.optString("error", ""),
                        logFile = File(File(TermlouDirs.base(ctx), "services"), "$name.log"),
                        tempDir = File(File(ctx.filesDir, "workspace/tmp"), "svc-$name"),
                    )
                }
            }
            if (converted) writeStateLocked()
        }.onFailure { Log.w(TAG, "load service state failed", it) }
    }

    /** 测试/退出时清理；正常运行由主 PRoot 消失、App 关闭或用户 stop 触发。 */
    fun shutdownForTest() {
        synchronized(lock) {
            stopAllLocked("shutdown")
            runCatching { monitor?.shutdownNow() }
            monitor = null
            MainProotTracker.reset()
            services.clear()
            appContext = null
            stateLoaded = false
            hostRequested = false
            hostEpoch = 0L
        }
    }
}
