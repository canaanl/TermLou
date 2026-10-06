package com.workspace.proot

import android.animation.ValueAnimator
import android.os.Handler
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView

/**
 * 状态栏长文本的纵向整行滚动（5.9.22）。
 *
 * ## 为什么要有这个
 *
 * 状态栏是个裸 `TextView`，没设行数限制。点到名字/路径极长的文件时，
 * `statusText.text = fullName` 直接把栏撑成多行，下面的元素全被顶下去。
 *
 * 现在栏高**锁死 1 行**：超长时整行往上走，一次一行，每行停一下；
 * 行数 ≤ 1 时和原来一模一样（设文字 + 到点恢复）。
 *
 * ## 时间规则
 *
 * - 正常名字：还是原来的 1000ms。
 * - 极端长名：走完一遍要 `N×每行停留 + (N−1)×过渡`，如果超过 1000ms，
 *   **这次就延长到走完一次为止** —— 用户明确要求"至少保证滚动显示完整名称一次"，
 *   **不要上限**。
 *
 * ## 位置
 *
 * `FileListManager` 原来三处都是"设文字 + 1000ms 后恢复"的手写重复
 * （返回上层、进目录、点文件），现在三处都调 [show]。
 */
object StatusMarquee {

    /** 每行停留（让人看清这一行）。5.9.22 是 250ms，用户反馈太快，5.9.23 起 450ms。 */
    const val HOLD_MS = 450L

    /** 行与行之间的过渡。5.9.22 是 150ms，5.9.23 起 250ms。 */
    const val STEP_MS = 250L

    /** 文件 tab 原来的展示时长，不动它。 */
    const val BASE_MS = 1000L

    /**
     * 走完一遍要多久。纯函数，单测锁。
     */
    fun passMs(lineCount: Int): Long {
        if (lineCount <= 1) return 0L
        return lineCount * HOLD_MS + (lineCount - 1) * STEP_MS
    }

    /**
     * 这次展示总时长 = max(原来时长, 走完一遍)。
     * 行数 ≤ 1 时就是原来的时长，一毫秒不加。
     */
    fun showMs(lineCount: Int, baseMs: Long = BASE_MS): Long =
        maxOf(baseMs, passMs(lineCount))

    /**
     * 某一时刻滚动到哪。纯函数，单测逐段锁。
     *
     * @param tops 每一行**实际排出来**的顶部（`layout.getLineTop(i)` 归一化到首行）。
     *  5.9.22 用的是 `i × lineHeight`，排版的零点几像素累计误差会让行与行对不齐，
     *  上一行的降部（比如 j 的脚）漏出来一截。改成一行一取，永远对齐。
     *
     * 时间轴（N 行）：第 i 行先停 HOLD_MS，再花 STEP_MS 走到下一行；
     * 最后一行停满剩余时间；超出总时长就停在底。
     */
    fun scrollYAt(elapsedMs: Long, tops: IntArray): Int {
        if (tops.size <= 1) return 0
        val max = tops.last()
        var t = 0L
        for (i in tops.indices) {
            if (elapsedMs < t + HOLD_MS) return tops[i]
            t += HOLD_MS
            if (i == tops.lastIndex) return max
            if (elapsedMs < t + STEP_MS) {
                val f = (elapsedMs - t).toFloat() / STEP_MS
                return (tops[i] + f * (tops[i + 1] - tops[i])).toInt()
            }
            t += STEP_MS
        }
        return max
    }

    private const val TAG_KEY = -0x5A1A

    private class Session(
        val animator: ValueAnimator?,
        val finish: Runnable
    )

    private fun current(view: TextView): Session? =
        view.getTag(TAG_KEY) as? Session

    /** 新展示进来时，旧的动画 + 恢复回调一并取消（原来只取消了恢复回调）。 */
    fun cancel(view: TextView, handler: Handler?) {
        val s = current(view) ?: return
        s.animator?.cancel()
        handler?.removeCallbacks(s.finish)
        view.setTag(TAG_KEY, null)
    }

