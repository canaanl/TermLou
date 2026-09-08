package com.workspace.proot

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class ShortcutManager(
    private val context: Context,
    private val theme: ThemeColors,
    private val settingsManager: SettingsManager,
    private val writeFn: (String) -> Unit,
    private val onCardUsed: (label: String, state: String, count: Int) -> Unit = { _, _, _ -> },
    private val onCommandExecuted: () -> Unit = {}
) : WheelDataSource {
    private var statusText: TextView? = null
    private val density = context.resources.displayMetrics.density
    private val cardW = context.resources.displayMetrics.widthPixels / 4

    private var lastList: List<ShortcutItem> = emptyList()
    private var lastEmptyCount = 0
    private var usageMap = settingsManager.loadUsageMap()
    private var seqMap = settingsManager.loadSeqMap()
    private var lastUsedMap = settingsManager.loadLastUsedMap()
    private var lastClickContext: Pair<String, String>? = null
    private var prevKey: String? = null
    private val TIE_BAND = 0.05f

    fun setStatusText(statusText: TextView) {
        this.statusText = statusText
    }

    fun refreshAllRows(
        rowTop: LinearLayout,
        rowBottom: LinearLayout,
        shortcutInner: LinearLayout,
        createShortcutKey: (String, String, Boolean, String, Int) -> Button,
        createCtrlKey: (Int) -> Button
    ) {
        rowTop.removeAllViews()
        rowBottom.removeAllViews()
        shortcutInner.removeViews(1, shortcutInner.childCount - 1)

        rowTop.addView(createShortcutKey("/", "/", false, "", cardW))
        rowTop.addView(createShortcutKey("Tab", "\t", false, "", cardW))
        rowTop.addView(createShortcutKey("Esc", "\u001b", false, "", cardW))
        rowTop.addView(createCtrlKey(cardW))

        rowBottom.addView(createShortcutKey("\u2191", "\u001b[A", true, "\u001b[1;5A", cardW))
        rowBottom.addView(createShortcutKey("\u2193", "\u001b[B", true, "\u001b[1;5B", cardW))
        rowBottom.addView(createShortcutKey("\u2190", "\u001b[D", true, "\u001b[1;5D", cardW))
        rowBottom.addView(createShortcutKey("\u2192", "\u001b[C", true, "\u001b[1;5C", cardW))
    }

    private fun reloadUsage() {
        usageMap = settingsManager.loadUsageMap()
        seqMap = settingsManager.loadSeqMap()
        lastUsedMap = settingsManager.loadLastUsedMap()
    }

    override fun execute(item: ShortcutItem) {
        when (item) {
            is ShortcutItem.Command -> {
                reloadUsage()
                val state = TuiStateDetector.refresh()
                if (lastClickContext?.first != state) lastClickContext = null
                val counted: Int
                if (lastClickContext?.second == item.id) {
                    counted = usageMap[state]?.get(item.id) ?: 0
                } else {
                    val stateMap = usageMap.getOrPut(state) { mutableMapOf() }
                    stateMap[item.id] = (stateMap[item.id] ?: 0) + 1
                    settingsManager.saveUsageMap(usageMap)
                    settingsManager.recordSeq(state, item.id)
                    settingsManager.recordLastUsed(item.id)
                    lastClickContext = state to item.id
                    counted = stateMap[item.id]!!
                }
                onCardUsed(item.label, state, counted)
                writeFn(buildWritePayload(interpretEscapes(item.cmd)))
                onCommandExecuted()
                prevKey = item.id
            }
            is ShortcutItem.Group -> {}
        }
    }

    override fun loadShortcutList(): List<ShortcutItem> {
        reloadUsage()
        val list = settingsManager.loadShortcuts(context)
        lastList = list
        lastEmptyCount = emptyCountFor(list)
        return list
    }

    override fun recommendedStartPos(force: Boolean): Int {
        if (force) TuiStateDetector.refresh() else TuiStateDetector.getCurrentState()
        if (lastList.isEmpty()) return Int.MAX_VALUE / 2
        return computeWheelStartPos(lastList, lastEmptyCount, Int.MAX_VALUE / 2, lastList.size + lastEmptyCount)
    }

    override fun groupStartPos(
        members: List<ShortcutItem.Command>,
        emptyCount: Int,
        base: Int,
        cycleSize: Int
    ): Int {
        if (cycleSize <= 0) return base
        val idx = bestMemberIndex(members)
        val slot = if (idx == null) emptyCount else emptyCount + idx
        return base - (base % cycleSize) + slot
    }

    override fun wheelCycleSize(): Int = lastList.size + lastEmptyCount

    override fun emptyCountFor(list: List<ShortcutItem>): Int = when {
        list.size <= 2 -> 1
        else -> 0
    }

    fun computeWheelStartPos(
        list: List<ShortcutItem>,
        emptyCount: Int,
        base: Int,
        cycleSize: Int
    ): Int {
        if (cycleSize <= 0) return base
        val recIndex = recommendedIndex(list)
        val slot = if (recIndex == null) emptyCount else emptyCount + recIndex
        return base - (base % cycleSize) + slot
    }

    private fun recommendedIndex(list: List<ShortcutItem>): Int? {
        val state = TuiStateDetector.getCurrentState()
        val stateUsage = usageMap[state]
        val seq = seqMap[state] ?: emptyList()
        val now = System.currentTimeMillis()
        var best: Int? = null
        var bestScore = 0f
        var bestUsed = 0L
        for ((i, item) in list.withIndex()) {
            val (score, id) = itemScore(item, stateUsage, seq, now)
            if (score <= 0f) continue
            val used = id?.let { lastUsedMap[it] } ?: 0L
            if (best == null || score > bestScore + bestScore * TIE_BAND ||
                (score >= bestScore - bestScore * TIE_BAND && used > bestUsed)
            ) {
                best = i
                bestScore = score
                bestUsed = used
            }
        }
        return best
    }

    private fun bestMemberIndex(members: List<ShortcutItem.Command>): Int? {
        val state = TuiStateDetector.getCurrentState()
        val stateUsage = usageMap[state]
        val seq = seqMap[state] ?: emptyList()
        val now = System.currentTimeMillis()
        var best: Int? = null
        var bestScore = 0f
        var bestUsed = 0L
        for ((i, m) in members.withIndex()) {
            val score = CommandRecommender.summonScore(
                m.id, stateUsage, seq, prevKey, usageMap, lastUsedMap, now
            )
            if (score <= 0f) continue
            val used = lastUsedMap[m.id] ?: 0L
            if (best == null || score > bestScore + bestScore * TIE_BAND ||
                (score >= bestScore - bestScore * TIE_BAND && used > bestUsed)
            ) {
                best = i
                bestScore = score
                bestUsed = used
            }
        }
        return best
    }

    private fun itemScore(
        item: ShortcutItem,
        stateUsage: Map<String, Int>?,
        seq: List<String>,
        now: Long
    ): Pair<Float, String?> {
        when (item) {
            is ShortcutItem.Command -> return CommandRecommender.summonScore(
                item.id, stateUsage, seq, prevKey, usageMap, lastUsedMap, now
            ) to item.id
            is ShortcutItem.Group -> {
                var bestScore = 0f
                var bestId: String? = null
                for (m in item.members) {
                    val s = CommandRecommender.summonScore(
                        m.id, stateUsage, seq, prevKey, usageMap, lastUsedMap, now
                    )
                    if (s > bestScore) {
                        bestScore = s
                        bestId = m.id
                    }
                }
                return bestScore to bestId
            }
        }
    }

    fun showCardEditDialog(index: Int, oldLabel: String, oldCmd: String, onSaved: () -> Unit) {
        val nameEdit = EditText(context).apply {
            setText(oldLabel)
            hint = context.getString(R.string.sc_name_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(theme.onSurfaceVariant)
            setBackgroundColor(theme.outline)
            setPadding(8, 6, 8, 6)
        }
        val cmdEdit = EditText(context).apply {
            setText(oldCmd)
            hint = context.getString(R.string.sc_cmd_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(theme.onSurfaceVariant)
            setBackgroundColor(theme.outline)
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            addView(nameEdit)
            addView(cmdEdit)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (index < 0) context.getString(R.string.sc_add) else context.getString(R.string.sc_edit))
            .setView(body)
            .setPositiveButton(context.getString(R.string.sc_confirm)) { _, _ ->
                var label = nameEdit.text.toString().trim()
                val cmd = cmdEdit.text.toString().trim()
                if (cmd.isBlank()) return@setPositiveButton
                if (label.isBlank()) label = cmd
                val list = settingsManager.loadShortcuts(context)
                val id = list.getOrNull(index)?.let { (it as? ShortcutItem.Command)?.id }
                    ?: settingsManager.newId()
                if (index < 0) list.add(ShortcutItem.Command(id, label, cmd))
                else list[index] = ShortcutItem.Command(id, label, cmd)
                settingsManager.saveShortcuts(list)
                onSaved()
            }
            .setNeutralButton(context.getString(R.string.sc_delete)) { _, _ ->
                val list = settingsManager.loadShortcuts(context)
                if (index in list.indices) {
                    list.removeAt(index)
                    settingsManager.saveShortcuts(list)
                    onSaved()
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun interpretEscapes(s: String): String {
        return s.replace("\\n", "\n").replace("\\r", "\r")
            .replace("\\t", "\t").replace("\\e", "\u001b")
            .replace(Regex("\\\\c([A-Za-z])")) { match ->
                val key = match.groupValues[1].toLowerCase()
                val code = key[0] - 'a' + 1
                String(charArrayOf(code.toChar()))
            }
    }
}

const val CURSOR_PLACEHOLDER = "@{}"

fun buildWritePayload(raw: String): String {
    val idx = raw.indexOf(CURSOR_PLACEHOLDER)
    if (idx < 0) return raw
    val text = raw.replace(CURSOR_PLACEHOLDER, "")
    val back = text.length - idx
    return if (back > 0) text + "\u001b[D".repeat(back) else text
}
