package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class RootfsPatcherTest {
    private lateinit var linuxDir: File
    private lateinit var etcDir: File
    private lateinit var patcher: RootfsPatcher

    @Before
    fun setUp() {
        linuxDir = File(System.getProperty("java.io.tmpdir"), "rootfs_test_${System.currentTimeMillis()}")
        etcDir = File(linuxDir, "etc")
        etcDir.mkdirs()
        patcher = RootfsPatcher()
    }

    @Test
    fun `patch should do nothing when linuxDir does not exist`() {
        val missingDir = File(System.getProperty("java.io.tmpdir"), "missing_${System.currentTimeMillis()}")
        patcher.patch(missingDir)
        assertFalse(missingDir.exists())
    }

    @Test
    fun `patch should create resolv conf with custom nameservers`() {
        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("8.8.8.8", "1.1.1.1")))

        val resolvConf = File(etcDir, "resolv.conf")
        assertTrue(resolvConf.exists())
        val content = resolvConf.readText()
        assertTrue(content.contains("nameserver 8.8.8.8"))
        assertTrue(content.contains("nameserver 1.1.1.1"))
        assertTrue(content.contains("options edns0 trust-ad"))
    }

    @Test
    fun `patch should use default DNS when no nameservers provided`() {
        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = emptyList()))

        val resolvConf = File(etcDir, "resolv.conf")
        assertTrue(resolvConf.exists())
        val content = resolvConf.readText()
        assertTrue(content.contains("nameserver 1.1.1.1"))
        assertTrue(content.contains("nameserver 8.8.8.8"))
        assertTrue(content.contains("nameserver 223.5.5.5"))
    }

    @Test
    fun `patch should filter blank nameservers`() {
        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("", "   ", "8.8.8.8")))

        val resolvConf = File(etcDir, "resolv.conf")
        val lines = resolvConf.readLines().filter { it.startsWith("nameserver") }
        assertEquals(1, lines.size)
    }

    @Test
    fun `patch should deduplicate nameservers`() {
        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("8.8.8.8", "8.8.8.8", "1.1.1.1")))

        val resolvConf = File(etcDir, "resolv.conf")
        val lines = resolvConf.readLines().filter { it.startsWith("nameserver") }
        assertEquals(2, lines.size)
    }

    @Test
    fun `patch should limit nameservers to 3`() {
        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "208.67.222.222")))

        val resolvConf = File(etcDir, "resolv.conf")
        val lines = resolvConf.readLines().filter { it.startsWith("nameserver") }
        assertTrue(lines.size <= 3)
    }

    @Test
    fun `patch should skip resolv conf when valid nameservers exist`() {
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\noptions edns0\n")
        val originalContent = File(etcDir, "resolv.conf").readText()

        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("1.1.1.1")))

        val content = File(etcDir, "resolv.conf").readText()
        assertEquals(originalContent, content)
    }

    @Test
    fun `patch should overwrite resolv conf with local resolver`() {
        File(etcDir, "resolv.conf").writeText("nameserver 127.0.0.1\n")

        patcher.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("8.8.8.8")))

        val content = File(etcDir, "resolv.conf").readText()
        assertTrue(content.contains("nameserver 8.8.8.8"))
    }

    @Test
    fun `patch should create hosts file with localhost entries`() {
        patcher.patch(linuxDir, RootfsPatchOptions(hostname = "termux"))

        val hosts = File(etcDir, "hosts")
        assertTrue(hosts.exists())
        val content = hosts.readText()
        assertTrue(content.contains("127.0.0.1 localhost"))
        assertTrue(content.contains("127.0.0.1 localhost termux"))
        assertTrue(content.contains("::1 localhost"))
    }

    @Test
    fun `patch should skip hosts when entries exist`() {
        File(etcDir, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        val originalContent = File(etcDir, "hosts").readText()

        patcher.patch(linuxDir, RootfsPatchOptions(hostname = "termux"))

        assertEquals(originalContent, File(etcDir, "hosts").readText())
    }

    @Test
    fun `patch should append ipv6 hosts if only ipv4 exists`() {
        File(etcDir, "hosts").writeText("127.0.0.1 localhost\n")

        patcher.patch(linuxDir, RootfsPatchOptions(hostname = "termux"))

        val content = File(etcDir, "hosts").readText()
        assertTrue(content.contains("::1 localhost"))
    }

    @Test
    fun `patch should create hostname file`() {
        patcher.patch(linuxDir, RootfsPatchOptions(hostname = "my-device"))

        val hostnameFile = File(etcDir, "hostname")
        assertTrue(hostnameFile.exists())
        assertEquals("my-device\n", hostnameFile.readText())
    }

    @Test
    fun `patch should use default hostname when blank`() {
        patcher.patch(linuxDir, RootfsPatchOptions(hostname = ""))

        val hostnameFile = File(etcDir, "hostname")
        assertEquals("localhost\n", hostnameFile.readText())
    }

    @Test
    fun `patch should skip hostname when already set`() {
        File(etcDir, "hostname").writeText("existing\n")

        patcher.patch(linuxDir, RootfsPatchOptions(hostname = "new-name"))

        assertEquals("existing\n", File(etcDir, "hostname").readText())
    }

    @Test
    fun `patch should create locale file with LANG`() {
        patcher.patch(linuxDir, RootfsPatchOptions(locale = "en_US.UTF-8"))

        val localeFile = File(etcDir, "default/locale")
        assertTrue(localeFile.exists())
        assertTrue(localeFile.readText().contains("LANG=en_US.UTF-8"))
    }

    @Test
    fun `patch should use default locale when not specified`() {
        patcher.patch(linuxDir)

        val localeFile = File(etcDir, "default/locale")
        assertTrue(localeFile.readText().contains("LANG=C.UTF-8"))
    }

    @Test
    fun `patch should skip locale when LANG already exists`() {
        File(etcDir, "default").mkdirs()
        File(etcDir, "default/locale").writeText("LANG=zh_CN.UTF-8\n")

        patcher.patch(linuxDir, RootfsPatchOptions(locale = "en_US.UTF-8"))

        assertTrue(File(etcDir, "default/locale").readText().contains("LANG=zh_CN.UTF-8"))
    }

    @Test
    fun `patch should create group file with root entry`() {
        patcher.patch(linuxDir, RootfsPatchOptions(groupIds = emptyList()))

        val groupFile = File(etcDir, "group")
        assertTrue(groupFile.exists())
        assertTrue(groupFile.readText().contains("root:x:0:"))
    }

    @Test
    fun `patch should add new group entries`() {
        patcher.patch(linuxDir, RootfsPatchOptions(groupIds = listOf(1001, 1002)))

        val groupFile = File(etcDir, "group")
        val content = groupFile.readText()
        assertTrue(content.contains("android_gid_1001:x:1001:"))
        assertTrue(content.contains("android_gid_1002:x:1002:"))
    }

    @Test
    fun `patch should skip existing group ids`() {
        File(etcDir, "group").writeText("root:x:0:\ncustom:x:1001:\n")

        patcher.patch(linuxDir, RootfsPatchOptions(groupIds = listOf(1001, 1002)))

        val content = File(etcDir, "group").readText()
        assertTrue(content.contains("custom:x:1001:"))
        assertTrue(content.contains("android_gid_1002:x:1002:"))
    }

    @Test
    fun `patch should filter out root group id`() {
        patcher.patch(linuxDir, RootfsPatchOptions(groupIds = listOf(0, 1001)))

        val content = File(etcDir, "group").readText()
        assertFalse(content.contains("gid_0:"))
        assertTrue(content.contains("android_gid_1001:x:1001:"))
    }

    @Test
    fun `patch should create temp directories`() {
        patcher.patch(linuxDir)

        assertTrue(File(linuxDir, "tmp").isDirectory)
        assertTrue(File(linuxDir, "var/tmp").isDirectory)
        assertTrue(File(linuxDir, "root").isDirectory)
    }

    @Test
    fun `patch should handle empty linuxDir`() {
        linuxDir.deleteRecursively()
        linuxDir.mkdirs()

        patcher.patch(linuxDir)

        assertFalse(File(etcDir, "resolv.conf").exists())
    }
}
