package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FlowLogTest {

    @Before
    fun setup() {
        FlowLog.clear()
        DnsEvents.clear()
    }

    private companion object {
        const val UP0 = 100L
        const val DOWN0 = 50L
        const val UP1 = 260L
        const val DOWN1 = 70L
    }

    @Test
    fun `add - seeds counters and active count`() {
        val id = FlowLog.add("TCP", "1.1.1.1", 0x1BB, UP0, DOWN0, "OPEN")
        val c = FlowLog.counters()
        assertEquals(UP0, c.totalUp)
        assertEquals(DOWN0, c.totalDown)
        assertEquals(1, c.active)
        assertEquals(0, c.blocked)
        assertEquals(id, FlowLog.list().first().id)
    }

    @Test
    fun `updateBytes - accumulates deltas and closes active`() {
        val id = FlowLog.add("TCP", "1.1.1.1", 0x1BB, UP0, DOWN0, "OPEN")
        val c1 = FlowLog.counters()
        assertEquals(UP0, c1.totalUp)
        FlowLog.updateBytes(id, UP1, DOWN1, "CLOSED")
        val c2 = FlowLog.counters()
        assertEquals(UP1, c2.totalUp)
        assertEquals(DOWN1, c2.totalDown)
        assertEquals(0, c2.active)
    }

    @Test
    fun `updateBytes - blocked transition counts`() {
        val id = FlowLog.add("TCP", "1.1.1.1", 0x50, 0, 0, "OPEN")
        FlowLog.updateBytes(id, 0, 0, "CLOSED")
        assertEquals(0, FlowLog.counters().blocked)
        FlowLog.add("TCP", "1.1.1.1", 0x50, 0, 0, "BLOCKED")
        assertEquals(1, FlowLog.counters().blocked)
    }

    @Test
    fun `patchMeta - attaches sni and http`() {
        val id = FlowLog.add("TCP", "1.1.1.1", 0x1BB, 0, 0, "OPEN")
        FlowLog.patchMeta(id, "sni.example.com", "GET /x → 200")
        val f = FlowLog.list().first()
        assertEquals("sni.example.com", f.sni)
        assertEquals("GET /x → 200", f.http)
    }

    @Test
    fun `clear - resets rows and counters`() {
        FlowLog.add("TCP", "1.1.1.1", 0x1BB, UP0, DOWN0, "OPEN")
        FlowLog.add("TCP", "2.2.2.2", 0x50, 0, 0, "BLOCKED")
        FlowLog.clear()
        assertEquals(0, FlowLog.size())
        assertEquals(0L, FlowLog.counters().totalUp)
        assertEquals(0L, FlowLog.counters().totalDown)
        assertEquals(0, FlowLog.counters().active)
        assertEquals(0, FlowLog.counters().blocked)
    }

    @Test
    fun `eviction - active count is corrected when active rows fall out of the 2000 window`() {
        FlowLog.clear()
        repeat(2100) { FlowLog.add("TCP", "1.1.1.1", 0x1BB, 0, 0, "OPEN") }
        val c = FlowLog.counters()
        assertEquals(2000, FlowLog.size())
        // 旧实现淘汰时不扣减，会虚高到 2100
        assertEquals(2000, c.active)
    }

    @Test
    fun `eviction - blocked count is corrected when blocked rows fall out of the window`() {
        FlowLog.clear()
        repeat(2100) { FlowLog.add("TCP", "1.1.1.1", 0x50, 0, 0, "BLOCKED") }
        val c = FlowLog.counters()
        assertEquals(2000, FlowLog.size())
        assertEquals(2000, c.blocked)
        // 累计总量不受淘汰影响
        assertEquals(0, c.active)
    }

    @Test
    fun `notifyOnce - change swallowed by the 300ms window is re-dispatched`() {
        FlowLog.clear()
        val stamps = java.util.Collections.synchronizedList(ArrayList<Long>())
        val listener: () -> Unit = { stamps.add(System.currentTimeMillis()); Unit }
        FlowLog.subscribe(listener)
        try {
            FlowLog.add("TCP", "1.1.1.1", 0x1BB, 1, 1, "OPEN")
            Thread.sleep(20)
            FlowLog.add("TCP", "2.2.2.2", 0x50, 1, 1, "OPEN")
            val afterLastAdd = System.currentTimeMillis()
            val deadline = afterLastAdd + 3000L
            while (System.currentTimeMillis() < deadline) {
                if (stamps.any { it >= afterLastAdd }) break
                Thread.sleep(20)
            }
            assertTrue(
                "最后一次变更没有补发通知，看板会停在旧状态",
                stamps.any { it >= afterLastAdd }
            )
        } finally {
            FlowLog.unsubscribe(listener)
        }
    }
}
