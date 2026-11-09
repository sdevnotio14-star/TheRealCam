package com.therealcam.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class HistogramTest {
    @Test
    fun binsAreNormalisedAndClippingIsCounted() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val h = Histogram.of(IntArray(300) { if (it < 100) white else black })
        assertEquals(Histogram.BINS + 1, h.size)
        assertEquals(1f, h[0], 1e-6f)
        assertEquals(0.5f, h[Histogram.BINS - 1], 1e-6f)
        assertEquals(1f / 3f, h[Histogram.BINS], 1e-6f)
    }
}
