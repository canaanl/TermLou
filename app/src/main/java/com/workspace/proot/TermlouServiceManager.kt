package com.workspace.proot

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Linux 后台服务托管（5.12.0，方案 A）。
 *
 * 与一次性 [TermlouCommandRunner] 分开：
 *  - 服务启动后由 [TermlouBackgroundService] 前台承载独立 PRoot；
 *  - 主 PRoot 未启动时进入 WAITING，主 PRoot 出现后绑定为 RUNNING；
 *  - 主 PRoot 退出（包括按返回键 finish Activity）时停止已绑定服务；
 *  - WAITING 服务保留，等待下一次主 PRoot；已绑定过的旧服务不会改绑新会话；
 *  - 普通磁贴命令、`runInProot()` 的行为不变。
 *
 * 主会话身份使用 [TerminalSession] 对象 + UUID token，不依赖 PID。
 * WeakHashMap 保证 ThemeSwap/Activity 重建后同一个 TerminalSession 仍拿到原 token，
 * 新会话则拿到新 token，旧服务不会误绑新会话。
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

    private val lock = Any()
    private var appContext: Context? = null
    private var stateLoaded = false
    private var hostRequested = false
    private var hostEpoch = 0L

    private val services = LinkedHashMap<String, Managed>()
    private val sessionTokens = WeakHashMap<TerminalSession, String>()
    private var mainToken: String? = null
    private var mainRef: WeakReference<TerminalSession>? = null
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
        var boundToken: String?,
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
        TermlouServiceProtocol.State.WAITING,
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

    // ---------- 主 PRoot 会话 ----------

    /**
     * 主终端会话创建/ThemeSwap 恢复时调用。必须是同一个 TerminalSession 才会复用 token；
     * 新会话 token 出现时，绑定在旧 token 上的服务先停掉，绝不自动改绑新主 PRoot。
     */
    fun onMainSessionStarted(context: Context, session: TerminalSession) {
        attach(context)
        val stale: List<Managed>
        synchronized(lock) {
            val token = sessionTokens.getOrPut(session) { UUID.randomUUID().toString() }
            mainToken = token
            mainRef = WeakReference(session)

            stale = services.values.filter { s ->
                s.state in ACTIVE && s.boundToken != null && s.boundToken != token
            }
            // 未绑定（WAITING）的服务才允许绑定新主 PRoot；已绑旧会话的服务走 stale 停止。
            bindWaitingLocked(token)
            ensureMonitorLocked()
            writeStateLocked()
        }
        if (stale.isNotEmpty()) {
            io.execute {
                synchronized(lock) { stopStaleLocked(stale) }
            }
        }
    }

    private fun bindWaitingLocked(token: String) {
        for (s in services.values) {
            if (s.state == TermlouServiceProtocol.State.WAITING && s.process?.isAlive == true) {
                s.state = TermlouServiceProtocol.State.RUNNING
                s.boundToken = token
            }
        }
    }

    private fun stopStaleLocked(stale: List<Managed>) {
        val targets = stale.filter { s ->
            services[s.name] === s &&
                s.state in ACTIVE &&
                s.boundToken != null &&
                s.boundToken != mainToken
        }
        for (s in targets) {
            s.state = TermlouServiceProtocol.State.STOPPING
            val clean = stopProcessLocked(s, "main session changed")
            s.boundToken = null
            if (clean) {
                s.state = TermlouServiceProtocol.State.STOPPED
                s.error = "main session changed"
            } else {
                s.state = TermlouServiceProtocol.State.STOPPING
                s.error = "stop_incomplete"
            }
        }
        writeStateLocked()
        refreshHostNotificationLocked(appContext)
        stopMonitorIfIdleLocked()
        stopHostIfIdleLocked()
    }

    /**
     * 主终端会话结束：只停止绑定到该 token 的服务；WAITING 服务保留，等下一次主 PRoot。
     * 新主 PRoot 不会自动复活已绑定过的旧服务。
     */
    fun onMainSessionFinished(context: Context, session: TerminalSession) {
        attach(context)
        val token = synchronized(lock) {
            val t = sessionTokens.remove(session) ?: return
            if (t == mainToken) {
                mainToken = null
                mainRef = null
            }
            t
        }
        io.execute {
            synchronized(lock) { stopBoundToLocked(setOf(token), "main session ended") }
        }
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

            // 主会话可能已结束但回调没到（例如系统直接杀掉 shell 回调链）：先核实存活再绑定。
            if (mainToken != null && mainRef?.get()?.isRunning != true) {
                mainToken = null
                mainRef = null
            }

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
                boundToken = null,
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

    @Suppress("TooGenericExceptionCaught")
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
            val process = tm.startBackground(s.argv)
            s.process = process
            s.pid = processPid(process)
            appendLog(s, "pid: ${s.pid ?: -1}\n")
            drainOutput(s, process)

            // 启动宽限：catch command-not-found / .profile 阻塞解除后立刻退出等情况。
            val early = awaitStartupLocked(context, s, process)
            if (early != null) return early

            s.knownTargets = TermlouServiceReaper.snapshot(s.pid ?: 0)
            s.boundToken = mainToken
            s.state = if (mainToken != null) {
                TermlouServiceProtocol.State.RUNNING
            } else {
                TermlouServiceProtocol.State.WAITING
            }
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
            s.boundToken = null
            s.error = "stopped"
            writeStateLocked()
            refreshHostNotificationLocked(context = appContext)
            stopHostIfIdleLocked()
            TermlouServiceProtocol.ok(snapshot(s))
        } else {
            s.state = TermlouServiceProtocol.State.STOPPING
            s.boundToken = null
            s.error = "stop_incomplete"
            writeStateLocked()
            refreshHostNotificationLocked(context = appContext)
            TermlouServiceProtocol.error("stop_incomplete", snapshot(s))
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
            s.boundToken = null

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

    /**
     * `Process.pid()` 在 Android 26+ 存在，但 Kotlin 编译器的 java.lang.Process
     * 签名不一定暴露它；反射取值，取不到就退化为只 destroy。
     */
    private fun processPid(process: Process): Int? = runCatching {
        val method = Process::class.java.getMethod("pid")
        when (val value = method.invoke(process)) {
            is Int -> value
            is Long -> value.toInt()
            else -> null
        }
    }.getOrNull()

    private fun stopProcessLocked(s: Managed, reason: String): Boolean {
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
            runCatching { process.waitFor(SIGTERM_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
        // 包装进程已经退出时传 0，禁止 reaper 再用旧 PID 查 /proc（PID 可能已复用）。
        val rootPid = if (process?.isAlive == true) pid ?: 0 else 0
        val reaped = TermlouServiceReaper.kill(rootPid, known)

        if (process != null) {
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            runCatching { process.inputStream.close() }
        }
        s.knownTargets = reaped.survivors
        s.exitCode = process?.let { runCatching { it.exitValue() }.getOrNull() }
        appendLog(s, "\n--- service ${s.name} stop: $reason clean=${reaped.clean} ---\n")
        if (reaped.clean) {
            runCatching { s.tempDir.deleteRecursively() }
            s.error = reason
        } else {
            appendLog(s, "survivors: ${reaped.survivors.joinToString(",") { it.pid.toString() }}\n")
            s.error = "stop_incomplete"
        }
        Log.i(TAG, "service ${s.name} stop: $reason clean=${reaped.clean}")
        return reaped.clean
    }

    private fun stopAllLocked(reason: String) {
        for (s in services.values) {
            if (s.state in ACTIVE || s.knownTargets.isNotEmpty()) {
                s.state = TermlouServiceProtocol.State.STOPPING
                val clean = stopProcessLocked(s, reason)
                s.boundToken = null
                if (clean) {
                    s.state = TermlouServiceProtocol.State.STOPPED
                    s.error = reason
                } else {
                    s.state = TermlouServiceProtocol.State.STOPPING
                    s.error = "stop_incomplete"
                }
            }
        }
        writeStateLocked()
        refreshHostNotificationLocked(appContext)
        stopMonitorIfIdleLocked()
        stopHostIfIdleLocked()
    }

    /** 只停止绑定在指定主会话 token 上的服务；WAITING 与其它会话的服务不动。 */
    private fun stopBoundToLocked(tokens: Set<String>, reason: String) {
        val targets = services.values.filter { s ->
            (s.state in ACTIVE || s.knownTargets.isNotEmpty()) &&
                s.boundToken?.let { it in tokens } == true
        }
        for (s in targets) {
            s.state = TermlouServiceProtocol.State.STOPPING
            val clean = stopProcessLocked(s, reason)
            s.boundToken = null
            if (clean) {
                s.state = TermlouServiceProtocol.State.STOPPED
                s.error = reason
            } else {
                s.state = TermlouServiceProtocol.State.STOPPING
                s.error = "stop_incomplete"
            }
        }
        if (targets.isNotEmpty()) {
            writeStateLocked()
            refreshHostNotificationLocked(appContext)
        }
        stopMonitorIfIdleLocked()
        stopHostIfIdleLocked()
    }

    // ---------- 监控（60 秒兜底） ----------

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

    private fun monitorTick() {
        val lostToken = synchronized(lock) {
            // 定期刷新后代快照，保证停止时能补杀中途 fork 的子进程。
            for (s in services.values) {
                val pid = s.pid
                if (pid != null && s.process?.isAlive == true) {
                    s.knownTargets = (s.knownTargets + TermlouServiceReaper.snapshot(pid))
                        .distinctBy { it.pid to it.startTime }
                }
            }
            val token = mainToken ?: return@synchronized null
            val alive = mainRef?.get()?.isRunning == true
            if (!alive) {
                mainToken = null
                mainRef = null
                token
            } else {
                null
            }
        }
        if (lostToken != null) {
            io.execute {
                synchronized(lock) { stopBoundToLocked(setOf(lostToken), "main session lost") }
            }
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
            boundToken = s.boundToken,
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
                        boundToken = null,
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

    /** 测试/退出时清理；正常运行由主会话或用户 stop 触发。 */
    fun shutdownForTest() {
        synchronized(lock) {
            stopAllLocked("shutdown")
            runCatching { monitor?.shutdownNow() }
            monitor = null
            mainToken = null
            mainRef = null
            sessionTokens.clear()
            services.clear()
            appContext = null
            stateLoaded = false
            hostRequested = false
            hostEpoch = 0L
        }
    }
}
