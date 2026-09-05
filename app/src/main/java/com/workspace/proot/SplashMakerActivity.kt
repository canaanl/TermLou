package com.workspace.proot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 启动工坊：96×40 洞洞板绘制开屏点阵（1 像素拆 4，屏尺寸不变仅密度翻倍）。
 * 触摸拖动画线（一次抬起=一笔），越界时网格外沿白圈呼吸高亮（采样态禁用）。
 * 品字四键：回退 / 导入照片 / 预览 / 保存；空板 = 默认 TERMLOU。
 */
class SplashMakerActivity : AppCompatActivity() {

    private lateinit var board: SplashBoard
    private val cells = mutableSetOf<Pair<Int, Int>>()
    private val undoStack = mutableListOf<MutableList<Pair<Int, Int>>>()
    private var saving = false
    private var photoBitmap: Bitmap? = null
    private var isPhotoSampling = false
    private var overlayCells: Set<Pair<Int, Int>>? = null
    private lateinit var photoView: ImageView
    private lateinit var boardContainer: FrameLayout
    private lateinit var styleGroup: RadioGroup
    private lateinit var styleBar: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private var selectedStyle = 0
    private var invertEnabled = false
    private var photoOffsetX = 0f
    private var photoOffsetY = 0f
    private var photoScale = 1f

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val stream = contentResolver.openInputStream(uri) ?: return@runCatching
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bmp != null) enterPhotoSampling(bmp)
        }
    }

    private val storeDir: File
        get() = TermlouDirs.base(this)
    private val splashFile: File
        get() = File(storeDir, "splash.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCustom()
        val theme = ThemeColors.default()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        // 标题栏
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((16 * density()).toInt(), (12 * density()).toInt(), (16 * density()).toInt(), (12 * density()).toInt())
            addView(TextView(this@SplashMakerActivity).apply {
                text = getString(R.string.sm_title)
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = UiTokens.TEXT_TITLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        // 洞洞板 + 照片取景框
        boardContainer = FrameLayout(this)
        board = SplashBoard(this, cells)
        photoView = ImageView(this).apply {
            alpha = 0.55f
            visibility = View.GONE
            scaleType = ImageView.ScaleType.MATRIX
        }
        boardContainer.addView(photoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        boardContainer.addView(board, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(boardContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })
        styleGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@SplashMakerActivity).apply { text = getString(R.string.sm_outline); id = 1001; isChecked = true })
            addView(RadioButton(this@SplashMakerActivity).apply { text = getString(R.string.sm_block); id = 1002 })
            addView(RadioButton(this@SplashMakerActivity).apply { text = getString(R.string.sm_mixed); id = 1003 })
            setOnCheckedChangeListener { _, checkedId ->
                selectedStyle = when (checkedId) {
                    1002 -> 1
                    1003 -> 2
                    else -> 0
                }
                if (isPhotoSampling) generatePhotoCells()
            }
        }
        // 取景态工具栏：风格单选 + 反选开关，两者同步显隐
        styleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12 * density()).toInt(), (8 * density()).toInt(), (12 * density()).toInt(), 0)
            addView(styleGroup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SplashMakerActivity).apply {
                text = getString(R.string.sm_invert)
                setTextColor(Color.WHITE)
            })
            addView(Switch(this@SplashMakerActivity).apply {
                setOnCheckedChangeListener { _, c ->
                    invertEnabled = c
                    if (isPhotoSampling) generatePhotoCells()
                }
            })
        }
        root.addView(styleBar)
        // 品字四键（底栏 surfaceVariant，与弹窗工坊一致）
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((12 * density()).toInt(), (8 * density()).toInt(), (12 * density()).toInt(), (12 * density()).toInt())
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val ub = makeBtn(getString(R.string.sm_undo)) { undo() }
            ub.setOnLongClickListener { clearAll(); true }
            addView(ub)
            addView(makeBtn(getString(R.string.sm_import)) {
                if (isPhotoSampling) cancelPhotoSampling() else pickPhotoLauncher.launch("image/*")
            })
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density()).toInt(), 0, 0)
            addView(makeBtn(getString(R.string.sm_preview), true) { preview() })
            addView(makeBtn(getString(R.string.save), true) { save() })
        }
        bottomBar.addView(row1)
        bottomBar.addView(row2)
        root.addView(bottomBar)
        rootFrame = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(rootFrame)
    }

    private fun makeBtn(text: String, primary: Boolean = false, onClick: () -> Unit): View =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = UiTokens.TEXT_BODY
            setPadding((16 * density()).toInt(), (6 * density()).toInt(), (16 * density()).toInt(), (6 * density()).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density()).toInt()
                marginEnd = (4 * density()).toInt()
            }
            ButtonStyle.apply(this, if (primary) ThemeColors.default().primary else ThemeColors.default().outline)
            setOnClickListener {
                if (saving) return@setOnClickListener
                onClick()
            }
        }

    private fun density(): Float = resources.displayMetrics.density

    private fun loadCustom() {
        cells.clear()
        runCatching {
            val f = splashFile
            if (f.exists()) {
                val obj = MiniJson.parse(f.readText())
                val arr = obj.optArr("cells") ?: return
                // 旧版 48×20 数据：按 2×2 块放大为 96×40（仅当列减半才视为旧档，避免 96×40 被误判）
                val legacy = obj.optInt("cols", SplashTokens.COLS) <= SplashTokens.COLS / 2
                for (i in 0 until arr.length()) {
                    val o = arr.getObj(i) ?: continue
                    val r = o.optInt("r", -1)
                    val c = o.optInt("c", -1)
                    if (legacy) {
                        if (r in 0 until SplashTokens.ROWS / 2 && c in 0 until SplashTokens.COLS / 2) {
                            val rr = r * 2
                            val cc = c * 2
                            cells.add(rr to cc)
                            cells.add(rr + 1 to cc)
                            cells.add(rr to cc + 1)
                            cells.add(rr + 1 to cc + 1)
                        }
                    } else {
                        if (r in 0 until SplashTokens.ROWS && c in 0 until SplashTokens.COLS) cells.add(r to c)
                    }
                }
            }
        }
    }

    private fun enterPhotoSampling(bmp: Bitmap) {
        photoBitmap?.recycle()
        photoBitmap = bmp
        renderSrc?.recycle()
        renderSrc = buildRenderSrc(bmp)
        // 新图：强制重建采样缓存
        cacheScale = -1f
        photoView.setImageBitmap(bmp)
        // 初始缩放：覆盖画板 1.3 倍，居中
        board.post {
            val bw = board.width
            val bh = board.height
            if (bw > 0 && bh > 0) {
                val s = maxOf(bw * 1.3f / bmp.width, bh * 1.3f / bmp.height)
                photoScale = s
                photoView.scaleX = s
                photoView.scaleY = s
                photoOffsetX = 0f
                photoOffsetY = 0f
                photoView.translationX = 0f
                photoView.translationY = 0f
            }
            photoView.visibility = View.VISIBLE
            styleBar.visibility = View.VISIBLE
            isPhotoSampling = true
            generatePhotoCells()
        }
    }

    /** 退出采样态并丢弃 overlay，回到手绘板。 */
    private fun cancelPhotoSampling() {
        overlayCells = null
        photoView.visibility = View.GONE
        styleBar.visibility = View.GONE
        isPhotoSampling = false
        photoBitmap?.recycle()
        photoBitmap = null
        renderSrc?.recycle()
        renderSrc = null
        cacheScale = -1f
        board.invalidate()
    }

    /** 把当前 overlay 版画并入 cells（保存时调用）。 */
    private fun commitPhotoSampling() {
        if (overlayCells != null) {
            cells.clear()
            cells.addAll(overlayCells!!)
            undoStack.clear()
            undoStack.add(cells.toMutableList())
        }
    }

    /** 取景采样：轮廓/块面/混合三风格映射为 overlay 版画（支持反选）。 */
    private fun generatePhotoCells() {
        val bmp = photoBitmap ?: return
        val w = board.width
        val h = board.height
        if (w == 0 || h == 0) return
        val rows = SplashTokens.ROWS
        val cols = SplashTokens.COLS
        val ps = SplashTokens.pixelSize(w.toFloat())
        val maxH = h * 0.82f
        val pixelSize = if (ps * rows > maxH) maxH / rows else ps
        val totalW = pixelSize * cols
        val totalH = pixelSize * rows
        val offsetX = (w - totalW) / 2f
        val offsetY = (h - totalH) / 2f
        val photoW = bmp.width * photoScale
        val photoH = bmp.height * photoScale
        val photoLeft = w / 2f - photoW / 2f + photoOffsetX
        val photoTop = h / 2f - photoH / 2f + photoOffsetY
        // 照片显示矩形（保持原比例，绝不拉伸）
        val pL = photoLeft
        val pT = photoTop
        val pR = photoLeft + photoW
        val pB = photoTop + photoH
        // 缓冲分辨率封顶 ≤2×网格：输出本就 cols×rows，2× 余量足够，限制边缘/高斯/排序规模
        val bCols = minOf(2 * cols, maxOf(1, kotlin.math.ceil(photoW / pixelSize).toInt()))
        val bRows = minOf(2 * rows, maxOf(1, kotlin.math.ceil(photoH / pixelSize).toInt()))
        // 拖动只改 offset：key 不变 → 跳过降采样/边缘/Otsu，只重跑映射循环
        if (photoScale != cacheScale || selectedStyle != cacheStyle || bCols != cacheBC || bRows != cacheBR) {
            val src = renderSrc ?: bmp
            // 从缓存工作位图分级降采样到 bCols×bRows（逐次减半 + FILTER，去混叠/摩尔纹）
            val scaled = downscaleProgressive(src, bCols, bRows)
            val gray = IntArray(bCols * bRows)
            for (r in 0 until bRows) {
                for (c in 0 until bCols) {
                    val p = scaled.getPixel(c, r)
                    gray[r * bCols + c] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
                }
            }
            cachedGray = gray
            if (selectedStyle == 0 || selectedStyle == 2) {
                // 简单版边缘提取（Sobel + 固定分位双阈值）
                val edge = extractEdges(gray, bRows, bCols)
                // 闭运算补桥：连通边缘断点 → 线条连续
                cachedEdge = closeMask(edge, bRows, bCols)
            } else {
                cachedEdge = BooleanArray(0)
            }
            cachedOtsu = if (selectedStyle == 1 || selectedStyle == 2) otsu(gray, bRows, bCols) else 0
            cacheScale = photoScale
            cacheStyle = selectedStyle
            cacheBC = bCols
            cacheBR = bRows
        }
        val gray = cachedGray
        val edgeMask = cachedEdge
        val newSet = mutableSetOf<Pair<Int, Int>>()
        when (selectedStyle) {
            1 -> { // 块面：Otsu 自适应
                val th = cachedOtsu
                for (r in 0 until rows) for (c in 0 until cols) {
                    val gx = offsetX + (c + 0.5f) * pixelSize
                    val gy = offsetY + (r + 0.5f) * pixelSize
                    val inPhoto = gx >= pL && gx < pR && gy >= pT && gy < pB
                    var lit = false
                    if (inPhoto) {
                        val bx = ((gx - pL) / photoW * bCols).toInt().coerceIn(0, bCols - 1)
                        val by = ((gy - pT) / photoH * bRows).toInt().coerceIn(0, bRows - 1)
                        lit = gray[by * bCols + bx] < th
                    }
                    if (lit != invertEnabled) newSet.add(r to c)
                }
            }
            0 -> { // 轮廓：细线化连续边缘
                for (r in 0 until rows) for (c in 0 until cols) {
                    val gx = offsetX + (c + 0.5f) * pixelSize
                    val gy = offsetY + (r + 0.5f) * pixelSize
                    val inPhoto = gx >= pL && gx < pR && gy >= pT && gy < pB
                    var lit = false
                    if (inPhoto) {
                        val bx = ((gx - pL) / photoW * bCols).toInt().coerceIn(0, bCols - 1)
                        val by = ((gy - pT) / photoH * bRows).toInt().coerceIn(0, bRows - 1)
                        lit = edgeMask[by * bCols + bx]
                    }
                    if (lit != invertEnabled) newSet.add(r to c)
                }
            }
            else -> { // 混合：块面(Otsu) 与 细线化边缘 相或
                val th = cachedOtsu
                for (r in 0 until rows) for (c in 0 until cols) {
                    val gx = offsetX + (c + 0.5f) * pixelSize
                    val gy = offsetY + (r + 0.5f) * pixelSize
                    val inPhoto = gx >= pL && gx < pR && gy >= pT && gy < pB
                    var lit = false
                    if (inPhoto) {
                        val bx = ((gx - pL) / photoW * bCols).toInt().coerceIn(0, bCols - 1)
                        val by = ((gy - pT) / photoH * bRows).toInt().coerceIn(0, bRows - 1)
                        val i = by * bCols + bx
                        lit = gray[i] < th || edgeMask[i]
                    }
                    if (lit != invertEnabled) newSet.add(r to c)
                }
            }
        }
        overlayCells = newSet
        board.invalidate()
    }

    /** 缓存工作位图：导入时一次性把原图降到宽≤2*COLS，采样缓冲只从小图降采样，拖动/缩放不再碰原图。 */
    private var renderSrc: Bitmap? = null

    /** 拖动缓存：仅 offset 变化时 key 不变 → 跳过降采样/边缘/Otsu，只重跑映射循环。 */
    private var cacheScale = -1f
    private var cacheStyle = -1
    private var cacheBC = -1
    private var cacheBR = -1
    private var cachedOtsu = 0
    private var cachedGray = IntArray(0)
    private var cachedEdge = BooleanArray(0)

    /** 一次性把原图降到宽≤2*COLS（保持比例），回收中间层级，仅返回最终小图。 */
    private fun buildRenderSrc(bmp: Bitmap): Bitmap {
        val capW = 2 * SplashTokens.COLS
        if (bmp.width <= capW) return bmp
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var cur = bmp
        var cw = bmp.width
        var ch = bmp.height
        while (cw > capW) {
            val nw = maxOf(capW, cw / 2)
            val nh = maxOf(1, ch * nw / cw)
            val next = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, android.graphics.Rect(0, 0, cw, ch), android.graphics.Rect(0, 0, nw, nh), paint)
            if (cur !== bmp) cur.recycle()
            cur = next
            cw = nw
            ch = nh
        }
        return cur
    }

    /** 可复用的照片降采样灰度缓冲区（避免每次拖动/缩放反复分配）。 */
    private var reuseBuffer: Bitmap? = null

    /** 分级降采样中间缓冲池（按减半层级索引，复用各层级尺寸稳定的位图）。 */
    private val reuseStages = mutableListOf<Bitmap?>()

    /** 取第 level 层级的中间缓冲：尺寸匹配则复用，否则回收该层级旧图后新建（不回收其他层级/当前源图）。 */
    private fun stageBitmap(level: Int, w: Int, h: Int): Bitmap {
        while (reuseStages.size <= level) reuseStages.add(null)
        val b = reuseStages[level]
        return if (b != null && b.width == w && b.height == h) b
            else {
                b?.recycle()
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { reuseStages[level] = it }
            }
    }

    /** 逐次减半降采样，每步 FILTER（面积平均），消除单步大降采样造成的混叠/摩尔纹。 */
    private fun downscaleProgressive(src: Bitmap, tw: Int, th: Int): Bitmap {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var cur = src
        var cw = src.width
        var ch = src.height
        var level = 0
        while (cw > tw * 2 || ch > th * 2) {
            val nw = maxOf(tw, cw / 2)
            val nh = maxOf(th, ch / 2)
            // 写入下一层级；回收只发生在该层级旧图，永不回收当前 cur（上一层级或原图）
            val s = stageBitmap(level, nw, nh)
            Canvas(s).drawBitmap(cur, android.graphics.Rect(0, 0, cw, ch), android.graphics.Rect(0, 0, nw, nh), paint)
            cur = s
            cw = nw
            ch = nh
            level++
        }
        val buf = reuseBuffer
        val out = if (buf != null && buf.width == tw && buf.height == th) buf
            else {
                reuseBuffer?.recycle()
                Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { reuseBuffer = it }
            }
        Canvas(out).drawBitmap(cur, android.graphics.Rect(0, 0, cw, ch), android.graphics.Rect(0, 0, tw, th), paint)
        return out
    }

    /** 5×5 可分离高斯平滑（两趟 1D），压制纹理噪声。 */
    private fun gaussianBlur(f: FloatArray, br: Int, bc: Int): FloatArray {
        val k = floatArrayOf(0.0545f, 0.2442f, 0.4026f, 0.2442f, 0.0545f)
        val tmp = FloatArray(br * bc)
        val out = FloatArray(br * bc)
        for (r in 0 until br) {
            for (c in 0 until bc) {
                var s = 0f
                for (kk in 0 until 5) {
                    val cc = (c + kk - 2).coerceIn(0, bc - 1)
                    s += k[kk] * f[r * bc + cc]
                }
                tmp[r * bc + c] = s
            }
        }
        for (r in 0 until br) {
            for (c in 0 until bc) {
                var s = 0f
                for (kk in 0 until 5) {
                    val rr = (r + kk - 2).coerceIn(0, br - 1)
                    s += k[kk] * tmp[rr * bc + c]
                }
                out[r * bc + c] = s
            }
        }
        return out
    }

    /** 形态学闭运算（先膨胀再腐蚀 1 格），连通边缘上的 1–2 格断点，线条更连续。 */
    private fun closeMask(mask: BooleanArray, br: Int, bc: Int): BooleanArray {
        val dil = BooleanArray(br * bc)
        for (r in 0 until br) for (c in 0 until bc) {
            val r0 = if (r > 0) r - 1 else r
            val r1 = if (r < br - 1) r + 1 else r
            val c0 = if (c > 0) c - 1 else c
            val c1 = if (c < bc - 1) c + 1 else c
            var on = false
            outer@ for (rr in r0..r1) for (cc in c0..c1) if (mask[rr * bc + cc]) { on = true; break@outer }
            dil[r * bc + c] = on
        }
        val ero = BooleanArray(br * bc)
        for (r in 0 until br) for (c in 0 until bc) {
            val r0 = if (r > 0) r - 1 else r
            val r1 = if (r < br - 1) r + 1 else r
            val c0 = if (c > 0) c - 1 else c
            val c1 = if (c < bc - 1) c + 1 else c
            var all = true
            outer@ for (rr in r0..r1) for (cc in c0..c1) if (!dil[rr * bc + cc]) { all = false; break@outer }
            ero[r * bc + c] = all
        }
        return ero
    }


    /** 完整边缘提取：高斯平滑 → 局部对比度归一化(照明不变) → 真 Sobel → 非极大值抑制(细线) → 双阈值滞后(接轮廓/杀噪) → 去孤立斑。 */
    private fun extractEdges(gray: IntArray, br: Int, bc: Int): BooleanArray {
        val n = br * bc
        val f = FloatArray(n)
        for (i in 0 until n) f[i] = gray[i] / 255f
        // 局部均值 μ 与 E[x²]，σ² = E[x²]-μ²
        val mu = gaussianBlur(f, br, bc)
        val sq = FloatArray(n)
        for (i in 0 until n) sq[i] = f[i] * f[i]
        val mu2 = gaussianBlur(sq, br, bc)
        val norm = FloatArray(n)
        for (i in 0 until n) {
            val m = mu[i]
            var v = mu2[i] - m * m
            if (v < 0f) v = 0f
            val s = kotlin.math.sqrt(v) + 1e-3f
            norm[i] = ((f[i] - m) / s).coerceIn(-4f, 4f)
        }
        // 真 Sobel 幅值与梯度方向
        val mag = FloatArray(n)
        val gxv = FloatArray(n)
        val gyv = FloatArray(n)
        for (r in 1 until br - 1) {
            for (c in 1 until bc - 1) {
                val i = r * bc + c
                val gx = (norm[i - bc + 1] + 2f * norm[i + 1] + norm[i + bc + 1]) -
                    (norm[i - bc - 1] + 2f * norm[i - 1] + norm[i + bc - 1])
                val gy = (norm[i + bc - 1] + 2f * norm[i + bc] + norm[i + bc + 1]) -
                    (norm[i - bc - 1] + 2f * norm[i - bc] + norm[i - bc + 1])
                gxv[i] = gx
                gyv[i] = gy
                mag[i] = kotlin.math.sqrt(gx * gx + gy * gy)
            }
        }
        // 非极大值抑制：沿梯度方向保留局部最大 → 细线
        val nms = FloatArray(n)
        for (r in 1 until br - 1) {
            for (c in 1 until bc - 1) {
                val i = r * bc + c
                if (mag[i] <= 0f) continue
                val deg = Math.toDegrees(Math.atan2(gyv[i].toDouble(), gxv[i].toDouble())).toFloat()
                val ad = if (deg < 0f) deg + 180f else deg
                val n1: Int
                val n2: Int
                when {
                    ad < 22.5f || ad >= 157.5f -> { n1 = i - 1; n2 = i + 1 }
                    ad < 67.5f -> { n1 = i - bc - 1; n2 = i + bc + 1 }
                    ad < 112.5f -> { n1 = i - bc; n2 = i + bc }
                    else -> { n1 = i - bc + 1; n2 = i + bc - 1 }
                }
                if (mag[i] >= mag[n1] && mag[i] > mag[n2]) nms[i] = mag[i]
            }
        }
        // 双阈值滞后：强边为种子，弱边仅在 8 邻接强边时保留（自适应阈值）
        val vals = FloatArray(n)
        var vc = 0
        for (i in 0 until n) if (nms[i] > 0f) { vals[vc] = nms[i]; vc++ }
        if (vc == 0) return BooleanArray(n)
        vals.sort(0, vc)
        val hi = vals[((vc - 1) * 0.92).toInt()]
        val lo = hi * 0.4f
        val edge = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        for (i in 0 until n) if (nms[i] >= hi) { edge[i] = true; stack[sp++] = i }
        while (sp > 0) {
            val p = stack[--sp]
            val r = p / bc
            val c = p % bc
            val r0 = if (r > 0) r - 1 else r
            val r1 = if (r < br - 1) r + 1 else r
            val c0 = if (c > 0) c - 1 else c
            val c1 = if (c < bc - 1) c + 1 else c
            for (rr in r0..r1) for (cc in c0..c1) {
                val q = rr * bc + cc
                if (!edge[q] && nms[q] >= lo) { edge[q] = true; stack[sp++] = q }
            }
        }
        // 去孤立斑：剔除 <4 格连通域
        val seen = BooleanArray(n)
        val comp = IntArray(n)
        for (i in 0 until n) {
            if (!edge[i] || seen[i]) continue
            var cp = 0
            var csp = 0
            comp[csp++] = i
            seen[i] = true
            while (csp > 0) {
                val p = comp[--csp]
                cp++
                val r = p / bc
                val c = p % bc
                val r0 = if (r > 0) r - 1 else r
                val r1 = if (r < br - 1) r + 1 else r
                val c0 = if (c > 0) c - 1 else c
                val c1 = if (c < bc - 1) c + 1 else c
                for (rr in r0..r1) for (cc in c0..c1) {
                    val q = rr * bc + cc
                    if (edge[q] && !seen[q]) { seen[q] = true; comp[csp++] = q }
                }
            }
            if (cp < 4) {
                for (kk in 0 until cp) edge[comp[kk]] = false
            }
        }
        return edge
    }

    private fun otsu(gray: IntArray, rows: Int, cols: Int): Int {
        val hist = IntArray(256)
        for (i in gray.indices) hist[gray[i].coerceIn(0, 255)]++
        val total = rows * cols
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i]
        var bgSum = 0.0
        var bgW = 0
        var bestTh = 127
        var bestVar = -1.0
        for (t in 0 until 256) {
            bgW += hist[t]
            if (bgW == 0) continue
            val fgW = total - bgW
            if (fgW == 0) break
            bgSum += t * hist[t]
            val bgMean = bgSum / bgW
            val fgMean = (sum - bgSum) / fgW
            val v = bgW.toDouble() * fgW.toDouble() * (bgMean - fgMean) * (bgMean - fgMean)
            if (v > bestVar) {
                bestVar = v
                bestTh = t
            }
        }
        return bestTh
    }

    private fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        for (p in last) cells.remove(p)
        board.invalidate()
    }

    private fun clearAll() {
        // 有照片时：一并清除导入的照片及生成的轮廓，回到手绘板
        if (isPhotoSampling) cancelPhotoSampling()
        cells.clear()
        undoStack.clear()
        board.invalidate()
    }

    private fun preview() {
        val sampling = isPhotoSampling && overlayCells != null
        val cellsData = if (sampling) overlayCells!!.map { it }
        else if (cells.isEmpty()) SplashTokens.defaultCells()
        else cells.map { it }
        // 全屏黑底仅动画（无进度条/文字），叠加在现有界面之上，结束即移除，不重建视图
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val sv = SplashView(this, cellsData, showProgress = false)
        overlay.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sv.postDelayed({
            sv.dismiss {
                runOnUiThread {
                    rootFrame.removeView(overlay)
                }
            }
        }, 2400)
    }

    private fun save() {
        saving = true
        try {
            if (isPhotoSampling) commitPhotoSampling()
            storeDir.mkdirs()
            val obj = MiniJson.Obj()
            obj.put("rows", SplashTokens.ROWS).put("cols", SplashTokens.COLS)
            val arr = MiniJson.Arr()
            val sorted = cells.sortedWith(compareBy({ it.first }, { it.second }))
            for ((r, c) in sorted) arr.put(MiniJson.Obj().put("r", r).put("c", c))
            obj.put("cells", arr)
            val tmp = File(splashFile.parentFile, "splash.json.tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(splashFile)
            finish()
        } catch (e: Exception) {
            saving = false
        }
    }

    /** 洞洞板：96×40 网格，未绘制均一暗色；有像素以品牌渐变显示；越界白圈呼吸（采样态禁用）。 */
    inner class SplashBoard(context: Context, private val data: MutableSet<Pair<Int, Int>>) : View(context) {

        private val d = resources.displayMetrics.density
        private var pixelSize = 0f
        private val rows = SplashTokens.ROWS
        private val cols = SplashTokens.COLS
        private var totalW = 0f
        private var totalH = 0f
        private var offsetX = 0f
        private var offsetY = 0f
        private var backdropShader: RadialGradient? = null

        private fun ensureMetrics(w: Int, h: Int) {
            val ps = SplashTokens.pixelSize(w.toFloat())
            val maxH = h * 0.82f
            pixelSize = if (ps * rows > maxH) maxH / rows else ps
            totalW = pixelSize * cols
            totalH = pixelSize * rows
            offsetX = (w - totalW) / 2f
            offsetY = (h - totalH) / 2f
            backdropShader = RadialGradient(
                offsetX + totalW / 2f, offsetY + totalH / 2f, totalW * 1.1f,
                intArrayOf(UiTokens.backdropGlow, 0x00000000), null, Shader.TileMode.CLAMP
            )
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141414.toInt() }
        private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderThinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            alpha = 30
            strokeWidth = 1f * d
        }
        private val borderGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        private val bgPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)

        private var fingerInside = true
        private var wasInside = true
        private var exceededSides = mutableSetOf<String>()
        private var glowActive = false
        private val breatheRunnable = object : Runnable {
            override fun run() {
                if (fingerInside) return
                invalidate()
                postDelayed(this, 16)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            ensureMetrics(w, h)
        }

        init {
            setBackgroundColor(0xFF141414.toInt())
        }

        private var sampleDownX = 0f
        private var sampleDownY = 0f
        private var sampleDragging = false
        private var zoomMode = false
        private var pinchStartDist = 1f
        private var pinchStartScale = 1f
        private var MIN_SCALE = 0.4f
        private var MAX_SCALE = 5f

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 照片取景态：单指拖动取景框 + 双指缩放，不落笔画、不触发越界辉光
            if (isPhotoSampling) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sampleDownX = event.rawX
                        sampleDownY = event.rawY
                        sampleDragging = true
                        zoomMode = false
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount >= 2) {
                            zoomMode = true
                            sampleDragging = false
                            pinchStartDist = pinchDist(event)
                            pinchStartScale = photoScale
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (zoomMode && event.pointerCount >= 2) {
                            val dist = pinchDist(event)
                            if (dist > 1f) {
                                val ns = (pinchStartScale * dist / pinchStartDist).coerceIn(MIN_SCALE, MAX_SCALE)
                                if (ns != photoScale) {
                                    photoScale = ns
                                    photoView.scaleX = ns
                                    photoView.scaleY = ns
                                    generatePhotoCells()
                                }
                            }
                            return true
                        }
                        if (!sampleDragging) return true
                        photoOffsetX += event.rawX - sampleDownX
                        photoOffsetY += event.rawY - sampleDownY
                        sampleDownX = event.rawX
                        sampleDownY = event.rawY
                        photoView.translationX = photoOffsetX
                        photoView.translationY = photoOffsetY
                        generatePhotoCells()
                        return true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.pointerCount == 2) {
                            zoomMode = false
                            val idx = event.actionIndex
                            val other = if (idx == 0) 1 else 0
                            sampleDownX = event.getRawX(other)
                            sampleDownY = event.getRawY(other)
                            sampleDragging = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        sampleDragging = false
                        zoomMode = false
                        return true
                    }
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    undoStack.add(mutableListOf())
                    // 每次手势从零开始：起始点在画板内由 paintAt 置 true，起始在外则不亮辉光
                    fingerInside = false
                    wasInside = false
                    exceededSides.clear()
                    glowActive = false
                    removeCallbacks(breatheRunnable)
                    paintAt(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    paintAt(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    // 手指离开立即熄灭辉光
                    if (!fingerInside) {
                        fingerInside = true
                        wasInside = true
                        exceededSides.clear()
                        glowActive = false
                        removeCallbacks(breatheRunnable)
                        invalidate()
                    }
                    glowActive = false
                    wasInside = true
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun pinchDist(event: MotionEvent): Float {
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private fun paintAt(x: Float, y: Float) {
            val col = ((x - offsetX) / pixelSize).toInt()
            val row = ((y - offsetY) / pixelSize).toInt()
            val inside = row in 0 until rows && col in 0 until cols
            if (inside) {
                val p = row to col
                if (data.add(p)) {
                    undoStack.lastOrNull()?.add(p)
                }
                if (!fingerInside) {
                    fingerInside = true
                    wasInside = true
                    exceededSides.clear()
                    glowActive = false
                    removeCallbacks(breatheRunnable)
                } else {
                    wasInside = true
                }
            } else {
                val sides = mutableSetOf<String>()
                if (col < 0) sides.add("left")
                if (col >= cols) sides.add("right")
                if (row < 0) sides.add("top")
                if (row >= rows) sides.add("bottom")
                // 仅当从内部划到外部才亮
                if (wasInside) {
                    exceededSides = sides
                    fingerInside = false
                    wasInside = false
                    glowActive = true
                    postDelayed(breatheRunnable, 16)
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            ensureMetrics(canvas.width, canvas.height)
            // 背景：采样态透明以透出照片，否则均一暗色 + 底衬
            if (!isPhotoSampling) {
                val shader = backdropShader
                if (shader != null) {
                    bgPaint2.shader = shader
                    canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint2)
                    bgPaint2.shader = null
                } else {
                    canvas.drawColor(0xFF141414.toInt())
                }
            } else {
                canvas.drawColor(Color.TRANSPARENT)
            }
            // 已点亮像素：采样态显示 overlay 版画，否则显示手绘
            val drawData = if (isPhotoSampling && overlayCells != null) overlayCells!! else data
            val radius = pixelSize * SplashTokens.PIXEL_RADIUS_FACTOR
            for ((r, c) in drawData) {
                solidPaint.color = SplashTokens.cellColor(c)
                val l = offsetX + (c + 0.5f) * pixelSize - pixelSize * 0.5f
                val t = offsetY + (r + 0.5f) * pixelSize - pixelSize * 0.5f
                canvas.drawRoundRect(l, t, l + pixelSize, t + pixelSize, radius, radius, solidPaint)
            }
            // 常态细边框
            canvas.drawRoundRect(
                offsetX - 1 * d, offsetY - 1 * d,
                offsetX + totalW + 1 * d, offsetY + totalH + 1 * d,
                6 * d, 6 * d, borderThinPaint
            )
            // 越界：单边白辉光呼吸（采样态禁用，取景框仅取景不触发）
            if (!isPhotoSampling && glowActive && !fingerInside && exceededSides.isNotEmpty()) {
                val breathe = (1f + 0.5f * kotlin.math.sin(System.currentTimeMillis() * 0.004f)) / 1.5f
                borderGlowPaint.alpha = (200 * breathe).toInt()
                borderGlowPaint.strokeWidth = 3f * d
                borderGlowPaint.setShadowLayer(12f * d * breathe, 0f, 0f, Color.WHITE)
                for (side in exceededSides) {
                    when (side) {
                        "left" -> canvas.drawLine(offsetX, offsetY, offsetX, offsetY + totalH, borderGlowPaint)
                        "right" -> canvas.drawLine(offsetX + totalW, offsetY, offsetX + totalW, offsetY + totalH, borderGlowPaint)
                        "top" -> canvas.drawLine(offsetX, offsetY, offsetX + totalW, offsetY, borderGlowPaint)
                        "bottom" -> canvas.drawLine(offsetX, offsetY + totalH, offsetX + totalW, offsetY + totalH, borderGlowPaint)
                    }
                }
                borderGlowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }
}
