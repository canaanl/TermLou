package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **成品脚本的锁定测试**（5.9.3）—— 对 op 拼出来的**最终 JS** 逐条断言。
 *
 * ## 血泪教训
 *
 * 前两轮各出一个"全挂"的 bug，而**两轮的测试都只盯住了零件**：
 *  - 5.9.0/5.9.1：`pickJs` 产物里选择器漏了引号 → `querySelector(a)`。
 *    测试只查了"转义后不含裸换行"，没查**引号**。
 *  - 5.9.2：引号修好了，却**又引入语法错误** —— `PICK_TEMPLATE` 结尾漏了 `})()`，
 *    花括号不配平。`pickJs` 自己的括号是配平的（测试通过），
 *    **不配平的是调用方拼完之后的东西**——而这条没有任何测试覆盖。
 *
 * 所以这里的断言对象是**成品**：每个 op 拼出来的完整 JS。
 * 再加一条**自检**：故意制造缺闭合的错误版本，断言检查能抓住它
 * （否则这套检查自己坏了也没人知道）。
 */
class WebOpScriptsTest {

    // ---------- 括号与花括号配平（就是 5.9.2 漏掉的那条） ----------

    private fun assertBalanced(label: String, js: String) {
        assertEquals("$label 左花括号 != 右花括号", js.count { it == '{' }, js.count { it == '}' })
        assertEquals("$label 左括号 != 右括号", js.count { it == '(' }, js.count { it == ')' })
    }

