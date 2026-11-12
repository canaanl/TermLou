package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `js_file` 路径解析的回归（5.13.1）。
 *
 * 重点不是“能读文件”，而是**失败时说得清是哪条路径、为什么**：
 * 越权、不存在、不是文件、空路径必须给出不同的话。
 */
class WebJsFileTest {

    private lateinit var parent: File
    private lateinit var root: File

    @Before
    fun setUp() {
        parent = Files.createTempDirectory("termlou-jsfile").toFile()
        root = File(parent, "workspace")
        root.mkdirs()
        File(root, "ok.js").writeText("1+1")
        File(root, "sub").mkdirs()
        File(root, "sub/nested.js").writeText("2+2")
    }

    @After
    fun tearDown() {
        parent.deleteRecursively()
    }

    private fun ok(path: String): String {
        val result = WebJsFile.resolve(root, path)
        assertTrue("应当成功：$path -> $result", result is WebJsFile.Result.Ok)
        return (result as WebJsFile.Result.Ok).text
    }

    private fun failure(path: String): String {
        val result = WebJsFile.resolve(root, path)
        assertTrue("应当失败：$path -> $result", result is WebJsFile.Result.Failure)
        return (result as WebJsFile.Result.Failure).reason
    }

    @Test
    fun `absolute workspace path is readable`() {
        assertEquals("1+1", ok("/workspace/ok.js"))
    }

    @Test
    fun `relative path is relative to workspace`() {
        assertEquals("1+1", ok("ok.js"))
        assertEquals("2+2", ok("sub/nested.js"))
    }

    @Test
    fun `absolute path outside workspace is rejected with the path`() {
        val reason = failure("/tmp/t.js")
        assertTrue(reason, reason.contains("inside /workspace"))
        assertTrue(reason, reason.contains("/tmp/t.js"))
    }

    @Test
    fun `parent traversal is rejected even when the file exists`() {
        File(parent, "escape.js").writeText("secret")
        val reason = failure("../escape.js")
        assertTrue(reason, reason.contains("inside /workspace"))
    }

    @Test
    fun `missing file reports the resolved workspace path`() {
        val reason = failure("missing.js")
        assertTrue(reason, reason.contains("not found"))
        assertTrue(reason, reason.contains("/workspace/missing.js"))
    }

    @Test
    fun `directory is not a js file`() {
        val reason = failure("sub")
        assertTrue(reason, reason.contains("not a file"))
        assertTrue(reason, reason.contains("/workspace/sub"))
    }

    @Test
    fun `workspace root itself is not a js file`() {
        assertTrue(failure("/workspace").contains("not a file"))
    }

    @Test
    fun `blank request reports missing js_file`() {
        assertTrue(failure("   ").contains("missing js_file"))
    }
}
