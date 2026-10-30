package com.workspace.proot

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记 tab 内容区（主界面第 1 位，可滑动切换）：列表态 / 编辑态。
 * 底部单排三键按状态切换，列表 [待办][标签] + 右下角新建 FAB，编辑 [＋待办][＋标签]。
 * 正文退出编辑器时自动落盘（退列表/切笔记/切 tab/后台/跳子页面），无保存键、无草稿。Dialog/跳转全部直调 MainActivity。
 */
class NotesController(
    private val activity: MainActivity,
    private val scope: AppScope,
    private val status: StatusController
) {

    private val theme: ThemeColors = scope.theme
    private val storeLazy = lazy {
        NotesStore(File(activity.filesDir, "workspace/Notes")).also { it.reload() }
    }
    private val store: NotesStore by storeLazy

    private var current: String? = null

    private lateinit var listScroll: ScrollView
    private lateinit var listInner: LinearLayout
    private lateinit var editorBox: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var editRow: LinearLayout
    private lateinit var fab: TextView
    private lateinit var editorTitle: TextView
    private lateinit var tagRow: TagFlowLayout
    private lateinit var todoBox: LinearLayout
    private lateinit var body: EditText
    private lateinit var searchInput: EditText
    private var query = ""
    private val searchDebounce = Runnable { renderList() }

    private fun density(): Float = activity.resources.displayMetrics.density

    fun buildInto(notesArea: LinearLayout) {
        // 拼音表后台预热（主界面笔记 Tab 入口；子页由 NotesPageActivity 负责，幂等）。
        PinyinDict.loadAsync { activity.assets.open(PinyinDict.ASSET) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        searchInput = buildSearchRow()
        content.addView(searchInput)
        val scrolled = scrollColumn()
        listScroll = scrolled.first
        listInner = scrolled.second
        content.addView(
            listScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        editorBox = buildEditorBox()
        content.addView(
            editorBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )
        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            addView(content)
            fab = buildFab()
            addView(fab)
        }
        root.addView(frame)
        root.addView(divider())
        navRow = buildNavRow()
        editRow = buildEditRow()
        root.addView(navRow)
        root.addView(editRow)
        notesArea.addView(
            buildSwipe(root),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        showList()
    }

    /**
     * 横滑容器（5.9.53：只保留"编辑态右滑退回列表"这一个手势）。
     *
     * 原来这里还兼着"切页签"（左滑去文件、右滑回终端）—— 那是 50dp 死区 + 松手
     * 播固定动画的实现，页面在拖动阶段完全不跟手。页签切换现已交给 ViewPager2
     * （拖动实时跟手 + 松手按速度结算），所以这两个回调删掉。
     *
     * 保留 `onSwipeRight` 的理由：**编辑态右滑退回列表**不是页签切换，是笔记页
     * 自己的手势，ViewPager2 不会替我们做。拖动未过半又松手时 ViewPager2 会回弹到
     * 原页，不会触发它 —— 所以右滑退回列表这件事交给这里没有冲突。
     */
    private fun buildSwipe(root: LinearLayout): TabSwipeLayout =
        TabSwipeLayout(
            activity,
            onSwipeRight = {
                if (activity.currentTab == 1 && current != null) showList()
            }
        ).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                root,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

    /** 主界面 onResume 与切到本 tab 时都调：重载索引并刷新当前视图。 */
    fun refresh() {
        store.reload()
        val cur = current
        if (cur != null && store.listNotes().none { it.name == cur }) {
            current = null
            showList()
        } else if (cur != null) {
            refreshEditorTitle()
            refreshTagRow()
            refreshTodoBox()
        } else {
            renderList()
        }
    }

    /** 返回键：编辑态退回列表 → 列表态清搜索词，都没得退再放行。 */
    fun handleBack(): Boolean {
        if (activity.currentTab == 1 && current != null) {
            showList()
            return true
        }
        if (activity.currentTab == 1 && NoteMatcher.terms(query).isNotEmpty()) {
            clearSearch()
            return true
        }
        return false
    }

    /** 随主界面销毁关闭笔记库连接（此前从不 close，连接一直泄漏）。 */
    fun onDestroy() {
        if (storeLazy.isInitialized()) runCatching { store.close() }
    }

    /** 搜索行：样式与标签/待办页一致（outlined + 同间距），200ms 防抖后重渲染列表。 */
    private fun buildSearchRow(): EditText {
        val d = density()
        return EditText(activity).apply {
            hint = activity.getString(R.string.notes_search_notes)
            setSingleLine(true)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
            setPaddingMd(12, 10, 12, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt()) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = text?.toString().orEmpty()
                    removeCallbacks(searchDebounce)
                    postDelayed(searchDebounce, NoteMatcher.SEARCH_DEBOUNCE_MS)
                }
            })
        }
    }

    /** 返回键在列表态先清搜索词。 */
    private fun clearSearch() {
        searchInput.removeCallbacks(searchDebounce)
        searchInput.setText("")
        query = ""
        renderList()
    }

    private fun buildEditorBox(): LinearLayout {
        val d = density()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        editorTitle = TextView(activity).apply {
            setTextColor(theme.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE)
            gravity = Gravity.CENTER
            setPaddingMd(16, 10, 16, 4)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(editorTitle)
        tagRow = TagFlowLayout(activity).apply {
            setPaddingMd(12, 0, 12, 4)
        }
        val tagScroll = MaxHeightScrollView(activity).apply {
            maxHeight = activity.resources.displayMetrics.heightPixels / TAG_AREA_DIVISOR
            isVerticalScrollBarEnabled = false
            addView(tagRow)
        }
        box.addView(
            tagScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        todoBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        box.addView(todoBox)
        body = EditText(activity).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(theme.onSurface)
            setHintTextColor(theme.onSurfaceVariant)
            textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
            gravity = Gravity.TOP or Gravity.START
            setPaddingMd(16, 6, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        box.addView(body)
        return box
    }

    private fun buildNavRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPaddingMd(UiTokens.SPACE_1, UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_0)
            addView(segButton(activity.getString(R.string.notes_todo_tab_btn)) {
                flushPendingSave()
                activity.startActivity(
                    android.content.Intent(activity, NotesTodoActivity::class.java)
                )
            })
            addView(segButton(activity.getString(R.string.notes_tags_tab_btn)) {
                flushPendingSave()
                activity.startActivity(
                    android.content.Intent(activity, NotesTagsActivity::class.java)
                )
            })
            addView(segButton(activity.getString(R.string.notes_cal_tab_btn)) {
                flushPendingSave()
                activity.startActivity(
                    android.content.Intent(activity, NotesCalendarActivity::class.java)
                )
            })
        }
        SegmentStyle.applyRow(
            row,
            listOf(null, null, null),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        return row
    }

    private fun buildEditRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPaddingMd(UiTokens.SPACE_1, UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_0)
            addView(segButton(activity.getString(R.string.notes_insert_todo)) {
                showTodoDialog()
            })
            addView(segButton(activity.getString(R.string.notes_insert_tag)) {
                current?.let { showTagDialog(it) }
            })
            visibility = android.view.View.GONE
        }
        SegmentStyle.applyRow(
            row,
            listOf(null, null),
            theme.outline,
            theme.onSurface,
            SegmentStyle.Bar(0f, false)
        )
        return row
    }

    /** 拟态凸起悬浮新建键：与文件页返回键共用 [NeuFab]，只换符号。 */
    private fun buildFab(): TextView = NeuFab.create(
        activity,
        theme,
        "＋",
        activity.getString(R.string.notes_new)
    ) { onNewPressed() }

    /** 退出保存：正文与落盘不一致才写回一次。退列表/切笔记/切 tab/后台前调用。 */
    fun flushPendingSave() {
        val cur = current ?: return
        val text = body.text?.toString().orEmpty()
        if (text != store.readNote(cur)) {
            store.saveNote(cur, text)
        }
    }

    private fun showList() {
        val cur = current
        val draft = body.text?.toString().orEmpty()
        flushPendingSave()
        current = null
        fab.visibility = android.view.View.VISIBLE
        navRow.visibility = android.view.View.VISIBLE
        editRow.visibility = android.view.View.GONE
        editorBox.visibility = android.view.View.GONE
        searchInput.visibility = android.view.View.VISIBLE
        listScroll.visibility = android.view.View.VISIBLE
        // 5.9.65：退出编辑时笔记已被删空（正文空 + 无待办，标签不算内容）→ 按开关删除或询问。
        // 打字中途不判定（新建的笔记一打开就是空的，实时判定会当场误杀），退出即统一结算。
        if (cur != null && store.isEmptyNote(cur, draft)) {
            if (scope.settingsManager.autoDeleteEmptyNote()) {
                store.deleteNote(cur)
                renderList()
                status.showTempStatus("Notes | ${activity.getString(R.string.notes_empty_deleted_fmt, cur)}")
            } else {
                renderList()
                showEmptyNoteDialog(cur)
            }
            return
        }
        renderList()
    }

    /** 空笔记询问弹窗：删除或保留（取消=保留，下次再被打开退出时还会问）。 */
    private fun showEmptyNoteDialog(name: String) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.notes_empty_confirm_fmt, name))
            .setPositiveButton(activity.getString(R.string.notes_delete)) { _, _ ->
                store.deleteNote(name)
                if (current == null) renderList()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    fun openNote(name: String) {
        flushPendingSave()
        if (store.listNotes().none { it.name == name }) {
            showList()
            return
        }
        current = name
        body.setText(store.readNote(name))
        refreshEditorTitle()
        refreshTagRow()
        refreshTodoBox()
        listScroll.visibility = android.view.View.GONE
        searchInput.visibility = android.view.View.GONE
        editorBox.visibility = android.view.View.VISIBLE
        fab.visibility = android.view.View.GONE
        navRow.visibility = android.view.View.GONE
        editRow.visibility = android.view.View.VISIBLE
    }

    private fun refreshEditorTitle() {
        val cur = current ?: return
        editorTitle.text = cur
    }

    private fun refreshTagRow() {
        val cur = current ?: return
        tagRow.removeAllViews()
        for (tag in store.tagsOf(cur)) {
            tagRow.addView(tagCapsule(tag) { removeTagEverywhere(cur, tag) })
        }
    }

    private fun removeTagEverywhere(noteName: String, tag: String) {
        store.detachTag(noteName, tag)
        val before = body.text?.toString().orEmpty()
        val token = Regex("(?<![\\p{L}\\p{N}_])#" + Regex.escape(tag) + "(?![\\p{L}\\p{N}_])")
        val after = token.replace(before, "")
        if (after != before) {
            body.setText(after)
            store.saveNote(noteName, after)
        }
        refreshTagRow()
        refreshTodoBox()
        status.showTempStatus("Notes | ${activity.getString(R.string.notes_tag_removed)}「$tag」")
    }

    private fun renderList() {
        listInner.removeAllViews()
        val terms = NoteMatcher.terms(query)
        // 三段式搜索：库索引召回 → 交集 → 批量取正文精排（零 .txt 读盘，排序语义与旧全量扫描一致）
        val notes = store.searchNotesFull(query)
        if (notes.isEmpty()) {
            listInner.addView(TextView(activity).apply {
                text = activity.getString(
                    if (terms.isEmpty()) R.string.notes_empty_list else R.string.notes_search_empty
                )
                setTextColor(theme.onSurfaceVariant)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                gravity = Gravity.CENTER
                val d = density()
                setPaddingMd(0, 48, 0, 0)
            })
            return
        }
        for (entry in notes) {
            listInner.addView(
                noteRow(entry) { openNote(entry.name) }.apply {
                    setOnLongClickListener { showRenameDialog(entry.name); true }
                }
            )
            listInner.addView(divider())
        }
    }

    private fun noteRow(entry: NoteEntry, onClick: () -> Unit): LinearLayout {
        val d = density()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(16, 10, 16, 10)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = entry.name
                    setTextColor(theme.onSurface)
                    textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(activity).apply {
                    text = ROW_FMT.format(Date(entry.updatedAt))
                    setTextColor(theme.onSurfaceVariant)
                    textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * d).toInt() }
                })
            })
            if (entry.tags.isNotEmpty()) {
                addView(TagFlowLayout(activity).apply {
                    setPaddingMd(0, 4, 0, 0)
                    for (tag in entry.tags) {
                        addView(tagCapsule(tag) { onClick() })
                    }
                })
            }
            setOnClickListener { onClick() }
        }
    }

    private fun onNewPressed() {
        inputDialog(
            activity.getString(R.string.notes_new),
            activity.getString(R.string.notes_name_hint),
            "",
            activity.getString(R.string.notes_new)
        ) { raw -> openNote(store.createNote(raw)) }
    }

    private fun showRenameDialog(old: String) {
        inputDialog(
            activity.getString(R.string.notes_rename),
            activity.getString(R.string.notes_name_hint),
            old,
            activity.getString(R.string.save)
        ) { raw ->
            flushPendingSave()
            val name = store.renameNote(old, raw)
            if (current == old) {
                current = name
                refreshEditorTitle()
                refreshTagRow()
                refreshTodoBox()
            }
            if (current == null) renderList()
            status.showTempStatus("Notes | ${activity.getString(R.string.notes_renamed)}「$name」")
        }
    }

    /** 挂标签：只写 header 索引，不再往正文插入 #标签。 */
    private fun showTagDialog(noteName: String) {
        val d = density()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(24, 8, 24, 0)
        }
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.notes_tag_hint)
            setSingleLine(true)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
            setPaddingMd(12, 10, 12, 10)
        }
        box.addView(input)
        val existing = store.allTags().map { it.first } - store.tagsOf(noteName).toSet()
        if (existing.isNotEmpty()) {
            box.addView(TextView(activity).apply {
                text = activity.getString(R.string.notes_common_tags)
                setTextColor(theme.onSurfaceVariant)
                textMd(UiTokens.TEXT_META, UiTokens.LINE_META)
                setPaddingMd(0, 12, 0, 4)
            })
            val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (tag in existing) {
                chips.addView(tagCapsule(tag) {
                    val cur = input.text?.toString().orEmpty()
                    val sep = if (cur.isBlank()) "" else " "
                    input.setText("$cur$sep$tag")
                    input.setSelection(input.text?.length ?: 0)
                })
            }
            val scroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
            box.addView(scroll)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.notes_add_tags))
            .setView(box)
            .setPositiveButton(activity.getString(R.string.notes_add_tags)) { _, _ ->
                val picked = parseTags(input.text?.toString().orEmpty())
                if (picked.isEmpty()) return@setPositiveButton
                store.attachTags(noteName, picked)
                if (current == noteName) refreshTagRow()
                if (current == null) renderList()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    /** 加待办：只进本笔记 header，不再往正文插入。 */
    private fun showTodoDialog() {
        val cur = current ?: return
        inputDialog(
            activity.getString(R.string.notes_add_todo),
            activity.getString(R.string.notes_todo_hint),
            "",
            activity.getString(R.string.notes_add_todo)
        ) { raw ->
            if (store.addTodo(raw, cur) != null) refreshTodoBox()
        }
    }

    private fun refreshTodoBox() {
        val cur = current ?: return
        todoBox.removeAllViews()
        val items = store.todosOf(cur)
        todoBox.visibility =
            if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        for (item in items) {
            todoBox.addView(noteTodoRow(item))
        }
    }

    private fun noteTodoRow(item: TodoItem): LinearLayout {
        val d = density()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingMd(16, 6, 16, 6)
            addView(TextView(activity).apply {
                text = if (item.done) "☑" else "☐"
                setTextColor(if (item.done) theme.primaryVisible else theme.onSurface)
                textMd(UiTokens.TEXT_TITLE, UiTokens.LINE_TITLE)
                setPaddingMd(0, 0, 10, 0)
                setOnClickListener { toggleTodo(item) }
            })
            addView(TextView(activity).apply {
                text = item.text
                setTextColor(if (item.done) theme.onSurfaceVariant else theme.onSurface)
                textMd(UiTokens.TEXT_BODY, UiTokens.LINE_BODY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (item.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener { editTodoText(item) }
            })
            setOnClickListener { toggleTodo(item) }
            setOnLongClickListener { confirmDeleteNoteTodo(item); true }
        }
    }

    private fun toggleTodo(item: TodoItem) {
        store.setTodoDone(item.id, !item.done)
        refreshTodoBox()
    }

    private fun editTodoText(item: TodoItem) {
        inputDialog(
            activity.getString(R.string.notes_edit_todo),
            activity.getString(R.string.notes_todo_hint),
            item.text,
            activity.getString(R.string.save)
        ) { raw ->
            store.setTodoText(item.id, raw)
            refreshTodoBox()
        }
    }

    private fun confirmDeleteNoteTodo(item: TodoItem) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.notes_confirm_delete_fmt, item.text))
            .setPositiveButton(activity.getString(R.string.notes_delete)) { _, _ ->
                store.deleteTodo(item.id)
                refreshTodoBox()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(TAG_SPLIT).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

    private fun segButton(text: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            textSize = AppLang.bottomButtonTextSize(activity)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 5.9.40：原 3dp 不在 4dp 网格上，按 MD3 上调到 4dp（SPACE_1）
            setPaddingMd(UiTokens.SPACE_1, 0, UiTokens.SPACE_1, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // 5.9.41：统一圆角来源（此前是 MD2 主题自带圆角）
            ButtonStyle.apply(this, theme.primary, theme.primaryVisible)
            setOnClickListener { onClick() }
        }

    private fun tagCapsule(tag: String, onClick: () -> Unit): TextView {
        val d = density()
        val (fill, edge) = tagCapsuleColors(tag)
        return TextView(activity).apply {
            text = tag
            setTextColor(TAG_TEXT_COLOR)
            textMd(UiTokens.TEXT_COMPACT, UiTokens.LINE_COMPACT)
            maxLines = 1
            setPaddingMd(12, 4, 12, 4)
            if (tag.length > MARQUEE_CHARS) {
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = MARQUEE_FOREVER
                maxWidth = (MARQUEE_MAX_DP * d).toInt()
                isSelected = true
            } else {
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            background = GradientDrawable().apply {
                cornerRadius = snapCorner(UiTokens.CORNER_MD) * d
                setStroke((1 * d).toInt().coerceAtLeast(1), edge)
                setColor(fill)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun divider(): android.view.View = SegmentStyle.hDivider(activity, theme.onSurface)

    private fun scrollColumn(): Pair<ScrollView, LinearLayout> {
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(inner) }
        return scroll to inner
    }

    private fun inputDialog(
        title: String,
        hint: String,
        initial: String,
        confirmText: String,
        onConfirm: (String) -> Unit
    ) {
        val d = density()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingMd(24, 8, 24, 0)
        }
        val input = EditText(activity).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            setSingleLine(true)
            FieldStyle.applyOutlined(this, theme, UiTokens.TEXT_BODY)
            setPaddingMd(12, 10, 12, 10)
        }
        box.addView(input)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(confirmText) { _, _ -> onConfirm(input.text?.toString().orEmpty()) }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    companion object {
        private val ROW_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val TAG_SPLIT = Regex("[\\s,，、]+")
        private const val TAG_AREA_DIVISOR = 3
    }
}
