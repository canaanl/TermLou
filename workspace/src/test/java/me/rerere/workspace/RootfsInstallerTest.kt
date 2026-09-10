package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    private lateinit var baseDir: File
    private lateinit var rootfsDir: File
    private lateinit var manager: WorkspaceManager
    private lateinit var installer: RootfsInstaller

    @Before
    fun setUp() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "rootfs_installer_test_${System.currentTimeMillis()}")
        baseDir.mkdirs()
        manager = WorkspaceManager(baseDir)
        installer = RootfsInstaller(manager)
        rootfsDir = manager.linuxDir("test")
    }

    private data class TarEnt(
        val name: String,
        val type: Char,
        val data: ByteArray = ByteArray(0),
        val linkName: String = "",
        val mode: Int = 0,
        val mtime: Long = 0,
    )

    private fun extract(vararg entries: TarEnt) {
        val archive = File(baseDir, "rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { it.write(tarBytes(*entries)) }
        installer.extractTar(archive, rootfsDir, RootfsInstaller.ArchiveFormat.TAR_GZ) {}
    }

    private val RW = 0b110_100_100

    @Test
    fun `absolute symlink inside rootfs should be relativized`() {
        try {
            val probe = File(baseDir, "probe")
            probe.deleteOnExit()
            Files.createSymbolicLink(probe.toPath(), baseDir.toPath().resolve("."))
            probe.delete()
        } catch (e: Exception) {
            assumeTrue("symlinks not supported on this host: ${e.message}", false)
        }
        extract(
            TarEnt("etc", '5'),
            TarEnt("usr", '5'),
            TarEnt("usr/bin", '5'),
            TarEnt("usr/bin/prog", '0', "#!/bin/sh".toByteArray()),
            TarEnt("s", '2', linkName = "/usr/bin/prog"),
        )

        val link = File(rootfsDir, "s")
        val target = Files.readSymbolicLink(link.toPath()).toString()
        assertFalse("link must be relative, got: $target", File(target).isAbsolute)
        assertEquals(
            File(rootfsDir, "usr/bin/prog").canonicalFile,
            link.canonicalFile,
        )
        assertEquals("#!/bin/sh", File(rootfsDir, "usr/bin/prog").readText())
    }

    @Test
    fun `hard link with missing source should fail install`() {
        try {
            extract(TarEnt("linux", '1', linkName = "no-such-file"))
            fail("expected IOException for missing hard link source")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("no-such-file"))
        }
    }

    @Test
    fun `hard link should copy bytes from existing source`() {
        extract(
            TarEnt("a.txt", '0', "hello".toByteArray(), mode = RW),
            TarEnt("b.txt", '1', linkName = "a.txt", mode = RW),
        )
        assertEquals("hello", File(rootfsDir, "b.txt").readText())
    }

    @Test
    fun `pax mtime override should apply to next entry`() {
        val paxMtime = 1700000000L
extract(
            TarEnt("pax", 'x', "29 mtime=1700000000.123456789\n".toByteArray()),
            TarEnt("pax.txt", '0', "test".toByteArray(), mode = RW),
        )
        assertEquals(paxMtime * 1000, File(rootfsDir, "pax.txt").lastModified())
    }

    @Test
    fun `commitStaging should swap rootfs atomically`() {
        val staging = File(baseDir, "staging")
        val stagingBin = File(staging, "bin")
        stagingBin.mkdirs()
        File(stagingBin, "sh").writeText("new")
        val linux = File(baseDir, "linux")
        linux.mkdirs()
        File(linux, "old.txt").writeText("old")
        val temp = File(baseDir, "temp")
        temp.mkdirs()

        installer.commitStaging(staging, linux, temp)

        assertTrue(File(linux, "bin/sh").readText() == "new")
        assertFalse(File(linux, "old.txt").exists())
        assertFalse(File(temp, "rootfs-backup").exists())
    }

    @Test
    fun `commitStaging should restore old rootfs on failed swap`() {
        val linux = File(baseDir, "linux")
        linux.mkdirs()
        File(linux, "old.txt").writeText("old")
        val temp = File(baseDir, "temp")
        temp.mkdirs()
        val missingStaging = File(baseDir, "missing-staging")

        try {
            installer.commitStaging(missingStaging, linux, temp)
            fail("expected IOException for failed swap")
        } catch (e: IOException) {
            // 回滚：旧 rootfs 应仍在原位
        }

        assertTrue(File(linux, "old.txt").readText() == "old")
        assertFalse(File(temp, "rootfs-backup").exists())
    }

    private fun tarBytes(vararg entries: TarEnt): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            out.write(tarHeader(e))
            if (e.data.isNotEmpty()) {
                out.write(e.data)
                val pad = (TAR_BLOCK - (e.data.size % TAR_BLOCK)) % TAR_BLOCK
                if (pad > 0) out.write(ByteArray(pad))
            }
        }
        out.write(ByteArray(TAR_BLOCK))
        out.write(ByteArray(TAR_BLOCK))
        return out.toByteArray()
    }

    private fun tarHeader(e: TarEnt): ByteArray {
        val b = ByteArray(TAR_BLOCK)
        fun ascii(off: Int, len: Int, text: String) {
            val raw = text.toByteArray(Charsets.US_ASCII)
            for (i in 0 until minOf(len, raw.size)) b[off + i] = raw[i]
        }
        fun octal(off: Int, len: Int, value: Long) {
            for (i in 0 until len) b[off + i] = ' '.code.toByte()
            b[off + len - 1] = '\u0000'.code.toByte()
            if (value == 0L) return
            val s = java.lang.Long.toOctalString(value)
            for (i in s.indices) b[off + len - 2 - i] = s[s.length - 1 - i].code.toByte()
        }
        ascii(0, 100, e.name)
        octal(100, 8, e.mode.toLong())
        octal(124, 12, e.data.size.toLong())
        octal(136, 12, e.mtime)
        b[156] = e.type.code.toByte()
        ascii(157, 100, e.linkName)
        ascii(257, 8, "ustar\u000000")
        for (i in 148 until 156) b[i] = ' '.code.toByte()
        var sum = 0
        for (x in b) sum += x.toInt() and 0xFF
        val cs = java.lang.Long.toOctalString(sum.toLong())
        val padded = "0".repeat(6 - cs.length) + cs
        ascii(148, 6, padded)
        b[154] = 0
        b[155] = ' '.code.toByte()
        return b
    }

    companion object {
        private const val TAR_BLOCK = 512
    }
}