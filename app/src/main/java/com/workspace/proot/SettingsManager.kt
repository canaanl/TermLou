package com.workspace.proot

import android.content.Context
import android.content.SharedPreferences

sealed class ShortcutItem {
    data class Command(val id: String, val label: String, val cmd: String) : ShortcutItem()
    data class Group(val name: String, val members: MutableList<Command>) : ShortcutItem()
}

data class FavoriteApp(val label: String, val pkg: String)

class SettingsManager(private val prefs: SharedPreferences) {
    var fontSizeIndex: Int = 2
        private set
    var fontSizeSp: Int = 28
        private set
    var shellCmd: String = ""
        private set
    var tileCommand: String = ""
        private set
    var keepAlive: Boolean = false
        private set
    var nightMode: Boolean = true
        private set

    val fontSizes = listOf(18, 22, 28, 34, 40)

    fun fontNames(ctx: Context): List<String> =
        ctx.resources.getStringArray(R.array.font_size_names).toList()

    fun load() {
        fontSizeIndex = prefs.getInt("fontSizeIndex", 2)
        fontSizeSp = fontSizes.getOrElse(fontSizeIndex) { 28 }
        shellCmd = prefs.getString("shellCmd", "") ?: ""
        tileCommand = prefs.getString("tileCommand", "") ?: ""
        keepAlive = prefs.getBoolean("keepAlive", false)
        nightMode = prefs.getBoolean("nightMode", true)
    }

    fun setFontSizeIndex(index: Int) {
        fontSizeIndex = index
        fontSizeSp = fontSizes.getOrElse(index) { 28 }
        prefs.edit().putInt("fontSizeIndex", index).apply()
    }

    fun setShellCmd(cmd: String) {
        shellCmd = cmd
        prefs.edit().putString("shellCmd", cmd).apply()
    }

    fun setTileCommand(cmd: String) {
        tileCommand = cmd
        prefs.edit().putString("tileCommand", cmd).apply()
    }

    fun setKeepAlive(enabled: Boolean) {
        keepAlive = enabled
        prefs.edit().putBoolean("keepAlive", enabled).apply()
    }

    fun setNightMode(enabled: Boolean) {
        nightMode = enabled
        // 夜间切换后紧跟着杀进程，必须同步落盘，否则新进程读到旧值（同 setLangExplicit）。
        prefs.edit().putBoolean("nightMode", enabled).commit()
    }

    fun newId(): String = java.util.UUID.randomUUID().toString()

