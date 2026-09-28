package com.workspace.proot

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 抽屉预取缓存：磁贴 onStartListening（下拉面板展开时）填充收藏列表与已解码图标，
 * 点击后经跳板延迟弹出时零 binder/零解码——把重活从「弹出瞬间」挪到「面板展开时」，
 * 消除抽屉弹出卡顿。进程级共享（磁贴服务填充 → 跳板 Activity 消费）。
 */
object TileDrawerCache {
    @Volatile
    var apps: List<FavoriteApp> = emptyList()
        private set

    @Volatile
    var icons: Map<String, Drawable> = emptyMap()
        private set

    fun prefetch(ctx: Context) {
        val list = SettingsManager(ctx.getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE))
            .loadFavoriteApps()
        icons = if (list.size > 1) {
            val pm = ctx.packageManager
            buildMap {
                for (a in list) {
                    runCatching { pm.getApplicationIcon(a.pkg) }.getOrNull()?.let { put(a.pkg, it) }
                }
            }
        } else {
            emptyMap()
        }
        apps = list
    }
}

class TileDrawer(private val ctx: Context, private val apps: List<FavoriteApp>) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var downY = 0f
    private var startTrans = 0f
    private var shown = false

    fun show() {
        if (shown || apps.isEmpty() || active != null) return
        shown = true
        active = this

        val density = ctx.resources.displayMetrics.density
        val itemH = (58 * density).toInt()

        // 拟物实心（5.8.x）：底色/字色/握把全部取自 app 当前主题，与 app 内部同源
        val colors = SolidPanel.of(
            PaletteStore.theme(ctx.getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE))
        )

        val maxRows = 4
        val rows = maxOf(1, minOf(apps.size, maxRows))
        val colHeight = rows * itemH + (18 * density).toInt()

        val frame = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }

        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            // 拟物实心板（5.8.1）：纯 app 背景色，顶角 20dp / 底角 0；
            // 立体感全部由每行的机械键盘式按键承担（顶边棱线与上方落影已按反馈删除）
            background = GradientDrawable().apply {
                setColor(colors.panel)
                cornerRadii = floatArrayOf(
                    20 * density, 20 * density, 20 * density, 20 * density,
                    0f, 0f, 0f, 0f
                )
            }
        }.also { p ->
            val topGlow = View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(colors.grip)
                    cornerRadius = 2 * density
                }
            }
            val handle = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (30 * density).toInt())
                addView(topGlow, FrameLayout.LayoutParams(
                    (120 * density).toInt(), (3 * density).toInt(), Gravity.CENTER
                ))
                setOnTouchListener { _, ev -> handleDrag(ev); true }
            }
            p.addView(handle)

            val body = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, colHeight)
            }
            val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            for (app in apps) inner.addView(appRow(app, itemH, colors, density))
            body.addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            p.addView(body)
        }

        //通栏全宽、底边直接贴屏幕底端（cornerRadii 本就底角 0，边距与贴底设计相悖，归零）
        frame.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { setMargins(0, 0, 0, 0) })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val result = runCatching { wm.addView(frame, params) }
        if (result.isFailure) {
            shown = false
            active = null
            return
        }

        root = frame
        frame.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val p = panel ?: return true
                p.translationY = p.height.toFloat()
                p.alpha = 0.72f
                frame.viewTreeObserver.removeOnPreDrawListener(this)
                // 弹出：上滑 + 材质化淡入（快进缓出、无回弹——贴边不会被顶起）
                p.animate().translationY(0f).alpha(1f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .start()
                return true
            }
        })
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun appRow(app: FavoriteApp, itemH: Int, colors: SolidColors, density: Float): View {
        val tv = TextView(ctx).apply {
            text = app.label
            setTextColor(colors.ink)
            textSize = UiTokens.TEXT_BODY
            gravity = Gravity.CENTER_VERTICAL
            // 下内边距 = 键面下方的外投影带（[RowKeyDrawable.BAND_DP]），
            // 文字因此仍精确居中在键面而非整个行
            setPadding(dp(20), 0, dp(12), dp(RowKeyDrawable.BAND_DP))
            setSingleLine(true)
            // 机械键盘式按键（5.8.4）：静止凸起 / 按下凹陷（drawable 由 pressed 驱动），
            // 圆角取 app 按钮同款 20dp；静止 elevation 出框架真阴影（形状由 drawable 的
            // getOutline 决定 = 键面圆角矩形，贴边真模糊，等价 CSS box-shadow）
            background = RowKeyDrawable(colors, density)
            elevation = RowKeyDrawable.ELEVATION_DP * density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 阴影染色与手绘影同源（低版本无框架阴影，仅剩手绘层，形态仍成立）
                setOutlineAmbientShadowColor(colors.keyCast)
                setOutlineSpotShadowColor(colors.keyCast)
            }
            // 按下：键帽下沉 1.5dp + 影子收掉（同一状态机驱动，回弹自动反向）
            val sink = ObjectAnimator.ofFloat(
                this,
                View.TRANSLATION_Y,
                0f,
                SolidPanel.sinkPx(density)
            )
            val lower = ObjectAnimator.ofFloat(
                this,
                "elevation",
                RowKeyDrawable.ELEVATION_DP * density,
                RowKeyDrawable.PRESSED_ELEVATION_DP * density
            )
            stateListAnimator = StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    AnimatorSet().apply { duration = 60; playTogether(sink, lower) })
            }
            setOnClickListener {
                runCatching {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.pkg)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                dismiss()
            }
        }
        val icon = TileDrawerCache.icons[app.pkg]
            ?: runCatching { ctx.packageManager.getApplicationIcon(app.pkg) }.getOrNull()
        if (icon != null) {
            val s = dp(26)
            icon.setBounds(0, 0, s, s)
        }
        runCatching {
            if (icon != null) tv.setCompoundDrawables(icon, null, null, null)
            tv.setCompoundDrawablePadding(dp(12))
        }
        // 键不贯穿通栏：左右各留 ROW_SIDE_GAP（= app 屏幕内容边距）留白，与 app 内按钮一致；
        // 不加上下间距 → 面板总高公式 30dp + rows×58dp + 18dp 不变
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH)
            .apply { setMargins(dp(ROW_SIDE_GAP_DP), 0, dp(ROW_SIDE_GAP_DP), 0) }
        return tv
    }

    private fun handleDrag(ev: MotionEvent): Boolean {
        val p = panel ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                startTrans = p.translationY
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (dy > 0) p.translationY = startTrans + dy
            }
            MotionEvent.ACTION_UP -> {
                val dy = ev.rawY - downY
                if (dy > p.height * 0.22f) dismiss()
                else p.animate().translationY(0f).setDuration(300)
                    .setInterpolator(OvershootInterpolator(1.3f)).start()
            }
        }
        return true
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        active = null
        val f = root
        if (f != null) {
            animateDismiss(f)
        }
        root = null
        panel = null
    }

    private fun animateDismiss(f: FrameLayout) {
        f.animate().translationY(f.height.toFloat())
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                runCatching {
                    f.visibility = View.INVISIBLE
                    wm.removeView(f)
                }
            }.start()
    }

    companion object {
        /** 行键左右留白（dp）= app 屏幕内容边距，键不贯穿通栏。 */
        private const val ROW_SIDE_GAP_DP = 16

        /** 进程级单例闸：跳板冷启动补发可能重复触发 show，只允许同时存在一层抽屉。 */
        @Volatile
        private var active: TileDrawer? = null
    }
}
