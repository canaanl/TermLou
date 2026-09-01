package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptDialogSpecTest {

    @Test
    fun parseFullRequest() {
        val json = """
            {
              "id": "req-1",
              "timeout": 30,
              "ui": {
                "title": "确认",
                "message": "继续?",
                "rows": [
                  {"kind": "text", "text": "line1\nline2"},
                  {"kind": "input", "key": "name", "label": "名字", "default": "张三"},
                  {"kind": "select", "key": "mode", "label": "模式", "multi": false,
                   "options": ["快速", "均衡", "省电"]},
                  {"kind": "select", "key": "feat", "label": "功能", "multi": true,
                   "options": ["a", "b"]},
                  {"kind": "buttons", "buttons": [
                     {"text": "确定", "id": "ok", "kind": "primary"},
                     {"text": "取消", "id": "cancel"}]}
                ]
              },
              "style": {"theme": "glass", "accent": "#4DD0E1", "radius": 24,
                        "position": "bottom", "anim": "slide-up", "widthPct": 92}
            }
        """.trimIndent()

        val req = ScriptDialogSpec.parseRequest(json)
        assertEquals("req-1", req.id)
        assertEquals(30.0, req.timeoutSec, 0.0)
        assertEquals("确认", req.ui.title)
        assertEquals("继续?", req.ui.message)
        assertEquals(5, req.ui.rows.size)

        val text = req.ui.rows[0] as ScriptDialogSpec.Row.Text
        assertEquals("line1\nline2", text.row.text)

        val input = req.ui.rows[1] as ScriptDialogSpec.Row.Input
        assertEquals("name", input.row.key)
        assertEquals("张三", input.row.default)

        val single = req.ui.rows[2] as ScriptDialogSpec.Row.Select
        assertFalse(single.row.multi)
        assertEquals(listOf("快速", "均衡", "省电"), single.row.options)

        val multi = req.ui.rows[3] as ScriptDialogSpec.Row.Select
        assertTrue(multi.row.multi)

        val buttons = req.ui.rows[4] as ScriptDialogSpec.Row.Buttons
        assertEquals(2, buttons.row.buttons.size)
        assertEquals("primary", buttons.row.buttons[0].kind)

        assertEquals(ScriptDialogSpec.THEME_GLASS, req.style.theme)
        assertEquals(0xFF4DD0E1.toInt(), req.style.accent)
        assertEquals(24, req.style.radiusDp)
        assertEquals(ScriptDialogSpec.POSITION_BOTTOM, req.style.position)
        assertEquals(92, req.style.widthPct)
    }

    @Test
    fun parseEmptyUsesDefaults() {
        val req = ScriptDialogSpec.parseRequest("""{"id":"x"}""")
        assertEquals(ScriptDialogSpec.THEME_DARK, req.style.theme)
        assertEquals(ScriptDialogSpec.DEFAULT_TIMEOUT_SEC.toDouble(), req.timeoutSec, 0.0)
        assertNull(req.style.accent)
        assertTrue(req.ui.rows.isEmpty())
    }

    @Test
    fun parseColor() {
        assertEquals(0xFF2D7D46.toInt(), ScriptDialogSpec.parseColor("#2D7D46"))
        assertEquals(0x802D7D46.toInt(), ScriptDialogSpec.parseColor("#802D7D46"))
        assertNull(ScriptDialogSpec.parseColor(""))
        assertNull(ScriptDialogSpec.parseColor("#ZZZ"))
        assertNull(ScriptDialogSpec.parseColor("2D7D46"))
    }

    @Test
    fun requestRoundTrip() {
        val req = ScriptDialogSpec.Request(
            id = "abc",
            timeoutSec = 12.0,
            ui = ScriptDialogSpec.Ui(
                title = "t",
                message = "m",
                rows = listOf(
                    ScriptDialogSpec.Row.Input(ScriptDialogSpec.InputRow("k", "l", "d")),
                    ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("tk", "开关", def = true)),
                    ScriptDialogSpec.Row.Buttons(
                        ScriptDialogSpec.ButtonsRow(
                            listOf(ScriptDialogSpec.Button("确定", "ok", ScriptDialogSpec.BTN_PRIMARY))
                        )
                    )
                )
            ),
            style = ScriptDialogSpec.Style(
                theme = ScriptDialogSpec.THEME_LIGHT,
                accent = 0xFF2D7D46.toInt(),
                radiusDp = 20,
                position = ScriptDialogSpec.POSITION_BOTTOM,
                anim = ScriptDialogSpec.ANIM_FADE,
                widthPct = 80
            )
        )
        val round = ScriptDialogSpec.parseRequest(ScriptDialogSpec.requestToJson(req))
        assertEquals(req.id, round.id)
        assertEquals(req.timeoutSec, round.timeoutSec, 0.0)
        assertEquals(req.ui.title, round.ui.title)
        assertEquals(req.ui.message, round.ui.message)
        assertEquals(req.style.theme, round.style.theme)
        assertEquals(req.style.accent, round.style.accent)
        assertEquals(req.style.radiusDp, round.style.radiusDp)
        assertEquals(req.style.position, round.style.position)
        assertEquals(req.style.anim, round.style.anim)
        assertEquals(req.style.widthPct, round.style.widthPct)
        val input = round.ui.rows[0] as ScriptDialogSpec.Row.Input
        assertEquals("d", input.row.default)
        val toggle = round.ui.rows[1] as ScriptDialogSpec.Row.Toggle
        assertTrue(toggle.row.def)
    }

    @Test
    fun resultToJson() {
        val json = ScriptDialogSpec.resultToJson(
            ScriptDialogSpec.Result("ok", mapOf("name" to "张三"))
        )
        assertEquals("ok", json.optString("id", ""))
        assertEquals("张三", json.optObj("values")?.optString("name", ""))
        assertFalse(json.has("error"))
    }

    @Test
    fun resultToJsonError() {
        val json = ScriptDialogSpec.resultToJson(
            ScriptDialogSpec.Result("error", error = ScriptDialogSpec.ERROR_PERMISSION)
        )
        assertEquals(ScriptDialogSpec.ERROR_PERMISSION, json.optString("error", ""))
    }

    @Test
    fun shellStyleMultiNumberParses() {
        // termlou_ui.sh 生成 "multi":1 / 0（数字），必须能被识别为布尔
        val req = ScriptDialogSpec.parseRequest(
            """{"id":"x","timeout":60,"ui":{"title":"t","rows":[
               {"kind":"select","key":"m","label":"多选","multi":1,"options":["a","b"]},
               {"kind":"select","key":"s","label":"单选","multi":0,"options":["c","d"]}
            ]}}"""
        )
        val multi = req.ui.rows[0] as ScriptDialogSpec.Row.Select
        assertTrue(multi.row.multi)
        assertEquals(listOf("a", "b"), multi.row.options)

        val single = req.ui.rows[1] as ScriptDialogSpec.Row.Select
        assertFalse(single.row.multi)
        assertEquals(listOf("c", "d"), single.row.options)
    }

    @Test
    fun shellStyleStringOptionsParses() {
        // termlou_ui.sh 生成 options 为字符串数组
        val req = ScriptDialogSpec.parseRequest(
            """{"id":"x","ui":{"rows":[
               {"kind":"select","key":"s","label":"S","options":["快速","均衡","省电"]}
            ]}}"""
        )
        val sel = req.ui.rows[0] as ScriptDialogSpec.Row.Select
        assertEquals(listOf("快速", "均衡", "省电"), sel.row.options)
    }

    @Test
    fun toggleFilterParsesAndRoundTrips() {
        // termlou_ui.sh 生成 --toggle 标签=key=标签 的 filter 字段
        val req = ScriptDialogSpec.parseRequest(
            """{"id":"x","ui":{"rows":[
               {"kind":"toggle","key":"filter","label":"只看运行中","default":0,"filter":"up"}
            ]}}"""
        )
        val t = req.ui.rows[0] as ScriptDialogSpec.Row.Toggle
        assertEquals("up", t.row.filter)

        val round = ScriptDialogSpec.parseRequest(ScriptDialogSpec.requestToJson(req))
        val rt = round.ui.rows[0] as ScriptDialogSpec.Row.Toggle
        assertEquals("up", rt.row.filter)

        // 无 filter 的开关 round-trip 后不产生 filter 字段
        val plain = ScriptDialogSpec.Request(
            id = "x",
            ui = ScriptDialogSpec.Ui(
                rows = listOf(ScriptDialogSpec.Row.Toggle(ScriptDialogSpec.ToggleRow("k", "普通", def = true)))
            )
        )
        val plainJson = ScriptDialogSpec.requestToJson(plain).toString()
        assertFalse(plainJson.contains("filter"))
        val plainRound = ScriptDialogSpec.parseRequest(plainJson)
        val pt = plainRound.ui.rows[0] as ScriptDialogSpec.Row.Toggle
        assertEquals("", pt.row.filter)
    }

    @Test
    fun toggleRowParses() {
        // termlou_ui.sh 生成 default 为数字 0/1，必须能识别为布尔
        val req = ScriptDialogSpec.parseRequest(
            """{"id":"x","ui":{"rows":[
               {"kind":"toggle","key":"auto","label":"自动备份","default":1},
               {"kind":"toggle","key":"dark","label":"深色","default":0}
            ]}}"""
        )
        val on = req.ui.rows[0] as ScriptDialogSpec.Row.Toggle
        assertTrue(on.row.def)
        val off = req.ui.rows[1] as ScriptDialogSpec.Row.Toggle
        assertFalse(off.row.def)
    }
}
