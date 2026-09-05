package com.workspace.proot

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 进程级崩溃自报：把未捕获异常完整（含全部 Caused by 链）写入 crash.log，
 * 并以浮窗 + 通知两条通道尽量把原因完整展示给用户，无 adb/无 root 也能反馈。
 */
class TermLouApp : Application() {

    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        appColdStartAt = SystemClock.elapsedRealtime()
        AppLang.apply(this)
        createCrashChannel()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildString {
                append(getString(R.string.crash_title) + "\n\n")
                append(formatThrowable(throwable))
            }
            runCatching { crashLog().writeText(report) }
            val shown = runCatching { showAndWaitCrashReport(report) }.getOrDefault(false)
            if (!shown) runCatching { notifyCrash(report) }
            if (shown) {
                Process.killProcess(Process.myPid())
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun crashLog(): File {
        return File(filesDir, "crash.log")
    }

    private fun formatThrowable(t: Throwable): String = buildString {
        append(t).append('\n')
        t.stackTrace.forEach { append("\tat ").append(it).append('\n') }
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 10) {
            append("Caused by: ").append(cause).append('\n')
            cause.stackTrace.forEach { append("\tat ").append(it).append('\n') }
            cause = cause.cause
            depth++
        }
    }

    fun rootCauseLine(report: String): String {
        val lines = report.lineSequence().toList()
        return lines.lastOrNull { it.startsWith("Caused by: ") }
            ?: lines.firstOrNull().orEmpty()
    }

    private fun createCrashChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CRASH_CHANNEL, getString(R.string.crash_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun notifyCrash(report: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openMain = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CRASH_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.crash_title))
            .setContentText(rootCauseLine(report))
            .setStyle(Notification.BigTextStyle().bigText(report))
            .setContentIntent(openMain)
            .setAutoCancel(true)
            .build()
        getSystemService(Context.NOTIFICATION_SERVICE)?.let {
            (it as NotificationManager).notify(CRASH_NOTIFICATION_ID, notification)
        }
    }

    private fun showAndWaitCrashReport(report: String): Boolean {
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canOverlay) return false
        val latch = CountDownLatch(1)
        val thread = HandlerThread("crash-report")
        thread.start()
        val handler = Handler(thread.looper)
        handler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val root = createOverlay(report) { latch.countDown() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching {
                wm.addView(root, params)
                latch.await()
                runCatching { wm.removeView(root) }
            }
        }
        thread.quitSafely()
        return true
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun createOverlay(report: String, onClose: () -> Unit): View {
        val px = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xF21E1E1E.toInt())
            setPadding(px(24), px(48), px(24), px(24))
        }
        val title = TextView(this).apply {
            text = getString(R.string.crash_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, px(12))
        }
        val scroll = ScrollView(this)
        val body = TextView(this).apply {
            text = report
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        scroll.addView(body)
        val close = Button(this).apply {
            text = getString(R.string.crash_copy_exit)
            setOnClickListener {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                }
                onClose()
            }
        }
        root.addView(title)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(close)
        return root
    }

    companion object {
        private const val CRASH_CHANNEL = "term-lou-crash"
        private const val CRASH_NOTIFICATION_ID = 3

        /** 进程启动时刻（elapsedRealtime），用于判断磁贴点击是否处于冷启动窗口。 */
        @JvmStatic
        var appColdStartAt: Long = 0L
    }
}
