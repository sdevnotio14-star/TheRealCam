package com.therealcam.capture

import com.therealcam.data.Profile
import com.therealcam.sensors.SensorHub
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * AI series: the first frames meter the scene, and exposure time and ISO of the remaining frames
 * are corrected from what the sensor actually recorded. Metering frames stay in the dataset but are
 * not stacked, so every stacked frame has the same settings and nothing is re-synthesised.
 */
object SeriesMeter {
    /** Corrections smaller than this (in stops) are ignored. */
    private const val MIN_STOPS = 1.0 / 3
    /** Motion blur within one frame above this (full-resolution pixels) shortens the exposure. */
    const val MAX_BLUR_PX = 2.0

    /** Luma of a downscaled frame: fraction of clipped pixels, median, 99.5th percentile, brightest 3×3 spot. */
    data class Stats(val clipped: Float, val p50: Float, val p995: Float, val peak: Float)

    data class Limits(
        val minExpNs: Long,
        val maxExpNs: Long,
        /** Longest exposure a brightening step may use (handheld limit, or the planned one for the moon). */
        val brightenMaxExpNs: Long,
        val minIso: Int,
        val maxIso: Int,
    )

    /** [luma] is sRGB luma 0..1, row-major [w]×[h]. */
    fun stats(luma: FloatArray, w: Int, h: Int): Stats {
        require(luma.size == w * h && luma.isNotEmpty())
        val hist = IntArray(256)
        var clipped = 0
        for (v in luma) {
            hist[(v * 255f).toInt().coerceIn(0, 255)]++
            if (v >= 0.98f) clipped++
        }
        fun pct(q: Double): Float {
            val target = q * luma.size
            var acc = 0L
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i / 255f
            }
            return 1f
        }
        // Mean of 3×3 so single hot pixels do not count as the moon.
        var peak = 0f
        if (w >= 3 && h >= 3) {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                var s = 0f
                for (dy in -1..1) {
                    val row = (y + dy) * w + x
                    s += luma[row - 1] + luma[row] + luma[row + 1]
                }
                peak = max(peak, s / 9f)
            }
        } else {
            peak = luma.max()
        }
        return Stats(clipped.toFloat() / luma.size, pct(0.5), pct(0.995), peak)
    }

    /** Linear-light brightness factor for the next frames, or null when this frame was fine. */
    fun correction(profile: Profile, s: Stats): Double? {
        val k = when (profile) {
            Profile.LUNAR -> when {
                s.peak >= 0.97f -> 0.5 // the lit disc clips; how much is unknown, so halve
                s.peak < 0.08f -> 2.0 // nothing bright in view: brighten carefully
                s.peak < 0.55f -> linear(0.85f / s.peak)
                else -> 1.0
            }
            Profile.SKY -> when {
                s.clipped > 0.002f -> 0.5
                s.p995 < 0.7f -> linear(0.88f / max(s.p995, 0.05f))
                else -> 1.0
            }
            // Planned exposure is already the longest without star trails: only darken a glowing sky.
            Profile.ASTRO -> if (s.p50 > 0.35f) linear(0.25f / s.p50) else 1.0
            Profile.NIGHT -> if (s.clipped > 0.03f) 0.6 else 1.0
            Profile.LONG -> if (s.clipped > 0.005f) 0.5 else 1.0
            else -> return null
        }.coerceIn(0.25, 4.0)
        return if (abs(log2(k)) < MIN_STOPS) null else k
    }

    /** sRGB ratio → linear-light ratio (gamma ≈ 2.2 is close enough for a correction step). */
    private fun linear(ratio: Float) = ratio.toDouble().pow(2.2)

    /**
     * New (exposure, ISO) from brightness factor [k] and the frame's motion blur, or null if unchanged.
     * Blur over [MAX_BLUR_PX] shortens the exposure and raises ISO by the same factor, but only when
     * [motionRms] shows real shaking: on a tripod, integrated gyro noise alone reaches tens of pixels
     * in a long exposure.
     * Darker: lower ISO first (less noise), except the moon, where shorter frames freeze the air.
     * Brighter: longer exposure up to [Limits.brightenMaxExpNs], then ISO.
     */
    fun apply(
        expNs: Long, iso: Int, k: Double?, blurPx: Double?, profile: Profile, lim: Limits, motionRms: Double? = null,
    ): Pair<Long, Int>? {
        var exp = expNs.toDouble()
        var gain = iso.toDouble()
        val shaken = motionRms != null && motionRms.isFinite() && motionRms >= SensorHub.STEADY_RAD_S
        if (shaken && blurPx != null && blurPx.isFinite() && blurPx > MAX_BLUR_PX && profile != Profile.TRAILS) {
            val b = (MAX_BLUR_PX / blurPx).coerceAtLeast(0.25)
            exp *= b
            gain /= b
        }
        val kk = k ?: 1.0
        if (kk < 1.0) {
            if (profile == Profile.LUNAR) {
                exp *= kk
            } else {
                val isoK = max(kk, lim.minIso / gain).coerceAtMost(1.0)
                gain *= isoK
                exp *= kk / isoK
            }
        } else if (kk > 1.0) {
            val expK = min(kk, max(1.0, lim.brightenMaxExpNs / exp))
            exp *= expK
            gain *= kk / expK
        }
        val e = exp.roundToLong().coerceIn(lim.minExpNs, max(lim.minExpNs, lim.maxExpNs))
        val i = gain.roundToInt().coerceIn(lim.minIso, max(lim.minIso, lim.maxIso))
        return if (e == expNs && i == iso) null else e to i
    }

    /** How many leading frames of an [frames]-frame AI series may be metering frames. */
    fun meteringBudget(frames: Int): Int = when {
        frames >= 6 -> 2
        frames >= 3 -> 1
        else -> 0
    }
}
