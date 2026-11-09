package com.therealcam.capture

import com.therealcam.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMeterTest {
    private val w = 100
    private val h = 75

    private fun frame(background: Float, spot: Float = background, spotSize: Int = 0): FloatArray {
        val l = FloatArray(w * h) { background }
        for (y in 30 until 30 + spotSize) for (x in 40 until 40 + spotSize) l[y * w + x] = spot
        return l
    }

    private val lim = SeriesMeter.Limits(
        minExpNs = 100_000L, maxExpNs = 30_000_000_000L, brightenMaxExpNs = 66_000_000L, minIso = 50, maxIso = 6400,
    )

    @Test
    fun statsFindTheMoonButIgnoreAHotPixel() {
        val moon = SeriesMeter.stats(frame(0.05f, 0.9f, spotSize = 5), w, h)
        assertEquals(0.9f, moon.peak, 1e-4f)
        assertEquals(0.05f, moon.p50, 0.01f)
        val hot = frame(0.05f).also { it[30 * w + 40] = 1f }
        assertTrue(SeriesMeter.stats(hot, w, h).peak < 0.2f)
    }

    @Test
    fun clippedMoonIsHalvedWithShorterFramesNotLowerIso() {
        val s = SeriesMeter.stats(frame(0.03f, 1f, spotSize = 6), w, h)
        val k = SeriesMeter.correction(Profile.LUNAR, s)
        assertEquals(0.5, k!!, 1e-9)
        val (e, iso) = SeriesMeter.apply(4_000_000L, 400, k, null, Profile.LUNAR, lim)!!
        assertEquals(2_000_000L, e)
        assertEquals(400, iso)
    }

    @Test
    fun dimMoonBrightensWithIsoBecauseExposureIsCappedAtThePlan() {
        val s = SeriesMeter.stats(frame(0.02f, 0.3f, spotSize = 6), w, h)
        val k = SeriesMeter.correction(Profile.LUNAR, s)!!
        assertTrue(k > 2)
        val moonLim = lim.copy(brightenMaxExpNs = 4_000_000L)
        val (e, iso) = SeriesMeter.apply(4_000_000L, 200, k, null, Profile.LUNAR, moonLim)!!
        assertEquals(4_000_000L, e)
        assertEquals((200 * k).toInt().toDouble(), iso.toDouble(), 2.0)
    }

    @Test
    fun wellExposedMoonKeepsSettings() {
        val s = SeriesMeter.stats(frame(0.02f, 0.85f, spotSize = 6), w, h)
        assertNull(SeriesMeter.correction(Profile.LUNAR, s))
    }

    @Test
    fun overexposedCloudsLowerIsoFirst() {
        val l = frame(0.6f, 1f, spotSize = 20)
        val k = SeriesMeter.correction(Profile.SKY, SeriesMeter.stats(l, w, h))!!
        assertEquals(0.5, k, 1e-9)
        val (e, iso) = SeriesMeter.apply(10_000_000L, 400, k, null, Profile.SKY, lim)!!
        assertEquals(10_000_000L, e)
        assertEquals(200, iso)
        // At base ISO the exposure time takes the rest.
        val (e2, iso2) = SeriesMeter.apply(10_000_000L, 64, k, null, Profile.SKY, lim)!!
        assertEquals(50, iso2)
        assertEquals(10_000_000.0 * 0.5 / (50.0 / 64.0), e2.toDouble(), 2.0)
    }

    @Test
    fun skyGlowDarkensAstroButNeverBrightensIt() {
        val glow = SeriesMeter.correction(Profile.ASTRO, SeriesMeter.stats(frame(0.5f), w, h))!!
        assertTrue(glow < 0.5)
        assertNull(SeriesMeter.correction(Profile.ASTRO, SeriesMeter.stats(frame(0.08f), w, h)))
    }

    @Test
    fun handheldBlurTradesExposureForIso() {
        val (e, iso) = SeriesMeter.apply(1_000_000_000L, 800, null, 8.0, Profile.ASTRO, lim, motionRms = 0.03)!!
        assertEquals(250_000_000L, e)
        assertEquals(3200, iso)
        assertNull(SeriesMeter.apply(1_000_000_000L, 800, null, 1.0, Profile.ASTRO, lim, motionRms = 0.03))
    }

    @Test
    fun tripodGyroNoiseDoesNotShortenLongExposures() {
        // 16 s on a tripod: the integrated gyro noise looks like ~70 px of blur, but the RMS says the phone is still.
        assertNull(SeriesMeter.apply(16_000_000_000L, 1600, null, 70.0, Profile.ASTRO, lim, motionRms = 0.002))
        assertNull(SeriesMeter.apply(16_000_000_000L, 1600, null, 70.0, Profile.ASTRO, lim, motionRms = null))
    }

    @Test
    fun brighteningIsLimitedBySensorRanges() {
        val r = SeriesMeter.apply(50_000_000L, 3200, 4.0, null, Profile.SKY, lim)
        assertNotNull(r)
        assertEquals(66_000_000L, r!!.first)
        assertEquals(6400, r.second)
    }

    @Test
    fun profilesWithoutMeteringAndBudget() {
        assertNull(SeriesMeter.correction(Profile.TRAILS, SeriesMeter.stats(frame(1f), w, h)))
        assertNull(SeriesMeter.correction(Profile.DEFAULT, SeriesMeter.stats(frame(1f), w, h)))
        assertEquals(0, SeriesMeter.meteringBudget(2))
        assertEquals(1, SeriesMeter.meteringBudget(4))
        assertEquals(2, SeriesMeter.meteringBudget(12))
    }
}