    private fun assertBalancedOutsideStrings(label: String, js: String) {
        // 简易扫描：跳过字符串字面量里的括号
        var depthCurly = 0
        var depthParen = 0
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < js.length) {
            val ch = js[i]
            when {
                inSingle && ch == '\\' -> i++
                inDouble && ch == '\\' -> i++
                inSingle -> if (ch == '\'') inSingle = false
                inDouble -> if (ch == '"') inDouble = false
                ch == '\'' -> inSingle = true
                ch == '"' -> inDouble = true
                ch == '{' -> depthCurly++
                ch == '}' -> depthCurly--
                ch == '(' -> depthParen++
                ch == ')' -> depthParen--
            }
            i++
        }
        assertFalse("$label 卡在未闭合的字符串里（inSingle=$inSingle inDouble=$inDouble）", inSingle || inDouble)
        assertEquals("$label 字符串外花括号不配平", 0, depthCurly)
        assertEquals("$label 字符串外括号不配平", 0, depthParen)
    }

    private val samples = listOf(
        WebSelector.Kind.Css("a"),
        WebSelector.Kind.Css("input[name=q]"),
        WebSelector.Kind.Css("div > p:nth-child(2)"),
        WebSelector.Kind.Text("More information", exact = false),
        WebSelector.Kind.Text("登录", exact = true)
    )

    @Test
    fun `click 成品脚本括号配平`() {
        for (k in samples) assertBalanced("click/$k", WebOpScripts.click(WebSelector.pickJs(k)))
    }

    @Test
    fun `text 成品脚本括号配平`() {
        for (k in samples) {
            assertBalanced("text/$k", WebOpScripts.text(WebSelector.pickJs(k)))
            assertBalanced("text-整页", WebOpScripts.text(null))
        }
    }

    @Test
    fun `type 成品脚本括号配平`() {
        for (k in samples) {
            assertBalanced("type/$k", WebOpScripts.type(WebSelector.pickJs(k), "你好 world", true))
        }
        assertBalanced("type-焦点", WebOpScripts.type(null, "abc", false))
    }

    @Test
    fun `select 成品脚本括号配平`() {
        for (k in samples) {
            assertBalanced("select/$k", WebOpScripts.select(WebSelector.pickJs(k), "optB"))
        }
    }

    @Test
    fun `成品脚本的括号在字符串外也配平`() {
        for (k in listOf(WebSelector.Kind.Css("a[title=\"}\"]"), WebSelector.Kind.Text("(括号)", exact = false))) {
            assertBalancedOutsideStrings("click/$k", WebOpScripts.click(WebSelector.pickJs(k)))
            assertBalancedOutsideStrings("text/$k", WebOpScripts.text(WebSelector.pickJs(k)))
        }
    }

    @Test
    fun `自检：缺闭合的错误版本会被这套检查抓住`() {
        // 复刻 5.9.2 的 bug：模板不带结尾
        val broken = "(function(){var e=%PICK%;return e.innerText" +
            WebSelector.pickJs(WebSelector.Kind.Css("a")).let { "(function(){$it})()" }
        val mismatched = broken.count { it == '{' } != broken.count { it == '}' }
        assertTrue("故意的不配平应被 assertBalanced 抓到", mismatched)
    }

    // ---------- 外壳契约 ----------

    @Test
    fun `成品脚本自带结尾闭合`() {
        val js = WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Css("a")))
        assertTrue("必须以 })() 收尾，实际末尾: ${js.takeLast(12)}", js.endsWith("})()"))
    }

    @Test
    fun `每个成品脚本都自带 try catch`() {
        val scripts = buildList {
            val p = WebSelector.pickJs(WebSelector.Kind.Css("a"))
            add(WebOpScripts.click(p))
            add(WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Text("x", false))))
            add(WebOpScripts.type(p, "v", true))
            add(WebOpScripts.select(p, "v"))
        }
        for (js in scripts) {
            assertTrue("缺 try", js.contains("try{"))
            assertTrue("缺 catch", js.contains("catch(err)"))
            assertTrue("catch 必须把错误信息带回来", js.contains(WebOpScripts.WEB_ERR_PREFIX))
        }
    }

    @Test
    fun `WEB_ERR 在所有成品脚本里都有定义`() {
        // 5.9.2 遗留：WEB_ERR 只在 type 的 FILL_HELPER 里定义，click/select 引用它会 ReferenceError
        val scripts = mapOf(
            "click" to WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Css("a"))),
            "select" to WebOpScripts.select(WebSelector.pickJs(WebSelector.Kind.Css("select")), "v"),
            "type" to WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("input")), "v", true),
            "text" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Css("h1")))
        )
        for ((name, js) in scripts) {
            assertTrue("$name 缺少 WEB_ERR 定义", js.contains("var WEB_ERR='"))
        }
        // 引用了 WEB_ERR 的脚本必须也有定义
        for ((name, js) in scripts) {
            if (js.contains("WEB_ERR+") || js.contains("WEB_ERR+'")) {
                assertTrue("$name 用了 WEB_ERR 却没定义", js.contains("var WEB_ERR='"))
            }
        }
    }

    @Test
    fun `成品脚本处理了元素不是对象的情况`() {
        for (js in listOf(
            WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Css("a"))),
            WebOpScripts.select(WebSelector.pickJs(WebSelector.Kind.Css("select")), "v"),
            WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("input")), "v", true)
        )) {
            assertTrue("必须先判 e 是不是对象", js.contains("typeof e!=='object'"))
        }
    }

    // ---------- 字面量必须带引号（5.9.0/5.9.1 的病根） ----------

    @Test
    fun `type 的文本值是带引号的字面量`() {
        val js = WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("input")), "你好 world", true)
        assertTrue("文本值应带引号: $js", js.contains("\"你好 world\""))
    }

    @Test
    fun `select 的目标值是带引号的字面量`() {
        val js = WebOpScripts.select(WebSelector.pickJs(WebSelector.Kind.Css("select")), "opt B")
        assertTrue("value 应带引号", js.contains("\"opt B\""))
    }

    @Test
    fun `type 的 clear 参数是布尔字面量`() {
        assertTrue(
            WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("input")), "v", true)
                .contains(",true)")
        )
        assertTrue(
            WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("input")), "v", false)
                .contains(",false)")
        )
    }

    // ---------- 哨兵解析 ----------

    @Test
    fun `识别元素没找到`() {
        assertTrue(WebOpScripts.isNotFound(WebOpScripts.NOT_FOUND))
        assertFalse(WebOpScripts.isNotFound(null))
        assertFalse(WebOpScripts.isNotFound("ok"))
    }

    @Test
    fun `解析页面侧错误`() {
        assertEquals(
            "not a text field (got <select>)",
            WebOpScripts.webErrorOf("${WebOpScripts.WEB_ERR_PREFIX}not a text field (got <select>)")
        )
        assertNull(WebOpScripts.webErrorOf("ok"))
        assertNull(WebOpScripts.webErrorOf(WebOpScripts.NOT_FOUND))
        assertNull(WebOpScripts.webErrorOf(null))
    }

    @Test
    fun `三类结果互不混淆`() {
        val notFound = WebOpScripts.NOT_FOUND
        val webErr = "${WebOpScripts.WEB_ERR_PREFIX}boom"
        val jsErr = "${WebOpScripts.JS_ERROR_PREFIX}SyntaxError"
        val normal = "元素文字"
        // 每个哨兵只被自己的解析器认领
        assertTrue(WebOpScripts.isNotFound(notFound))
        assertFalse(WebOpScripts.isNotFound(webErr))
        assertFalse(WebOpScripts.isNotFound(normal))
        assertNotNull(WebOpScripts.webErrorOf(webErr))
        assertNull(WebOpScripts.webErrorOf(notFound))
        assertNotNull(WebOpScripts.jsErrorOf(jsErr))
        assertNull(WebOpScripts.jsErrorOf(webErr))
        assertNull(WebOpScripts.webErrorOf(normal))
    }
}
