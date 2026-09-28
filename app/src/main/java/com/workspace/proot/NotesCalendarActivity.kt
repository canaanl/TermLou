package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历视图：周一开头的 6×7 月历，每天一个圆角格子，三类状态——
 * 有笔记 = 格子背景填充固定色（无笔记透明，不画笔记图标）、
 * 待办三态小图标（红空 ○/黄半 ◐/绿满 ✔，无待办不画、不显数量）、标签价签（有/无不显名）。
 * 上下滑动**跟手翻月**（拖动时当前月与目标月两块一起走，松手过阈值补完换月、不足回弹，
 * 出界带阻尼拖不动、松手弹回并 Toast）；到更早已无内容或翻未来时拦住不翻并 Toast 提示；
 * 点日期弹当日详情（标签名 + Todo 总数/完成数 + 固定高度滚动笔记索引），
 * 点笔记直接进编辑页，无中间步骤；溢出月格子只显淡化数字、不可点。
 */
class NotesCalendarActivity : NotesPageActivity() {

    private var month: YearMonth = YearMonth.now()
    private lateinit var monthTitle: TextView
    /** 当前月网格（主格）。 */
    private lateinit var gridBox: LinearLayout
    /** 目标月网格（次格），只在跟手期间铺内容，平时 GONE。 */
    private lateinit var gridNext: LinearLayout
    /** 装主/次两格的居中 pager，两块都在这里裁剪。 */
    private lateinit var pager: FrameLayout
    private var status: Map<LocalDate, NotesCalendar.DayStatus> = emptyMap()
    private var detailDialog: AlertDialog? = null
    /** 全库最早的有内容月份（笔记+待办），随 onResume 刷新；null = 一条内容都没有。 */
    private var contentEarliest: YearMonth? = null

    // ---- 翻月跟手状态 ----
    /** 补完动画（换月/回弹）在播时非 null；此时新手势先把它结算掉再跟手，不吞操作。 */
    private var monthAnimator: Animator? = null
    /** 在播动画结束时要执行的落定动作（换月落定 / 复位），cancel 也会执行一次。 */
    private var animAction: (() -> Unit)? = null
    /** 当前手势是否处于跟手状态。 */
    private var dragging = false
    /** 本次手势的方向：+1 = 下个月（次格从下方进）、-1 = 上个月（次格从上方进）。 */
    private var dragSign = 1L
    /** 次格里已铺好的目标月；null = 出界没铺（只有阻尼）。 */
    private var pendingTarget: YearMonth? = null
    /** 手指位移速度（px/s，负=向上），由相邻 MOVE 估算，用于快速甩动直接换月。 */
    private var dragVel = 0f
    private var lastY = 0f
    private var lastAt = 0L

