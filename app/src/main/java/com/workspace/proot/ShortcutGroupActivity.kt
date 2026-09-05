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
        mainItems.addAll(settingsManager.loadShortcuts(this))
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
            text = getString(R.string.sc_hint_group)
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setBackgroundColor(theme.surfaceVariant)
        }
        root.addView(hintTv)

        val exitBtn = Button(this).apply {
            text = getString(R.string.sc_exit_group)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = true
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
            hint = getString(R.string.sc_name_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(theme.onSurfaceVariant)
            setBackgroundColor(theme.outline)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val cmdEdit = EditText(this).apply {
            setText(oldCmd)
            hint = getString(R.string.sc_cmd_hint)
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
            .setTitle(getString(R.string.sc_edit_title))
            .setView(body)
            .setPositiveButton(getString(R.string.sc_confirm)) { _, _ ->
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
            .setNegativeButton(getString(R.string.cancel), null)
        if (id != null) {
            builder.setNeutralButton(getString(R.string.sc_freq_reset)) { _, _ ->
                settingsManager.clearCommandUsage(id)
                Snackbar.make(root, getString(R.string.sc_freq_cleared), Snackbar.LENGTH_SHORT).show()
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
            .setItems(arrayOf(getString(R.string.sc_move_out), getString(R.string.sc_delete_cmd))) { _, which ->
                when (which) {
                    0 -> moveOut(pos)
                    1 -> deleteMember(pos)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
