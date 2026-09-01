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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TermLou")
            .setContentText("后台保活中")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        KeepAliveWakeLock.init(this)
        KeepAliveWakeLock.setActive(true)
        return START_STICKY
    }

    override fun onDestroy() {
        KeepAliveWakeLock.setActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "term-lou-keepalive"
        private const val NOTIFICATION_ID = 1
    }
}
