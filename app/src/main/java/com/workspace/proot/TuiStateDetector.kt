package com.workspace.proot

import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object TuiStateDetector {
    private const val CACHE_MS = 800L

    @Volatile
    private var cachedState: String = "shell"

    @Volatile
    private var cachedAt: Long = 0L

    private val excluded = setOf(
        "proot", "proot_exec", "proot_loader",
        "libproot_exec", "libproot_loader",
        "libproot_exec.so", "libproot_loader.so",
        "bash", "dash", "sh", "ash", "zsh", "csh", "ksh", "fish", "tcsh", "mksh"
    )

    fun getCurrentState(): String {
        val now = System.currentTimeMillis()
        val cached = cachedState
        if (now - cachedAt < CACHE_MS && cached.isNotEmpty()) return cached
        cachedState = detect()
        cachedAt = now
        return cachedState
    }

    fun refresh(): String {
        cachedState = detect()
        cachedAt = System.currentTimeMillis()
        return cachedState
    }

    private fun detect(): String {
        val myUid = Process.myUid()
        val myPid = Process.myPid()
        val candidates = mutableListOf<Candidate>()

        val entries = File("/proc").listFiles() ?: return "shell"
        for (f in entries) {
            val pidStr = f.name
            if (!pidStr.all { it.isDigit() }) continue
            val pid = pidStr.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val c = readCandidate(pid, myUid) ?: continue
            candidates.add(c)
        }

        val candidatePids = candidates.mapTo(mutableSetOf()) { it.pid }
        val best = candidates
            .filter { it.ppid !in candidatePids }
            .maxByOrNull { it.start }
        return best?.name ?: "shell"
    }

    private fun readCandidate(pid: Int, myUid: Int): Candidate? {
        if (!sameUid(pid, myUid)) return null
        return try {
            val name = File("/proc/$pid/comm").readText().trim()
            if (name in excluded || name.startsWith("libproot") || name.startsWith("proot")) null
            else {
                val stat = File("/proc/$pid/stat").readText()
                val close = stat.lastIndexOf(')')
                if (close < 0) null
                else {
                    val rest = stat.substring(close + 1).trim().split(' ')
                    Candidate(
                        pid, name,
                        rest.getOrNull(1)?.toIntOrNull() ?: -1,
                        rest.getOrNull(19)?.toLongOrNull() ?: -1L
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sameUid(pid: Int, uid: Int): Boolean {
        return try {
            val r = BufferedReader(FileReader("/proc/$pid/status"))
            r.use {
                var match = false
                while (true) {
                    val line = it.readLine() ?: break
                    if (line.startsWith("Uid:")) {
                        match = line.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() == uid
                        break
                    }
                }
                match
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class Candidate(val pid: Int, val name: String, val ppid: Int, val start: Long)
}
