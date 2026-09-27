package com.workspace.proot

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
 * 待办三态小图标（红空 ○/黄半 ◐/绿满 ✔，无待办不画、不显数量）、标签 #（有/无不显名）。
 * 上下滑动翻月，到底（更早已无内容）或翻未来时拦住不翻并 Toast 提示；
 * 点日期弹当日详情（标签名 + Todo 总数/完成数 + 固定高度滚动笔记索引），
 * 点笔记直接进编辑页，无中间步骤；溢出月格子只显淡化数字、不可点。
 */
class NotesCalendarActivity : NotesPageActivity() {

    private var month: YearMonth = YearMonth.now()
    private lateinit var monthTitle: TextView
    private lateinit var gridBox: LinearLayout
    private var status: Map<LocalDate, NotesCalendar.DayStatus> = emptyMap()
    private var detailDialog: AlertDialog? = null
    /** 全库最早的有内容月份（笔记+待办），随 onResume 刷新；null = 一条内容都没有。 */
    private var contentEarliest: YearMonth? = null

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
        root.addView(buildTitleBar(getString(R.string.notes_cal_tab)))

        // 纵滑翻月挂容器级 intercept（OnTouchListener 会被消费触摸的格子屏蔽）。
        val body = MonthSwipeLayout(
            this,
            onSwipeUp = { shiftMonth(1) },
            onSwipeDown = { shiftMonth(-1) }
        ).apply { orientation = LinearLayout.VERTICAL }

        monthTitle = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = UiTokens.TEXT_TITLE
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.onSurface)
            contentDescription = getString(R.string.notes_cal_back_now)
            setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
            setOnClickListener {
                month = YearMonth.now()
                render()
            }
        }
        body.addView(monthTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(weekdayRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(gridBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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

    /**
     * 翻月：范围内直接翻；出界拦住不翻页并 Toast 说明原因——
     * 早于最早内容月 = 「没有更旧的笔记了」，晚于本月 = 「未来还没到呢」。
     */
    private fun shiftMonth(delta: Long) {
        val verdict = NotesCalendar.shiftVerdict(month, delta, YearMonth.now(), boundaryEarliest())
        when (verdict) {
            NotesCalendar.ShiftVerdict.OK -> {
                month = month.plusMonths(delta)
                render()
            }
            NotesCalendar.ShiftVerdict.BLOCK_NO_OLDER ->
                Toast.makeText(this, R.string.notes_cal_no_older, Toast.LENGTH_SHORT).show()
            NotesCalendar.ShiftVerdict.BLOCK_FUTURE ->
                Toast.makeText(this, R.string.notes_cal_future, Toast.LENGTH_SHORT).show()
        }
    }

    /** 月历整体重建（数据量小，滑动翻月/回本月都直接重铺 42 格）。 */
    private fun render() {
        monthTitle.text = getString(
            R.string.notes_cal_month_fmt, month.year, monthName(month.monthValue)
        )
        gridBox.removeAllViews()
        val y = month.year
        val m = month.monthValue
        val cells = NotesCalendar.monthGrid(y, m)
        val today = LocalDate.now()
        for (row in 0 until ROWS) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until COLS) {
                rowView.addView(cell(cells[row * COLS + col], y, m, today))
            }
            gridBox.addView(rowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
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
     * 格与格之间靠 2dp margin 留空，不再挤成一片；今天在容器上套主色描边。
     */
    private fun cell(date: LocalDate, y: Int, m: Int, today: LocalDate): View {
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
                if (isToday) setStroke((1.5 * d).toInt().coerceAtLeast(1), theme.primary)
            }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ).apply {
                marginStart = (2 * d).toInt()
                marginEnd = (2 * d).toInt()
                topMargin = (2 * d).toInt()
                bottomMargin = (2 * d).toInt()
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
            // 有笔记不画图标（由格子填充表达），这里只剩待办三态与标签 #
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
        setTextColor(theme.primary)
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

    /** 关页前收掉详情弹窗，避免窗口泄漏告警。 */
    override fun onDestroy() {
        detailDialog?.dismiss()
        detailDialog = null
        super.onDestroy()
    }

    companion object {
        private const val ROWS = 6
        private const val COLS = 7
        /** 弹窗笔记索引固定高度：超出滚动，保证标签/Todo 段不被挤出屏幕。 */
        private const val NOTES_LIST_H_DP = 220
    }
}