    /**
     * 常态长路径的横向跑马灯（5.9.24）。
     *
     * `prefix`（如 `Files | `）由盖板定死盖住，滚动从 `|` 后面开始：
     * 正文里放完整的一整行（含它自己的前缀），打开系统跑马灯让它自己滚；
     * 盖板画着一模一样的前缀、不透明底，盖在正文左端。滚到盖板下面的部分被挡住，
     * 看上去就是前缀不动、只有后面在滚。
     *
     * 放得下就不滚不盖，和原来逐像素一致。跑马灯是循环的（到头停一下再回来），
     * 因为它是常态显示不是临时提示 —— 滚一遍停住的话，末尾就一直是末尾了。
     */
    fun showPersistent(view: TextView?, overlay: TextView?, prefix: String, path: String) {
        if (view == null) return
        val full = prefix + path
        val avail = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        if (avail <= 0f || view.paint.measureText(full) <= avail) {
            clearPersistent(view, overlay)
            view.text = full
            return
        }
        view.text = full
        view.isSingleLine = true
        view.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        view.marqueeRepeatLimit = -1
        view.isSelected = true
        if (overlay != null) {
            overlay.text = prefix
            overlay.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * 关掉常态跑马灯（临时全名展示前必须调，否则单行模式会把纵向滚动憋成一行）。
     * 放得下的纯文字不受影响（本来就没开）。
     */
    fun clearPersistent(view: TextView?, overlay: TextView?) {
        overlay?.visibility = android.view.View.GONE
        if (view == null) return
        view.isSelected = false
        view.ellipsize = null
        view.setSingleLine(false)
    }

    /**
     * 展示长文本。`onDone` = 时间到后的恢复动作（原来那三处"回到 Files | 路径"的逻辑，原样搬进来）。
     *
     * 栏高在展示期间锁死 1 行：先按 1 行高度定死高度**再**设文字，避免设文字到
     * `post` 之间的那一帧把栏撑高闪一下。行数 ≤ 1 时高度不动，和原来逐像素一致。
     */
    fun show(
        view: TextView?,
        text: String,
        baseMs: Long = BASE_MS,
        handler: Handler?,
        overlay: TextView? = null,
        onDone: () -> Unit
    ) {
        if (view == null) {
            onDone()
            return
        }
        // 先关常态跑马灯：单行模式不关，纵向滚动会被憋成一行量不出行数。
        clearPersistent(view, overlay)
        cancel(view, handler)
        // 先锁高再设文字：中间那一帧不能闪
        val oneLineH = view.lineHeight + view.paddingTop + view.paddingBottom
        val lp = view.layoutParams
        val oldHeight = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp != null) {
            lp.height = oneLineH
            view.layoutParams = lp
        }
        view.scrollY = 0
        view.text = text
        view.post {
            val layout = view.layout
            val lines = view.lineCount
            val tops = if (layout != null && lines > 1) {
                val base = layout.getLineTop(0)
                IntArray(lines) { layout.getLineTop(it) - base }
            } else {
                IntArray(maxOf(lines, 0)) { 0 }
            }
            val total = showMs(lines, baseMs)
            // ⚠ 必须先声明再赋值：finish 里要停掉 animator（往下看为什么）。
            var animator: ValueAnimator? = null
            val finish = Runnable {
                // 先停帧循环，再恢复 —— 顺序不能反：
                // animator 的结束帧和这个 finish 都排在 total 毫秒，Choreographer
                // 和 Handler 的先后没保证。如果结束帧落在 finish 后面，
                // 它会把 scrollY 写回最大值，而此时栏里已经是 1 行的常态文字 ——
                // 常态文字被顶出可视区，栏空。这正是"滚完常态显示消失"。
                // （静态路径没有 animator，所以短名永远没事，只坏滚动的情况。）
                animator?.cancel()
                view.setTag(TAG_KEY, null)
                val p = view.layoutParams
                if (p != null) {
                    p.height = oldHeight
                    view.layoutParams = p
                }
                view.scrollY = 0
                onDone()
            }
            if (lines <= 1) {
                view.setTag(TAG_KEY, Session(null, finish))
                handler?.postDelayed(finish, total)
                return@post
            }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = total
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val elapsed = (animatedFraction * total).toLong()
                    view.scrollY = scrollYAt(elapsed, tops)
                }
            }
            view.setTag(TAG_KEY, Session(animator, finish))
            animator.start()
            handler?.postDelayed(finish, total)
        }
    }
}
