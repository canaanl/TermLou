package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 长期服务托管的前台宿主（5.12.0）。
 *
 * 它本身不执行命令，只负责让 App 进程在 Activity 关闭/后台时继续存活，
 * 并持有 [TermlouServiceBridge]；真正的 PRoot 进程由 [TermlouServiceManager] 管理。
 *
 * `START_NOT_STICKY`：系统杀掉后不无限自动重启；服务状态由持久化文件标记为
 * FAILED，避免“看起来还活着”的幽灵记录。
 */
class TermlouBackgroundService : Service() {

    private var hostEpoch = -1L

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        TermlouServiceManager.attach(this)
        TermlouServiceBridge.acquire(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        hostEpoch = intent?.getLongExtra(EXTRA_HOST_EPOCH, -1L) ?: -1L
        startForeground(NOTIFICATION_ID, buildNotification(this, TermlouServiceManager.activeServiceCount()))
        TermlouServiceManager.onHostStarted(this, hostEpoch)
        if (!TermlouServiceManager.hasActiveServices()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TermlouServiceManager.onHostDestroyed(hostEpoch)
        TermlouServiceBridge.release()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /**
     * 用户从最近任务划掉 TermLou 时，Activity 的 onDestroy 可能不执行；
     * 这里作为“关闭主程序”的第二条可靠路径，停掉全部后台服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        TermlouServiceManager.onAppClosed(applicationContext)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_HOST_EPOCH = "term_lou_host_epoch"
        private const val CHANNEL_ID = "term-lou-services"
        private const val NOTIFICATION_ID = 3
        private const val OPEN_REQUEST_CODE = 3

        internal fun refresh(context: Context, count: Int) {
            ensureChannel(context)
            if (count <= 0) return
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(context, count))
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.service_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        private fun buildNotification(context: Context, count: Int): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingOpen = PendingIntent.getActivity(
                context,
                OPEN_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.service_notif_title))
                .setContentText(context.getString(R.string.service_notif_fmt, count))
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .build()
        }
    }
}
