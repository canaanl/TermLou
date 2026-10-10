package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **生成 JS 的语义锁**（5.9.2）—— 纯字符串层面，把那条致命 bug 钉死。
 *
 * 5.9.0/5.9.1 的问题：`jsString()` 只转义、**不含两侧引号**，而四处调用点都把它当完整
 * 字面量拼进 JS，生成的是 `document.querySelector(a)` 而不是 `querySelector("a")`：
 * `a` 是未定义变量 → JS 抛 `ReferenceError` → 被 `catch` 吞掉 → **所有走选择器的指令恒定
 * not found**。而不走选择器的指令（`html` / `text` 不带 sel / `eval`）完全不受影响，
 * 真机表现极具迷惑性（"eval 里能找到元素，但 click 说找不到"）。
 *
 * 关键教训：上一版测试只断言"转义后不含裸换行"，**没有断言字面量必须带引号**，
 * 所以这个 bug 从写测试那天起就能溜过去。这里补的锁直接检查引号。
 *
 * （曾想用 Nashorn 真跑一遍语法，但构建用 JDK 17、已无内置引擎；改为检查
 *  "实参形状"——那才是真正区分对错的那条。）
 *
 * ⚠ 5.9.38：这里**只**查"实参形状"这一类；**整段脚本的结构**与**真引擎解析**
 * 另外有两条锁，别把这一份当成全部：
 *  - [WebJsStructureTest]：括号配平 / `try` 配对 / 裸换行 / `WEB_ERR` 作用域
 *    （5.9.37 那两处真机 bug 就是它这一类漏掉的 —— 当时的锁只验了字样）
 *  - [WebJsRealEngineTest]：交给真 JS 引擎编译（机器上有 node 就跑）
 */
class WebJsSyntaxTest {

    /** 从 pickJs 的产物里取出 querySelector 的实参文本（到配对的右括号为止）。 */
    private fun querySelectorArg(js: String): String {
        val marker = "querySelector("
        val start = js.indexOf(marker) + marker.length
        var depth = 1
        var i = start
        while (i < js.length && depth > 0) {
            when (js[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) i++
        }
        return js.substring(start, i)
    }

    @Test
    fun `querySelector 的实参必须是字符串字面量`() {
        for (sel in listOf("a", "input[name=s]", ".Sinput", "select", "div > p")) {
            val js = WebSelector.pickJs(WebSelector.Kind.Css(sel))
            val arg = querySelectorArg(js)
            assertEquals("实参必须是 \"$sel\"（带引号），实际: $arg", "\"$sel\"", arg)
        }
    }

    @Test
    fun `实参不能是裸标识符`() {
        // 裸标识符是 5.9.0/5.9.1 的病根：它语法合法、运行时才炸，所以纯"能不能 parse"抓不到
        val arg = querySelectorArg(WebSelector.pickJs(WebSelector.Kind.Css("a")))
        assertTrue("实参必须以引号开头，实际: $arg", arg.startsWith("\""))
    }

    @Test
    fun `引号被转义时实参仍然自洽`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("input[value=\"a'b\"]"))
        val arg = querySelectorArg(js)
        assertTrue("外层引号应保留", arg.startsWith("\"") && arg.endsWith("\""))
        assertTrue("内层双引号应被转义", arg.contains("\\\""))
    }

    @Test
    fun `text 分支的 want 也是字面量`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = false))
        assertTrue("want 应带引号: $js", js.contains("var want=\"登录\""))
        assertTrue("exact 标志应显式为布尔", js.contains("var exact=false;"))
    }

    @Test
    fun `好写法与坏写法可被这条检查区分`() {
        val good = querySelectorArg(WebSelector.pickJs(WebSelector.Kind.Css("a")))
        val bad = "a"                                   // 5.9.0/5.9.1 的实参
        assertEquals("\"a\"", good)
        assertTrue("坏写法不以引号开头", !bad.startsWith("\""))
        assertTrue("检查能区分二者", good != bad)
    }

    @Test
    fun `括号与引号配平`() {
        for (kind in listOf(
            WebSelector.Kind.Css("input[value=\"a'b\"]"),
            WebSelector.Kind.Text("第一行\n第二行", exact = false)
        )) {
            val js = WebSelector.pickJs(kind)
            assertEquals("括号配平", js.count { it == '(' }, js.count { it == ')' })
            assertTrue("不含裸换行", !js.contains("\n"))
        }
    }

    @Test
    fun `括号配平检查本身可用（自检）`() {
        val broken = "(function(){var e=1;"
        assertTrue(
            "故意的不配平应被这套检查抓到",
            broken.count { it == '(' } != broken.count { it == ')' }
        )
    }
}
