package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器与 JS 字面量的锁定测试（5.9.2 重点加固）。
 *
 * ## 为什么要专门加固
 *
 * 5.9.0 / 5.9.1 里 `jsString()` 只做转义、**不含两侧引号**，而四处调用点都把它当完整
 * 字面量拼进了 JS，生成的是 `document.querySelector(a)` 而不是 `querySelector("a")`：
 * `a` 是未定义变量 → JS 抛 `ReferenceError` → 被 `catch` 吞掉 → 恒定返回"not found"。
 * 真机表现是**所有走选择器的指令全挂**（`click`/`text`/`type`/`select`），而不走选择器的全活。
 *
 * 而且当时的测试只锁了"转义后不含裸换行"，**没锁"必须带引号"**，所以这个 bug
 * 从写测试那天起就一直能溜过去。这里把那条缺失的锁补上。
 */
class WebSelectorTest {

    // ---------- 解析 ----------

    @Test
    fun `普通写法当 CSS 选择器`() {
        val k = WebSelector.parse("input[name=q]") as WebSelector.Kind.Css
        assertEquals("input[name=q]", k.value)
    }

    @Test
    fun `text 开头按可见文字找`() {
        val k = WebSelector.parse("text=登录") as WebSelector.Kind.Text
        assertEquals("登录", k.value)
        assertFalse(k.exact)
    }

    @Test
    fun `双等号是严格相等`() {
        val k = WebSelector.parse("text==登录") as WebSelector.Kind.Text
        assertEquals("登录", k.value)
        assertTrue(k.exact)
    }

    @Test
    fun `两端空白会被去掉`() {
        assertEquals("登录", (WebSelector.parse("  text=登录  ") as WebSelector.Kind.Text).value)
        assertEquals("div", (WebSelector.parse(" div ") as WebSelector.Kind.Css).value)
    }

    @Test
    fun `空选择器解析失败`() {
        assertNull(WebSelector.parse(null))
        assertNull(WebSelector.parse(""))
        assertNull(WebSelector.parse("   "))
        assertNull(WebSelector.parse("text="))
        assertNull(WebSelector.parse("text=="))
    }

    // ---------- 字面量：必须带引号（5.9.2 的核心锁） ----------

    @Test
    fun `jsLiteral 带双引号`() {
        assertEquals("\"a\"", WebSelector.jsLiteral("a"))
        assertEquals("\"登录\"", WebSelector.jsLiteral("登录"))
        assertEquals("\"\"", WebSelector.jsLiteral(""))
    }

    @Test
    fun `jsLiteral 里的引号被转义`() {
        assertEquals("\"a\\\"b\"", WebSelector.jsLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", WebSelector.jsLiteral("a\\b"))
        assertEquals("\"a\\nb\"", WebSelector.jsLiteral("a\nb"))
    }

    @Test
    fun `escape 不加引号——它只是转义，别拿去直接拼 JS`() {
        assertEquals("a", WebSelector.escape("a"))
        assertEquals("a\\\"b", WebSelector.escape("a\"b"))
    }

    // ---------- pickJs 产出的 JS 必须是合法字面量 ----------

    @Test
    fun `CSS 分支产出带引号的 querySelector`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("a"))
        // 这条就是 5.9.0/5.9.1 漏掉的那条锁
        assertTrue("选择器必须带引号，实际: $js", js.contains("querySelector(\"a\")"))
        assertFalse("不能出现裸变量 querySelector(a)", js.contains("querySelector(a)"))
    }

    @Test
    fun `text 分支产出带引号的 want`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = false))
        assertTrue("want 必须带引号，实际: $js", js.contains("var want=\"登录\""))
    }

    @Test
    fun `带引号的选择器不会破坏生成的 JS`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("input[value=\"a'b\"]"))
        assertTrue(js.contains("\\\""))
        assertTrue(js.contains("\\'"))
        assertEquals(js.count { it == '(' }, js.count { it == ')' })
        assertTrue(js.contains("querySelector"))
    }

    @Test
    fun `text 选择器生成的 JS 含候选元素表`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = false))
        assertTrue(js.contains("登录"))
        assertTrue(js.contains("button"))
        assertTrue(js.contains("var exact=false;"))
    }

    @Test
    fun `严格相等模式下 JS 里是 true`() {
        assertTrue(WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = true)).contains("var exact=true;"))
    }

    @Test
    fun `生成的 JS 不含裸换行`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("第一行\n第二行", exact = false))
        assertFalse(js.contains("\n"))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun `CSS 分支带异常保护并把错误带回来`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("::::bad"))
        assertTrue("必须有 try", js.contains("try{"))
        assertTrue("错误要编码成哨兵带回来，不能只是 return null", js.contains(WebSelector.JS_ERROR_PREFIX))
    }

    @Test
    fun `text 分支找不到时返回哨兵而不是 null`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("x", exact = false))
        assertTrue(js.contains(WebSelector.NOT_FOUND))
    }

    // ---------- 哨兵解析 ----------

    @Test
    fun `识别没找到哨兵`() {
        assertTrue(WebSelector.isNotFound(WebSelector.NOT_FOUND))
        assertFalse(WebSelector.isNotFound(null))
        assertFalse(WebSelector.isNotFound("ok"))
        assertFalse(WebSelector.isNotFound(""))
    }

    @Test
    fun `解析 JS 侧错误信息`() {
        assertEquals("SyntaxError: bad", WebSelector.jsErrorOf("${WebSelector.JS_ERROR_PREFIX}SyntaxError: bad"))
        assertNull(WebSelector.jsErrorOf("ok"))
        assertNull(WebSelector.jsErrorOf(WebSelector.NOT_FOUND))
        assertNull(WebSelector.jsErrorOf(null))
    }

    @Test
    fun `错误信息为空时给个兜底说法`() {
        assertEquals("syntax error", WebSelector.jsErrorOf(WebSelector.JS_ERROR_PREFIX))
    }

    @Test
    fun `哨兵不会与正常值混淆`() {
        // 页面文字恰好等于哨兵串时的兜底：正常值一律按"找到"处理之外的路径走不太现实，
        // 但至少两个哨兵必须互不相同，解析才不会张冠李戴
        assertTrue(WebSelector.NOT_FOUND != WebSelector.JS_ERROR_PREFIX)
        assertFalse(WebSelector.isNotFound(WebSelector.jsErrorOf(WebSelector.NOT_FOUND)))
    }
}
