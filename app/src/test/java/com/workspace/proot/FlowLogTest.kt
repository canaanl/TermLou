package com.workspace.proot

import org.junit.Assert.assertEquals
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
}
