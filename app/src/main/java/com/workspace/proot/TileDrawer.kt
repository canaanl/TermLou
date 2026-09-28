package com.workspace.proot

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

    @Volatile
    private var showArmed = false

    /**
     * 武装一次性展示令牌（磁贴点击、startActivityAndCollapse 之前）：
     * 首个真正跑起来的跳板消费成功才挂 250ms 展示定时器——冷启动 150ms 补发拉起的第二个
     * 跳板消费不到，不会重复挂定时器（否则用户在补发窗口内快速关闭抽屉会被重新弹开）；
     * 首个启动被系统冷启动竞态吞掉时令牌仍在，恰好由补发跳板消费，展示时序相对真实的
     * 面板收起时刻反而更准。
     */
    fun armShow() {
        showArmed = true
    }

    fun consumeShowArmed(): Boolean {
        if (!showArmed) return false
        showArmed = false
        return true
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
            background = GradientDrawable().apply {
                setColor(UiTokens.tilePanelBg)
                cornerRadii = floatArrayOf(
                    (20 * density), (20 * density),
                    (20 * density), (20 * density),
                    0f, 0f, 0f, 0f
                )
            }
        }.also { p ->
            val topGlow = View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(UiTokens.whiteFaint)
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
            for (app in apps) inner.addView(appRow(app, itemH))
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
                frame.viewTreeObserver.removeOnPreDrawListener(this)
                p.animate().translationY(0f).setDuration(240).start()
                return true
            }
        })
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun appRow(app: FavoriteApp, itemH: Int): View {
        val tv = TextView(ctx).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, 8, 0)
            setSingleLine(true)
            elevation = 0f
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
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH)
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
                else p.animate().translationY(0f).setDuration(180).start()
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
        /** 进程级单例闸：跳板冷启动补发可能重复触发 show，只允许同时存在一层抽屉。 */
        @Volatile
        private var active: TileDrawer? = null
    }
}
