package com.workspace.proot

/** 脚本 → 应用 的浮窗请求/结果/样式数据模型。纯逻辑，无 Android 依赖，可 JVM 单测。 */
object ScriptDialogSpec {

    // ===== 样式字段 =====
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val THEME_GLASS = "glass"

    const val POSITION_CENTER = "center"
    const val POSITION_BOTTOM = "bottom"

    const val ANIM_FADE = "fade"
    const val ANIM_SCALE = "scale"
    const val ANIM_SLIDE = "slide-up"

    const val KIND_TEXT = "text"
    const val KIND_INPUT = "input"
    const val KIND_SELECT = "select"
    const val KIND_TOGGLE = "toggle"
    const val KIND_BUTTONS = "buttons"

    const val BTN_PRIMARY = "primary"
    const val BTN_DANGER = "danger"
    const val BTN_NORMAL = "normal"

    const val RESULT_ID_DISMISS = "dismiss"
    const val RESULT_ID_TIMEOUT = "timeout"
    const val ERROR_PERMISSION = "permission"
    const val ERROR_BUSY = "busy"

    const val DEFAULT_TIMEOUT_SEC = 60
    const val DEFAULT_RADIUS_DP = 18
    const val DEFAULT_WIDTH_PCT = 88

    data class Style(
        val theme: String = THEME_DARK,
        val accent: Int? = null,
        val radiusDp: Int = DEFAULT_RADIUS_DP,
        val position: String = POSITION_CENTER,
        val anim: String = ANIM_SCALE,
        val widthPct: Int = DEFAULT_WIDTH_PCT
    )

    data class Button(
        val text: String,
        val id: String,
        val kind: String = BTN_NORMAL,
        val close: Boolean = true
    )

    data class TextRow(val text: String)
    data class InputRow(
        val key: String,
        val label: String,
        val default: String = "",
        val password: Boolean = false,
        val multiline: Boolean = false
    )
    data class SelectRow(
        val key: String,
        val label: String,
        val options: List<String>,
        val multi: Boolean = false
    )
    data class ToggleRow(
        val key: String,
        val label: String,
        val def: Boolean = false,
        val filter: String = ""
    )
    data class ButtonsRow(val buttons: List<Button>)

    sealed class Row {
        data class Text(val row: TextRow) : Row()
        data class Input(val row: InputRow) : Row()
        data class Select(val row: SelectRow) : Row()
        data class Toggle(val row: ToggleRow) : Row()
        data class Buttons(val row: ButtonsRow) : Row()
    }

    data class Ui(
        val title: String = "",
        val message: String = "",
        val rows: List<Row> = emptyList()
    )

    data class Request(
        val id: String,
        val timeoutSec: Double = DEFAULT_TIMEOUT_SEC.toDouble(),
        val ui: Ui,
        val style: Style = Style(),
        val chain: String? = null,
        val op: String? = null
    )

    data class Result(
        val id: String,
        val values: Map<String, String> = emptyMap(),
        val error: String? = null
    )

    // ===== 解析 =====

    fun parseRequest(json: MiniJson.Obj): Request {
        val uiObj = json.optObj("ui")
        val rows = uiObj?.optArr("rows")
        val parsedRows = mutableListOf<Row>()
        if (rows != null) {
            for (i in 0 until rows.length()) {
                rows.getObj(i)?.let { parseRow(it)?.let { r -> parsedRows.add(r) } }
            }
        }
        val styleObj = json.optObj("style")
        val chainRaw = json.optString("chain", "")
        val opRaw = json.optString("op", "")
        return Request(
            id = json.optString("id", ""),
            timeoutSec = json.optDouble("timeout", DEFAULT_TIMEOUT_SEC.toDouble()),
            ui = Ui(
                title = uiObj?.optString("title", "") ?: "",
                message = uiObj?.optString("message", "") ?: "",
                rows = parsedRows
            ),
            style = parseStyle(styleObj),
            chain = if (chainRaw.isEmpty()) null else chainRaw,
            op = if (opRaw.isEmpty()) null else opRaw
        )
    }

    fun parseRequest(text: String): Request = parseRequest(MiniJson.parse(text))

    private fun parseRow(obj: MiniJson.Obj): Row? {
        return when (obj.optString("kind", "")) {
            KIND_TEXT -> Row.Text(TextRow(obj.optString("text", "")))
            KIND_INPUT -> Row.Input(
                InputRow(
                    key = obj.optString("key", ""),
                    label = obj.optString("label", ""),
                    default = obj.optString("default", ""),
                    password = obj.optBoolean("password", false),
                    multiline = obj.optBoolean("multiline", false)
                )
            )
            KIND_SELECT -> Row.Select(
                SelectRow(
                    key = obj.optString("key", ""),
                    label = obj.optString("label", ""),
                    options = optStringArray(obj, "options"),
                    multi = obj.optBoolean("multi", false)
                )
            )
            KIND_TOGGLE -> Row.Toggle(
                ToggleRow(
                    key = obj.optString("key", ""),
                    label = obj.optString("label", ""),
                    def = obj.optBoolean("default", false),
                    filter = obj.optString("filter", "")
                )
            )
            KIND_BUTTONS -> Row.Buttons(
                ButtonsRow(parseButtons(obj.optArr("buttons")))
            )
            else -> null
        }
    }

