package com.workspace.proot

import java.util.concurrent.CopyOnWriteArrayList

/** 本次抓包会话内的 DNS 查询事件（UI 展示与计数用，不外发到导出文件）。 */
object DnsEvents {
    private const val MAX = 2000
    private const val NOTIFY_GAP_MS = 300L

    private val lock = Any()
    private val events = ArrayList<Pair<String, String>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var total = 0L
    @Volatile private var lastNotifyMs = 0L

    fun record(qname: String) {
        if (qname.isBlank()) return
        val e = formatTime() to qname.lowercase()
        synchronized(lock) {
            total++
            events.add(0, e)
            while (events.size > MAX) events.removeAt(events.size - 1)
        }
        notifyOnce()
    }

    fun list(): List<Pair<String, String>> = synchronized(lock) { ArrayList(events) }

    fun count(): Long = synchronized(lock) { total }

    fun clear() {
        synchronized(lock) {
            events.clear()
            total = 0
        }
        notifyOnce()
    }

    fun subscribe(l: () -> Unit) {
        listeners.add(l)
    }

    fun unsubscribe(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyOnce() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < NOTIFY_GAP_MS) return
        lastNotifyMs = now
        listeners.forEach { it.invoke() }
    }

    private fun formatTime(): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return f.format(java.util.Date())
    }
}
