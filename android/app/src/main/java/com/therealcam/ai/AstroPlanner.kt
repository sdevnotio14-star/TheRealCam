package com.therealcam.ai

import com.therealcam.camera.ExposureScale
import com.therealcam.camera.FocusMode
import com.therealcam.camera.ShotSettings
import com.therealcam.data.StackMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Astro mode: one press shoots a long series from a tripod. Per-frame exposure is the longest that keeps
 * stars point-like (NPF rule), ISO comes from the preview meter, the number of frames from the chosen
 * duration. Frames are stacked with alignment, frame rejection and sigma clipping (Profile.ASTRO).
 */
object AstroPlanner {
    /** Per-frame cap: longer frames trail at the edges and catch more wind. */
    const val MAX_FRAME_S = 16.0
    const val MAX_FRAMES = 120
    const val MIN_FRAMES = 3
    /** Sky background the series aims for, in linear light (≈ 0.19 sRGB). */
    const val TARGET_LIN = 0.03
    /** Upper ISO for astro: beyond it phones mostly add digital gain and noise. */
    const val MAX_ISO = 3200
    /** Frames the camera's auto exposure takes in the dark without manual control, seconds each. */
    private const val AUTO_FRAME_S = 0.35
    val DURATIONS_S = listOf(60, 120, 240, 600, 1200, 1800)

    data class Input(
        val durationS: Int,
        val manualSensor: Boolean,
        val focalMm: Float?,
        val aperture: Float?,
        val pixelPitchUm: Float?,
        val equivMm: Float?,
        /** Crop over the lens's own field of view (pinch zoom), ≥ 1. */
        val digitalZoom: Float,
        val minExpNs: Long?,
        val maxExpNs: Long?,
        val minIso: Int?,
        val maxIso: Int?,
        val canFocusInfinity: Boolean,
        val previewExpNs: Long?,
        val previewIso: Int?,
        val previewMeanLin: Double?,
    )

    data class Plan(
        val settings: ShotSettings,
        /** Null when the camera has no manual exposure and auto exposure picks the frames. */
        val frameS: Double?,
        val totalS: Double,
        /** The sensor cannot expose as long as the stars allow. */
        val sensorLimited: Boolean,
        val label: String,
    )

    /** Longest exposure (s) before stars visibly trail: NPF rule, or the 500 rule without lens data. */
    fun maxStarExposureS(focalMm: Float?, aperture: Float?, pixelPitchUm: Float?, equivMm: Float?, digitalZoom: Float): Double {
        val z = digitalZoom.coerceAtLeast(1f).toDouble()
        if (focalMm != null && focalMm > 0f && aperture != null && aperture > 0f && pixelPitchUm != null && pixelPitchUm > 0f) {
            return (35.0 * aperture + 30.0 * pixelPitchUm) / (focalMm * z)
        }
        if (equivMm != null && equivMm > 0f) return 500.0 / (equivMm * z)
        return 10.0 / z
    }

    fun plan(i: Input): Plan {
        val dur = i.durationS.coerceAtLeast(10).toDouble()
        val focus = if (i.canFocusInfinity) FocusMode.INFINITY else FocusMode.AUTO
        val inf = if (focus == FocusMode.INFINITY) " · ∞" else ""
        if (!i.manualSensor || i.maxExpNs == null || i.minIso == null || i.maxIso == null) {
            val frames = (dur / AUTO_FRAME_S).roundToInt().coerceIn(MIN_FRAMES, 60)
            val totalS = frames * AUTO_FRAME_S
            return Plan(
                ShotSettings(focus = focus, frames = frames, stackMode = StackMode.SIGMA_CLIP), null, totalS, true,
                "$frames кадров · авто-экспозиция$inf · ≈${formatDuration(totalS)}",
            )
        }
        val wanted = min(maxStarExposureS(i.focalMm, i.aperture, i.pixelPitchUm, i.equivMm, i.digitalZoom), MAX_FRAME_S)
        val sensorMaxS = i.maxExpNs / 1e9
        val frameS = wanted.coerceAtMost(sensorMaxS).coerceAtLeast((i.minExpNs ?: 0L) / 1e9)
        val expNs = (frameS * 1e9).roundToLong()

        val isoCap = min(i.maxIso, MAX_ISO).coerceAtLeast(i.minIso)
        val lin = i.previewMeanLin
        val metered = if (i.previewExpNs != null && i.previewIso != null && lin != null && lin > 0.0) {
            i.previewIso * (i.previewExpNs / 1e9 / frameS) * (TARGET_LIN / lin)
        } else null
        // A dark preview is mostly noise and black level and overstates the sky, so keep a floor;
        // the series meter darkens a sky that turns out to glow (city, moon).
        val floor = if (lin != null && lin > 0.05) i.minIso else min(800, isoCap)
        val iso = (metered?.roundToInt() ?: min(1600, isoCap)).coerceIn(floor, isoCap)

        val overheadS = max(0.15, frameS * 0.03)
        val frames = (dur / (frameS + overheadS)).roundToInt().coerceIn(MIN_FRAMES, MAX_FRAMES)
        val totalS = frames * (frameS + overheadS)
        val settings = ShotSettings(iso = iso, exposureNs = expNs, focus = focus, frames = frames, stackMode = StackMode.SIGMA_CLIP)
        val label = "$frames × ${ExposureScale.formatShutter(expNs)} · ISO$iso$inf · ≈${formatDuration(totalS)}"
        return Plan(settings, frameS, totalS, sensorMaxS < wanted * 0.9, label)
    }

    /** "45 с", "4 мин", "1 мин 18 с". */
    fun formatDuration(s: Double): String {
        val t = s.roundToInt()
        return when {
            t < 60 -> "$t с"
            t % 60 == 0 -> "${t / 60} мин"
            else -> "${t / 60} мин ${t % 60} с"
        }
    }
}
