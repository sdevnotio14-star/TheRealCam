package com.therealcam.capture

import com.therealcam.camera.FocusMode
import com.therealcam.camera.ShotSettings
import com.therealcam.data.Profile
import com.therealcam.data.StackMode
import com.therealcam.data.UserPreset
import com.therealcam.data.UserPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetPlannerTest {
    private fun input(
        expNs: Long? = 100_000_000L, iso: Int? = 3200, meanLin: Double? = 0.02, steady: Boolean = false, manual: Boolean = true,
    ) = PresetPlanner.Input(
        manualSensor = manual, minExpNs = 100_000L, maxExpNs = 30_000_000_000L, minIso = 50, maxIso = 6400,
        canFocusInfinity = true, aperture = 1.8f, previewExpNs = expNs, previewIso = iso, previewMeanLin = meanLin, steady = steady,
    )

    @Test
    fun handheldNightIsEightShortFramesAtHighIso() {
        val p = PresetPlanner.plan(ScenePreset.NIGHT, input())
        assertEquals(100_000_000L, p.settings.exposureNs)
        assertEquals(3200, p.settings.iso)
        assertEquals(8, p.settings.frames)
        assertEquals(StackMode.SIGMA_CLIP, p.settings.stackMode)
        assertEquals(FocusMode.AUTO, p.settings.focus)
    }

    @Test
    fun nightOnATripodUsesLongerFramesAndLowerIso() {
        val p = PresetPlanner.plan(ScenePreset.NIGHT, input(steady = true))
        assertEquals(1_000_000_000L, p.settings.exposureNs)
        // need = 0.1 s · 3200 · 9 · 2^-0.5 ≈ 2036 s·ISO → ISO ≈ 2036 at 1 s
        assertEquals(2036.0, p.settings.iso!!.toDouble(), 2.0)
        assertEquals(6, p.settings.frames)
        assertTrue(p.needsTripod)
    }

    @Test
    fun daylightCloudsStayAtBaseIsoAndDarker() {
        val p = PresetPlanner.plan(ScenePreset.CLOUDS, input(expNs = 500_000L, iso = 50, meanLin = 0.18))
        assertEquals(50, p.settings.iso)
        // 1/2000 s at −0.7 EV
        assertEquals(500_000.0 * 0.6156, p.settings.exposureNs!!.toDouble(), 2_000.0)
        assertEquals(StackMode.MEAN, p.settings.stackMode)
        assertEquals(FocusMode.INFINITY, p.settings.focus)
    }

    @Test
    fun moonIgnoresThePreviewMeter() {
        val p = PresetPlanner.plan(ScenePreset.MOON, input(expNs = 100_000_000L, iso = 6400, meanLin = 0.001))
        assertEquals(50, p.settings.iso)
        // f/1.8, EV100 14, ISO 50: 1.8² / 2^14 · 2 ≈ 1/2528 s
        assertEquals(395_500.0, p.settings.exposureNs!!.toDouble(), 1_000.0)
        assertEquals(24, p.settings.frames)
        assertEquals(StackMode.LUCKY, p.settings.stackMode)
    }

    @Test
    fun actionKeepsAFastShutter() {
        val p = PresetPlanner.plan(ScenePreset.ACTION, input(expNs = 10_000_000L, iso = 400, meanLin = 0.18))
        assertEquals(2_000_000L, p.settings.exposureNs)
        assertEquals(2000, p.settings.iso)
        assertEquals(StackMode.BEST_OF, p.settings.stackMode)
    }

    @Test
    fun withoutManualSensorAutoExposureWithBias() {
        val p = PresetPlanner.plan(ScenePreset.CLOUDS, input(manual = false))
        assertNull(p.settings.exposureNs)
        assertNull(p.settings.iso)
        assertEquals(-0.7f, p.settings.ev, 1e-6f)
        assertEquals(6, p.settings.frames)
    }

    @Test
    fun presetKeysRoundTrip() {
        ScenePreset.entries.forEach { assertEquals(it, ScenePreset.of(it.key)) }
        assertEquals(ScenePreset.NIGHT, ScenePreset.of("unknown"))
    }

    @Test
    fun userPresetIsKeptAndLimitedToTheCamera() {
        val saved = UserPreset.from(
            "u1", "Мои звёзды", "stars",
            ShotSettings(iso = 25_600, exposureNs = 60_000_000_000L, focus = FocusMode.INFINITY, frames = 10, stackMode = StackMode.SIGMA_CLIP),
            Profile.ASTRO,
        )
        val back = UserPresets.decode(UserPresets.encode(listOf(saved)))
        assertEquals(listOf(saved), back)
        val p = PresetPlanner.planUser(back.single(), input(steady = true))
        assertEquals(6400, p.settings.iso)
        assertEquals(30_000_000_000L, p.settings.exposureNs)
        assertEquals(10, p.settings.frames)
        assertEquals(StackMode.SIGMA_CLIP, p.settings.stackMode)
        assertTrue(p.needsTripod)
        val noManual = PresetPlanner.planUser(saved, input(manual = false))
        assertNull(noManual.settings.exposureNs)
        assertTrue(UserPresets.decode("not json").isEmpty())
    }
}
