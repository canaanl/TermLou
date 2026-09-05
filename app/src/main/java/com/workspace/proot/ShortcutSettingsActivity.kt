package com.workspace.proot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShortcutSettingsActivity : ComponentActivity() {

    private val theme = ThemeColors.default()
    private val prefs by lazy { getSharedPreferences("term-lou-settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: ShortcutSettingsAdapter
    private lateinit var recyclerView: RecyclerView
    private val items = mutableListOf<ShortcutItem>()

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { readRestore(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager(prefs)
        items.addAll(settingsManager.loadShortcuts(this))

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
            text = getString(R.string.sc_manage_title)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_TITLE
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleTv)

        root.addView(titleBar)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ShortcutSettingsActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }
        root.addView(recyclerView)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setBackgroundColor(theme.surfaceVariant)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val newBtn = Button(this).apply {
            text = getString(R.string.sc_new)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * d).toInt()
            }
            ButtonStyle.apply(this, theme.primary)
            setOnClickListener { showEditDialog(-1, "", "") }
        }
        btnRow.addView(newBtn)

        val exitBtn = Button(this).apply {
            text = getString(R.string.sc_exit)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * d).toInt()
            }
            ButtonStyle.apply(this, theme.primary)
            setOnClickListener { finish() }
        }
        btnRow.addView(exitBtn)

        bottomBar.addView(btnRow)

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }

        val backupBtn = Button(this).apply {
            text = getString(R.string.sc_backup)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * d).toInt()
            }
            ButtonStyle.apply(this, theme.primary)
            setOnClickListener { backupWheel() }
        }
        btnRow2.addView(backupBtn)

        val restoreBtn = Button(this).apply {
            text = getString(R.string.sc_restore)
            setTextColor(Color.WHITE)
            textSize = UiTokens.TEXT_BODY
            isAllCaps = false
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * d).toInt()
            }
            ButtonStyle.apply(this, theme.primary)
            setOnClickListener { restoreLauncher.launch(arrayOf("*/*")) }
        }
        btnRow2.addView(restoreBtn)

        bottomBar.addView(btnRow2)

        val hintTv = TextView(this).apply {
            text = getString(R.string.sc_disband_msg)
            setTextColor(theme.onSurfaceVariant)
            textSize = UiTokens.TEXT_META
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        bottomBar.addView(hintTv)

        root.addView(bottomBar)

        setContentView(root)

        setupAdapter()
    }

    override fun onResume() {
        super.onResume()
        items.clear()
        items.addAll(settingsManager.loadShortcuts(this))
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun setupAdapter() {
        adapter = ShortcutSettingsAdapter(
            items = items,
            theme = theme,
            onCommandClick = { index, label, cmd -> showEditDialog(index, label, cmd) },
            onGroupClick = { _, name -> openGroup(name) },
            onCommandSwiped = { pos -> deleteCommand(pos) },
            onGroupSwiped = { pos, name -> dissolveGroup(pos, name) },
            onMove = { _, _ -> saveList() },
            onMerge = { from, target -> askMerge(from, target) },
            onJoin = { from, target -> askJoin(from, target) }
        )
        recyclerView.adapter = adapter

        val touchHelper = ShortcutSettingsAdapter.createItemTouchHelper(adapter)
        touchHelper.attachToRecyclerView(recyclerView)
    }

    private fun openGroup(name: String) {
        startActivity(Intent(this, ShortcutGroupActivity::class.java).putExtra("group_name", name))
    }

    private fun deleteCommand(pos: Int) {
        if (pos !in items.indices) return
        items.removeAt(pos)
        adapter.notifyDataSetChanged()
        saveList()
    }

    private fun dissolveGroup(pos: Int, name: String) {
        if (pos !in items.indices) return
        val group = items[pos] as? ShortcutItem.Group ?: return
        val msg = getString(R.string.sc_disband_msg_fmt, name, group.members.size)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sc_disband_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.sc_disband)) { _, _ ->
                items.removeAt(pos)
                var i = pos
                for (m in group.members) {
                    items.add(i++, ShortcutItem.Command(m.id, m.label, m.cmd))
                }
                adapter.notifyDataSetChanged()
                saveList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun askMerge(from: Int, target: Int) {
        if (from !in items.indices || target !in items.indices) return
        val src = items[from] as? ShortcutItem.Command ?: return
        val tgt = items[target] as? ShortcutItem.Command ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.sc_merge_title))
            .setMessage(getString(R.string.sc_merge_msg_fmt, src.label.ifBlank { src.cmd }, tgt.label.ifBlank { tgt.cmd }))
            .setPositiveButton(getString(R.string.sc_merge_ok)) { _, _ -> showGroupNameDialog(from, target) }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun showGroupNameDialog(from: Int, target: Int) {
        if (from !in items.indices || target !in items.indices) return
        val src = items[from] as? ShortcutItem.Command ?: return
        val d = resources.displayMetrics.density
        val nameEdit = EditText(this).apply {
            setText(src.label.ifBlank { "" })
            hint = getString(R.string.sc_group_name_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(theme.onSurfaceVariant)
            setBackgroundColor(theme.outline)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            addView(nameEdit)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sc_name_group))
            .setView(body)
            .setPositiveButton(getString(R.string.sc_create)) { _, _ ->
                if (from !in items.indices || target !in items.indices) return@setPositiveButton
                val a = items[from] as? ShortcutItem.Command ?: return@setPositiveButton
                val b = items[target] as? ShortcutItem.Command ?: return@setPositiveButton
                var name = nameEdit.text.toString().trim()
                if (name.isBlank()) name = a.label.ifBlank { getString(R.string.sc_default_group) }
                val idxA = minOf(from, target)
                val idxB = maxOf(from, target)
                items.removeAt(idxB)
                items.removeAt(idxA)
                items.add(idxA, ShortcutItem.Group(name, mutableListOf(a, b)))
                adapter.notifyDataSetChanged()
                saveList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun askJoin(from: Int, target: Int) {
        if (from !in items.indices || target !in items.indices) return
        val src = items[from] as? ShortcutItem.Command ?: return
        val grp = items[target] as? ShortcutItem.Group ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sc_join_title))
            .setMessage(getString(R.string.sc_join_msg_fmt, src.label.ifBlank { src.cmd }, grp.name))
            .setPositiveButton(getString(R.string.sc_join)) { _, _ ->
                if (from !in items.indices || target !in items.indices) return@setPositiveButton
                val cmd = items[from] as? ShortcutItem.Command ?: return@setPositiveButton
                val g = items[target] as? ShortcutItem.Group ?: return@setPositiveButton
                g.members.add(cmd)
                items.removeAt(from)
                adapter.notifyDataSetChanged()
                saveList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private fun showEditDialog(index: Int, oldLabel: String, oldCmd: String) {
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
        val builder = AlertDialog.Builder(this)
            .setTitle(if (index < 0) getString(R.string.sc_new_title) else getString(R.string.sc_edit_title))
            .setView(body)
            .setPositiveButton(getString(R.string.sc_confirm)) { _, _ ->
                var label = nameEdit.text.toString().trim()
                val cmd = cmdEdit.text.toString().trim()
                if (cmd.isBlank()) return@setPositiveButton
                if (label.isBlank()) label = cmd
                if (index < 0) {
                    items.add(ShortcutItem.Command(settingsManager.newId(), label, cmd))
                    adapter.notifyItemInserted(items.size - 1)
                } else {
                    val old = items[index] as? ShortcutItem.Command
                    items[index] = ShortcutItem.Command(old?.id ?: settingsManager.newId(), label, cmd)
                    adapter.notifyItemChanged(index)
                }
                saveList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
        if (index >= 0) {
            val id = (items[index] as? ShortcutItem.Command)?.id
            if (id != null) {
                builder.setNeutralButton(getString(R.string.sc_freq_reset)) { _, _ ->
                    settingsManager.clearCommandUsage(id)
                    Snackbar.make(root, getString(R.string.sc_freq_cleared), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
        val dialog = builder.create()
        dialog.show()
        DialogStyler.apply(dialog, theme)
    }

    private fun saveList() {
        settingsManager.saveShortcuts(items.toList())
    }

    // ===== 备份 / 恢复 =====

    private fun backupWheel() {
        try {
            val envelope = org.json.JSONObject().apply {
                put("type", BACKUP_TYPE)
                put("version", BACKUP_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("shortcuts", org.json.JSONArray(prefs.getString("shortcuts", "[]")))
                put("usage", org.json.JSONObject(prefs.getString("tuiUsageMap", "{}")))
                put("seq", org.json.JSONObject(prefs.getString("tuiSeqMap", "{}")))
                put("lastUsed", org.json.JSONObject(prefs.getString("cmdLastUsed", "{}")))
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(cacheDir, "tmp").apply { mkdirs() }
            val out = File(dir, "termlou_wheel_backup_$stamp.json")
            out.writeText(envelope.toString())
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.sc_backup_to)))
        } catch (e: Exception) {
            Snackbar.make(root, getString(R.string.sc_backup_fail_fmt, e.message), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun readRestore(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
        val parsed = text?.let { runCatching { parseBackup(it) }.getOrNull() }
        if (parsed == null) {
            Snackbar.make(root, getString(R.string.sc_restore_invalid), Snackbar.LENGTH_LONG).show()
            return
        }
        var cmds = 0
        var groups = 0
        for (item in parsed.items) {
            if (item is ShortcutItem.Group) groups++ else cmds++
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sc_restore_title))
            .setMessage(getString(R.string.sc_restore_msg_fmt, cmds, groups))
            .setPositiveButton(getString(R.string.sc_restore)) { _, _ -> applyRestore(parsed) }
            .setNegativeButton(getString(R.string.cancel), null)
            .showStyled(theme)
    }

    private class WheelBackup(
        val items: MutableList<ShortcutItem>,
        val usage: MutableMap<String, MutableMap<String, Int>>,
        val seq: MutableMap<String, MutableList<String>>,
        val lastUsed: MutableMap<String, Long>
    )

    /** 信封校验硬失败；条目校验宽松容错（缺 id 补、坏条目跳过、组不变式 ≥2）。 */
    private fun parseBackup(text: String): WheelBackup? {
        val obj = org.json.JSONObject(text)
        if (obj.optString("type") != BACKUP_TYPE) return null
        if (obj.optInt("version") != BACKUP_VERSION) return null
        val arr = obj.optJSONArray("shortcuts") ?: return null

        val list = mutableListOf<ShortcutItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("group", false)) {
                val marr = o.optJSONArray("members") ?: continue
                val members = mutableListOf<ShortcutItem.Command>()
                for (j in 0 until marr.length()) {
                    val m = marr.getJSONObject(j)
                    val cmd = m.optString("cmd", "")
                    if (cmd.isBlank()) continue
                    val id = m.optString("id", "").ifBlank { settingsManager.newId() }
                    members.add(ShortcutItem.Command(id, m.optString("label", cmd), cmd))
                }
                when {
                    members.size >= 2 ->
                        list.add(ShortcutItem.Group(o.optString("name", getString(R.string.sc_default_group)), members))
                    members.size == 1 ->
                        list.add(members[0]) // 组不变式 ≥2：不足还原为单条命令
                }
            } else {
                val cmd = o.optString("cmd", "")
                if (cmd.isBlank()) continue
                val id = o.optString("id", "").ifBlank { settingsManager.newId() }
                list.add(ShortcutItem.Command(id, o.optString("label", cmd), cmd))
            }
        }

        val usage = mutableMapOf<String, MutableMap<String, Int>>()
        obj.optJSONObject("usage")?.let { u ->
            for (state in u.keys()) {
                val inner = mutableMapOf<String, Int>()
                u.optJSONObject(state)?.let { io ->
                    for (k in io.keys()) inner[k] = io.optInt(k, 0)
                }
                if (inner.isNotEmpty()) usage[state] = inner
            }
        }

        val seq = mutableMapOf<String, MutableList<String>>()
        obj.optJSONObject("seq")?.let { s ->
            for (state in s.keys()) {
                val arr2 = s.optJSONArray(state) ?: continue
                val l = mutableListOf<String>()
                for (i in 0 until arr2.length()) l.add(arr2.optString(i))
                if (l.isNotEmpty()) seq[state] = l
            }
        }

        val lastUsed = mutableMapOf<String, Long>()
        obj.optJSONObject("lastUsed")?.let { lu ->
            for (k in lu.keys()) lastUsed[k] = lu.optLong(k, 0L)
        }

        return WheelBackup(list, usage, seq, lastUsed)
    }

    private fun applyRestore(b: WheelBackup) {
        val ids = mutableSetOf<String>()
        for (item in b.items) {
            when (item) {
                is ShortcutItem.Command -> ids.add(item.id)
                is ShortcutItem.Group -> item.members.forEach { ids.add(it.id) }
            }
        }
        // 顺序关键：先写命令（saveShortcuts 内部会按当前 id 集合 prune 旧频次），再写恢复的频次数据
        settingsManager.saveShortcuts(b.items)

        val usage = mutableMapOf<String, MutableMap<String, Int>>()
        for ((state, inner) in b.usage) {
            val f = inner.filterKeys { it in ids }
            if (f.isNotEmpty()) usage[state] = f.toMutableMap()
        }
        settingsManager.saveUsageMap(usage)

        val seq = mutableMapOf<String, MutableList<String>>()
        for ((state, l) in b.seq) {
            val f = l.filter { it in ids }
            if (f.isNotEmpty()) seq[state] = f.toMutableList()
        }
        settingsManager.saveSeqMap(seq)

        settingsManager.saveLastUsedMap(b.lastUsed.filterKeys { it in ids })

        items.clear()
        items.addAll(b.items)
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        Snackbar.make(root, getString(R.string.sc_restored_fmt, b.items.size), Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveList()
    }

    companion object {
        private const val BACKUP_TYPE = "termlou-wheel-backup"
        private const val BACKUP_VERSION = 1
    }
}
