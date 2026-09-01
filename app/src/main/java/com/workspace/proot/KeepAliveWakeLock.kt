package com.workspace.proot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

object KeepAliveWakeLock {
    private var pm: PowerManager? = null
    private var appPkg: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var active = false
    private var statusListener: ((String) -> Unit)? = null
    private val idleRunnable = Runnable { release() }
    private const val IDLE_TIMEOUT_MS = 90_000L

    fun init(context: Context) {
        if (pm == null) {
            val app = context.applicationContext
            pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            appPkg = app.packageName
        }
    }

    fun setStatusListener(listener: ((String) -> Unit)?) {
        statusListener = listener
    }

    fun status(): String {
        if (!active) return "保活：关"
        val whitelisted = try {
            appPkg?.let { pm?.isIgnoringBatteryOptimizations(it) } ?: false
        } catch (_: Exception) {
            false
        }
        val wlState = if (wl?.isHeld == true) "持有" else "待机"
        return "保活：开 · 豁免：${if (whitelisted) "是" else "否"} · 持锁：$wlState"
    }

    private fun notifyStatus() {
        statusListener?.invoke(status())
    }

    @Synchronized
    fun setActive(on: Boolean) {
        active = on
        if (!on) {
            handler.removeCallbacks(idleRunnable)
            release()
        } else {
            ensureHeld()
            handler.removeCallbacks(idleRunnable)
            handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
        }
        notifyStatus()
    }

    @Synchronized
    fun poke() {
        if (!active) return
        ensureHeld()
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private fun ensureHeld() {
        if (wl == null) {
            wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermLou::keepalive")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            notifyStatus()
        }
    }

    private fun release() {
        if (wl != null) {
            wl?.let { if (it.isHeld) it.release() }
            wl = null
            notifyStatus()
        }
    }
}
