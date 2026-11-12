package com.workspace.proot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.13.0 新增的协议能力：结构化错误、字段裁剪、表格响应。
 */
@Suppress("MagicNumber")
class WebProtocolExtensionsTest {

    private fun request(body: String): WebProtocol.Request =
        WebProtocol.Request("POST", "/op", "t", body)

    // ---------- 结构化错误 ----------

    @Test
    fun `detailed error keeps the old string and adds structured fields`() {
        val json = WebProtocol.errJsonDetailed("TypeError", "x is null", "at <anonymous>:1:1")
        val o = JSONObject(json)
        assertFalse(o.getBoolean("ok"))
        assertEquals("x is null", o.getString("error"))
        assertEquals("TypeError", o.getString("error_name"))
        assertEquals("x is null", o.getString("error_message"))
        assertEquals("at <anonymous>:1:1", o.getString("error_stack"))
    }

    @Test
    fun `detailed error tolerates a missing name and stack`() {
        val o = JSONObject(WebProtocol.errJsonDetailed(null, "boom", null))
        assertEquals("Error", o.getString("error_name"))
        assertEquals("", o.getString("error_stack"))
    }

    // ---------- fields 裁剪 ----------

    @Test
    fun `fields keeps the named keys plus ok and error`() {
        val body = WebProtocol.okJson("value" to "x", "url" to "https://a", "extra" to 1)
        val out = JSONObject(WebProtocol.projectFields(body, request("""{"fields":["value"]}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("x", out.getString("value"))
        assertFalse(out.has("url"))
        assertFalse(out.has("extra"))
    }

    @Test
    fun `fields without ok is filled in`() {
        val body = JSONObject().put("value", 7).put("url", "u").toString()
        val out = JSONObject(WebProtocol.projectFields(body, request("""{"fields":["value"]}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals(7, out.getInt("value"))
    }

    @Test
    fun `missing or empty fields leaves the body untouched`() {
        val body = WebProtocol.okJson("value" to "x", "url" to "u")
        assertEquals(body, WebProtocol.projectFields(body, request("""{"op":"eval"}""")))
        assertEquals(body, WebProtocol.projectFields(body, request("""{"fields":[]}""")))
    }

    @Test
    fun `fields never hides error`() {
        val body = WebProtocol.errJson("boom")
        val out = JSONObject(WebProtocol.projectFields(body, request("""{"fields":["value"]}""")))
        assertFalse(out.getBoolean("ok"))
        assertEquals("boom", out.getString("error"))
    }

    @Test
    fun `fields reports unknown names instead of dropping them`() {
        val body = WebProtocol.okJson("value" to "1")
        val out = JSONObject(
            WebProtocol.projectFields(body, request("""{"fields":["ok","value","nonexistent"]}"""))
        )
        assertEquals("1", out.getString("value"))
        assertEquals(1, out.getJSONArray("fields_unknown").length())
        assertEquals("nonexistent", out.getJSONArray("fields_unknown").getString(0))
    }

    @Test
    fun `fields with all known names has no unknown diagnostic`() {
        val body = WebProtocol.okJson("value" to "1", "url" to "u")
        val out = JSONObject(WebProtocol.projectFields(body, request("""{"fields":["value","url"]}""")))
        assertFalse(out.has("fields_unknown"))
    }

    @Test
    fun `fields does not report its own diagnostic as unknown`() {
        val body = WebProtocol.okJson("value" to "1")
        val out = JSONObject(WebProtocol.projectFields(body, request("""{"fields":["fields_unknown"]}""")))
        assertFalse(out.has("fields_unknown"))
    }

    // ---------- table ----------

    @Test
    fun `table response exposes headers and rows as native arrays`() {
        val page = JSONObject()
            .put("ok", true)
            .put("headers", JSONArray().put("车次").put("出发"))
            .put("rows", JSONArray().put(JSONObject().put("cells", JSONArray().put("G1"))))
            .put("rowCount", 1)
            .put("truncated", false)
        val out = JSONObject(WebTable.response(page))
        assertTrue(out.getBoolean("ok"))
        assertEquals(2, out.getJSONArray("headers").length())
        assertEquals("车次", out.getJSONArray("headers").getString(0))
        assertEquals(1, out.getJSONArray("rows").length())
        assertEquals(1, out.getInt("rowCount"))
        assertFalse(out.getBoolean("truncated"))
        assertFalse(out.getBoolean("headers_empty"))
    }

    @Test
    fun `table response flags empty headers`() {
        val page = JSONObject()
            .put("ok", true)
            .put("headers", JSONArray())
            .put("rows", JSONArray())
        val out = JSONObject(WebTable.response(page))
        assertTrue(out.getBoolean("headers_empty"))
        assertEquals(0, out.getJSONArray("headers").length())
    }

    @Test
    fun `table page script handles selector sentinels and ancestor tables`() {
        val js = WebTable.pageJs("document.querySelector('#t')", 50)
        assertTrue("字符串哨兵必须先原样返回：$js", js.contains("typeof e==='string'"))
        assertTrue("tbody/td 要能找到祖先 <table>：$js", js.contains("closest('table')"))
    }

    @Test
    fun `table help explains selector forms and empty headers`() {
        val help = WebProtocol.help(39080)
        assertTrue(help.contains("headers_empty"))
        assertTrue(help.contains("tbody/td"))
    }

    @Test
    fun `help documents js_file root and fields diagnostics`() {
        val help = WebProtocol.help(39080)
        assertTrue(help.contains("只允许 /workspace 内"))
        assertTrue(help.contains("fields_unknown"))
        assertTrue(help.contains("must be inside"))
    }

    @Test
    fun `table page script is a real script that reports not found`() {
        val js = WebTable.pageJs("document.querySelector('#t')", 50)
        assertTrue(js.startsWith("(function(){"))
        assertTrue(js.endsWith("})()"))
        assertTrue(js.contains("not found"))
        assertTrue(js.contains("aria-label"))
        assertTrue(js.contains("data-"))
    }

    @Test
    fun `table parse rejects failed payloads`() {
        assertNull(WebTable.parse("""{"ok":false,"error":"x"}"""))
        assertNull(WebTable.parse("not json"))
        assertNull(WebTable.parse(null))
        assertTrue(WebTable.parse("""{"ok":true,"headers":[]}""") != null)
    }
}
