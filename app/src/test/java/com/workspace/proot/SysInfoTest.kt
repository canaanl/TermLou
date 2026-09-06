package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class SysInfoTest {

    private lateinit var testDir: File

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "sysinfo_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    private fun writeOsRelease(content: String): File {
        val etc = File(testDir, "etc").apply { mkdirs() }
        return File(etc, "os-release").apply { writeText(content) }
    }

    @Test
    fun `trixie os-release should parse pretty version and codename`() {
        writeOsRelease(
            "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\n" +
                "NAME=\"Debian GNU/Linux\"\n" +
                "VERSION_ID=\"13\"\n" +
                "VERSION=\"13 (trixie)\"\n" +
                "VERSION_CODENAME=trixie\n" +
                "ID=debian\n"
        )
        val info = resolveDistroInfo(testDir)!!
        assertEquals("Debian GNU/Linux 13 (trixie)", info.pretty)
        assertEquals("13", info.versionId)
        assertEquals("trixie", info.codename)
    }

    @Test
    fun `missing os-release should return null`() {
        assertNull(resolveDistroInfo(testDir))
    }

    @Test
    fun `empty os-release should return null`() {
        writeOsRelease("")
        assertNull(resolveDistroInfo(testDir))
    }

    @Test
    fun `quoted version id should be unquoted`() {
        writeOsRelease("PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\nVERSION_ID=\"12\"\nVERSION_CODENAME=bookworm\n")
        val info = resolveDistroInfo(testDir)!!
        assertEquals("12", info.versionId)
        assertEquals("bookworm", info.codename)
    }
}
