package com.workspace.proot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * eval 包装层的语义回归（5.13.0）。
 *
 * 三条契约：
 *  1. JS 抛错必须走 `ok:false`，不能塌成 `value:null`；
 *  2. Promise 必须真的等到落地，不能返回 `{}`；
 *  3. 默认仍是字符串，`raw:true` 才还原原生类型。
 */
@Suppress("MagicNumber")
class WebEvalScriptsTest {

    private fun envelope(json: String): WebEvalScripts.Envelope =
        WebEvalScripts.parseEnvelope(json) ?: throw AssertionError("解析失败：$json")

    // ---------- 信封 ----------

    @Test
    fun `string envelope keeps the raw text`() {
        val env = envelope("""{"ok":true,"t":"string","s":"苹果"}""")
        assertTrue(env.ok)
        assertEquals("string", env.type)
        assertEquals("苹果", env.text)
        assertEquals("苹果", WebEvalScripts.textValue(env))
        assertEquals("苹果", WebEvalScripts.rawValue(env))
    }

    @Test
    fun `number and boolean keep text form by default`() {
        val n = envelope("""{"ok":true,"t":"number","s":"42","n":42}""")
        val b = envelope("""{"ok":true,"t":"boolean","s":"true","b":true}""")
        assertEquals("42", WebEvalScripts.textValue(n))
        assertEquals("true", WebEvalScripts.textValue(b))
    }

    @Test
    fun `raw mode restores native number and boolean`() {
        val n = envelope("""{"ok":true,"t":"number","s":"42","n":42}""")
        val b = envelope("""{"ok":true,"t":"boolean","s":"true","b":true}""")
        assertEquals(42L, WebEvalScripts.rawValue(n))
        assertEquals(true, WebEvalScripts.rawValue(b))
    }

    @Test
    fun `object keeps json text by default and native object in raw`() {
        val env = envelope("""{"ok":true,"t":"object","s":"{\"a\":1}","j":"{\"a\":1}"}""")
        assertEquals("{\"a\":1}", WebEvalScripts.textValue(env))
        val raw = WebEvalScripts.rawValue(env)
        assertTrue(raw is JSONObject)
        assertEquals(1, (raw as JSONObject).getInt("a"))
    }

    @Test
    fun `array keeps json text by default and native array in raw`() {
        val env = envelope("""{"ok":true,"t":"array","s":"[1,2]","j":"[1,2]"}""")
        assertEquals("[1,2]", WebEvalScripts.textValue(env))
        val raw = WebEvalScripts.rawValue(env)
        assertTrue(raw is JSONArray)
        assertEquals(2, (raw as JSONArray).length())
    }

    @Test
    fun `null and undefined are JSON null in both contracts`() {
        val nul = envelope("""{"ok":true,"t":"null"}""")
        val undef = envelope("""{"ok":true,"t":"undefined"}""")
        assertNull(WebEvalScripts.textValue(nul))
        assertNull(WebEvalScripts.rawValue(nul))
        assertNull(WebEvalScripts.textValue(undef))
        assertNull(WebEvalScripts.rawValue(undef))
    }

    @Test
    fun `error envelope carries name message and stack`() {
        val env = envelope(
            """{"ok":false,"name":"TypeError","message":"x is null","stack":"at line 1"}"""
        )
        assertFalse(env.ok)
        assertEquals("TypeError", env.name)
        assertEquals("x is null", env.message)
        assertEquals("at line 1", env.stack)
    }

    @Test
    fun `unreadable envelope returns null`() {
        assertNull(WebEvalScripts.parseEnvelope("not json"))
        assertNull(WebEvalScripts.parseEnvelope(""))
        assertNull(WebEvalScripts.parseEnvelope(null))
        assertNull(WebEvalScripts.parseEnvelope("""{"t":"string","s":"x"}"""))
    }

    // ---------- 包装脚本 ----------

    @Test
    fun `sync wrapper always returns a json envelope`() {
        val js = WebEvalScripts.syncExpression("document.title", "t1")
        assertTrue(js.contains("try{"))
        assertTrue(js.contains("catch(e)"))
        assertTrue("必须返回 JSON 信封：$js", js.contains("JSON.stringify(Object.assign({ok:true}"))
        assertTrue("必须能检测 thenable：$js", js.contains("typeof v.then==='function'"))
    }

    @Test
    fun `statement wrapper puts user code in a function body`() {
        val js = WebEvalScripts.syncStatement("var x=1; return x", "t2")
        assertTrue(js.contains("(function(){var x=1; return x"))
    }

    @Test
    fun `async wrappers return the pending marker synchronously`() {
        val expr = WebEvalScripts.asyncScript(WebEvalScripts.asyncExpression("await 1"), "t3")
        val stmt = WebEvalScripts.asyncScript(
            WebEvalScripts.asyncStatement("var x=await 1; return x"), "t4"
        )
        assertTrue(expr.contains(WebEvalScripts.PENDING))
        assertTrue(stmt.contains(WebEvalScripts.PENDING))
        assertTrue(expr.contains("Promise.resolve"))
        assertTrue("必须有 async 以便顶层 await：$expr", expr.contains("async function"))
    }

    @Test
    fun `poll script reports pending and gone sentinels`() {
        val js = WebEvalScripts.poll("t5")
        assertTrue(js.contains(WebEvalScripts.PENDING))
        assertTrue(js.contains(WebEvalScripts.GONE))
        assertTrue(js.contains("__tl_eval"))
    }

    @Test
    fun `conditions parse to 1 or 0 or an error marker`() {
        val js = WebEvalScripts.condition("document.title!==''")
        assertTrue(js.contains("return v?1:0"))
        assertTrue(js.contains("'E:'"))

        val sel = WebEvalScripts.conditionSelector("document.querySelector('#kw')")
        assertTrue(sel.contains("return e?1:0"))
    }

    @Test
    fun `fetch script sends cookies and truncates the body`() {
        val js = WebEvalScripts.fetchScript(
            token = "t6",
            urlLiteral = "\"/api\"",
            methodLiteral = "\"GET\"",
            headersLiteral = "{}",
            bodyLiteral = "null",
            maxChars = 1234,
        )
        assertTrue("必须带页面 cookie：$js", js.contains("credentials:'include'"))
        assertTrue("必须截断：$js", js.contains("t.length>1234"))
        assertTrue("必须回报状态：$js", js.contains("status:r.status"))
        assertTrue(js.contains(WebEvalScripts.PENDING))
    }

    @Test
    fun `waitfor selector keeps waiting while the element is missing`() {
        val js = WebEvalScripts.conditionSelector("document.querySelector('#kw')")
        assertTrue("未找到必须是 0（继续等），不能当错误：$js", js.contains("return 0"))
        assertTrue(js.contains(WebSelector.NOT_FOUND))
    }

    @Test
    fun `jsonOrNull parses only json-looking bodies`() {
        assertTrue(WebEvalScripts.jsonOrNull("""{"a":1}""", "text/plain") is JSONObject)
        assertTrue(WebEvalScripts.jsonOrNull("[1,2]", "") is JSONArray)
        assertNull(WebEvalScripts.jsonOrNull("hello", "text/plain"))
        assertNull(WebEvalScripts.jsonOrNull("hello", "application/json"))
    }
}
