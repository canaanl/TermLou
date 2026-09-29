package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **假成功的锁**（5.9.4）。
 *
 * 5.9.4 真机复查抓到：对**不存在的元素**，`text`/`click`/`type`/`select` 全部返回
 * `ok:true`，值是字符串 `"null"` —— 因为页面侧一句 `String(null)` 把"没找到"
 * 变成了一个"看起来有内容"的值。
 *
 * 这里刻意**不用真实 JS 引擎**跑（那要新增依赖）。改用的办法是：
 * 把模板里决定语义的**那几行**抽出来断言，并且每条断言都配一条**自检**——
 * 故意写回 5.9.4 的错误写法，断言检查能抓住它。否则这套检查自己坏了也没人知道
 * （5.9.0–5.9.4 连续四版"测试全绿、真机全挂"就是这么来的）。
 */
class WebNotFoundTest {

    /** 所有走选择器的成品脚本。 */
    private fun wrappedScripts(): Map<String, String> = mapOf(
        "click/css" to WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Css("a"))),
        "click/text" to WebOpScripts.click(WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = false))),
        "text/css" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Css("h1"))),
        "text/text" to WebOpScripts.text(WebSelector.pickJs(WebSelector.Kind.Text("登录", exact = true))),
        "type/css" to WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Css("input")), "v", true),
        "type/text" to WebOpScripts.type(WebSelector.pickJs(WebSelector.Kind.Text("登录", false)), "v", true),
        "select/css" to WebOpScripts.select(WebSelector.pickJs(WebSelector.Kind.Css("select")), "v"),
        "select/text" to WebOpScripts.select(WebSelector.pickJs(WebSelector.Kind.Text("登录", false)), "v")
    )

    // ---------- 模板层 ----------

    @Test
    fun `null 必须先于 typeof 被拦下并回哨兵`() {
        for ((name, js) in wrappedScripts()) {
            assertTrue(
                "$name 缺 null 守卫，实际脚本：$js",
                js.contains("if(e===null)return")
            )
            assertTrue(
                "$name 的 null 守卫必须回 NOT_FOUND 哨兵而不是别的东西",
                js.contains(WebSelector.NOT_FOUND)
            )
        }
    }

    @Test
    fun `模板里不能再出现把 null 交给 String 的写法`() {
        // 5.9.4 的病根：`typeof e!=='object'||e===null` 里 e===null 那一支走的是 String(e)
        val forbidden = listOf(
            "typeof e!=='object'||e===null",
            "e===null)return String(e)",
            "||e===null)return String("
        )
        for ((name, js) in wrappedScripts()) {
            for (bad in forbidden) {
                assertFalse("$name 仍含 5.9.4 的错误写法 `$bad`：$js", js.contains(bad))
            }
        }
    }

    @Test
    fun `自检：故意写回旧模板会被上面两条抓住`() {
        val broken = "(function(){var e=1;if(typeof e!=='object'||e===null)return String(e);" +
            "return e.innerText;})()"
        assertTrue(
            "自检失败：检查没认出 5.9.4 的错误模板",
            broken.contains("typeof e!=='object'||e===null")
        )
        assertFalse("自检失败：错误模板居然通过了 null 守卫", broken.contains("if(e===null)return"))
    }

    // ---------- pickJs 层 ----------

    @Test
    fun `CSS 选择器查不到时回哨兵而不是 JS 的 null`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("#ghost"))
        assertTrue("CSS 分支必须回 NOT_FOUND，实际：$js", js.contains(WebSelector.NOT_FOUND))
        assertTrue("必须用三元回哨兵，实际：$js", js.contains("return e?e:"))
        // 这条是契约：pickJs 的文档写着"找不到时是 NOT_FOUND"
        assertFalse(
            "不能把 querySelector 的结果直接 return 出去（会是 null）",
            js.contains("try{return document.querySelector(")
        )
    }

    @Test
    fun `text 选择器查不到时回哨兵`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Text("没有这个字", exact = false))
        assertTrue("text 分支必须回 NOT_FOUND，实际：$js", js.contains(WebSelector.NOT_FOUND))
    }

    @Test
    fun `CSS 分支仍然把选择器语法错报成 JS 错误而不是 not found`() {
        val js = WebSelector.pickJs(WebSelector.Kind.Css("a[["))
        assertTrue("语法错必须是 JS_ERROR 前缀，实际：$js", js.contains(WebSelector.JS_ERROR_PREFIX))
    }

    // ---------- 上层翻译层 ----------

    @Test
    fun `字面量 null 也算没找到`() {
        assertTrue("哨兵要认", WebSelector.isMissing(WebSelector.NOT_FOUND))
        assertTrue("字符串 null 是 5.9.4 的假成功来源，必须认成没找到", WebSelector.isMissing("null"))
        assertFalse("正常内容不能误判", WebSelector.isMissing("Learn more"))
        assertFalse("null 不能误判成内容", WebSelector.isMissing(null))
        assertFalse("空字符串是合法内容（页面正文可能为空）", WebSelector.isMissing(""))
    }

    @Test
    fun `isNotFound 不 broadened 以免混淆两个判据`() {
        // isNotFound 只认哨兵；isMissing 才认 "null"。两个判据别被混成一个。
        assertEquals(false, WebSelector.isNotFound("null"))
        assertEquals(true, WebSelector.isMissing("null"))
    }

    @Test
    fun `自检：isMissing 不会把 webErr 或 jsErr 误认成没找到`() {
        assertFalse(
            WebSelector.isMissing("${WebOpScripts.WEB_ERR_PREFIX}not a text field")
        )
        assertFalse(
            WebSelector.isMissing("${WebSelector.JS_ERROR_PREFIX}SyntaxError: bad")
        )
    }
}
