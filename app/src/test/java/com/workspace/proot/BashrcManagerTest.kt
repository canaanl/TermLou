package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BashrcManagerTest {

    private val markerStart = BashrcManager.START_MARK
    private val markerEnd = BashrcManager.END_MARK

    @Test
    fun `managed block carries markers path hook and cmdLine`() {
        val block = BashrcManager.managedBlock("node")
        assertTrue(block.startsWith(markerStart + "\n"))
        assertTrue(block.contains(markerEnd))
        assertTrue(block.contains(".local/bin"))
        assertTrue(block.contains(".opencode/bin"))
        assertTrue(block.contains(BashrcManager.ENV_OVERLAY))
        assertTrue(block.contains("\nnode\n"))
    }

    @Test
    fun `managed block keeps opencode dir in path`() {
        val block = BashrcManager.managedBlock("")
        assertTrue(block.contains("export PATH=\"\$HOME/.local/bin:\$HOME/.opencode/bin:\$PATH\""))
    }

    @Test
    fun `blank input yields fresh block`() {
        assertEquals(BashrcManager.managedBlock(""), BashrcManager.merge("", ""))
    }

    @Test
    fun `replace managed block only and keep tail`() {
        val old = BashrcManager.managedBlock("old") + "\n# opencode\nexport PATH=\"\$HOME/.opencode/bin:\$PATH\"\n"
        val merged = BashrcManager.merge(old, "new")
        assertTrue(merged.contains("\nnew\n"))
        assertFalse(merged.contains("\nold\n"))
        assertTrue(merged.endsWith("# opencode\nexport PATH=\"\$HOME/.opencode/bin:\$PATH\"\n"))
    }

    @Test
    fun `changing shellCmd keeps user tail`() {
        val base = BashrcManager.managedBlock("a")
        val withTail = base + "alias ll='ls -l'\n"
        val merged = BashrcManager.merge(withTail, "b")
        assertTrue(merged.contains("\nb\n"))
        assertTrue(merged.endsWith("alias ll='ls -l'\n"))
    }

    @Test
    fun `user authored file without markers is appended not overwritten`() {
        val userOwned = "export FOO=bar\nalias myls='ls -la'\n"
        val merged = BashrcManager.merge(userOwned, "")
        assertTrue(merged.startsWith(userOwned))
        assertTrue(merged.contains(markerStart))
    }

    @Test
    fun `legacy v6 file keeps tail after cmdLine`() {
        val legacy = "# TERMLOU_V6\n" +
            "export PATH=\"\$HOME/.local/bin:\$PATH\"\n" +
            "alias id='id 2>/dev/null'\n" +
            "startup\n" +
            "\n# opencode\nexport PATH=\"\$HOME/.opencode/bin:\$PATH\"\n"
        val merged = BashrcManager.merge(legacy, "startup")
        assertTrue(merged.startsWith(markerStart + "\n"))
        assertTrue(merged.endsWith("# opencode\nexport PATH=\"\$HOME/.opencode/bin:\$PATH\"\n"))
        assertFalse(merged.contains("# TERMLOU_V6"))
    }

    @Test
    fun `legacy v6 file with blank cmd keeps tail after curl line`() {
        val legacy = "# TERMLOU_V6\n" +
            "export PATH=\"\$HOME/.local/bin:\$PATH\"\n" +
            "command -v curl >/dev/null 2>&1 && [ -f /etc/ssl/certs/ca-certificates.crt ] || (true)\n" +
            "\n# opencode\nexport PATH=\"\$HOME/.opencode/bin:\$PATH\"\n"
        val merged = BashrcManager.merge(legacy, "")
        assertTrue(merged.endsWith("# opencode\nexport PATH=\"\$HOME/.opencode/bin:\$PATH\"\n"))
        assertFalse(merged.contains("|| (true)"))
    }
}