    fun loadShortcuts(ctx: Context): MutableList<ShortcutItem> {
        val list = mutableListOf<ShortcutItem>()
        var migrated = false
        try {
            val arr = org.json.JSONArray(prefs.getString("shortcuts", "[]"))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optBoolean("group", false)) {
                    val name = obj.optString("name", ctx.getString(R.string.sc_default_group))
                    val members = mutableListOf<ShortcutItem.Command>()
                    val marr = obj.optJSONArray("members")
                    if (marr != null) {
                        for (j in 0 until marr.length()) {
                            val m = marr.getJSONObject(j)
                            var id = m.optString("id", "")
                            if (id.isBlank()) {
                                id = newId()
                                migrated = true
                            }
                            members.add(ShortcutItem.Command(id, m.getString("label"), m.getString("cmd")))
                        }
                    }
                    list.add(ShortcutItem.Group(name, members))
                } else {
                    var id = obj.optString("id", "")
                    if (id.isBlank()) {
                        id = newId()
                        migrated = true
                    }
                    list.add(ShortcutItem.Command(id, obj.getString("label"), obj.getString("cmd")))
                }
            }
        } catch (_: Exception) {}
        if (list.isEmpty()) {
            list.add(ShortcutItem.Command(newId(), ctx.getString(R.string.sc_default_pi), "mkdir -p ~/.local && curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 -o /tmp/pi.tar.gz \"https://github.com/earendil-works/pi/releases/latest/download/pi-linux-\$(uname -m | sed -e 's/x86_64/x64/' -e 's/aarch64/arm64/').tar.gz\" && tar -tzf /tmp/pi.tar.gz >/dev/null && rm -rf /tmp/pi.new && mkdir -p /tmp/pi.new && tar -xzf /tmp/pi.tar.gz -C /tmp/pi.new && /tmp/pi.new/pi/pi --version >/dev/null && (cd /tmp/pi.new && find . -type f -o -type l | sed 's#^\\./##' | sort -u > /tmp/pi.new-files) && if [ -d ~/.local/pi ]; then (cd ~/.local/pi && find . -type f -o -type l | sed 's#^\\./##' | sort > /tmp/pi.old-files); comm -23 /tmp/pi.old-files /tmp/pi.new-files | while IFS= read -r f; do [ -n \"\$f\" ] && rm -f \"\$HOME/.local/pi/\$f\" && echo \"删除残留: \$f\"; done; (cd ~/.local/pi && find . -depth -type d -empty -not -path . -exec rmdir {} + 2>/dev/null) || true; true; fi && tar -xzf /tmp/pi.tar.gz -C ~/.local && mkdir -p ~/.local/bin && ln -sf ~/.local/pi/pi ~/.local/bin/pi && rm -f /tmp/pi.tar.gz /tmp/pi.new-files /tmp/pi.old-files && rm -rf /tmp/pi.new && \"\$HOME/.local/bin/pi\" --version\r"))
            list.add(ShortcutItem.Command(newId(), ctx.getString(R.string.sc_default_opencode), "mkdir -p ~/.local && curl -fsSL https://opencode.ai/install | bash && mkdir -p \"\$HOME/.local/bin\" && for d in \"\$HOME/.local/bin\" \"\$HOME/.opencode/bin\" \"\$HOME/bin\" \"\$XDG_BIN_DIR\"; do [ -n \"\$d\" ] && [ -x \"\$d/opencode\" ] && ln -sf \"\$d/opencode\" \"\$HOME/.local/bin/opencode\" && break; done && \"\$HOME/.local/bin/opencode\" --version\r"))
            saveShortcuts(list)
        } else if (migrated) {
            saveShortcuts(list)
        }
        return list
    }

    fun saveShortcuts(list: List<ShortcutItem>) {
        val arr = org.json.JSONArray()
        for (item in list) {
            when (item) {
                is ShortcutItem.Command -> arr.put(org.json.JSONObject().apply {
                    put("id", item.id)
                    put("label", item.label)
                    put("cmd", item.cmd)
                })
                is ShortcutItem.Group -> arr.put(org.json.JSONObject().apply {
                    put("group", true)
                    put("name", item.name)
                    put("members", org.json.JSONArray().apply {
                        for (m in item.members) {
                            put(org.json.JSONObject().apply {
                                put("id", m.id)
                                put("label", m.label)
                                put("cmd", m.cmd)
                            })
                        }
                    })
                })
            }
        }
        prefs.edit().putString("shortcuts", arr.toString()).apply()
        pruneUsage(currentCommandIds(list))
    }

    private fun currentCommandIds(list: List<ShortcutItem>): Set<String> {
        val ids = mutableSetOf<String>()
        for (item in list) {
            when (item) {
                is ShortcutItem.Command -> ids.add(item.id)
                is ShortcutItem.Group -> for (m in item.members) ids.add(m.id)
            }
        }
        return ids
    }

    private fun pruneUsage(keep: Set<String>) {
        val usage = loadUsageMap()
        var changed = false
        val it = usage.entries.iterator()
        while (it.hasNext()) {
            val (state, inner) = it.next()
            val it2 = inner.entries.iterator()
            while (it2.hasNext()) {
                if (it2.next().key !in keep) {
                    it2.remove()
                    changed = true
                }
            }
            if (inner.isEmpty()) {
                it.remove()
                changed = true
            }
        }
        if (changed) saveUsageMap(usage)

        val seq = loadSeqMap()
        var seqChanged = false
        val sit = seq.entries.iterator()
        while (sit.hasNext()) {
            val (state, list) = sit.next()
            val before = list.size
            list.removeAll { it !in keep }
            if (list.isEmpty()) {
                sit.remove()
                seqChanged = true
            } else if (list.size != before) {
                seqChanged = true
            }
        }
        if (seqChanged) saveSeqMap(seq)

        val lastUsed = loadLastUsedMap()
        val before = lastUsed.size
        lastUsed.keys.removeAll { it !in keep }
        if (lastUsed.size != before) saveLastUsedMap(lastUsed)

        pruneBanditMap(keep)
    }

    private fun pruneBanditMap(keep: Set<String>) {
        val bandit = loadBanditMap()
        var changed = false
        val bit = bandit.entries.iterator()
        while (bit.hasNext()) {
            val (_, inner) = bit.next()
            val it3 = inner.entries.iterator()
            while (it3.hasNext()) {
                if (it3.next().key !in keep) {
                    it3.remove()
                    changed = true
                }
            }
            if (inner.isEmpty()) {
                bit.remove()
                changed = true
            }
        }
        if (changed) saveBanditMap(bandit)
    }

    fun clearCommandUsage(id: String) {
        val usage = loadUsageMap()
        var changed = false
        for (inner in usage.values) {
            if (inner.remove(id) != null) changed = true
        }
        if (changed) saveUsageMap(usage)

        val seq = loadSeqMap()
        var seqChanged = false
        val sit = seq.entries.iterator()
        while (sit.hasNext()) {
            val (state, list) = sit.next()
            list.removeAll { it == id }
            if (list.isEmpty()) {
                sit.remove()
                seqChanged = true
            } else {
                seqChanged = true
            }
        }
        if (seqChanged) saveSeqMap(seq)

        val lastUsed = loadLastUsedMap()
        if (lastUsed.remove(id) != null) saveLastUsedMap(lastUsed)

        val bandit = loadBanditMap()
        var banditChanged = false
        for (inner in bandit.values) {
            if (inner.remove(id) != null) banditChanged = true
        }
        if (banditChanged) saveBanditMap(bandit)
    }

    fun loadFavoriteApps(): MutableList<FavoriteApp> {
        val list = mutableListOf<FavoriteApp>()
        try {
            val arr = org.json.JSONArray(prefs.getString("favoriteApps", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(FavoriteApp(o.getString("label"), o.getString("pkg")))
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveFavoriteApps(list: List<FavoriteApp>) {
        val arr = org.json.JSONArray()
        for (a in list) {
            arr.put(org.json.JSONObject().apply { put("label", a.label); put("pkg", a.pkg) })
        }
        prefs.edit().putString("favoriteApps", arr.toString()).apply()
    }

    fun addFavoriteApp(label: String, pkg: String) {
        val list = loadFavoriteApps()
        if (list.any { it.pkg == pkg }) return
        list.add(FavoriteApp(label, pkg))
        saveFavoriteApps(list)
    }

    fun removeFavoriteApp(pkg: String) {
        saveFavoriteApps(loadFavoriteApps().filterNot { it.pkg == pkg })
    }

    fun clearFavoriteApps() {
        saveFavoriteApps(emptyList())
    }

    fun loadUsageMap(): MutableMap<String, MutableMap<String, Int>> {
        val map = mutableMapOf<String, MutableMap<String, Int>>()
        try {
            val obj = org.json.JSONObject(prefs.getString("tuiUsageMap", "{}"))
            val it = obj.keys()
            while (it.hasNext()) {
                val state = it.next()
                val inner = mutableMapOf<String, Int>()
                val io = obj.getJSONObject(state)
                val it2 = io.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    inner[k] = io.optInt(k, 0)
                }
                map[state] = inner
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveUsageMap(map: Map<String, Map<String, Int>>) {
        val obj = org.json.JSONObject()
        for ((state, inner) in map) {
            obj.put(state, org.json.JSONObject().apply {
                for ((k, v) in inner) put(k, v)
            })
        }
        prefs.edit().putString("tuiUsageMap", obj.toString()).apply()
    }

    fun loadSeqMap(): MutableMap<String, MutableList<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        try {
            val obj = org.json.JSONObject(prefs.getString("tuiSeqMap", "{}"))
            val it = obj.keys()
            while (it.hasNext()) {
                val state = it.next()
                val arr = obj.getJSONArray(state)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                map[state] = list
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveSeqMap(map: Map<String, List<String>>) {
        val obj = org.json.JSONObject()
        for ((state, list) in map) {
            obj.put(state, org.json.JSONArray().apply {
                for (s in list) put(s)
            })
        }
        prefs.edit().putString("tuiSeqMap", obj.toString()).apply()
    }

    fun recordSeq(state: String, id: String) {
        val map = loadSeqMap()
        val list = map.getOrPut(state) { mutableListOf() }
        if (list.lastOrNull() == id) return
        list.add(id)
        while (list.size > CommandRecommender.seqCap()) list.removeAt(0)
        saveSeqMap(map)
    }

    fun loadLastUsedMap(): MutableMap<String, Long> {
        val map = mutableMapOf<String, Long>()
        try {
            val obj = org.json.JSONObject(prefs.getString("cmdLastUsed", "{}"))
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = obj.optLong(k, 0L)
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveLastUsedMap(map: Map<String, Long>) {
        val obj = org.json.JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString("cmdLastUsed", obj.toString()).apply()
    }

    fun recordLastUsed(id: String) {
        val map = loadLastUsedMap()
        map[id] = System.currentTimeMillis()
        saveLastUsedMap(map)
    }

    fun loadBanditMap(): MutableMap<String, MutableMap<String, Float>> {
        val map = mutableMapOf<String, MutableMap<String, Float>>()
        try {
            val obj = org.json.JSONObject(prefs.getString("tuiBandit", "{}"))
            val it = obj.keys()
            while (it.hasNext()) {
                val state = it.next()
                val inner = mutableMapOf<String, Float>()
                val io = obj.getJSONObject(state)
                val it2 = io.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    inner[k] = io.optDouble(k, 0.0).toFloat()
                }
                map[state] = inner
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveBanditMap(map: Map<String, Map<String, Float>>) {
        val obj = org.json.JSONObject()
        for ((state, inner) in map) {
            obj.put(state, org.json.JSONObject().apply {
                for ((k, v) in inner) put(k, v.toDouble())
            })
        }
        prefs.edit().putString("tuiBandit", obj.toString()).apply()
    }

    /** 猜中率计数：每次有效推荐记 shown，点中第 1 位再记 top1。只后台记数，不展示。 */
    fun recordHit(hit: Boolean) {
        prefs.edit()
            .putLong("tuiHitShown", prefs.getLong("tuiHitShown", 0L) + 1)
            .putLong("tuiHitTop1", prefs.getLong("tuiHitTop1", 0L) + if (hit) 1L else 0L)
            .apply()
    }

    fun loadHitStats(): Pair<Long, Long> {
        return try {
            prefs.getLong("tuiHitShown", 0L) to prefs.getLong("tuiHitTop1", 0L)
        } catch (_: Exception) {
            0L to 0L
        }
    }

    /** 回放日志追加（状态，前文，点击），超上限丢最旧；坏串直接丢弃不抛。 */
    fun appendReplay(state: String, anchor: List<String>, tappedId: String) {
        try {
            val arr = org.json.JSONArray(prefs.getString("tuiReplay", "[]"))
            arr.put(org.json.JSONObject().apply {
                put("s", state)
                put("a", org.json.JSONArray().apply {
                    for (a in anchor) put(a)
                })
                put("t", tappedId)
            })
            while (arr.length() > BanditTuner.REPLAY_CAP) arr.remove(0)
            prefs.edit().putString("tuiReplay", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadReplay(): List<ReplaySample> {
        val out = mutableListOf<ReplaySample>()
        try {
            val arr = org.json.JSONArray(prefs.getString("tuiReplay", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ReplaySample(o.getString("s"), readReplayAnchor(o.optJSONArray("a")), o.getString("t")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun readReplayAnchor(ja: org.json.JSONArray?): MutableList<String> {
        val anchor = mutableListOf<String>()
        if (ja == null) return anchor
        for (j in 0 until ja.length()) anchor.add(ja.getString(j))
        return anchor
    }

    fun clearReplay() {
        prefs.edit().remove("tuiReplay").apply()
    }

    fun includeSystemApps(): Boolean = prefs.getBoolean("includeSystemApps", false)

    fun setIncludeSystemApps(v: Boolean) {
        prefs.edit().putBoolean("includeSystemApps", v).apply()
    }

    fun loadCaptureApps(): Set<String> {
        val set = linkedSetOf<String>()
        try {
            val arr = org.json.JSONArray(prefs.getString("netCaptureApps", "[]"))
            for (i in 0 until arr.length()) set.add(arr.getString(i))
        } catch (_: Exception) {}
        return set
    }

    fun saveCaptureApps(set: Set<String>) {
        val arr = org.json.JSONArray()
        for (s in set) arr.put(s)
        prefs.edit().putString("netCaptureApps", arr.toString()).apply()
    }

    fun netUpstream(): String = prefs.getString("netUpstream", "") ?: ""

    fun setNetUpstream(v: String) {
        prefs.edit().putString("netUpstream", v).apply()
    }

    fun langExplicit(): String = prefs.getString("langExplicit", "") ?: ""

    fun setLangExplicit(v: String) {
        // 语言切换后紧跟着杀进程，必须同步落盘，否则新进程读到旧值。
        prefs.edit().putString("langExplicit", v).commit()
    }

    fun lanUser(): String = prefs.getString("lanUser", "") ?: ""

    fun lanPass(): String = prefs.getString("lanPass", "") ?: ""

    fun lanPort(): Int = prefs.getInt("lanPort", 0)

    fun setLanAuth(user: String, pass: String) {
        prefs.edit().putString("lanUser", user).putString("lanPass", pass).apply()
    }

    fun setLanPort(port: Int) {
        prefs.edit().putInt("lanPort", port).apply()
    }

    fun clearLanAuth() {
        prefs.edit().remove("lanUser").remove("lanPass").remove("lanPort").apply()
    }
}
