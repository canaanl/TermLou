package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 按需任务执行器：磁贴点击 termlou-ui 命令时由跳板 Activity 拉起。
 * 静默在 proot 里执行命令，浮窗由进程级 OverlayBridge 单例呈现；命令返回后自停。
 */
class TermlouCommandRunner : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var released = false
    private val runLock = Any()
    private var busy = false
    private var lastStartAt = 0L
    private var lastCmd: String? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tile_runner_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromExtra = intent?.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
        val extraId = intent?.getStringExtra(EXTRA_ID)
        synchronized(runLock) {
            // 已有命令在执行：本次请求已落盘，服务下次启动时自会按序消费，避免并发执行。
            if (busy) return START_NOT_STICKY
            // 冷启动 150ms 双投去重；超过时间窗的再次点击视为新请求放行。
            val now = SystemClock.elapsedRealtime()
            if (fromExtra == lastCmd && now - lastStartAt < DEDUP_WINDOW_MS) return START_NOT_STICKY
        }
        val pending = collectPendingCommands()
        val commands = buildList {
            // 同一次点击经 EXTRA 与其落盘备份携带同一意图 ID，只跑一次；
            // 不同 ID（不同点击/兜底）照常按序跑，不丢任何意图。
            if (fromExtra != null && markExecuted(extraId ?: "extra-${fromExtra.hashCode()}")) add(fromExtra)
            for ((id, cmd) in pending) {
                if (markExecuted(id)) add(cmd)
            }
        }
        if (commands.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须先 startForeground：本服务由 startForegroundService() 拉起，
        // 无论后续是否执行，都要在时限内完成前台声明，否则系统判 ForegroundServiceDidNotStartInTimeException 闪退。
        startForeground(NOTIFICATION_ID, buildNotification())
        synchronized(runLock) {
            busy = true
            lastCmd = fromExtra
            lastStartAt = SystemClock.elapsedRealtime()
        }
        // 已取得命令，落盘文件全部消费掉，避免累积。
        consumePendingFiles()
        scope.launch {
            try {
                for (cmd in commands) {
                    runCommand(cmd)
                }
            } finally {
                synchronized(runLock) { busy = false }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** 读取所有落盘的待执行命令（按命名排序；不删除，由调用方统一消费）。返回（意图ID，命令）。 */
    private fun collectPendingCommands(): List<Pair<String, String>> {
        return TermlouDirs.pendingFiles(applicationContext).mapNotNull { f ->
            val text = runCatching { f.readText().trim().takeIf { it.isNotBlank() } }.getOrNull()
                ?: return@mapNotNull null
            TermlouDirs.pendingId(f.name) to text
        }
    }

    private fun consumePendingFiles() {
        TermlouDirs.pendingFiles(applicationContext).forEach { runCatching { it.delete() } }
    }

    private fun runCommand(cmd: String) {
        val app = applicationContext
        val lxRoot = File(app.filesDir, "workspace/linux")
        val wsFiles = File(app.filesDir, "workspace")
        val wsTmp = File(app.filesDir, "workspace/tmp/run-" + System.currentTimeMillis())
        OverlayBridge.acquire(app, TermlouDirs.base(app))
        val tm = TerminalManager(app, lxRoot, wsFiles, wsTmp)
        try {
            tm.setupWrappers()
            // 去掉末尾孤立的续行反斜杠，防止 bash -c 把它当字面参数
            val execCmd = cmd.trim().trimEnd('\\').trim()
            tm.runInProot(execCmd, RUN_TIMEOUT_SEC)
        } catch (e: Exception) {
            Log.e("TermlouCommandRunner", "run failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TermLou")
            .setContentText(getString(R.string.tile_runner_running))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseOnce()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun releaseOnce() {
        if (released) return
        released = true
        OverlayBridge.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_COMMAND = "tile_command"
        const val EXTRA_ID = "tile_intent_id"
        private const val CHANNEL_ID = "term-lou-command"
        private const val NOTIFICATION_ID = 2
        private const val RUN_TIMEOUT_SEC = 600L

        /** 已执行意图 ID（进程级：同一点击的 EXTRA 与落盘备份、冷启动重试都共享 ID，只跑一次）。
         * 重复只可能来自同一进程内的双胞胎/重试，内存集合充分；新点击永远是新 ID。 */
        private val executedIds = LinkedHashSet<String>()
        private const val EXECUTED_IDS_CAP = 100

        /** 记名已执行；返回 false 表示该意图已跑过，本次跳过。 */
        private fun markExecuted(id: String): Boolean {
            if (!executedIds.add(id)) return false
            while (executedIds.size > EXECUTED_IDS_CAP) {
                executedIds.remove(executedIds.first())
            }
            return true
        }

        /** 冷启动重试 150ms 双投的时间窗；超过则视为新的真实点击。 */
        private const val DEDUP_WINDOW_MS = 500L
    }
}
