package com.workspace.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsString] 的锁。
 *
 * 这一步坏了的表征是**报告整片空白但不报错** —— 极难排查，所以必须有测试。
 * 尤其要挡住"直接 JSONObject(raw)"那种写法。
 */
class JsStringTest {

    @Test
    fun `典型场景：页面 stringify 一个对象回来`() {
        // 页面里 return JSON.stringify({innerW:412}) 时 Android 实际收到的是这个形状
        val raw = "\"{\\\"innerW\\\":412,\\\"title\\\":\\\"probe-ready\\\"}\""
        val o = JsString.decodeJson(raw)
        assertEquals(412, o?.optInt("innerW"))
        assertEquals("probe-ready", o?.optString("title"))
    }

    @Test
    fun `自检：不解码直接 JSONObject 一定会失败（所以这一步不能省）`() {
        val raw = "\"{\\\"innerW\\\":412}\""
        val failed = runCatching { org.json.JSONObject(raw) }.isFailure
        assertTrue("不解码就解析应当失败，否则这层就是多余的", failed)
    }

    @Test
    fun `页面里带换行与制表符`() {
        val raw = "\"a\\nb\\tc\""
        assertEquals("a\nb\tc", JsString.decode(raw))
    }

    @Test
    fun `值里有反斜杠与引号`() {
        val raw = "\"say \\\"hi\\\" \\\\ done\""
        assertEquals("say \"hi\" \\ done", JsString.decode(raw))
    }

    @Test
    fun `中文按原样解出`() {
        val raw = "\"探针页面\""
        assertEquals("探针页面", JsString.decode(raw))
    }

    @Test
    fun `残缺与非法输入返回 null 而不是抛异常`() {
        assertNull(JsString.decode(null))
        // 引号在，但结尾的 \" 把收尾引号吃掉了 —— 真正截断的字符串。
        // 拼出来而不是写成字面量：Kotlin 里这串引号/反斜杠数着费劲，容易数错。
        val truncated = "\"abc" + "\\" + "\""
        assertEquals("自检：这串确实以引号开头结尾", '"', truncated.last())
        assertNull("不该有落单的转义符", JsString.decode(truncated))
        assertNull("不该有截断的 \\u", JsString.decode("\"\\u12\""))
        assertNull("非法十六进制", JsString.decode("\"\\uZZZZ\""))
        assertNull("未知转义", JsString.decode("\"a\\qb\""))
        // 空串解出来就是空串（这是对的）；解不出对象是下一步的事
        assertEquals("", JsString.decode("\"\""))
        assertNull("空串里解析不出对象", JsString.decodeJson("\"\""))
    }

    @Test
    fun `页面回的不是字符串时原样给出`() {
        // evaluateJavascript 对 undefined / number 有时就不加引号
        assertEquals("undefined", JsString.decode("undefined"))
        assertNull(JsString.decodeJson("undefined"))
    }
}
