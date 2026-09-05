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

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tile_runner_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var cmd = intent?.getStringExtra(EXTRA_COMMAND)
        if (cmd.isNullOrBlank()) {
            // 启动被吞/命令未随 Intent 到达时，兜底消费 pending 文件里残留的命令。
            cmd = takePendingCommand()
        }
        if (cmd.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 拿到有效命令后统一清掉 pending 残留（无论命令来自 intent 还是文件），
        // 避免文件无限累积；若删除失败也不影响本次执行，下次点击会覆盖。
        deletePendingFile()
        // 必须先 startForeground：本服务由 startForegroundService() 拉起，
        // 无论后续是否执行，都要在时限内完成前台声明，否则系统判 ForegroundServiceDidNotStartInTimeException 闪退。
        startForeground(NOTIFICATION_ID, buildNotification())
        // 冷启动重试 150ms 内可能重复投递同一命令，去重避免重复执行；
        // 超过时间窗的再次点击视为新请求，放行进入队列，避免连续点击偶发无响应。
        val now = SystemClock.elapsedRealtime()
        if (executing == cmd && now - lastStartAt < DEDUP_WINDOW_MS) return START_NOT_STICKY
        executing = cmd
        lastStartAt = now
        runCommand(cmd)
        return START_NOT_STICKY
    }

    /** 读取 pending 文件中的命令（不删除；由调用方统一清理）。 */
    private fun takePendingCommand(): String? {
        val file = TermlouDirs.pending(applicationContext)
        if (!file.exists()) return null
        return runCatching {
            file.readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun deletePendingFile() {
        runCatching {
            TermlouDirs.pending(applicationContext).delete()
        }
    }

    private fun runCommand(cmd: String) {
        val app = applicationContext
        val lxRoot = File(app.filesDir, "workspace/linux")
        val wsFiles = File(app.filesDir, "workspace")
        val wsTmp = File(app.filesDir, "workspace/tmp")
        OverlayBridge.acquire(app, TermlouDirs.base(app))
        val tm = TerminalManager(app, lxRoot, wsFiles, wsTmp)
        scope.launch {
            try {
                tm.setupWrappers()
                // 去掉末尾孤立的续行反斜杠，防止 bash -c 把它当字面参数
                val execCmd = cmd.trim().trimEnd('\\').trim()
                tm.runInProot(execCmd, RUN_TIMEOUT_SEC)
            } catch (e: Exception) {
                Log.e("TermlouCommandRunner", "run failed", e)
            } finally {
                executing = null
                stopSelf()
            }
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
        private const val CHANNEL_ID = "term-lou-command"
        private const val NOTIFICATION_ID = 2
        private const val RUN_TIMEOUT_SEC = 600L

        /** 冷启动重试 150ms 双投的时间窗；超过则视为新的真实点击。 */
        private const val DEDUP_WINDOW_MS = 500L

        /** 当前正在执行的命令（跨 onStartCommand 去重）。 */
        private var executing: String? = null

        /** 最近一次放行命令的时间戳（elapsedRealtime）。 */
        private var lastStartAt = 0L
    }
}
