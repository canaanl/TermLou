package com.workspace.proot

import org.junit.Assert.assertEquals
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
        FlowClassifier.FlowSample(up, down, 0L, durMs, now, state, port)

    @Test
    fun dnsPort() {
        assertEquals(FlowClassifier.Tag.DNS, FlowClassifier.classify(sample(300, 400, 5_000, port = 53)))
    }

    @Test
    fun mdnsPort() {
        assertEquals(FlowClassifier.Tag.DNS, FlowClassifier.classify(sample(300, 400, 5_000, port = 5353)))
    }

    @Test
    fun mediaLargeDown() {
        assertEquals(
            FlowClassifier.Tag.MEDIA,
            FlowClassifier.classify(sample(200 * 1024, 40L * 1024 * 1024, 90_000))
        )
    }

    @Test
    fun mediaJustAboveThreshold() {
        assertEquals(
            FlowClassifier.Tag.MEDIA,
            FlowClassifier.classify(sample(2 * 1024, 260 * 1024, 30_000))
        )
    }

    @Test
    fun uploadStrong() {
        assertEquals(
            FlowClassifier.Tag.UPLOAD,
            FlowClassifier.classify(sample(20L * 1024 * 1024, 200 * 1024, 40_000))
        )
    }

    @Test
    fun heartbeatLongIdle() {
        assertEquals(FlowClassifier.Tag.HEARTBEAT, FlowClassifier.classify(sample(300, 500, 120_000)))
    }

    @Test
    fun heartbeatWithoutDurUsesElapsed() {
        assertEquals(
            FlowClassifier.Tag.HEARTBEAT,
            FlowClassifier.classify(FlowClassifier.FlowSample(300, 500, now - 120_000, null, now, "OPEN", 443))
        )
    }

    @Test
    fun otherFallback() {
        assertEquals(FlowClassifier.Tag.OTHER, FlowClassifier.classify(sample(1024, 1024, 2_000)))
    }

    @Test
    fun otherWhenUploadBelowThreshold() {
        assertEquals(FlowClassifier.Tag.OTHER, FlowClassifier.classify(sample(300 * 1024, 200 * 1024, 40_000)))
    }

    @Test
    fun otherWhenUploadBelowRatio() {
        assertEquals(FlowClassifier.Tag.OTHER, FlowClassifier.classify(sample(2L * 1024 * 1024, 800 * 1024, 40_000)))
    }

    @Test
    fun otherWhenMediaBelowThreshold() {
        assertEquals(FlowClassifier.Tag.OTHER, FlowClassifier.classify(sample(2 * 1024, 200 * 1024, 30_000)))
    }

    @Test
    fun dnsWinsOverOtherSignals() {
        assertEquals(FlowClassifier.Tag.DNS, FlowClassifier.classify(sample(0, 0, 1_000, port = 53)))
    }
}
