package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器解析与 JS 生成的锁定测试（5.9.0）。
 *
 * 这里拼出来的 JS 是直接塞进页面执行的：选择器或用户输入里带引号、反斜杠、换行，
 * 就得靠 [WebSelector.jsString] 转义挡住，否则轻则页面报错、重则注入出别的东西。
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

    // ---------- JS 字符串转义 ----------

    @Test
    fun `引号反斜杠换行都要转义`() {
        assertEquals("a\\\"b", WebSelector.jsString("a\"b"))
        assertEquals("a\\\\b", WebSelector.jsString("a\\b"))
        assertEquals("a\\nb", WebSelector.jsString("a\nb"))
        assertEquals("a\\tb", WebSelector.jsString("a\tb"))
        assertEquals("a\\'b", WebSelector.jsString("a'b"))
    }

    @Test
    fun `控制字符转成 unicode 序列`() {
        assertEquals("\\u0000", WebSelector.jsString("\u0000"))
        assertEquals("\\u001f", WebSelector.jsString("\u001f"))
    }

    @Test
    fun `中文与 emoji 原样保留`() {
        assertEquals("登录", WebSelector.jsString("登录"))
        assertEquals("a\uD83D\uDE00b", WebSelector.jsString("a\uD83D\uDE00b"))
    }

    @Test
    fun `带引号的选择器不会破坏生成的 JS`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("input[value=\"a'b\"]"))
        assertTrue(js.contains("\\\""))
        assertTrue(js.contains("\\'"))
        // 结构必须完整：括号配平、能看出是 querySelector 调用
        assertEquals(
            js.count { it == '(' },
            js.count { it == ')' }
        )
        assertTrue(js.contains("querySelector"))
    }

    @Test
    fun `text 选择器生成的 JS 含文字与候选元素`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = false))
        assertTrue(js.contains("登录"))
        assertTrue(js.contains("button"))
        assertTrue(js.contains("var exact=false;"))
    }

    @Test
    fun `严格相等模式下 JS 里是 true`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = true))
        assertTrue(js.contains("var exact=true;"))
    }

    @Test
    fun `生成的 JS 不含裸换行`() {
        // 换行会把 JS 字面量截断——所有字面量都必须走转义
        val js = WebSelector.pickJs(WebSelector.Kind.Text("第一行\n第二行", exact = false))
        assertFalse(js.contains("\n"))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun `CSS 选择器生成为 querySelector 且带异常保护`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("::::bad"))
        assertTrue(js.contains("try{"))
        assertTrue(js.contains("catch(e){return null}"))
    }
}
