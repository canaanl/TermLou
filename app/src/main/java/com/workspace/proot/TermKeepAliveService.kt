package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

class TermKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { instance = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        startForeground(NOTIFICATION_ID, buildNotification())
        KeepAliveWakeLock.init(this)
        KeepAliveWakeLock.setActive(true)
        // 预热启动（设置里没开保活）：300ms 后自杀并写预热标记。
        // 生命周期绑定服务本身 —— Activity 随时销毁都不会再把服务/wakelock 留给下一次启动。
        if (!keepAliveEnabled()) {
            handler.removeCallbacks(stopWarmup)
            handler.postDelayed(stopWarmup, WARMUP_STOP_MS)
        } else {
            handler.removeCallbacks(stopWarmup)
        }
        return START_STICKY
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val stopWarmup = Runnable {
        // 到点仍未被设置项真正开启才停；期间开了保活就永久驻留
        if (!keepAliveEnabled()) {
            runCatching {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_WARMED, true).apply()
            }
            stopSelf()
        }
    }

    private fun keepAliveEnabled(): Boolean = try {
        SettingsManager(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)).let { it.load(); it.keepAlive }
    } catch (_: Exception) { false }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TermLou")
            .setContentText(getString(R.string.keepalive_notif))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        KeepAliveWakeLock.setActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        synchronized(lock) { if (instance === this) instance = null }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "term-lou-keepalive"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "term-lou-settings"
        private const val KEY_WARMED = "fgServiceWarmed"
        private const val WARMUP_STOP_MS = 300L
        private val lock = Any()
        private var instance: TermKeepAliveService? = null

        fun refreshLocale() {
            val s = synchronized(lock) { instance }
            s?.runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }
}
