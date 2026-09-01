package com.workspace.proot

import java.util.concurrent.CopyOnWriteArrayList

class FlowEntry(
    val id: Long,
    val time: String,
    val proto: String,
    val dstIp: String,
    val dstPort: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val state: String,
    val domain: String?
)

object FlowLog {
    private const val MAX = 2000
    private const val NOTIFY_GAP_MS = 300L

    private val lock = Any()
    private val flows = ArrayList<FlowEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var seq = 0L
    @Volatile private var lastNotifyMs = 0L

    fun clear() = synchronized(lock) { flows.clear() }

    fun subscribe(l: () -> Unit) {
        listeners.add(l)
    }

    fun unsubscribe(l: () -> Unit) {
        listeners.remove(l)
    }

    fun size(): Int = synchronized(lock) { flows.size }

    fun list(): List<FlowEntry> = synchronized(lock) { ArrayList(flows) }

    fun add(
        proto: String,
        dstIp: String,
        dstPort: Int,
        bytesUp: Long,
        bytesDown: Long,
        state: String,
        domain: String? = null
    ): Long {
        val e = FlowEntry(++seq, formatTime(), proto, dstIp, dstPort, bytesUp, bytesDown, state, domain)
        synchronized(lock) {
            flows.add(0, e)
            while (flows.size > MAX) flows.removeAt(flows.size - 1)
        }
        notifyOnce()
        return e.id
    }

    fun updateBytes(id: Long, bytesUp: Long, bytesDown: Long, state: String) {
        synchronized(lock) {
            val i = flows.indexOfFirst { it.id == id }
            if (i < 0) return
            val e = flows[i]
            flows[i] = FlowEntry(e.id, e.time, e.proto, e.dstIp, e.dstPort, bytesUp, bytesDown, state, e.domain)
        }
        notifyOnce()
    }

    /** DNS 映射新到达后，回填已有行中尚未关联域名的记录。 */
    fun patchDomain(ip: String, domain: String) {
        var changed = false
        synchronized(lock) {
            for (i in flows.indices) {
                val e = flows[i]
                if (e.dstIp == ip && e.domain == null) {
                    flows[i] = FlowEntry(e.id, e.time, e.proto, e.dstIp, e.dstPort, e.bytesUp, e.bytesDown, e.state, domain)
                    changed = true
                }
            }
        }
        if (changed) notifyOnce()
    }

    private fun notifyOnce() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < NOTIFY_GAP_MS) return
        lastNotifyMs = now
        listeners.forEach { it.invoke() }
    }

    private fun formatTime(): String {
        val c = java.util.Calendar.getInstance()
        val t = c.time
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return f.format(t)
    }
}