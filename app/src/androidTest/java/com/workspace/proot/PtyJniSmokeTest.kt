package com.workspace.proot

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PTY JNI 层真机冒烟：建会话 → 回显 → resize/SIGWINCH → 杀进程树 → 收割（无僵尸）。
 *
 * 覆盖 5.4.1 的 JNI 修复面：createSubprocess 9 参 / setPtyWindowSize 5 参签名对齐、
 * ws_xpixel/ypixel 写入、SessionTerminator.killTree + waitPid 收割。
 * 运行：gradlew :app:connectedDebugAndroidTest（需真机或模拟器）。
 */
@RunWith(AndroidJUnit4::class)
class PtyJniSmokeTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun ptyLifecycle() {
        val script = """
            trap 'echo GOTWINCH' WINCH
            echo SMOKE_OK
            while :; do
              if IFS= read -r line; then
                echo GOT:${'$'}line
              fi
            done
        """.trimIndent()
        val pidOut = IntArray(1)
        val fd = PtyJni.createSubprocess(
            "/system/bin/sh", "/",
            arrayOf("-c", script),
            arrayOf("PATH=/system/bin:/system/xbin", "TERM=xterm-256color"),
            pidOut, 24, 80
        )
        assertTrue("createSubprocess fd=$fd", fd >= 0)
        val pid = pidOut[0]
        assertTrue("pid=$pid", pid > 0)

        val pfd = ParcelFileDescriptor.adoptFd(fd)
        try {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val received = StringBuilder()

            // 1) 建会话 + 首包回显
            assertTrue(received.waitFor(input, "SMOKE_OK"))

            // 2) 交互输入回显
            output.write("ping\n".toByteArray())
            output.flush()
            assertTrue(received.waitFor(input, "GOT:ping"))

            // 3) resize：内核 winsize 生效（stty size 反映 30 100）
            PtyJni.setPtyWindowSize(fd, 30, 100, 8, 16)
            output.write("stty size\n".toByteArray())
            output.flush()
            assertTrue(received.waitFor(input, "30 100"))

            // 4) SIGWINCH 已投递给前台进程组（shell trap 回显）
            assertTrue(received.waitFor(input, "GOTWINCH"))

            // 5) 杀整棵树 + 同步收割：waitpid 返回 137 即无僵尸残留
            SessionTerminator.killTree(pid)
            val exit = TunSpawner.waitPid(pid)
            assertTrue("exit=$exit, expect 137 (SIGKILL)", exit == 137)
        } finally {
            runCatching { SessionTerminator.killTree(pid) }
            runCatching { TunSpawner.waitPid(pid) }
            runCatching { pfd.close() }
        }
    }

    /** 累积读取直到出现 needle；超时返回 false（附带内容由断言信息呈现）。 */
    private fun StringBuilder.waitFor(input: FileInputStream, needle: String): Boolean {
        val deadline = System.currentTimeMillis() + NEEDLE_TIMEOUT_MS
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            if (contains(needle)) return true
            val n = try {
                if (input.available() > 0) input.read(buf) else -2
            } catch (_: Exception) {
                -1
            }
            when {
                n > 0 -> append(String(buf, 0, n, Charsets.UTF_8))
                n == -1 -> return contains(needle)
                else -> Thread.sleep(READ_POLL_MS)
            }
        }
        return contains(needle)
    }

    companion object {
        private const val NEEDLE_TIMEOUT_MS = 10_000L
        private const val READ_POLL_MS = 20L
    }
}
