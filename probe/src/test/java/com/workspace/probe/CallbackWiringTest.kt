package com.workspace.probe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接线层的防复发检查。
 *
 * ## 为什么要有这条
 *
 * v8 头一次卡死，`runScrollVariant` 里写了 `{ done }` —— 那是**函数引用**不是调用。
 * Kotlin 把 lambda 最后一个表达式强制转成 `Unit`，**编译器一声不吭地收下了**。
 * 每一步都跑完了、PNG 也存了，队列永远不往下走。
 *
 * 这类错误**任何单测都测不出来**：函数签名、返回值、类型全对，
 * 只有"跑一遍真机"才看得见。而那条路太贵 —— 用户已经为它付了一次。
 *
 * 所以直接扫源码。`{ done }`、`{ emit }`、`{ ok }` 这种形状一旦再出现，
 * 编译期不会有任何反应，但这里会红。
 *
 * 注释里提到它的地方不算（那三处正是在讲这个坑）。
 */
class CallbackWiringTest {

    /** `{\s*名字\s*}` —— lambda 体只有一个裸引用，没有任何调用。 */
    private val bareRef = Regex("""\{\s*(done|emit|ok|give|onDone|onStall|passed|f|cb|next)\s*\}""")

    /**
     * 去掉注释，但**只去注释**。
     *
     * 早先用 `substringBefore("*")` 一刀切，那会把 `w * 2` 这种真代码截掉 ——
     * 截掉的行正好可能藏着违规写法，检查就成了空跑。宁可漏掉注释里的
     * 误报，也不要漏掉真代码里的正例。
     */
    private fun code(line: String): String {
        val t = line.trimStart()
        // 整行是块注释的续行（以 * 开头）
        if (t.startsWith("*") || t.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    private fun sourceDir(): File {
        var d: File? = File("").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val p = File(d, "probe/src/main/java")
            if (p.isDirectory) return p
            d = d.parentFile
            hops++
        }
        // 找不到就红。这条检查在找不到源码时"假装通过"等于没有检查
        throw AssertionError("找不到 probe/src/main/java，user.dir=${File("").absolutePath}")
    }

    @Test
    fun `主源码里没有引用而不调用的回调`() {
        val root = sourceDir()
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("在 $root 下一个 .kt 都没找到，这条检查等于没跑", files.isNotEmpty())

        val hits = files.flatMap { f ->
            f.readLines().withIndex()
                .map { (n, line) -> n + 1 to code(line) }
                .filter { (_, line) -> bareRef.containsMatchIn(line) }
                .map { (n, line) -> "${f.name}:$n  $line" }
        }
        assertTrue(
            "这几行是把回调引用了却没调用 —— 队列会静默停住（v8 头一次就是这么死的）：\n" +
                hits.joinToString("\n"),
            hits.isEmpty()
        )
    }

    @Test
    fun `结果只能从一处交出去`() {
        val f = File(sourceDir(), "com/workspace/probe/ProbeActivity.kt")
        assertTrue("找不到 ProbeActivity.kt", f.isFile)
        val runScrollVariant = f.readText()
            .substringAfter("private fun runScrollVariant(")
            .substringBefore("private fun doScrollStep(")
        // 一处。多一处就多一个能"引用而不调用"的地方
        val emits = Regex("""\bemit\s*\(""").findAll(runScrollVariant).count()
        assertEqualsWithMsg(
            "runScrollVariant 里 emit 应当只出现 1 次，实际 $emits 次",
            1, emits
        )
    }

    private fun assertEqualsWithMsg(msg: String, want: Int, got: Int) {
        if (want != got) throw AssertionError(msg)
    }
}
