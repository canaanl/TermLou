package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * LAN 共享前台服务：起 WsServer（HTTP + WebSocket 同端口），关即停并释放端口。
 * 关闭时清空账号密码与端口偏好（状态重置为空）。
 */
class LanShareService : Service() {

    private val lock = Any()
    private var active = false
    private var wsServer: WsServer? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LAN 共享", NotificationManager.IMPORTANCE_LOW)
        )
        synchronized(lock) { instance = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            statusText = "启动失败"
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        doStart()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        synchronized(lock) { if (instance === this) instance = null }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun doStart() {
        synchronized(lock) { if (active) return }
        Thread {
            try {
                startServer()
                synchronized(lock) { active = true }
            } catch (e: Exception) {
                Log.e(TAG, "start lan failed", e)
                statusText = "启动失败: ${e.message}"
                isRunning = false
                teardown()
                stopSelf()
            }
        }.start()
    }

    private fun startServer() {
        val sm = SettingsManager(getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE))
        sm.load()
        val token = WsServer.newToken()
        val server = WsServer(this, token, sm.lanUser(), sm.lanPass()) { n ->
            clientCount = n
            refreshNotification()
        }
        val port = server.startListening(8080)
        val ip = NetworkUtils.getLanIp(this) ?: "0.0.0.0"
        wsServer = server
        currentToken = token
        boundPort = port
        sm.setLanPort(port)
        lanUrl = "http://$ip:$port"
        isRunning = true
        statusText = if (sm.lanUser().isEmpty()) "运行中 · 开放访问" else "运行中 · 需认证"
        refreshNotification()
    }

    private fun refreshNotification() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(null))
        }
    }

    private fun teardown() {
        var wasActive = false
        synchronized(lock) {
            wasActive = active
            active = false
        }
        try { wsServer?.stopListening() } catch (_: Exception) {}
        wsServer = null
        isRunning = false
        clientCount = 0
        // 关闭即重置为空：清账号密码与端口
        runCatching {
            val sm = SettingsManager(getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE))
            sm.clearLanAuth()
        }
        boundPort = 0
        lanUrl = ""
        currentToken = ""
        if (wasActive) statusText = "已停止"
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(overrideText: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 4, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 5, Intent(this, LanShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = overrideText
            ?: "$lanUrl · 已连接 $clientCount · ${if (currentToken.isEmpty()) "" else "点开 App 查看"}"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TermLou LAN 共享运行中")
            .setContentText(text.ifEmpty { statusText })
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pendingOpen)
            .addAction(Notification.Action.Builder(null, "停止共享", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LanShareService"
        private const val CHANNEL_ID = "term-lou-lan"
        private const val NOTIFICATION_ID = 4
        const val ACTION_START = "com.workspace.proot.LAN_START"
        const val ACTION_STOP = "com.workspace.proot.LAN_STOP"

        @Volatile var isRunning = false
            private set
        @Volatile var statusText = "未开启"
            private set
        @Volatile var lanUrl = ""
            private set
        @Volatile var boundPort = 0
            private set
        @Volatile var clientCount = 0
            private set
        @Volatile var currentToken = ""
            private set

        private var instance: LanShareService? = null

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LanShareService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LanShareService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
