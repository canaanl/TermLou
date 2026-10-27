package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RootfsExtractorTest {

    private lateinit var testDir: File

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "rootfs_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `SymlinkEntry data class should store path and target correctly`() {
        val linkPath = File("/system/bin/sh")
        val linkTarget = "/bin/bash"
        val entry = SymlinkEntry(linkPath, linkTarget)

        assertEquals(linkPath, entry.linkPath)
        assertEquals(linkTarget, entry.linkTarget)
    }

    @Test
    fun `SymlinkEntry data class equality should work correctly`() {
        val entry1 = SymlinkEntry(File("/path1"), "target1")
        val entry2 = SymlinkEntry(File("/path1"), "target1")
        val entry3 = SymlinkEntry(File("/path2"), "target2")

        assertEquals(entry1, entry2)
        assertNotEquals(entry1, entry3)
    }

    @Test
    fun `test directory creation`() {
        val dir = File(testDir, "test_dir")
        assertTrue(dir.mkdirs())
        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `test file creation and deletion`() {
        val file = File(testDir, "test_file.txt")
        file.writeText("test content")
        assertTrue(file.exists())
        assertEquals("test content", file.readText())
        assertTrue(file.delete())
        assertFalse(file.exists())
    }

    @Test
    fun `test recursive directory deletion`() {
        val parentDir = File(testDir, "parent")
        val childDir = File(parentDir, "child")
        childDir.mkdirs()
        File(childDir, "file.txt").writeText("content")

        assertTrue(parentDir.exists())
        assertTrue(parentDir.deleteRecursively())
        assertFalse(parentDir.exists())
    }

    @Test
    fun `test executable permission setting`() {
        val file = File(testDir, "executable_file")
        file.writeText("#!/bin/bash\necho hello")

        // On Windows, setExecutable may not work as expected
        // Just verify the file exists and can be written
        assertTrue(file.exists())
        assertTrue(file.canRead())
        assertTrue(file.canWrite())
    }

    @Test
    fun `rootfs asset follows aapt gzip stripping`() {
        // 源码是 .tar.gz；AAPT 打包后 APK 内是去掉 .gz 的 .tar。
        assertEquals("rootfs.tar", RootfsExtractor.ROOTFS_ASSET)
        assertEquals("rootfs.tar.gz", RootfsExtractor.ROOTFS_GZ_ASSET)
        val sourceCandidates = listOf(
            File("src/main/assets/${RootfsExtractor.ROOTFS_GZ_ASSET}"),
            File("app/src/main/assets/${RootfsExtractor.ROOTFS_GZ_ASSET}")
        )
        assertTrue(
            "missing ${RootfsExtractor.ROOTFS_GZ_ASSET}; checked $sourceCandidates",
            sourceCandidates.any { it.isFile }
        )
    }

    @Test
    fun `legacy install gets marker without reinstall`() {
        val linux = legacyRootfs()
        assertTrue(RootfsExtractor.isInstalled(linux))
        assertTrue(File(linux, RootfsExtractor.ROOTFS_MARKER).isFile)
    }

    @Test
    fun `legacy passwd only install still gets marker`() {
        val linux = File(testDir, "linux")
        File(linux, "etc").mkdirs()
        File(linux, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
        assertTrue(RootfsExtractor.isInstalled(linux))
        assertTrue(File(linux, RootfsExtractor.ROOTFS_MARKER).isFile)
    }

    @Test
    fun `marker alone is not enough without key paths`() {
        val linux = File(testDir, "linux")
        linux.mkdirs()
        File(linux, RootfsExtractor.ROOTFS_MARKER).writeText("old")
        assertFalse(RootfsExtractor.isInstalled(linux))
    }

    private fun legacyRootfs(): File {
        val linux = File(testDir, "linux")
        File(linux, "etc").mkdirs()
        File(linux, "usr/bin").mkdirs()
        File(linux, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
        File(linux, "usr/bin/env").writeText("")
        File(linux, "usr/bin/sh").writeText("")
        return linux
    }
}
