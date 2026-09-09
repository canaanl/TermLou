package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowClassifierTest {

    private val now: Long = System.currentTimeMillis()

    private fun sample(
        up: Long,
        down: Long,
        durMs: Long?,
        port: Int = 443,
        state: String = "OPEN"
    ): FlowClassifier.FlowSample =
        FlowClassifier.FlowSample(up, down, 0L, durMs, now, state, port, false)

    @Test
    fun heartbeat() {
        val t = FlowClassifier.classify(sample(300, 500, 120_000))
        assertEquals(listOf(FlowClassifier.Tag.HEARTBEAT), t.map { it.tag })
        assertEquals(92, t[0].confidence)
    }

    @Test
    fun interactBalanced() {
        val t = FlowClassifier.classify(sample(1024, 1024, 2_000))
        assertEquals(FlowClassifier.Tag.INTERACT, t.single().tag)
        assertEquals(84, t[0].confidence)
    }

    @Test
    fun interactSkewed() {
        val t = FlowClassifier.classify(sample(1024, 8192, 2_000))
        assertEquals(FlowClassifier.Tag.INTERACT, t.single().tag)
        assertEquals(70, t[0].confidence)
    }

    @Test
    fun mediaHigh() {
        val t = FlowClassifier.classify(sample(100 * 1024, 40L * 1024 * 1024, 90_000))
        assertEquals(FlowClassifier.Tag.MEDIA, t.single().tag)
        assertEquals(92, t[0].confidence)
    }

    @Test
    fun mediaMid() {
        val t = FlowClassifier.classify(sample(2 * 1024, 4L * 1024 * 1024, 30_000))
        assertEquals(FlowClassifier.Tag.MEDIA, t.single().tag)
        assertEquals(84, t[0].confidence)
    }

    @Test
    fun mediaLow() {
        val t = FlowClassifier.classify(sample(2 * 1024, 512 * 1024, 30_000))
        assertEquals(FlowClassifier.Tag.MEDIA, t.single().tag)
        assertEquals(72, t[0].confidence)
    }

    @Test
    fun uploadStrong() {
        val t = FlowClassifier.classify(sample(20L * 1024 * 1024, 200 * 1024, 40_000))
        assertEquals(FlowClassifier.Tag.UPLOAD, t.single().tag)
        assertEquals(88, t[0].confidence)
    }

    @Test
    fun uploadMedium() {
        val t = FlowClassifier.classify(sample(2L * 1024 * 1024, 512 * 1024, 40_000))
        assertEquals(FlowClassifier.Tag.UPLOAD, t.single().tag)
        assertEquals(74, t[0].confidence)
    }

    @Test
    fun zeroBytesReturnsEmpty() {
        assertTrue(FlowClassifier.classify(sample(0, 0, 1_000)).isEmpty())
    }

    @Test
    fun blockedReturnsEmpty() {
        assertTrue(FlowClassifier.classify(sample(300, 200, 50_000, state = "BLOCKED")).isEmpty())
    }

    @Test
    fun dnsPortReturnsEmpty() {
        assertTrue(FlowClassifier.classify(sample(300, 400, 5_000, port = 53)).isEmpty())
    }

    @Test
    fun ambiguousSmallMediumReturnsEmpty() {
        val t = FlowClassifier.classify(sample(8 * 1024, 9 * 1024, 45_000))
        assertTrue(t.isEmpty())
    }

    @Test
    fun adForcesFirstAndShowsMulti() {
        val t = FlowClassifier.classify(sample(300, 400, 120_000).copy(adHit = true))
        assertEquals(2, t.size)
        assertEquals(FlowClassifier.Tag.AD, t[0].tag)
        assertEquals(FlowClassifier.Tag.HEARTBEAT, t[1].tag)
    }

    @Test
    fun adWithMedia() {
        val t = FlowClassifier.classify(sample(10 * 1024, 8L * 1024 * 1024, 120_000).copy(adHit = true))
        assertEquals(2, t.size)
        assertEquals(FlowClassifier.Tag.AD, t[0].tag)
        assertEquals(FlowClassifier.Tag.MEDIA, t[1].tag)
    }
}
