package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 5.9.40：MD3 UI 规范的第二层锁 —— **扫真实源码，验规则被用上了**。
 *
 * ## 为什么必须扫源码（这个项目的老教训）
 *
 * 5.9.40 之前的实测：`UiTokens` 里定义了 4 个字号档，测试也是绿的、
 * 引用也有 164 处 —— 但全项目**同时还有 11/14/16/17/24/32 六种裸 sp 字面量**，
 * `setPadding` 236 处全是裸数字，圆角有 4 种不同值。
 *
 * **定义了、测试了、有人引用了 —— 规则照样被绕开。**
 * 这是本项目反复栽过的同一类坑（已栽 5 次），所以规范光有第一层锁是不够的。
 *
 * ## 本测试的边界（诚实说明）
 *
 * 扫描只能证明"这些写法不存在了"，**证明不了"剩下的那些就是对的"**。
 * 残留的裸数字可能藏在正则覆盖不到的形式里。所以：
 * - 正则刻意放宽，宁可多报也不漏报；
 * - 白名单按**文件**豁免并逐个写明理由，不做整类沉默豁免。
 */
class UiDimsScanTest {

    /** 白名单：这些文件按设计不属于"通用组件"，逐个给理由（见每个条目的注释）。 */
    private val exempt = mapOf(
        // 开屏动画工坊：画布像素级绘制，不是通用 UI 组件（点阵一个格子=1px 语义）
        "SplashView.kt" to "开屏画布，按像素绘制，非组件 UI",
        "SplashMakerActivity.kt" to "开屏画布工坊，编辑区按格绘制",
        // SolidPanel：实体键帽语言（内阴影/按压高光），是本项目招牌设计，不套 MD3 圆角
        "SolidPanel.kt" to "实体键帽自定义绘制，MD3 shape 不适用",
        // Wheel：文件已自行声明白名单豁免（终端滚轮，1:1 复刻 TUI 观感）
        "WheelController.kt" to "终端滚轮，TUI 复刻，文件内已声明豁免",
        "WheelAdapter.kt" to "同上",
        // 浮窗/小窗：窗口外形（圆角边框决定窗口长什么样），不是组件
        "WebFloatWindowHost.kt" to "悬浮窗窗口外形，非组件 UI",
        "WebFloatWindow.kt" to "悬浮窗几何，非组件 UI",
        // 本规范自身
        "UiDims.kt" to "规范定义本身",
        "UiTokens.kt" to "规范定义本身",
        // 终端输出：等宽字体是功能性要求
        "TerminalActivityBar.kt" to "终端相关，等宽字体为功能性要求",
        "StatusMarquee.kt" to "终端跑马灯，等宽为功能性要求",
        "StatusController.kt" to "终端状态栏，等宽为功能性要求",
        "TerminalController.kt" to "终端本体，等宽为功能性要求",
        "AnsiParser.kt" to "ANSI 解析，不渲染 UI",
    )

    private val srcDir = File("src/main/java/com/workspace/proot")

