package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `termlou-ui service` 的参数序列化回归测试。
 *
 * 直接抽取 termlou_ui.sh 里的 `svc_json_str` 函数和 argv 拼装块，在真实 bash
 * 里跑一遍，确认空格、引号、反斜杠、换行、`$`、中文都不会在 shell → JSON
 * 这一层丢失。没有 bash 的环境跳过（例如纯 Windows JVM）。
 */
@Suppress("MagicNumber", "ReturnCount", "ThrowsCount")
class TermlouServiceShellTest {

    private fun script(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val f = File(dir, "app/src/main/res/raw/termlou_ui.sh")
            if (f.isFile) return f
            dir = dir?.parentFile
        }
        throw AssertionError("找不到 termlou_ui.sh")
    }

    private fun findBash(): String? {
        System.getenv("BASH")?.let { if (File(it).isFile) return it }
        val candidates = listOf(
            "/bin/bash",
            "/usr/bin/bash",
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
        )
        for (path in candidates) if (File(path).isFile) return path
        return runCatching {
            val p = ProcessBuilder("bash", "-c", "exit 0").start()
            p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false).let { if (it) "bash" else null }
    }

    @Test
    fun `service argv survives shell to json without loss`() {
        val bash = findBash()
        assumeTrue("bash not available; skipping shell round-trip", bash != null)

        val text = script().readText()
        val fn = Regex("""(?s)svc_json_str\(\) \{.*?\n\}""").find(text)
            ?: throw AssertionError("找不到 svc_json_str 函数")
        val block = Regex("""(?s)\n  argv_json=""\n  if \[ .*?\n  fi\n""").find(text)
            ?: throw AssertionError("找不到 argv_json 拼装块")

        val args = listOf(
            "magpie",
            "serve",
            "a b",
            "",
            "a\"b",
            "\$HOME",
            "single'quote",
            "line\nbreak",
            "tab\there",
            "semi;colon",
            "star*question?",
            "back\\slash",
            "九宫格",
            "--port",
            "39080",
        )

        val harness = File.createTempFile("termlou-service-args", ".sh").apply {
            deleteOnExit()
            writeText(
                buildString {
                    append("set -u\n")
                    append(fn.value).append('\n')
                    // 参数直接写进脚本，避免 Windows 下 Git Bash 对命令行参数做二次拆分；
                    // 这里测的是 shell → JSON 这一层的转义与数组保真。
                    append("argv=( ")
                    append(args.joinToString(" ") { arg -> "'" + arg.replace("'", "'\\''") + "'" })
                    append(" )\n")
                    append(block.value.trimStart('\n'))
                    append("printf '{\"argv\":[%s]}\\n' \"\$argv_json\"\n")
                }
            )
        }

        val process = ProcessBuilder(listOf(bash!!, harness.absolutePath))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assumeTrue("bash did not finish", finished)
        assertEquals("shell failed: $output", 0, process.exitValue())
        val parsed = MiniJson.parse(output).optArr("argv")
            ?: throw AssertionError("输出不是 JSON: $output")
        assertEquals(args.size, parsed.length())
        for (i in args.indices) {
            assertEquals("arg #$i lost or changed", args[i], parsed.getString(i))
        }
    }
}