    private val weekdayRes = intArrayOf(
        R.string.notes_cal_wd_mon, R.string.notes_cal_wd_tue, R.string.notes_cal_wd_wed,
        R.string.notes_cal_wd_thu, R.string.notes_cal_wd_fri, R.string.notes_cal_wd_sat,
        R.string.notes_cal_wd_sun
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = density()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        // 纵向跟手翻月挂容器级 intercept（OnTouchListener 会被消费触摸的格子屏蔽）。
        // 容器包住整页（标题栏 + 月头 + 表头 + 网格），配合 isClickable → 全区域可滑。
        val body = MonthSwipeLayout(
            this,
            onDrag = { dy -> handleDrag(dy) },
            onDragEnd = { dy, cancelled -> handleDragEnd(dy, cancelled) }
        ).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            buildTitleBar(getString(R.string.notes_cal_tab)),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        monthTitle = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = UiTokens.TEXT_TITLE
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.onSurface)
            contentDescription = getString(R.string.notes_cal_back_now)
            setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
            setOnClickListener { jumpMonth(YearMonth.now()) }
        }
        body.addView(monthTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(weekdayRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 网格放进 weight=1 的中置容器：标题/表头贴顶，整块方格从页面高度居中。
        // pager 里叠两块（主格=当前月、次格=目标月），跟手时两块一起平移做出滚动效果。
        gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gridNext = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pager = FrameLayout(this).apply {
            addView(gridBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(gridNext, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val centerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        body.addView(centerBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        status = NotesCalendar.dayStatus(store.listNotes(), store.todos())
        contentEarliest = NotesCalendar.earliestContentMonth(store.listNotes(), store.todos())
        render()
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        status = NotesCalendar.dayStatus(store.listNotes(), store.todos())
        contentEarliest = NotesCalendar.earliestContentMonth(store.listNotes(), store.todos())
        render()
    }

    /** 翻月可到的最早月：有内容按最早内容月，一条内容都没有就以当前正在看的月为底；不越过本月。 */
    private fun boundaryEarliest(): YearMonth {
        val today = YearMonth.now()
        val base = contentEarliest ?: month
        return if (base > today) today else base
    }

    // ------------------------------------------------------------------
    // 跟手翻月：拖动时主格（当前月）与次格（目标月）一起平移，松手补完换月或回弹。
    // ------------------------------------------------------------------

    /** 起手：铺好目标月，之后每次 MOVE 把位移套到两格上（出界只给阻尼）。 */
    private fun handleDrag(dy: Float) {
        // 动画在播时先结算（换月落定/归位），再接着跟手，新手势不被吞掉。
        if (monthAnimator != null) monthAnimator?.cancel()
        if (!dragging) {
            dragging = true
            dragSign = if (dy < 0) 1L else -1L // 上滑 = 下个月（次格从下方进）
            pendingTarget = null
            dragVel = 0f
            lastY = dy
            lastAt = 0L
            prepareTarget(month.plusMonths(dragSign))
        }
        trackVelocity(dy)
        val dist = pagerHeight()
        if (pendingTarget != null) {
            // 次格起点 = dy + sign*dist（sign=+1 时在下方），随手指一起走
            gridBox.translationY = dy
            gridNext.translationY = dy + dragSign * dist
        } else {
            // 出界：目标月没铺，当前月带阻尼地拖一点点
            gridBox.translationY = dy * BAND
        }
    }

    /**
     * 把目标月铺进次格；出界（更早已无内容 / 要翻未来）则不铺、返回 false。
     * 次格按方向摆到 pager 外一侧（目标在后 → 从下方进），动画从这里接着走。
     */
    private fun prepareTarget(target: YearMonth): Boolean {
        val delta = (target.year * 12L + target.monthValue) - (month.year * 12L + month.monthValue)
        val verdict = NotesCalendar.shiftVerdict(month, delta, YearMonth.now(), boundaryEarliest())
        if (verdict != NotesCalendar.ShiftVerdict.OK) {
            gridNext.visibility = View.GONE
            gridNext.translationY = 0f
            return false
        }
        fillGrid(gridNext, target)
        gridNext.visibility = View.VISIBLE
        gridNext.translationY = if (delta < 0) -pagerHeight() else pagerHeight()
        pendingTarget = target
        return true
    }

    /** 相邻两帧 MOVE 估算手指速度（px/s，负=向上）：甩得快即使位移不够阈值也换月。 */
    private fun trackVelocity(dy: Float) {
        val now = SystemClock.uptimeMillis()
        if (lastAt > 0) {
            val dt = now - lastAt
            if (dt > 0) dragVel = (dy - lastY) / dt * 1000f
        }
        lastY = dy
        lastAt = now
    }

    /**
     * 松手：位移达标（或快速甩动）且在范围内 → 补完平移换月；
     * 出界且够大 → 弹回并 Toast（拖动中不提示，避免连闪）；其余 → 回弹归位。
     */
    private fun handleDragEnd(dy: Float, cancelled: Boolean) {
        if (!dragging) return
        dragging = false
        val dist = pagerHeight()
        val threshold = minOf(SWIPE_COMMIT_DP * density(), dist / 3f)
        val enough = kotlin.math.abs(dy) >= threshold ||
            (kotlin.math.abs(dragVel) >= FLING_VEL_DPS * density() && (dragVel < 0) == (dy < 0))
        val verdict = NotesCalendar.shiftVerdict(month, dragSign, YearMonth.now(), boundaryEarliest())
        if (!cancelled && enough && pendingTarget != null && verdict == NotesCalendar.ShiftVerdict.OK) {
            // 完成位：主格走到 -sign*dist（整块滑出），次格归位（endDy = -sign*dist）
            animateSwitch(-dragSign * dist, dragSign) { applySwitch() }
        } else {
            if (!cancelled && enough && pendingTarget == null) toastBlocked(verdict)
            animateSwitch(0f, dragSign) { resetPager() }
        }
    }

    /** 位移补完动画：endDy = -sign*dist 换月、0 回弹；被打断也只执行一次落定动作。 */
    private fun animateSwitch(endDy: Float, sign: Long, action: () -> Unit) {
        val nextEnd = endDy + sign * pagerHeight()
        animAction = action
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(gridBox, View.TRANSLATION_Y, gridBox.translationY, endDy),
                ObjectAnimator.ofFloat(gridNext, View.TRANSLATION_Y, gridNext.translationY, nextEnd)
            )
            duration = ANIM_MS
            // Material 标准曲线 cubic-bezier(0.4, 0, 0.2, 1)，起步柔、收尾稳
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val run = animAction
                animAction = null
                monthAnimator = null
                run?.invoke()
            }
        })
        monthAnimator = set
        set.start()
    }

    /** 换月落定：次格的月份成为当前月，次格收起、位移归零并重铺。 */
    private fun applySwitch() {
        pendingTarget?.let { month = it }
        resetPager()
        render()
    }

    /** 收起次格、两格位移归零。 */
    private fun resetPager() {
        pendingTarget = null
        gridNext.visibility = View.GONE
        gridNext.translationY = 0f
        gridBox.translationY = 0f
    }

    /** 无拖动换月（点月头回本月）：铺好次格直接补完平移，方向按目标月与当前月的大小。 */
    private fun jumpMonth(target: YearMonth) {
        if (target == month) return
        if (monthAnimator != null) monthAnimator?.cancel()
        dragging = false
        if (!prepareTarget(target)) {
            month = target
            render()
            return
        }
        val sign = if (target > month) 1L else -1L
        animateSwitch(-sign * pagerHeight(), sign) { applySwitch() }
    }

    private fun toastBlocked(verdict: NotesCalendar.ShiftVerdict) {
        val res = when (verdict) {
            NotesCalendar.ShiftVerdict.BLOCK_NO_OLDER -> R.string.notes_cal_no_older
            NotesCalendar.ShiftVerdict.BLOCK_FUTURE -> R.string.notes_cal_future
            else -> return
        }
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    /** pager 的滚动距离 = 一屏高度（两格都在其中被裁剪），未布局时退化为常量。 */
    private fun pagerHeight(): Float {
        val h = when {
            pager.height > 0 -> pager.height
            gridBox.height > 0 -> gridBox.height
            else -> (120 * density()).toInt()
        }
        return h.toFloat()
    }

    /**
     * 月历整体重建：先结算在播动画、复位 pager，再重铺当前月
     * （数据量小，翻月/回本月/onResume 都走这里）。
     */
    private fun render() {
        if (monthAnimator != null) monthAnimator?.cancel()
        dragging = false
        monthTitle.text = getString(
            R.string.notes_cal_month_fmt, month.year, monthName(month.monthValue)
        )
        resetPager()
        fillGrid(gridBox, month)
    }

    /** 铺某一月的网格到指定格（主格/次格同构：5 或 6 行、只补到本周日）。 */
    private fun fillGrid(box: LinearLayout, ym: YearMonth) {
        box.removeAllViews()
        val y = ym.year
        val m = ym.monthValue
        val cells = NotesCalendar.monthGrid(y, m)
        val rows = cells.size / COLS
        val gap = (2 * density()).toInt()      // 格间留白：上下左右各 2dp
        val pitch = columnPitch()             // 列距 = 方格边长（行高同宽 → 近似正方形）
        val boxH = pitch - 2 * gap            // 格子内容盒：pitch-4dp，见方
        val today = LocalDate.now()
        for (row in 0 until rows) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until COLS) {
                rowView.addView(cell(cells[row * COLS + col], y, m, today, boxH, gap))
            }
            box.addView(rowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pitch))
        }
    }

    /**
     * 列距 = 理想方格边长 = 全宽 / 7（竖屏锁定，displayMetrics 宽度即网格可用宽）。
     * 行高取同一值，内容盒 = 列距 - 4dp → 宽高相等的正方形，不再被 weight 拉成竖长条。
     */
    private fun columnPitch(): Int {
        val w = resources.displayMetrics.widthPixels
        return if (w > 0) w / COLS else (52 * density()).toInt()
    }

    /** 周一…周日表头（7 等分，每列加与格子相同的 2dp 边距，保证与格列严格对齐）。 */
    private fun weekdayRow(): LinearLayout {
        val d = density()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (4 * d).toInt())
            for (res in weekdayRes) {
                addView(TextView(this@NotesCalendarActivity).apply {
                    text = getString(res)
                    textSize = UiTokens.TEXT_META
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(theme.onSurfaceVariant)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (2 * d).toInt()
                    marginEnd = (2 * d).toInt()
                })
            }
        }
    }

    /**
     * 单格：圆角容器（有笔记 = 固定填充色；无笔记透明）+ 日期数字 + 固定高度图标行。
     * 格与格之间靠 2dp margin 留空，外框由 boxHeight×boxHeight 给成正方形；
     * 今天在容器上套主色描边。
     */
    private fun cell(date: LocalDate, y: Int, m: Int, today: LocalDate, boxHeight: Int, gap: Int): View {
        val d = density()
        val inMonth = NotesCalendar.isInMonth(date, y, m)
        val isToday = inMonth && date == today
        val st = if (inMonth) status[date] else null
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(
                    if (st?.hasNote == true) CalendarColors.noteFill(theme.night)
                    else Color.TRANSPARENT
                )
                cornerRadius = 8 * d
                if (isToday) setStroke((1.5 * d).toInt().coerceAtLeast(1), theme.primaryVisible)
            }
            layoutParams = LinearLayout.LayoutParams(
                0, boxHeight, 1f
            ).apply {
                marginStart = gap
                marginEnd = gap
                topMargin = gap
                bottomMargin = gap
            }
        }
        val num = TextView(this).apply {
            text = date.dayOfMonth.toString()
            textSize = UiTokens.TEXT_COMPACT
            gravity = Gravity.CENTER
            maxLines = 1
            when {
                // 今天：加粗 + 容器主色描边（数字本身仍用 onSurface，保证填充底上 ≥7:1）
                isToday -> {
                    setTextColor(theme.onSurface)
                    typeface = Typeface.DEFAULT_BOLD
                }
                inMonth -> setTextColor(theme.onSurface)
                else -> setTextColor(faded(theme.onSurface))
            }
        }
        cell.addView(num, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (2 * d).toInt(), 0, 0)
        }
        if (inMonth) {
            // 有笔记不画图标（由格子填充表达），这里只剩待办三态与标签价签
            when (st?.todoState) {
                NotesCalendar.TodoState.OPEN ->
                    icons.addView(statusIcon(R.drawable.ic_cal_todo_open))
                NotesCalendar.TodoState.PARTIAL ->
                    icons.addView(statusIcon(R.drawable.ic_cal_todo_partial))
                NotesCalendar.TodoState.DONE ->
                    icons.addView(statusIcon(R.drawable.ic_cal_todo_done))
                null -> Unit
            }
            if (st?.hasTag == true) icons.addView(statusIcon(R.drawable.ic_cal_tag))
        }
        cell.addView(icons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (15 * d).toInt()))
        if (inMonth) cell.setOnClickListener { openDetail(date) }
        return cell
    }

    /** 状态图标：12dp 固定尺寸（vector 线条色已写死，不再 tint）。 */
    private fun statusIcon(res: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = false
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams((12 * density()).toInt(), (12 * density()).toInt())
            .apply { marginEnd = (1 * density()).toInt() }
    }

    /** 溢出月日期的淡化色（固定 alpha，两主题都还看得见）。 */
    private fun faded(color: Int): Int =
        Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))

    /** 当日详情弹窗：标签（含名称）+ Todo 统计 + 固定高度滚动的笔记索引。 */
    private fun openDetail(date: LocalDate) {
        val d = density()
        val st = status[date] ?: NotesCalendar.DayStatus()
        val dayNotes = store.listNotes().filter { NotesCalendar.dayOf(it.createdAt) == date }
        val dayTags = dayNotes.flatMap { it.tags }.distinct()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), 0)
        }

        box.addView(sectionLabel(getString(R.string.notes_tags_tab)))
        if (dayTags.isEmpty()) {
            box.addView(emptyLine(getString(R.string.notes_no_tags)))
        } else {
            val flow = TagFlowLayout(this)
            for (tag in dayTags) flow.addView(staticCapsule(tag))
            box.addView(flow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        box.addView(sectionLabel(getString(R.string.notes_todo_tab)))
        if (st.todoTotal <= 0) {
            box.addView(emptyLine(getString(R.string.notes_cal_no_todos)))
        } else {
            box.addView(TextView(this).apply {
                text = getString(R.string.notes_cal_todo_fmt, st.todoTotal, st.todoDone)
                setTextColor(theme.onSurface)
                textSize = UiTokens.TEXT_BODY
            })
        }

        box.addView(sectionLabel(getString(R.string.notes_cal_notes_sec)))
        val listInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (dayNotes.isEmpty()) {
            listInner.addView(emptyLine(getString(R.string.notes_empty_list)))
        } else {
            for (entry in dayNotes) {
                listInner.addView(textRow(entry.name, "") { jumpToNote(entry.name) })
                listInner.addView(divider())
            }
        }
        box.addView(
            ScrollView(this).apply {
                isFillViewport = false
                addView(listInner)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (NOTES_LIST_H_DP * d).toInt())
        )

        val title = getString(
            R.string.notes_cal_day_fmt,
            date.year, monthName(date.monthValue), date.dayOfMonth, weekdayName(date.dayOfWeek.value)
        )
        detailDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(getString(R.string.close), null)
            .showStyled(theme)
    }

    /** 点笔记直跳编辑：与标签页同构的双宿主分支（独立笔记页 vs 主界面）。 */
    private fun jumpToNote(name: String) {
        detailDialog?.dismiss()
        detailDialog = null
        if (intent.getBooleanExtra(EXTRA_FROM_STANDALONE, false)) {
            startActivity(
                Intent(this, NotesStandaloneActivity::class.java).apply {
                    putExtra(EXTRA_OPEN, name)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN, name)
            })
        }
    }

    /** 弹窗里的分区小标题（主题强调色）。 */
    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(theme.primaryVisible)
        typeface = Typeface.DEFAULT_BOLD
        textSize = UiTokens.TEXT_COMPACT
        setPadding(0, (12 * density()).toInt(), 0, (4 * density()).toInt())
    }

    /** 弹窗里的空态行。 */
    private fun emptyLine(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(theme.onSurfaceVariant)
        textSize = UiTokens.TEXT_META
        setPadding(0, 0, 0, (4 * density()).toInt())
    }

    /** 只展示、不响应点击的标签胶囊（外观与 tagCapsule 一致）。 */
    private fun staticCapsule(tag: String): TextView = TextView(this).apply {
        val d = density()
        val (fill, edge) = tagCapsuleColors(tag)
        text = tag
        setTextColor(TAG_TEXT_COLOR)
        textSize = UiTokens.TEXT_COMPACT
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 12 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), edge)
            setColor(fill)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (6 * d).toInt()
        }
    }

    private fun monthName(monthValue: Int): String =
        resources.getString(R.string.notes_cal_month_names).split(',')[monthValue - 1].trim()

    private fun weekdayName(dayOfWeekValue: Int): String = getString(weekdayRes[dayOfWeekValue - 1])

    /** 关页前收掉详情弹窗、停掉翻月动画（不给动画留回调），避免窗口泄漏告警。 */
    override fun onDestroy() {
        animAction = null
        monthAnimator?.cancel()
        monthAnimator = null
        detailDialog?.dismiss()
        detailDialog = null
        super.onDestroy()
    }

    companion object {
        private const val COLS = 7
        /** 弹窗笔记索引固定高度：超出滚动，保证标签/Todo 段不被挤出屏幕。 */
        private const val NOTES_LIST_H_DP = 220
        /** 换月/回弹补完动画时长（ms）。 */
        private const val ANIM_MS = 170L
        /** 出界拖动的阻尼比：拖不动的感觉，松手弹回。 */
        private const val BAND = 0.25f
        /** 松手换月的位移阈值（dp），与 pager 高度 1/3 取小。 */
        private const val SWIPE_COMMIT_DP = 90
        /** 快速甩动的速度阈值（dp/s）：位移不够但甩得快也换月。 */
        private const val FLING_VEL_DPS = 500
    }
}
