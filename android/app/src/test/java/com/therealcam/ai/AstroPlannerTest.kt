package com.therealcam.ai

import com.therealcam.camera.FocusMode
import com.therealcam.data.StackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroPlannerTest {
    private fun input(
        durationS: Int = 240, maxExpS: Double = 30.0, previewMeanLin: Double? = 0.004,
        manual: Boolean = true, zoom: Float = 1f,
    ) = AstroPlanner.Input(
        durationS = durationS, manualSensor = manual,
        focalMm = 5.4f, aperture = 1.8f, pixelPitchUm = 1.8f, equivMm = 26f, digitalZoom = zoom,
        minExpNs = 100_000L, maxExpNs = (maxExpS * 1e9).toLong(), minIso = 50, maxIso = 6400,
        canFocusInfinity = true,
        previewExpNs = 100_000_000L, previewIso = 3200, previewMeanLin = previewMeanLin,
    )

    @Test
    fun npfRuleForATypicalMainCamera() {
        // (35·1.8 + 30·1.8) / 5.4 = 21.7 s
        assertEquals(21.67, AstroPlanner.maxStarExposureS(5.4f, 1.8f, 1.8f, 26f, 1f), 0.01)
        assertEquals(10.83, AstroPlanner.maxStarExposureS(5.4f, 1.8f, 1.8f, 26f, 2f), 0.01)
        assertEquals(500.0 / 26, AstroPlanner.maxStarExposureS(null, null, null, 26f, 1f), 1e-9)
    }

    @Test
    fun fourMinutesOnATripodIsFifteenFramesOf16s() {
        val p = AstroPlanner.plan(input())
        assertEquals(16_000_000_000L, p.settings.exposureNs)
        assertEquals(15, p.settings.frames)
        assertEquals(StackMode.SIGMA_CLIP, p.settings.stackMode)
        assertEquals(FocusMode.INFINITY, p.settings.focus)
        assertFalse(p.sensorLimited)
        assertTrue(p.label, p.label.startsWith("15 × 16с"))
        assertTrue(p.totalS in 230.0..260.0)
    }

    @Test
    fun thirtyMinutesIsCappedByFrameCount() {
        val p = AstroPlanner.plan(input(durationS = 1800))
        assertEquals(109, p.settings.frames)
        assertTrue(p.totalS in 1750.0..1850.0)
    }

    @Test
    fun shortSensorMeansManyShortFramesAndIsReported() {
        val p = AstroPlanner.plan(input(maxExpS = 0.5))
        assertEquals(500_000_000L, p.settings.exposureNs)
        assertTrue(p.sensorLimited)
        assertEquals(AstroPlanner.MAX_FRAMES, p.settings.frames)
    }

    @Test
    fun isoFromMeterWithFloorForDarkPreviews() {
        // Very dark preview: metered ISO would be tiny, the floor keeps 800.
        assertEquals(800, AstroPlanner.plan(input(previewMeanLin = 0.004)).settings.iso)
        // Bright (moonlit / city) preview: no floor, ISO follows the meter: 3200·(0.1/16)·(0.03/0.08) = 7.5 → 50.
        assertEquals(50, AstroPlanner.plan(input(previewMeanLin = 0.08)).settings.iso)
        // Unknown meter: ISO 1600.
        assertEquals(1600, AstroPlanner.plan(input(previewMeanLin = null)).settings.iso)
    }

    @Test
    fun withoutManualSensorAutoExposureFramesAreUsed() {
        val p = AstroPlanner.plan(input(manual = false))
        assertNull(p.settings.exposureNs)
        assertNull(p.settings.iso)
        assertNull(p.frameS)
        assertEquals(60, p.settings.frames)
        assertTrue(p.label.contains("авто-экспозиция"))
    }

    @Test
    fun durationText() {
        assertEquals("45 с", AstroPlanner.formatDuration(45.0))
        assertEquals("4 мин", AstroPlanner.formatDuration(240.0))
        assertEquals("1 мин 18 с", AstroPlanner.formatDuration(78.0))
    }
}
