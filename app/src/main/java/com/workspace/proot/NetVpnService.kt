package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * 网络抓包/代理 VPN：建立 TUN 接管所选应用流量，
 * 交给原生 tun2socks 转发到 SOCKS5 上游（默认进程内 MiniSocks5Server 直连，
 * 可选外部上游如 proot 代理端口），连接流由 MiniSocks5Server 侧记录进 FlowLog。
 */
class NetVpnService : VpnService() {

    private val lock = Any()
    private var active = false
    private var tunFd: ParcelFileDescriptor? = null
    private var miniSocks: MiniSocks5Server? = null
    private var tun2socksPid: Int? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW)
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
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            statusText = getString(R.string.vpn_fail_fg_fmt, e.message.toString())
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        doStart()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
        super.onRevoke()
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
                startVpn()
                synchronized(lock) { active = true }
            } catch (e: Exception) {
                Log.e(TAG, "start vpn failed", e)
                statusText = getString(R.string.vpn_fail_fmt, e.message.toString())
                isRunning = false
                teardown()
                stopSelf()
            }
        }.start()
    }

    private fun startVpn() {
        val sm = SettingsManager(getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE))
        sm.load()

        val builder = Builder().apply {
            setSession(getString(R.string.vpn_session))
            setMtu(1500)
            addAddress("10.7.0.1", 32)
            addRoute("0.0.0.0", 0)
            addDnsServer("8.8.8.8")
            addDnsServer("8.8.4.4")
        }
        val apps = sm.loadCaptureApps()
        if (apps.isEmpty()) {
            throw IllegalStateException(getString(R.string.vpn_no_apps))
        }
        val pm = packageManager
        for (pkg in apps) {
            try {
                pm.getPackageInfo(pkg, 0)
                builder.addAllowedApplication(pkg)
            } catch (_: Exception) {}
        }

        if (VpnService.prepare(this) != null) {
            throw IllegalStateException(getString(R.string.vpn_no_auth))
        }

        val tun = builder.establish() ?: throw IllegalStateException(getString(R.string.vpn_establish_fail))
        tunFd = tun

        val upstream = sm.netUpstream().trim()
        if (upstream.isEmpty()) {
            upstreamLabel = getString(R.string.vpn_upstream_direct)
            val ms = MiniSocks5Server("127.0.0.1", 0, { protect(it) }, { protect(it) }, File(filesDir, "net"))
            miniSocks = ms
            socksPort = ms.startListening()
        } else {
            upstreamLabel = upstream
            socksPort = 0
        }

        val bin = extractBinary()
        val cmd = mutableListOf("--device", "fd://3", "--mtu", "1500")
        if (socksPort > 0) {
            cmd += listOf("--proxy", "socks5://127.0.0.1:$socksPort")
        } else {
            cmd += listOf("--proxy", upstream)
        }
        cmd += listOf("--loglevel", "warning")
        if (socksPort == 0) {
            activeInterfaceName()?.let { cmd += listOf("--interface", it) }
        }

        val logFile = File(filesDir, "net/tun2socks.log")
        logFile.parentFile?.mkdirs()

        try {
            File(filesDir, "net/start.log").writeText(
                "cmd=${bin.absolutePath} ${cmd.joinToString(" ")}\ntunFd=${tun.fd}\n" +
                    "parentFds=${runCatching { File("/proc/self/fd").listFiles()?.size }.getOrDefault(-1)}\n" +
                    "sdk=${android.os.Build.VERSION.SDK_INT}\n"
            )
        } catch (_: Exception) {}

        val pid = TunSpawner.spawnTun2Socks(bin.absolutePath, cmd.toTypedArray(), tun.fd, logFile.absolutePath)
        if (pid < 0) throw IllegalStateException(getString(R.string.vpn_tun_fail))
        tun2socksPid = pid

        FlowLog.clear()
        VpnFlowExporter.start(this)
        isRunning = true
        statusText = if (socksPort > 0) getString(R.string.vpn_running_direct_fmt, socksPort) else getString(R.string.vpn_running_upstream_fmt, upstream)

        Thread {
            val code = TunSpawner.waitPid(pid)
            var doStop = false
            synchronized(lock) {
                if (active) {
                    active = false
                    doStop = true
                }
            }
            if (doStop) {
                Log.e(TAG, "tun2socks exited $code")
                val tail = readLogTail(this, 10)
                statusText = getString(R.string.vpn_dead_fmt, code, if (tail.isEmpty()) "" else "\n" + tail)
                isRunning = false
                stopSelf()
            }
        }.start()
    }

    private fun extractBinary(): File {
        return File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
    }

    private fun activeInterfaceName(): String? {
        return runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.interfaceName }
        }.getOrNull()
    }

    private fun teardown() {
        var wasActive = false
        synchronized(lock) {
            wasActive = active
            active = false
        }
        try { tun2socksPid?.let { runCatching { TunSpawner.killPid(it) } } } catch (_: Exception) {}
        tun2socksPid = null
        try { miniSocks?.stopListening() } catch (_: Exception) {}
        miniSocks = null
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        isRunning = false
        socksPort = 0
        upstreamLabel = getString(R.string.vpn_upstream_direct)
        BlockRules.clear()
        DnsMap.clear()
        VpnFlowExporter.stop()
        if (wasActive) statusText = getString(R.string.vpn_stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 3, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TermLou")
            .setContentText(getString(R.string.vpn_notif))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NetVpnService"
        private const val CHANNEL_ID = "term-lou-net"
        private const val NOTIFICATION_ID = 3
        const val ACTION_START = "com.workspace.proot.NET_START"
        const val ACTION_STOP = "com.workspace.proot.NET_STOP"

        @Volatile var isRunning = false
            private set
        @Volatile var statusText = ""
            private set
        @Volatile var socksPort = 0
            private set
        @Volatile var upstreamLabel = ""
            private set

        private var instance: NetVpnService? = null

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, NetVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NetVpnService::class.java).setAction(ACTION_STOP)
            )
        }

        fun readLogTail(context: Context, maxLines: Int = 40): String {
            val f = File(context.filesDir, "net/tun2socks.log")
            if (!f.exists()) return ""
            return runCatching {
                val lines = f.readLines()
                lines.takeLast(maxLines).joinToString("\n")
            }.getOrDefault("")
        }
    }
}