package com.workspace.proot

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BanditStoreTest {

    private val store = mutableMapOf<String, Any?>()
    private lateinit var manager: SettingsManager

    @Before
    fun setUp() {
        store.clear()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { store[firstArg<String>()] as? String ?: secondArg() }
        every { editor.putString(any(), any()) } answers { store[firstArg<String>()] = secondArg<String>(); editor }
        every { prefs.getLong(any(), any()) } answers { store[firstArg<String>()] as? Long ?: secondArg() }
        every { editor.putLong(any(), any()) } answers { store[firstArg<String>()] = secondArg<Long>(); editor }
        every { editor.remove(any()) } answers { store.remove(firstArg<String>()); editor }
        manager = SettingsManager(prefs)
    }

    @Test
    fun `bandit map round-trips`() {
        manager.saveBanditMap(mapOf("shell" to mapOf("a" to 0.3f, "b" to 1f)))
        val loaded = manager.loadBanditMap()
        assertEquals(0.3f, loaded["shell"]?.get("a") ?: -1f, 0.001f)
        assertEquals(1f, loaded["shell"]?.get("b") ?: -1f, 0.001f)
    }

    @Test
    fun `corrupt bandit json loads empty`() {
        store["tuiBandit"] = "{oops"
        assertTrue(manager.loadBanditMap().isEmpty())
    }

    @Test
    fun `hit counters accumulate`() {
        manager.recordHit(true)
        manager.recordHit(false)
        manager.recordHit(true)
        val (shown, top1) = manager.loadHitStats()
        assertEquals(3L, shown)
        assertEquals(2L, top1)
    }

    @Test
    fun `replay appends and caps oldest first`() {
        repeat(BanditTuner.REPLAY_CAP + 5) { i -> manager.appendReplay("s", listOf("a"), "t$i") }
        val loaded = manager.loadReplay()
        assertEquals(BanditTuner.REPLAY_CAP, loaded.size)
        assertEquals("t5", loaded.first().tappedId)
        assertEquals("t${BanditTuner.REPLAY_CAP + 4}", loaded.last().tappedId)
        assertEquals(listOf("a"), loaded.last().anchor)
    }

    @Test
    fun `clearReplay empties log`() {
        manager.appendReplay("s", emptyList(), "a")
        manager.clearReplay()
        assertTrue(manager.loadReplay().isEmpty())
    }
}
