package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Service pid 解析回归（5.12.2）。
 *
 * Android 设备的 java.lang.Process 没有 runtime pid()，所以必须能从
 * /proc 直接子进程里按服务唯一临时目录找到 wrapper。这里用 fake /proc
 * 锁定 cmdline / environ 两条路径，不依赖真机。
 */
@Suppress("MagicNumber")
class ServiceProcessPidResolverTest {

    private lateinit var root: File
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("term-lou-proc").toFile()
        tempDir = File(root, "svc-temp").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun child(pid: Int, ppid: Int, cmdline: String? = null, environ: String? = null) {
        val dir = File(root, pid.toString()).apply { mkdirs() }
        File(dir, "stat").writeText("$pid (proot) S $ppid 1 1 0 -1 0 0 0 0 0 0 0 0 20 0 1 0 0\n")
        if (cmdline != null) File(dir, "cmdline").writeBytes(cmdline.toByteArray(Charsets.UTF_8))
        if (environ != null) File(dir, "environ").writeBytes(environ.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `finds direct child by cmdline temp dir`() {
        child(
            123,
            1000,
            cmdline = "libproot_exec.so\u0000-b\u0000${tempDir.absolutePath}:/tmp\u0000",
        )
        assertEquals(123, ServiceProcessPidResolver.findChildPid(1000, tempDir, root))
    }

    @Test
    fun `finds direct child by environ`() {
        child(
            124,
            1000,
            environ = "PATH=/usr/bin\u0000PROOT_TMP_DIR=${tempDir.absolutePath}\u0000",
        )
        assertEquals(124, ServiceProcessPidResolver.findChildPid(1000, tempDir, root))
    }

    @Test
    fun `ignores non child and other temp dirs`() {
        child(125, 999, cmdline = "${tempDir.absolutePath}:/tmp")
        child(126, 1000, cmdline = "/tmp/other-service:/tmp")
        assertNull(ServiceProcessPidResolver.findChildPid(1000, tempDir, root))
    }

    @Test
    fun `returns null when no child matches`() {
        assertNull(ServiceProcessPidResolver.findChildPid(1000, tempDir, root))
    }

    @Test
    fun `readPidFile accepts only positive pid`() {
        val pidFile = File(root, "host.pid")
        pidFile.writeText("4321\n")
        assertEquals(4321, ServiceProcessPidResolver.readPidFile(pidFile))

        pidFile.writeText("-1\n")
        assertNull(ServiceProcessPidResolver.readPidFile(pidFile))
        pidFile.writeText("0\n")
        assertNull(ServiceProcessPidResolver.readPidFile(pidFile))
        assertNull(ServiceProcessPidResolver.readPidFile(File(root, "missing.pid")))
        assertNull(ServiceProcessPidResolver.readPidFile(null))
    }

    @Test
    fun `positivePid rejects -1 and zero`() {
        assertNull(ServiceProcessPidResolver.positivePid(-1))
        assertNull(ServiceProcessPidResolver.positivePid(0))
        assertNull(ServiceProcessPidResolver.positivePid(null))
        assertEquals(4321, ServiceProcessPidResolver.positivePid(4321))
    }
}