    private fun parseStyle(obj: MiniJson.Obj?): Style {
        if (obj == null) return Style()
        return Style(
            theme = obj.optString("theme", THEME_DARK),
            accent = parseColor(obj.optString("accent", "")),
            radiusDp = obj.optInt("radius", DEFAULT_RADIUS_DP),
            position = obj.optString("position", POSITION_CENTER),
            anim = obj.optString("anim", ANIM_SCALE),
            widthPct = obj.optInt("widthPct", DEFAULT_WIDTH_PCT)
        )
    }

    private fun parseButtons(arr: MiniJson.Arr?): List<Button> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Button>()
        for (i in 0 until arr.length()) {
            val o = arr.getObj(i) ?: continue
            out.add(
                Button(
                    text = o.optString("text", ""),
                    id = o.optString("id", ""),
                    kind = o.optString("kind", BTN_NORMAL),
                    close = o.optBoolean("close", true)
                )
            )
        }
        return out
    }

    /** 判定一个结果是否应关闭当前浮窗（默认关；按钮 close=false 则保持窗口等待下一弹窗）。 */
    fun shouldDismiss(request: Request, result: Result): Boolean {
        if (result.error != null) return true
        if (result.id == RESULT_ID_DISMISS || result.id == RESULT_ID_TIMEOUT) return true
        val btn = request.ui.rows.filterIsInstance<Row.Buttons>()
            .firstOrNull()?.row?.buttons?.find { it.id == result.id } ?: return true
        return btn.close
    }

    private fun optStringArray(obj: MiniJson.Obj, key: String): List<String> {
        val arr = obj.optArr(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i) ?: "")
        return out
    }

    /** 支持 #RRGGBB / #AARRGGBB，失败返回 null（走默认）。 */
    fun parseColor(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("#")) return null
        val hex = t.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val v = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000 or v).toInt() else v.toInt()
    }

    // ===== 序列化 =====

    fun requestToJson(req: Request): MiniJson.Obj {
        val ui = MiniJson.Obj()
            .put("title", req.ui.title)
            .put("message", req.ui.message)
        val rows = MiniJson.Arr()
        for (r in req.ui.rows) {
            when (r) {
                is Row.Text -> rows.put(
                    MiniJson.Obj().put("kind", KIND_TEXT).put("text", r.row.text)
                )
                is Row.Input -> rows.put(
                    MiniJson.Obj()
                        .put("kind", KIND_INPUT)
                        .put("key", r.row.key)
                        .put("label", r.row.label)
                        .put("default", r.row.default)
                        .put("password", r.row.password)
                        .put("multiline", r.row.multiline)
                )
                is Row.Select -> {
                    val o = MiniJson.Obj()
                        .put("kind", KIND_SELECT)
                        .put("key", r.row.key)
                        .put("label", r.row.label)
                        .put("multi", r.row.multi)
                    val opts = MiniJson.Arr()
                    for (opt in r.row.options) opts.put(opt)
                    o.put("options", opts)
                    rows.put(o)
                }
                is Row.Toggle -> {
                    val o = MiniJson.Obj()
                        .put("kind", KIND_TOGGLE)
                        .put("key", r.row.key)
                        .put("label", r.row.label)
                        .put("default", r.row.def)
                    if (r.row.filter.isNotEmpty()) o.put("filter", r.row.filter)
                    rows.put(o)
                }
                is Row.Buttons -> {
                    val o = MiniJson.Obj().put("kind", KIND_BUTTONS)
                    val btns = MiniJson.Arr()
                    for (b in r.row.buttons) {
                        val bo = MiniJson.Obj().put("text", b.text).put("id", b.id).put("kind", b.kind)
                        if (!b.close) bo.put("close", false)
                        btns.put(bo)
                    }
                    o.put("buttons", btns)
                    rows.put(o)
                }
            }
        }
        ui.put("rows", rows)
        val style = MiniJson.Obj()
            .put("theme", req.style.theme)
            .put("radius", req.style.radiusDp)
            .put("position", req.style.position)
            .put("anim", req.style.anim)
            .put("widthPct", req.style.widthPct)
        if (req.style.accent != null) {
            style.put("accent", "#%08X".format(req.style.accent))
        }
        val out = MiniJson.Obj()
            .put("id", req.id)
            .put("timeout", req.timeoutSec)
            .put("ui", ui)
            .put("style", style)
        if (req.chain != null) out.put("chain", req.chain)
        if (req.op != null) out.put("op", req.op)
        return out
    }

    fun requestToJsonString(req: Request): String = requestToJson(req).toString()

    fun resultToJson(result: Result): MiniJson.Obj {
        val o = MiniJson.Obj().put("id", result.id)
        if (result.error != null) o.put("error", result.error)
        val values = MiniJson.Obj()
        for ((k, v) in result.values) values.put(k, v)
        o.put("values", values)
        return o
    }

    fun resultToJsonString(result: Result): String = resultToJson(result).toString()
}
