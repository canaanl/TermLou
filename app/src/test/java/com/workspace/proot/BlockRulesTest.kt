package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRulesTest {

    @Test
    fun `ip block - add remove`() {
        BlockRules.clear()
        BlockRules.addBlockIp("1.2.3.4")
        assertTrue(BlockRules.isBlockedIp("1.2.3.4"))
        assertTrue(BlockRules.isBlocked("1.2.3.4", null))
        assertTrue(BlockRules.isBlocked("1.2.3.4", "example.com"))
        assertFalse(BlockRules.isBlockedIp("5.6.7.8"))
        BlockRules.removeBlockIp("1.2.3.4")
        assertFalse(BlockRules.isBlockedIp("1.2.3.4"))
    }

    @Test
    fun `domain block - suffix matches subdomains`() {
        BlockRules.clear()
        BlockRules.addBlockDomain("example.com")
        assertTrue(BlockRules.isBlockedDomain("example.com"))
        assertTrue(BlockRules.isBlockedDomain("sub.example.com"))
        assertTrue(BlockRules.isBlockedDomain("a.b.example.com"))
        assertFalse(BlockRules.isBlockedDomain("example.org"))
        assertFalse(BlockRules.isBlockedDomain("notexample.com"))
        assertTrue(BlockRules.isBlocked("9.9.9.9", "sub.example.com"))
        assertFalse(BlockRules.isBlocked("9.9.9.9", "example.org"))
    }

    @Test
    fun `normalize - wildcard prefix and trailing dot`() {
        BlockRules.clear()
        BlockRules.addBlockDomain("*.Example.COM.")
        assertTrue(BlockRules.isBlockedDomain("example.com"))
        BlockRules.removeBlockDomain("example.com")
        assertFalse(BlockRules.isBlockedDomain("example.com"))
    }

    @Test
    fun `blockedDomainFor returns matched rule`() {
        BlockRules.clear()
        BlockRules.addBlockDomain("example.com")
        assertEquals("example.com", BlockRules.blockedDomainFor("sub.example.com"))
        assertNull(BlockRules.blockedDomainFor("other.org"))
    }

    @Test
    fun `blockedDomainForIp - via dns map`() {
        BlockRules.clear()
        DnsMap.clear()
        DnsMap.record("ads.example.com", "1.2.3.4")
        BlockRules.addBlockDomain("example.com")
        assertEquals("example.com", BlockRules.blockedDomainForIp("1.2.3.4"))
        BlockRules.removeBlockDomain("example.com")
        assertNull(BlockRules.blockedDomainForIp("1.2.3.4"))
    }

    @Test
    fun `clear empties everything`() {
        BlockRules.clear()
        BlockRules.addBlockIp("1.2.3.4")
        BlockRules.addBlockDomain("example.com")
        BlockRules.clear()
        assertFalse(BlockRules.isBlockedIp("1.2.3.4"))
        assertFalse(BlockRules.isBlockedDomain("example.com"))
        assertTrue(BlockRules.blockedIpsSnapshot().isEmpty())
        assertTrue(BlockRules.blockedDomainsSnapshot().isEmpty())
    }
}