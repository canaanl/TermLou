package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 抽干原语的回归护栏（5.8.6）。
 *
 * 背景：app 侧 `TerminalManager.runInProot` 曾经是"先 waitFor 再读 stdout"——子进程输出
 * 超过管道缓冲（约 64KB）就阻塞在写端永不退出，只能等超时被 destroyForcibly，然后只拿回
 * 残缺输出（`apt list` / 大日志表现为"卡住整个超时"）。根因是管道没人并发抽干。
 *
 * 这里用**真子进程**把不变量钉死：输出远超管道缓冲时，子进程仍须自然退出、退出码 0、
 * 且采集器能拿到有界内容。以后任何人把"抽干"改回"先等后读"，测试立刻红。
 */
class StreamCollectorDrainTest {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** 造一个"输出远超 64KB 管道缓冲后正常退出"的子进程。 */
    private fun chattyChild(): Process {
        val pb = if (isWindows) {
            // 4000 行 × 41 字节 ≈ 160KB
            ProcessBuilder(
                "cmd", "/c",
                "for /L %i in (1,1,4000) do @echo 0123456789012345678901234567890123456789"
            )
        } else {
            ProcessBuilder("sh", "-c", "head -c 300000 /dev/zero | tr '\\0' 'x'")
        }
        return pb.start()
    }

    @Test
    fun largeOutputDoesNotBlockChild() {
        val p = chattyChild()
        // 纯抽干丢弃（app 侧 runInProot 的用法）
        val drain = StreamCollector(p.inputStream, 0)
        val finished = p.waitFor(20, TimeUnit.SECONDS)
        drain.join(2_000)
        assertTrue("子进程应自然退出（未被管道写满卡死）", finished)
        assertEquals("退出码应为 0（不是被强杀）", 0, p.exitValue())
    }

    @Test
    fun drainKeepsBoundedContentAndMarksTruncated() {
        val p = chattyChild()
        val drain = StreamCollector(p.inputStream, 64)
        p.waitFor(20, TimeUnit.SECONDS)
        drain.join(2_000)
        assertEquals("只保留上限内的内容", 64, drain.text().length)
        assertTrue("超出上限必须标记截断", drain.truncated)
    }
}
