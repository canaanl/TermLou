package com.workspace.proot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 把一个 ScriptDialogSpec.Request 以 TYPE_APPLICATION_OVERLAY 浮层形式弹到任意 App 之上。
 *  @param fast 队列连续弹出时用极短动画，避免翻页时动画叠加拖慢体感。 */
@Suppress("TooManyFunctions")
class ScriptDialogOverlay(
    private val ctx: Context,
    private val request: ScriptDialogSpec.Request,
    private val fast: Boolean = false,
    private val onResult: (ScriptDialogSpec.Result) -> Unit
) {

    private enum class State { SHOWN, DISMISSING, DESTROYED }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var state = State.SHOWN
    private var focusAcquired = false
    private var outsideArmed = false
    private var _renderer: ScriptDialogRenderer? = null
    private var outsideHint: TextView? = null
    private val disarmRunnable = Runnable {
        outsideArmed = false
        hideOutsideHint()
    }

    /** 窗口真正移除后回调；OverlayBridge 用它清空 activeOverlay，而不是在 dismiss 开始时清空。 */
    var onDismissed: (() -> Unit)? = null

    /** 卡片内任意有效交互；OverlayBridge 用它重置会话空闲计时。 */
    var onUserActivity: (() -> Unit)? = null

    /** 返回键/点外部第二次：销毁整个会话，由 OverlayBridge 清队列。 */
    var onSessionClose: (() -> Unit)? = null

    // 当前请求的可变引用，供 chain 原地更新时替换
    private var currentRequest: ScriptDialogSpec.Request = request

    // 当前屏的结果归属：updateContent 换屏时同步替换。
    // 按钮栏/返回键/点外部一律在触发时读取这两个字段，杜绝换屏后还把点击算到上一屏头上。
    private var currentOnResult: (ScriptDialogSpec.Result) -> Unit = onResult

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun show() {
        // 同一窗口只允许 addView 一次；重复 show 不重建也不回退到初始请求。
        if (root != null) return
        if (state == State.DESTROYED) return

        val renderer = ScriptDialogRenderer(ctx)
        _renderer = renderer
        currentRequest = request
        currentOnResult = onResult
        val content = renderer.buildRoot(request) { result ->
            onResultFromNode(result)
        }
        bindInsideTouch(renderer)
        content.isFocusableInTouchMode = true
        content.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                if (state == State.SHOWN) requestSessionClose()
                true
            } else {
                false
            }
        }
        // 点外部两段式关闭：第一次显示顶部白字提示，第二次销毁整个会话。
        val card = (content as? FrameLayout)?.getChildAt(0)
        card?.let { bindCardActivity(it) }
        content.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val c = card
                if (c != null) {
                    val x = ev.x.toInt()
                    val y = ev.y.toInt()
                    val inside = x >= c.left && x <= c.right && y >= c.top && y <= c.bottom
                    if (inside) {
                        outsideArmed = false
                        hideOutsideHint()
                        mainHandler.removeCallbacks(disarmRunnable)
                    } else {
                        onOutsideTap()
                    }
                }
            }
            false
        }

        // 提示必须是最顶层子 View：加在 card 之后，z-order 最高。
        val hint = buildOutsideHint()
        outsideHint = hint
        content.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(HINT_TOP_DP) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val result = runCatching { wm.addView(content, params) }
        if (result.isFailure) {
            state = State.DESTROYED
            root = null
            currentOnResult(ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS, error = "add_view_failed"))
            onDismissed?.invoke()
            return
        }
        root = content
        state = State.SHOWN

        content.post {
            if (state == State.SHOWN && !focusAcquired && content.isAttachedToWindow) {
                focusAcquired = true
                content.requestFocus()
            }
            if (state == State.SHOWN) animateIn(content, request.style.anim)
        }
    }

    fun captureValues(): Map<String, String> = _renderer?.captureValues() ?: emptyMap()

    /** 脚本 canvas patch：只改活动 Canvas 的场景，不写 req/res、不重建卡片。 */
    fun applyCanvasPatch(key: String?, patch: CanvasPatch): Boolean =
        _renderer?.applyCanvasPatch(key, patch) ?: false

    /** DISMISSING 也算 alive：淡出期间来新请求要取消消失并原位更新，不能新建窗口。 */
    fun isAlive(): Boolean = state != State.DESTROYED && root != null

    internal fun isDismissing(): Boolean = state == State.DISMISSING

    internal fun windowRoot(): View? = root

    internal fun outsideHintForTest(): TextView? = outsideHint

    /**
     * 原位换内容：只更新现有 card/dialog，不 addView/removeView，也不播窗口动画。
     * @return 是否成功更新；窗口已销毁或内部视图缺失时返回 false。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun updateContent(
        newRequest: ScriptDialogSpec.Request,
        newOnResult: ((ScriptDialogSpec.Result) -> Unit)? = null
    ): Boolean {
        if (state == State.DESTROYED) return false
        if (state == State.DISMISSING) cancelDismiss()
        currentRequest = newRequest
        if (newOnResult != null) currentOnResult = newOnResult
        outsideArmed = false
        hideOutsideHint()
        mainHandler.removeCallbacks(disarmRunnable)
        val r = root as? FrameLayout ?: return false
        val oldCard = r.getChildAt(0) as? LinearLayout ?: return false
        val renderer = _renderer ?: ScriptDialogRenderer(ctx).also { _renderer = it }
        // 尺寸/位置/内容过渡全部交给 renderer，外层窗口和 card 实例不动。
        renderer.updateCard(oldCard, newRequest) { result ->
            onResultFromNode(result)
        }
        bindInsideTouch(renderer)
        // 更新外部点击卡片引用与触摸监听（卡片实例复用，仅更新引用）。
        r.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val x = ev.x.toInt()
                val y = ev.y.toInt()
                val inside = x >= oldCard.left && x <= oldCard.right && y >= oldCard.top && y <= oldCard.bottom
                if (inside) {
                    outsideArmed = false
                    hideOutsideHint()
                    mainHandler.removeCallbacks(disarmRunnable)
                } else {
                    onOutsideTap()
                }
            }
            false
        }
        // 焦点：不打断首次呼出动画，只补一次焦点。
        r.post {
            if (state == State.SHOWN && !focusAcquired && r.isAttachedToWindow) {
                focusAcquired = true
                r.requestFocus()
            }
        }
        return true
    }

    private fun onResultFromNode(result: ScriptDialogSpec.Result) {
        if (state == State.DESTROYED) return
        if (!ScriptDialogSpec.shouldDismiss(currentRequest, result)) {
            currentOnResult(result)
        } else {
            // 先进入消失，再通知桥；桥里的新请求可以取消这次消失并原位更新。
            dismiss()
            currentOnResult(result)
        }
    }

    /** 取消正在进行的消失；窗口从未真正移除，所以不播任何动画。 */
    private fun cancelDismiss() {
        if (state != State.DISMISSING) return
        val v = root ?: return
        v.animate().cancel()
        v.alpha = 1f
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.visibility = View.VISIBLE
        state = State.SHOWN
    }

    /** 最终消失；只有这里允许窗口动画。 */
    @Suppress("ReturnCount")
    fun dismiss(immediate: Boolean = false) {
        if (state == State.DESTROYED || state == State.DISMISSING) return
        state = State.DISMISSING
        outsideArmed = false
        hideOutsideHint()
        mainHandler.removeCallbacks(disarmRunnable)
        val v = root
        if (v == null) {
            finishDismiss()
            return
        }
        if (immediate) {
            v.visibility = View.INVISIBLE
            runCatching { wm.removeView(v) }
            finishDismiss()
            return
        }
        v.animate().cancel()
        animateOut(v, currentRequest.style.anim)
    }

    /** 消失动画和弹窗工坊的 --anim 设置一致，不是固定淡出。 */
    private fun animateOut(v: View, anim: String) {
        val end = {
            if (state == State.DISMISSING) {
                v.visibility = View.INVISIBLE
                runCatching { wm.removeView(v) }
                finishDismiss()
            }
        }
        val a = when (anim) {
            ScriptDialogSpec.ANIM_FADE -> v.animate().alpha(0f)
            ScriptDialogSpec.ANIM_SLIDE -> v.animate()
                .translationY(v.height.toFloat())
                .alpha(0f)
            else -> v.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0f)
        }
        a.setDuration(NORMAL_ANIM_MS).withEndAction(end).start()
    }

    private fun finishDismiss() {
        if (state == State.DESTROYED) return
        state = State.DESTROYED
        root = null
        outsideHint = null
        mainHandler.removeCallbacksAndMessages(null)
        onDismissed?.invoke()
    }

    private fun bindInsideTouch(renderer: ScriptDialogRenderer) {
        val dialog = renderer.canvasDialog() ?: return
        dialog.onUserActivity = { onUserActivity?.invoke() }
        dialog.host.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // 点卡片内容也立即收起顶部提示；返回 false 不抢 Canvas 自己的触摸。
                outsideArmed = false
                hideOutsideHint()
                mainHandler.removeCallbacks(disarmRunnable)
            }
            false
        }
    }

    private fun bindCardActivity(card: View) {
        card.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                outsideArmed = false
                hideOutsideHint()
                mainHandler.removeCallbacks(disarmRunnable)
                onUserActivity?.invoke()
            }
            false
        }
        (card as? LinearLayout)?.getChildAt(0)?.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onUserActivity?.invoke()
            false
        }
    }

    private fun requestSessionClose() {
        if (state != State.SHOWN) return
        dismiss()
        val cb = onSessionClose
        if (cb != null) cb() else currentOnResult(ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
    }

    private fun onOutsideTap() {
        _renderer?.hideInput()
        if (outsideArmed) {
            hideOutsideHint()
            requestSessionClose()
        } else {
            outsideArmed = true
            showOutsideHint()
            mainHandler.removeCallbacks(disarmRunnable)
            mainHandler.postDelayed(disarmRunnable, OUTSIDE_ARM_MS)
        }
    }

    private fun showOutsideHint() {
        outsideHint?.visibility = View.VISIBLE
    }

    private fun hideOutsideHint() {
        outsideHint?.visibility = View.GONE
    }

    private fun buildOutsideHint(): TextView = TextView(ctx).apply {
        text = ctx.getString(R.string.dialog_tap_again_to_close)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_TEXT_SP)
        gravity = Gravity.CENTER
        setPadding(dp(HINT_PAD_X_DP), dp(HINT_PAD_Y_DP), dp(HINT_PAD_X_DP), dp(HINT_PAD_Y_DP))
        background = GradientDrawable().apply {
            setColor(HINT_BG)
            cornerRadius = dp(HINT_CORNER_DP).toFloat()
        }
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun animateIn(v: View, anim: String) {
        if (fast) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(FAST_ANIM_MS).start()
            return
        }
        when (anim) {
            ScriptDialogSpec.ANIM_FADE -> {
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(NORMAL_ANIM_MS).start()
            }
            ScriptDialogSpec.ANIM_SLIDE -> {
                v.translationY = v.height.toFloat()
                v.animate().translationY(0f).setDuration(NORMAL_ANIM_MS).start()
            }
            else -> {
                v.scaleX = 0.9f
                v.scaleY = 0.9f
                v.animate().scaleX(1f).scaleY(1f).setDuration(NORMAL_ANIM_MS).start()
            }
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val OUTSIDE_ARM_MS = 2500L
        private const val NORMAL_ANIM_MS = 140L
        private const val FAST_ANIM_MS = 80L
        private const val HINT_TOP_DP = 44
        private const val HINT_TEXT_SP = 13f
        private const val HINT_PAD_X_DP = 12
        private const val HINT_PAD_Y_DP = 7
        private const val HINT_CORNER_DP = 8
        private val HINT_BG = 0xB3000000.toInt()
    }
}
