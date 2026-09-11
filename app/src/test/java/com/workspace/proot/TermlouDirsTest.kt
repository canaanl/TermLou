package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TermlouDirsTest {

    @Test
    fun pendingIdParsesNewFormat() {
        assertEquals("a1b2c3d4", TermlouDirs.pendingId("tile_pending-1757328000000-a1b2c3d4.json"))
    }

    @Test
    fun pendingIdFallsBackForLegacyFormat() {
        assertEquals("legacy-1757328000000", TermlouDirs.pendingId("tile_pending-1757328000000.json"))
    }

    @Test
    fun pendingIdsAreDistinctPerTap() {
        val a = TermlouDirs.pendingId("tile_pending-1757328000000-a1b2c3d4.json")
        val b = TermlouDirs.pendingId("tile_pending-1757328000001-e5f6a7b8.json")
        assertNotEquals(a, b)
    }
}
