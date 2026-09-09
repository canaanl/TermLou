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
    val domain: String?,
    val sni: String? = null,
    val http: String? = null,
    val startMs: Long = System.currentTimeMillis(),
    val durMs: Long? = null
)

object FlowLog {
    private const val MAX = 2000
    private const val NOTIFY_GAP_MS = 300L

    private val lock = Any()
    private val flows = ArrayList<FlowEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var seq = 0L
    @Volatile private var lastNotifyMs = 0L
    private var totalUp = 0L
    private var totalDown = 0L
    private var activeCount = 0
    private var blockedCount = 0

    /** 会话累计指标（跨 2000 淘汰仍累计），供看板 O(1) 读取。 */
    class FlowCounters(
        val totalUp: Long,
        val totalDown: Long,
        val active: Int,
        val blocked: Int
    )

    fun counters(): FlowCounters = synchronized(lock) {
        FlowCounters(totalUp, totalDown, activeCount, blockedCount)
    }

    fun clear() {
        synchronized(lock) {
            flows.clear()
            totalUp = 0
            totalDown = 0
            activeCount = 0
            blockedCount = 0
        }
        notifyOnce()
    }

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
        domain: String? = null,
        sni: String? = null
    ): Long {
        val e = FlowEntry(++seq, formatTime(), proto, dstIp, dstPort, bytesUp, bytesDown, state, domain, sni)
        synchronized(lock) {
            flows.add(0, e)
            while (flows.size > MAX) flows.removeAt(flows.size - 1)
            if (bytesUp > 0) totalUp += bytesUp
            if (bytesDown > 0) totalDown += bytesDown
            if (state == "OPEN" || state == "UDP") activeCount++
            if (state == "BLOCKED") blockedCount++
        }
        notifyOnce()
        return e.id
    }

    fun updateBytes(id: Long, bytesUp: Long, bytesDown: Long, state: String) {
        synchronized(lock) {
            val i = flows.indexOfFirst { it.id == id }
            if (i < 0) return
            val e = flows[i]
            if (bytesUp > e.bytesUp) totalUp += bytesUp - e.bytesUp
            if (bytesDown > e.bytesDown) totalDown += bytesDown - e.bytesDown
            val wasActive = e.state == "OPEN" || e.state == "UDP"
            val isActive = state == "OPEN" || state == "UDP"
            if (wasActive && !isActive) activeCount--
            if (!wasActive && isActive) activeCount++
            val wasBlocked = e.state == "BLOCKED"
            if (wasBlocked && state != "BLOCKED") blockedCount--
            if (!wasBlocked && state == "BLOCKED") blockedCount++
            val dur = if (state == "CLOSED") System.currentTimeMillis() - e.startMs else e.durMs
            flows[i] = FlowEntry(e.id, e.time, e.proto, e.dstIp, e.dstPort, bytesUp, bytesDown, state, e.domain, e.sni, e.http, e.startMs, dur)
        }
        notifyOnce()
    }

    /** 捕获增强结果回填（SNI / 明文 HTTP 摘要）。 */
    fun patchMeta(id: Long, sni: String?, http: String?) {
        if (sni == null && http == null) return
        var changed = false
        synchronized(lock) {
            val i = flows.indexOfFirst { it.id == id }
            if (i < 0) return
            val e = flows[i]
            flows[i] = FlowEntry(
                e.id, e.time, e.proto, e.dstIp, e.dstPort, e.bytesUp, e.bytesDown, e.state,
                e.domain, sni ?: e.sni, http ?: e.http, e.startMs, e.durMs
            )
            changed = true
        }
        if (changed) notifyOnce()
    }

    /** DNS 映射新到达后，回填已有行中尚未关联域名的记录。 */
    fun patchDomain(ip: String, domain: String) {
        var changed = false
        synchronized(lock) {
            for (i in flows.indices) {
                val e = flows[i]
                if (e.dstIp == ip && e.domain == null) {
                    flows[i] = FlowEntry(e.id, e.time, e.proto, e.dstIp, e.dstPort, e.bytesUp, e.bytesDown, e.state, domain, e.sni, e.http, e.startMs, e.durMs)
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
