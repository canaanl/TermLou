package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DnsEventsTest {

    @Before
    fun setup() {
        DnsEvents.clear()
    }

    @Test
    fun `record - appends newest first and counts`() {
        DnsEvents.record("Example.com")
        DnsEvents.record("WWW.EXAMPLE.ORG")
        assertEquals(2L, DnsEvents.count())
        val list = DnsEvents.list()
        assertEquals(2, list.size)
        assertEquals("www.example.org", list[0].second)
        assertEquals("example.com", list[1].second)
        assertTrue(list[0].first.isNotEmpty())
    }

    @Test
    fun `record - blank is ignored`() {
        DnsEvents.record("   ")
        assertEquals(0L, DnsEvents.count())
    }

    @Test
    fun `clear - resets events and count`() {
        DnsEvents.record("a.com")
        DnsEvents.clear()
        assertEquals(0L, DnsEvents.count())
        assertTrue(DnsEvents.list().isEmpty())
    }
}