    private fun scanFiles(): List<File> {
        val dir = srcDir
        assertTrue("源码目录不存在：${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles { f -> f.name.endsWith(".kt") && f.extension == "kt" }
            ?.sortedBy { it.name }
            ?.filter { !exempt.containsKey(it.name) }
            ?: emptyList()
    }

    private fun read(f: File): String =
        String(f.readBytes(), Charsets.UTF_8).replace("\r\n", "\n")

    /**
     * 裸 sp 字号：项目里出现过 11/14/16/17/24/32 等。
     * 只放行 5 个规范档位，其余一律报错。
     */
    @Test
    fun `no raw sp font sizes outside the five md3 tiers`() {
        val allowed = setOf("12", "13", "15", "18", "28")
        val offenders = mutableListOf<String>()
        for (f in scanFiles()) {
            val lines = read(f).lines()
            lines.forEachIndexed { i, line ->
                val trimmed = line.trim()
                // 注释与文档不参与
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                // 抓 setTextSize(..., Nf) 与 textSize = Nf
                val re = Regex("""setTextSize\([^,]*,\s*([0-9]+(?:\.[0-9]+)?)f?\s*\)|textSize\s*=\s*([0-9]+(?:\.[0-9]+)?)f""")
                for (m in re.findAll(line)) {
                    val v = (m.groups[1]?.value ?: m.groups[2]?.value)?.removeSuffix("f") ?: continue
                    if (!allowed.contains(v.trim())) {
                        offenders += "${f.name}:${i + 1}  裸字号 ${v}f  ->  $trimmed"
                    }
                }
            }
        }
        assertEquals(
            "以下地方还在用规范外的裸字号（收编到 12/13/15/18/28）：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /**
     * 裸圆角：此前项目有 2 / 8 / 12 / 20 四种自定义半径。
     * 2 与 20 已判定并入 MD3 档位，不许再出现。
     */
    @Test
    fun `no raw corner radii outside md3 shape scale`() {
        val offenders = mutableListOf<String>()
        val banned = setOf("2", "20")
        for (f in scanFiles()) {
            read(f).lines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                val re = Regex("""cornerRadius\s*=\s*([0-9]+(?:\.[0-9]+)?)f|Radius\(([0-9]+(?:\.[0-9]+)?)f""")
                for (m in re.findAll(line)) {
                    val v = (m.groups[1]?.value ?: m.groups[2]?.value)?.removeSuffix("f") ?: continue
                    if (banned.contains(v.trim())) {
                        offenders += "${f.name}:${i + 1}  裸圆角 ${v}f  ->  $trimmed"
                    }
                }
            }
        }
        assertEquals(
            "2dp / 20dp 圆角已并入 MD3 档位，不许再出现：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /**
     * 间距必须是 4 的倍数。
     *
     * 这里只看 `setPadding(` 第一个参数与**显式 dp 常量**，覆盖面窄但绝不误报 ——
     * 宁可漏，不可把 `(16 * density)` 这类像素换算误判成违规间距。
     */
    @Test
    fun `explicit dp paddings sit on the four dp grid`() {
        val offenders = mutableListOf<String>()
        for (f in scanFiles()) {
            read(f).lines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                // setPadding(数字, ...) 且该数字没有乘 density —— 也就是裸 dp 写法
                val re = Regex("""setPadding\(\s*([0-9]+)\s*,""")
                for (m in re.findAll(line)) {
                    val v = m.groups[1]!!.value.toInt()
                    if (v % 4 != 0) {
                        offenders += "${f.name}:${i + 1}  间距 ${v}dp 不在 4 的倍数上  ->  $trimmed"
                    }
                }
            }
        }
        assertEquals(
            "间距必须落在 4dp 网格上（0/4/8/12/16/24/32）：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /**
 * 行高此前全项目 0 次 —— 现在必须真的开始用。
     *
     * 注意这里扫的是**全部** .kt（含白名单文件）：`setLineSpacing` 的唯一调用点在
     * `UiDims.textMd` 里，而 UiDims 本身在白名单中 —— 若用 `scanFiles()`（排除白名单）
     * 就会永远扫不到，这条锁会变成一个永远为真、也永远验不出问题的摆设。
     */
    @Test
    fun `line spacing is actually applied somewhere`() {
        val dir = srcDir
        val used = dir.listFiles { f -> f.name.endsWith(".kt") }
            ?.filter { read(it).contains("setLineSpacing") }
            ?.map { it.name }
            ?: emptyList()
        assertTrue(
            "全项目一处 setLineSpacing 都没有，行高还是没人管",
            used.isNotEmpty(),
        )
        // 反过来也锁一下：不能只有定义处有调用点
        val realUse = used.filter { it != "UiDims.kt" }
        assertTrue(
            "setLineSpacing 只出现在规范自身，没有页面真的用上行高：" + used.joinToString(),
            realUse.isNotEmpty() || scanFiles().any { read(it).contains("textMd(") },
        )
    }

    /**
     * 5.9.41 加的锁：**按钮必须走统一圆角来源**。
     *
     * 5.9.40 的漏洞：只扫了 `cornerRadius = ...` 这个字样，把所有手写圆角都改了，
     * 还据此写下"改一处全 App 按钮圆角统一"。
     *
     * 真机立刻打脸 —— **约 25 处裸 `Button(activity).apply {}` 压根没有 `cornerRadius`**，
     * 它们用的是主 App 主题（`Theme.AppCompat`，MD2）自带的圆角，
     * 与设置页的 `CORNER_MD` 并排就是两种圆角。
     *
     * 根因还是老问题：**扫源码的锁只验字样，验不了"它有没有用"**。
     * 没有 `cornerRadius` 字样的地方，恰恰是最漏的那批。
     *
     * 本锁反向扫描 —— **找出创建了 Button 却没有任何统一外观的地方**。
     * 允许的"统一外观"有两种：
     * - `ButtonStyle.apply/outlined`：普通按钮
     * - `SegmentStyle.applyRow`：分段控件（之后会接管外观，首末段只圆一侧）
     */
    @Test
    fun `every button goes through ButtonStyle or SegmentStyle`() {
        val offenders = mutableListOf<String>()
        for (f in scanFiles()) {
            val lines = read(f).lines()
            lines.forEachIndexed { i, line ->
                if (!line.contains(".apply {") && !line.contains("= Button(")) {
                    if (!Regex("""Button\(\s*(activity|this|ctx|context)\s*\)""").containsMatchIn(line)) return@forEachIndexed
                }
                // RadioButton / CheckBox 等不是 Button，跳过
                if (Regex("""(RadioButton|CheckBox|Switch|ToggleButton)\(""").containsMatchIn(line)) return@forEachIndexed
                if (!Regex("""\bButton\(\s*(activity|this|ctx|context)\s*\)""").containsMatchIn(line)) return@forEachIndexed

                // 从这一行往下 60 行找统一外观的来源。
                // 窗口取 60 而不是 20：LanController 里按钮在第 69 行、
                // SegmentStyle.applyRow 在第 111 行（隔着一个方法定义），
                // 窗口太窄会把"已被接管"误判成漏网。
                // 放宽窗口的代价是可能漏看"下一个按钮"——但那种情况
                // 会让另一个真正的按钮被算成合规，方向相反，仍然可接受：
                // 本锁是"抓漏网"，宁可多扫，不可漏扫。
                var found = false
                var j = i + 1
                while (j < lines.size && j <= i + 60) {
                    if (lines[j].contains("ButtonStyle.apply") ||
                        lines[j].contains("ButtonStyle.outlined") ||
                        lines[j].contains("SegmentStyle.applyRow") ||
                        // 外部方法里调用 SegmentStyle（如 LanController.applyLanSegments）
                        Regex("""apply[A-Za-z]*Segments\(\)""").containsMatchIn(lines[j]) ||
                        // 自绘背景且走了 snapCorner（agent 弹窗有自己的明暗两套 palette）
                        (lines[j].contains("cornerRadius = snapCorner(") && lines[j].contains("background ="))
                    ) { found = true; break }
                    j++
                }
                if (!found) {
                    offenders += "${f.name}:${i + 1}  ${line.trim()}"
                }
            }
        }
        assertEquals(
            "这些 Button 没有走 ButtonStyle / SegmentStyle（圆角会是 MD2 主题自带值，" +
                "与设置页按钮不一致）：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /** 分段控件与 agent 弹窗的圆角也必须落在 MD3 档位上。 */
    @Test
    fun `segment and script dialog radii follow md3`() {
        assertEquals(
            "分段控件圆角应与按钮同档（CORNER_MD）",
            UiTokens.CORNER_MD,
            SegmentStyle.RADIUS_DP,
        )
        assertEquals(
            "agent 弹窗默认圆角应与界面按钮同档（12）",
            12,
            ScriptDialogSpec.DEFAULT_RADIUS_DP,
        )
    }

    /**
     * 5.9.43 加的锁：**输入框不许用纯色方块背景**。
     *
     * 用户实机报"搜索框没有圆角"。查下来三处搜索框写的是 `setBackgroundColor(...)`
     * —— 纯色矩形，压根没有 `cornerRadius`，所以 5.9.40 的圆角扫描完全扫不到它们。
     * **和 5.9.42 的按钮是同一类漏洞：扫的是"写了圆角的地方"，漏的是"没写圆角的地方"。**
     *
     * 修法是改走 `FieldStyle.applyOutlined`（自带圆角+描边+字色），
     * 本锁防止再有人写 `setBackgroundColor` 给输入框。
     */
    @Test
    fun `edit texts use a rounded background not a flat color`() {
        /**
         * 合法豁免（逐条给理由，不做整类沉默放行）：
         * - `Color.TRANSPARENT`：笔记正文的多行输入框，**故意透明**（无边框的整页编辑区），
         *   圆角对不可见背景没有意义。
         * - `theme.primaryContainer`：对话框工坊里的**预览编辑框**（模拟 agent 看到的输入框
         *   外观），它是"被预览的对象"而不是正式输入控件，直角才是它该有的样子。
         *   注意：真正的搜索框（NetworkController / 两个 PickerDialog）**不在豁免里**，
         *   它们已改为走 FieldStyle。
         */
        val allowed = listOf("Color.TRANSPARENT", "theme.primaryContainer")
        val offenders = mutableListOf<String>()
        for (f in scanFiles()) {
            if (f.name == "FieldStyle.kt") continue // 定义自身
            val lines = read(f).lines()
            lines.forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                if (!t.contains("setBackgroundColor")) return@forEachIndexed
                // 合法豁免
                if (allowed.any { t.contains(it) }) return@forEachIndexed
                // 往上找 18 行，看这个 setBackgroundColor 是不是落在 EditText.apply{} 里
                var inEditText = false
                var j = i
                val from = if (i - 18 > 0) i - 18 else 0
                while (j >= from) {
                    if (lines[j].contains("EditText(")) { inEditText = true; break }
                    if (lines[j].contains("= View(") || lines[j].contains("fun ")) break
                    j--
                }
                if (inEditText) offenders += "${f.name}:${i + 1}  $t"
            }
        }
        assertEquals(
            "输入框不许用 setBackgroundColor（纯色方块=直角），请走 FieldStyle.applyOutlined：\n" +
                offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /**
     * 5.9.43 加的锁：**分段控件的相对边距必须清干净**。
     *
     * 用户实机报"上游代理那行的竖线比下面偏右"。真因：`relayoutMargins` 只调了
     * `setMargins()`（清绝对边距），**清不掉 `marginStart/marginEnd`**（相对边距）。
     * 三行残留写法不一（4dp / 4**像素** / 无），于是竖线横向位置各不相同。
     */
    @Test
    fun `segment rows clear relative margins too`() {
        val seg = File(srcDir, "SegmentStyle.kt")
        assertTrue("SegmentStyle.kt 不见了", seg.isFile)
        val code = read(seg)
        assertTrue(
            "relayoutMargins 必须同时清 marginStart/marginEnd，只清 setMargins 不够：" +
                "相对边距存在 mMarginFlags 里，setMargins 动不了它",
            code.contains("lp.marginStart = 0") && code.contains("lp.marginEnd = 0"),
        )
    }

        /**
     * 5.9.44 加的锁：**状态栏不带行高**（它是单行提示条，不是页面正文）。
     *
     * 历史：5.9.40 给全项目文字统一加行高时没区分"正文"和"提示条"，给状态栏也加了
     * 1.35 行高 —— 纯平白占垂直高度。5.9.41 只还原了正文、漏了盖板，两层错开，
     * 用户看到"像两行"，5.9.44 才补完。
     *
     * 5.9.45：盖板已整个删除，只剩正文一层，本锁相应收窄为"正文不带行高"。
     */
    @Test
    fun `the status bar carries no line height`() {
        val uiBuilder = File(srcDir, "UiBuilder.kt")
        assertTrue("UiBuilder.kt 不见了", uiBuilder.isFile)
        // 只看构造块本身：substringAfter 拿"文件剩余部分"会把同文件别的 textMd 算进来
        val body = read(uiBuilder).substringAfter("fun createStatusBar()").substringBefore("\n    }")
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertFalse(
            "状态栏是单行提示条，不该设行高（只会平白占高度）：\n$body",
            body.contains("textMd(") || body.contains("LINE_COMPACT"),
        )
        val ub = read(uiBuilder)
        assertTrue("正文字号应仍为 TEXT_COMPACT", ub.contains("textSize = UiTokens.TEXT_COMPACT"))
        assertTrue("正文内距应仍是 setPadding(16, 8, 16, 0)", ub.contains("setPadding(16, 8, 16, 0)"))
    }

    /**
 * 5.9.45 加的锁：**盖板（statusOverlay）必须整个删掉，不许回来**。
     *
     * 盖板是文件页长路径跑马灯的"定桩前缀"（5.9.24 加的）。删除的三个理由都在
     * StatusController.createStatusWrap 的 KDoc 里：
     * 1. 用户要求：前缀跟着正文一起滚即可；
     * 2. 它是"两层必须逐项一致"的耦合，差 1px 就露馅 —— 为此 5.9.43/5.9.44 返工两轮；
     * 3. 它带一个真 bug：显隐与状态文字各管各的，切页签无人复位 → 切走后盖板还杵着。
     */
    @Test
    fun `the status bar cover plate must not come back`() {
        // 这里**不用 scanFiles()**（那份排除白名单）：StatusController.kt 因为
        // "终端等宽字体"在白名单里，而盖板恰恰就住在那个文件里 —— 用 scanFiles()
        // 会让这条锁永远扫不到它，等于没有锁（本次就踩了：第一次注入验证没红）。
        // 字体豁免是"样式"层面的，与"盖板不许存在"是"结构"层面，两者不该共用一份名单。
        val dir = srcDir
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }?.sortedBy { it.name } ?: emptyList()
        val offenders = mutableListOf<String>()
        for (f in files) {
            val lines = read(f).lines()
                .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            lines.forEachIndexed { i, line ->
                if (line.contains("statusOverlay") || line.contains("setStatusOverlay")) {
                    offenders += "${f.name}:${i + 1}  ${line.trim()}"
                }
            }
        }
        assertEquals(
            "盖板已于 5.9.45 整个删除，不许回来：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /** 跑马灯本身必须保留 —— 删的是盖板，不是"字太长就横滚"这件事。 */
    @Test
    fun `the horizontal marquee itself stays`() {
        val marquee = File(srcDir, "StatusMarquee.kt")
        assertTrue("StatusMarquee.kt 不见了", marquee.isFile)
        val code = read(marquee)
        assertTrue("常态长路径的横向跑马灯不许被一起删掉", code.contains("fun showPersistent("))
        assertTrue("跑马灯的 MARQUEE 省略模式不许丢", code.contains("TruncateAt.MARQUEE"))
    }

    /**
     * 切页签必须复位跑马灯状态。
     *
     * 文件页把正文设成"单行 + MARQUEE + isSelected"后，这三个属性此前无人复位，
     * 切到别的页签仍带着 —— 短文字看不出，但一旦之后要纵向滚动，单行模式会把它
     * 憋成一行（StatusMarquee 的 KDoc 早就写过这个警告）。
     */
    @Test
    fun `tab switch resets the marquee state`() {
        val main = File(srcDir, "MainActivity.kt")
        assertTrue("MainActivity.kt 不见了", main.isFile)
        val code = read(main).substringAfter("internal fun refreshStatusBar()")
        assertTrue(
            "切页签时必须复位跑马灯（否则单行模式会污染别的页签）：\n$code",
            code.contains("StatusMarquee.clearPersistent("),
        )
    }

    /** 状态栏特征字必须走唯一的规则实现，且用新算出来的专用色。 */
    @Test
    fun `status accent uses the dedicated slot`() {
        val ctl = File(srcDir, "StatusController.kt")
        val accent = File(srcDir, "StatusAccent.kt")
        assertTrue("StatusController.kt 不见了", ctl.isFile)
        assertTrue("StatusAccent.kt 不见了", accent.isFile)

        val ctlCode = read(ctl)
        assertTrue(
            "状态栏上色必须走 StatusAccent（调色盘预览共用同一份规则）：\n$ctlCode",
            ctlCode.contains("StatusAccent.apply("),
        )
        assertTrue(
            "必须用 statusAccent 这个专用槽，不能拿 primaryVisible 顶替",
            ctlCode.contains("theme.statusAccent"),
        )
        // 不得绕过统一入口直接赋值（此前有 6 处，上色会漏）。
        // apply() 内部那一行是唯一合法的赋值点：它带 StatusAccent.apply，不该被算进去。
        val direct = ctlCode.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .filter { it.contains("statusView.text = ") }
            .filterNot { it.contains("StatusAccent.apply") }
        assertEquals(
            "状态栏文字只能经 apply() 赋值（此前 6 处直赋，上色会漏）：\n" + direct.joinToString("\n"),
            0, direct.size,
        )
        // 反过来确认统一入口真的存在，且它走了上色
        assertTrue(
            "统一入口 apply() 必须真的调 StatusAccent：\n$ctlCode",
            ctlCode.contains("statusView.text = StatusAccent.apply("),
        )
    }

    /**
     * 5.9.46 加的锁：**跨模块不许裸访问 `lateinit`**。
     *
     * 用户报"App 打不开、黑屏"。真因：5.9.45 新加的
     * `clearPersistent(statusController.statusView)` 直接摸了一个 `lateinit` 属性，
     * 而 `refreshStatusBar()` 会被 `onResume` 调到 —— **首次启动时 onResume 也跑**，
     * 那条路径上 `statusView` 还没建（要到 MainActivity:213 的 createStatusWrap）。
     *
     * 访问未初始化的 `lateinit` 抛 `UninitializedPropertyAccessException`
     * → onResume 炸 → **静默杀进程 → 黑屏**。
     *
     * **这个项目已经栽过两次同一个坑**：
     * - 5.9.24：`child already has a parent`，onCreate 炸 → 黑屏 + 磁贴全死
     * - 5.9.45：本次，漏了同样的保护
     *
     * ## 为什么只盯 `statusView`
     *
     * 全项目 160+ 个 `lateinit`，**绝大多数是 `private`** —— 类内自用，
     * 初始化与使用同在一条调用链上，不构成这个风险。
     * `StatusController.statusView` 是**唯一跨模块可见**的一个
     * （`lateinit var`，无 `private`），所以它是唯一的暴露面。
     *
     * 本锁断言：跨模块访问它必须走 `statusViewOrNull()`。
     */
    @Test
    fun `cross module code never touches the status view lateinit directly`() {
        // 全项目定位这处 lateinit 的持有文件
        val holder = File(srcDir, "StatusController.kt")
        assertTrue("StatusController.kt 不见了", holder.isFile)
        assertTrue(
            "statusView 应改为 private + 只经 statusViewOrNull() 暴露，避免跨模块裸访问",
            read(holder).contains("lateinit var statusView: TextView"),
        )
        assertTrue(
            "必须提供安全访问器 statusViewOrNull()",
            read(holder).contains("fun statusViewOrNull()"),
        )

        // 其他文件里不许出现裸的 `.statusView`
        val offenders = mutableListOf<String>()
        for (f in srcDir.listFiles { f -> f.name.endsWith(".kt") }?.sortedBy { it.name } ?: emptyList()) {
            if (f.name == "StatusController.kt") continue
            read(f).lines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                // 裸访问：形如 xxx.statusView 且后面不是 OrNull()
                if (Regex("""\.statusView(?!\w|OrNull)""").containsMatchIn(t)) {
                    offenders += "${f.name}:${i + 1}  $t"
                }
            }
        }
        assertEquals(
            "跨模块访问 statusView（lateinit，未初始化时访问 = 崩 = 黑屏），" +
                "必须走 statusViewOrNull()：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /** 规范层必须真的被生产代码引用，而不是摆着好看。 */
    @Test
    fun `the md3 helpers are actually used in production code`() {
        val all = scanFiles().joinToString("\n") { read(it) }
        for (fn in listOf("setPaddingMd", "roundedBg", "textMd")) {
            assertTrue("规范函数 $fn 一次都没被用上，规则是摆设", all.contains(fn))
        }
    }

    /**
     * 5.9.40 踩坑后加的锁：**单位混用会静默改掉视觉**。
     *
     * `snapCorner` 收 dp，`Context.dpf` 出 px。项目里早已有 `dpF()`（出 px）。
     * 迁移时把 `cornerRadius = dpF(16)` 改成 `cornerRadius = snapCorner(...)`，
     * **忘记乘 density** —— 圆角直接缩到 1/3，编译器不会报错，测试也不会红。
     *
     * 这条锁只抓"明显忘了转 px"的写法：
     * `snapCorner(...)` 后面既没有 `* d` 也没有 `dpf(...)` 包着。
     */
    @Test
    fun `snapCorner results are always converted to pixels`() {
        val offenders = mutableListOf<String>()
        for (f in scanFiles()) {
            read(f).lines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                if (!t.contains("snapCorner(")) return@forEachIndexed
                // 定义处与规范自身不管
                if (f.name == "UiDims.kt") return@forEachIndexed
                // 已经过了 dp→px 的关（乘了 d/density，或用 dpf 包了）
                val converted = Regex("""snapCorner\([^)]*\)\s*\*\s*[A-Za-z_]""").containsMatchIn(t) ||
                    t.contains("dpf(snapCorner(")
                if (!converted) offenders += "${f.name}:${i + 1}  $t"
            }
        }
        assertEquals(
            "snapCorner 出来的是 dp，赋给 cornerRadius 前必须转 px（乘 density 或用 dpf 包）：\n" +
                offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /** 同理：`setPaddingMd` 的参数必须是纯 dp 数字，不能塞进 px 表达式。 */
    @Test
    fun `setPaddingMd takes plain dp numbers only`() {
        val offenders = mutableListOf<String>()
        for (f in scanFiles()) {
            read(f).lines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                val m = Regex("""setPaddingMd\(([^)]*)\)""").find(t) ?: return@forEachIndexed
                val args = m.groupValues[1]
                if (Regex("""(px\(|dp\(|\*|density|\bd\b)""").containsMatchIn(args)) {
                    offenders += "${f.name}:${i + 1}  $t"
                }
            }
        }
        assertEquals(
            "setPaddingMd 的参数必须是纯 dp 数字：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }

    /**
     * 5.9.40 踩坑后加的锁：**空 lambda 会静默吃掉功能**。
     *
     * 这次批量收敛设置页按钮时，脚本的 body 提取正则失配，
     * 6 个按钮的 `setOnClickListener { ... }` 被替换成了**空的 lambda**。
     * 结果是：**编译通过、测试全绿，但按钮点了没反应**。
     *
     * 这类问题编译器和测试都抓不到 —— 只能靠人读，或者靠这条锁。
     */
    @Test
    fun `no action button was left with an empty click handler`() {
        val f = File(srcDir, "SettingsUiController.kt")
        assertTrue("设置页不见了", f.isFile)
        val lines = read(f).lines()
        val offenders = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (line.contains("addView(actionButton(")) {
                // 到 .also { 之前的那几行就是点击逻辑，空的即失效
                var j = i + 1
                var bodyLines = 0
                while (j < lines.size && !lines[j].contains(".also {")) {
                    if (lines[j].isNotBlank()) bodyLines++
                    j++
                }
                if (bodyLines == 0) offenders += "${i + 1}: ${line.trim()}"
            }
        }
        assertEquals(
            "这些按钮的点击逻辑是空的（点了没反应）：\n" + offenders.joinToString("\n"),
            0, offenders.size,
        )
    }
}
