package com.workspace.proot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

class ShortcutGroupActivity : Activity() {

    private val theme = ThemeColors.default()
    private lateinit var root: LinearLayout
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: ShortcutSettingsAdapter
    private lateinit var recyclerView: RecyclerView
    private val mainItems = mutableListOf<ShortcutItem>()
    private val memberItems = mutableListOf<ShortcutItem>()
    private var group: ShortcutItem.Group? = null
    private var groupName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupName = intent?.getStringExtra("group_name") ?: ""
        val prefs = getSharedPreferences("term-lou-settings", MODE_PRIVATE)
        settingsManager = SettingsManager(prefs)
        mainItems.addAll(settingsManager.loadShortcuts())
        group = mainItems.filterIsInstance<ShortcutItem.Group>()
            .firstOrNull { it.name == groupName }
        if (group == null) {
            finish()
            return
        }
        rebuildMemberItems()

        val d = resources.displayMetrics.density

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.surfaceVariant)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }

        val titleTv = TextView(this).apply {
            text = "\uD83D\uDCC1 " + groupName
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_TITLE
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleTv)

        root.addView(titleBar)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ShortcutGroupActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }
        root.addView(recyclerView)

        val hintTv = TextView(this).apply {
            text = "\u957f\u6309\u62d6\u52a8\u6392\u5e8f \u00b7 \u5de6\u6ed1\u547d\u4ee4"
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setBackgroundColor(theme.surfaceVariant)
        }
        root.addView(hintTv)

        val exitBtn = Button(this).apply {
            text = "\u9000\u51fa\u547d\u4ee4\u7ec4"
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            ButtonStyle.apply(this, theme.primary)
            setOnClickListener { finish() }
        }
        root.addView(exitBtn)

        setContentView(root)

        setupAdapter()
    }

    private fun rebuildMemberItems() {
        memberItems.clear()
        group?.members?.forEach { m ->
            memberItems.add(ShortcutItem.Command(m.id, m.label, m.cmd))
        }
    }

    private fun setupAdapter() {
        adapter = ShortcutSettingsAdapter(
            items = memberItems,
            theme = theme,
            memberMode = true,
            onCommandClick = { pos, label, cmd -> showMemberEditDialog(pos, label, cmd) },
            onGroupClick = { _, _ -> },
            onCommandSwiped = { pos -> showMemberAction(pos) },
            onGroupSwiped = { _, _ -> },
            onMove = { _, _ -> persist(false) },
            onMerge = { _, _ -> },
            onJoin = { _, _ -> }
        )
        recyclerView.adapter = adapter

        val touchHelper = ShortcutSettingsAdapter.createItemTouchHelper(adapter)
        touchHelper.attachToRecyclerView(recyclerView)
    }

    private fun persist(rebuild: Boolean) {
        val g = group ?: return
        g.members.clear()
        for (it in memberItems) {
            val c = it as ShortcutItem.Command
            g.members.add(ShortcutItem.Command(c.id, c.label, c.cmd))
        }
        settingsManager.saveShortcuts(mainItems.toList())
        if (rebuild) rebuildMemberItems()
    }

    private fun showMemberEditDialog(pos: Int, oldLabel: String, oldCmd: String) {
        if (pos !in memberItems.indices) return
        val d = resources.displayMetrics.density
        val nameEdit = EditText(this).apply {
            setText(oldLabel)
            hint = "\u540d\u79f0\uff08\u53ef\u4e0d\u586b\uff09"
            setTextColor(Color.WHITE)
            setHintTextColor(theme.onSurfaceVariant)
            setBackgroundColor(theme.outline)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val cmdEdit = EditText(this).apply {
            setText(oldCmd)
            hint = "\u547d\u4ee4\uff08\u652f\u6301 \\n \\t \\e \\cX\uff1b\u7528 @{} \u6807\u8bb0\u5149\u6807\uff09"
            setTextColor(Color.WHITE)
            setHintTextColor(theme.onSurfaceVariant)
            setBackgroundColor(theme.outline)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            addView(nameEdit)
            addView(cmdEdit)
        }
        val id = (memberItems[pos] as? ShortcutItem.Command)?.id
        val builder = AlertDialog.Builder(this)
            .setTitle("\u7f16\u8f91\u547d\u4ee4")
            .setView(body)
            .setPositiveButton("\u786e\u5b9a") { _, _ ->
                if (pos !in memberItems.indices) return@setPositiveButton
                var label = nameEdit.text.toString().trim()
                val cmd = cmdEdit.text.toString().trim()
                if (cmd.isBlank()) return@setPositiveButton
                if (label.isBlank()) label = cmd
                val old = memberItems[pos] as? ShortcutItem.Command
                memberItems[pos] = ShortcutItem.Command(old?.id ?: settingsManager.newId(), label, cmd)
                persist(false)
                adapter.notifyItemChanged(pos)
            }
            .setNegativeButton("\u53d6\u6d88", null)
        if (id != null) {
            builder.setNeutralButton("\u9891\u6b21\u6e05\u96f6") { _, _ ->
                settingsManager.clearCommandUsage(id)
                Snackbar.make(root, "\u5df2\u6e05\u96f6\u8be5\u547d\u4ee4\u6240\u6709\u573a\u666f\u7684\u9891\u6b21", Snackbar.LENGTH_SHORT).show()
            }
        }
        val dialog = builder.create()
        dialog.show()
        DialogStyler.apply(dialog, theme)
    }

    private fun showMemberAction(pos: Int) {
        if (pos !in memberItems.indices) return
        val m = memberItems[pos] as ShortcutItem.Command
        val label = m.label.ifBlank { m.cmd.take(16) + "\u2026" }
        val dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("\u79fb\u51fa\u547d\u4ee4\u7ec4", "\u5220\u9664\u547d\u4ee4")) { _, which ->
                when (which) {
                    0 -> moveOut(pos)
                    1 -> deleteMember(pos)
                }
            }
            .setNegativeButton("\u53d6\u6d88", null)
            .create()
        dialog.show()
        DialogStyler.apply(dialog, theme)
    }

    private fun moveOut(pos: Int) {
        if (pos !in memberItems.indices) return
        val m = memberItems.removeAt(pos) as ShortcutItem.Command
        persist(false)
        val gi = mainItems.indexOfFirst { it === group }
        if (gi < 0) {
            settingsManager.saveShortcuts(mainItems.toList())
            finish()
            return
        }
        mainItems.add(gi, ShortcutItem.Command(m.id, m.label, m.cmd))
        if (applyGroupReduction(gi + 1)) {
            settingsManager.saveShortcuts(mainItems.toList())
            finish()
        } else {
            settingsManager.saveShortcuts(mainItems.toList())
            adapter.notifyDataSetChanged()
        }
    }

    private fun deleteMember(pos: Int) {
        if (pos !in memberItems.indices) return
        memberItems.removeAt(pos)
        persist(false)
        val gi = mainItems.indexOfFirst { it === group }
        if (gi < 0) {
            settingsManager.saveShortcuts(mainItems.toList())
            finish()
            return
        }
        if (applyGroupReduction(gi)) {
            settingsManager.saveShortcuts(mainItems.toList())
            finish()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun applyGroupReduction(gi: Int): Boolean {
        val g = group ?: return false
        if (gi !in mainItems.indices) return false
        return when {
            g.members.isEmpty() -> {
                mainItems.removeAt(gi)
                true
            }
            g.members.size == 1 -> {
                val m = g.members[0]
                mainItems[gi] = ShortcutItem.Command(m.id, m.label, m.cmd)
                true
            }
            else -> false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (group != null) settingsManager.saveShortcuts(mainItems.toList())
    }
}
