package com.workspace.proot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** 把一个 ScriptDialogSpec.Request 以 TYPE_APPLICATION_OVERLAY 浮层形式弹到任意 App 之上。
 *  @param fast 队列连续弹出时用极短动画，避免翻页时动画叠加拖慢体感。 */
class ScriptDialogOverlay(
    private val ctx: Context,
    private val request: ScriptDialogSpec.Request,
    private val fast: Boolean = false,
    private val onResult: (ScriptDialogSpec.Result) -> Unit
) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var dismissed = false
    private var focusAcquired = false
    private var outsideArmed = false
    private var _renderer: ScriptDialogRenderer? = null
    private val disarmRunnable = Runnable { outsideArmed = false }

    // 当前请求的可变引用，供 chain 原地更新时替换
    private var currentRequest: ScriptDialogSpec.Request = request

    fun show() {
        val renderer = ScriptDialogRenderer(ctx)
        _renderer = renderer
        currentRequest = request
        val content = renderer.buildRoot(request) { result ->
            if (!dismissed) {
                if (!ScriptDialogSpec.shouldDismiss(request, result)) {
                    onResult(result)
                } else {
                    dismissed = true
                    onResult(result)
                    dismiss()
                }
            }
        }
        content.isFocusableInTouchMode = true
        content.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                if (!dismissed) {
                    dismissed = true
                    onResult(ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
                    dismiss()
                }
                true
            } else {
                false
            }
        }
        // 点外部两段式关闭：第一次闪烁提示，第二次真正关闭
        val card = (content as? android.widget.FrameLayout)?.getChildAt(0)
        content.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val c = card
                if (c != null) {
                    val x = ev.x.toInt()
                    val y = ev.y.toInt()
                    val inside = x >= c.left && x <= c.right && y >= c.top && y <= c.bottom
                    if (inside) {
                        outsideArmed = false
                        mainHandler.removeCallbacks(disarmRunnable)
                    } else {
                        onOutsideTap(c)
                    }
                }
            }
            false
        }

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
            if (!dismissed) {
                dismissed = true
                onResult(ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS, error = "add_view_failed"))
            }
            return
        }
        root = content

        content.post {
            if (!focusAcquired && content.isAttachedToWindow) {
                focusAcquired = true
                content.requestFocus()
            }
            animateIn(content, request.style.anim)
        }
    }

    fun captureValues(): Map<String, String> = _renderer?.captureValues() ?: emptyMap()

    fun isAlive(): Boolean = !dismissed && (root?.isAttachedToWindow == true) && (root?.parent != null)

    fun updateContent(newRequest: ScriptDialogSpec.Request, newOnResult: ((ScriptDialogSpec.Result) -> Unit)? = null) {
        if (dismissed) return
        val r = root as? android.widget.FrameLayout ?: return
        // 打断可能还在跑的进场动画，重置为可见态
        r.animate().cancel()
        r.alpha = 1f
        r.translationY = 0f
        r.scaleX = 1f
        r.scaleY = 1f
        outsideArmed = false
        mainHandler.removeCallbacks(disarmRunnable)
        currentRequest = newRequest
        val callback = newOnResult ?: onResult
        val renderer = _renderer ?: ScriptDialogRenderer(ctx).also { _renderer = it }
        val oldCard = r.getChildAt(0) as? android.widget.LinearLayout ?: return
        // 真原位 Diff：行级复用、值保留、Fade+ChangeBounds 120ms
        renderer.updateCard(oldCard, newRequest) { result ->
            if (!dismissed) {
                if (!ScriptDialogSpec.shouldDismiss(newRequest, result)) {
                    callback(result)
                } else {
                    dismissed = true
                    callback(result)
                    dismiss()
                }
            }
        }
        // 同步卡片宽度/位置（widthPct/position 可能变）
        try {
            val newW = renderer.screenWidthPx(newRequest.style.widthPct)
            val lp = oldCard.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (lp != null) {
                lp.width = newW
                lp.gravity = if (newRequest.style.position == ScriptDialogSpec.POSITION_BOTTOM) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
                oldCard.layoutParams = lp
            }
        } catch (_: Exception) {}
        // 更新外部点击卡片引用与触摸监听（卡片实例复用，仅更新引用）
        r.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val x = ev.x.toInt()
                val y = ev.y.toInt()
                val inside = x >= oldCard.left && x <= oldCard.right && y >= oldCard.top && y <= oldCard.bottom
                if (inside) {
                    outsideArmed = false
                    mainHandler.removeCallbacks(disarmRunnable)
                } else {
                    onOutsideTap(oldCard)
                }
            }
            false
        }
        // 焦点
        r.post {
            if (!focusAcquired && r.isAttachedToWindow) {
                focusAcquired = true
                r.requestFocus()
            }
        }
    }

    private fun onOutsideTap(card: View) {
        if (outsideArmed) {
            if (!dismissed) {
                dismissed = true
                onResult(ScriptDialogSpec.Result(ScriptDialogSpec.RESULT_ID_DISMISS))
                dismiss()
            }
        } else {
            outsideArmed = true
            mainHandler.removeCallbacks(disarmRunnable)
            mainHandler.postDelayed(disarmRunnable, OUTSIDE_ARM_MS)
            card.animate().alpha(0.4f).setDuration(80).withEndAction {
                card.animate().alpha(1f).setDuration(80).start()
            }.start()
        }
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

    fun dismiss() {
        if (dismissed && root == null) return
        dismissed = true
        val v = root ?: return
        v.animate().alpha(0f).setDuration(140).withEndAction {
            // 先隐藏再移除，避免全屏半透明窗口移除瞬间的合成闪烁
            v.visibility = View.INVISIBLE
            runCatching { wm.removeView(v) }
        }.start()
        root = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val OUTSIDE_ARM_MS = 2500L
        private const val NORMAL_ANIM_MS = 120L
        private const val FAST_ANIM_MS = 80L
    }
}
