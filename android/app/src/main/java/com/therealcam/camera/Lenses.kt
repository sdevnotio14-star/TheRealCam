package com.therealcam.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class LensKind(val key: String, val title: String) {
    ULTRAWIDE("ultrawide", "Ультраширик"),
    MAIN("main", "Основная"),
    TELE("tele", "Телевик"),
    MACRO("macro", "Макро"),
    FRONT("front", "Фронтальная"),
}

/** Facts about one camera device, copied out of CameraCharacteristics so the logic runs on the JVM. */
data class LensSpec(
    val id: String,
    val front: Boolean,
    /** Ascending; several values only for optical-zoom or logical cameras. */
    val focalsMm: List<Float>,
    /** Physical size of the active pixel area. */
    val activeWidthMm: Float,
    val activeHeightMm: Float,
    val activeWidthPx: Int,
    val activeHeightPx: Int,
    /** A colour camera usable for preview and stills (not depth, mono or IR). */
    val usable: Boolean,
    val logical: Boolean,
    val physicalIds: List<String>,
    /** CONTROL_ZOOM_RATIO_RANGE (API 30+); null when only crop zoom exists. */
    val zoomRatioRange: ClosedFloatingPointRange<Float>?,
    val maxDigitalZoom: Float,
    val fixedFocus: Boolean,
    /** Not in getCameraIdList(): some vendors list only one back and one front camera to third-party apps. */
    val hidden: Boolean = false,
) {
    val megapixels: Float get() = activeWidthPx.toFloat() * activeHeightPx / 1e6f
    val diagonalMm: Float get() = hypot(activeWidthMm, activeHeightMm)

    /** 35 mm equivalent of [focalMm]: same diagonal field of view on a 36×24 mm frame. */
    fun equivMm(focalMm: Float): Float? =
        if (focalMm > 0f && diagonalMm > 0f) focalMm * FULL_FRAME_DIAGONAL_MM / diagonalMm else null

    companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.27f
    }
}

/**
 * One selectable lens. [zoom] is its field of view relative to the main lens of the same side (1×).
 * Selecting it opens [cameraId] with zoom ratio [baseRatio]; on that camera the shown zoom is
 * `ratio * zoomPerRatio`, and the ratio may go from [minRatio] to [maxRatio].
 */
data class Lens(
    val cameraId: String,
    val kind: LensKind,
    val zoom: Float,
    val baseRatio: Float,
    val zoomPerRatio: Float,
    val minRatio: Float,
    val maxRatio: Float,
    val equivMm: Float?,
    val megapixels: Float,
    val hidden: Boolean = false,
) {
    val label: String get() = if (kind == LensKind.MACRO) "M" else ZoomMath.label(zoom)
    val minZoom: Float get() = minRatio * zoomPerRatio
    val maxZoom: Float get() = min(maxRatio * zoomPerRatio, ZoomMath.MAX_ZOOM)
    fun ratioFor(zoom: Float): Float = (zoom / zoomPerRatio).coerceIn(minRatio, maxRatio)

    /** "0.6× Ультраширик · 13 мм · 12 МП" */
    val description: String
        get() = buildString {
            if (kind != LensKind.MACRO) append(ZoomMath.display(zoom)).append(' ')
            append(kind.title)
            equivMm?.let { append(" · ").append(it.roundToInt()).append(" мм") }
            if (megapixels > 0f) append(" · ").append(megapixels.roundToInt().coerceAtLeast(1)).append(" МП")
        }
}

/** Lens and zoom a shot was taken with, for the dataset. */
data class LensUse(val kind: LensKind, val zoom: Float, val equivMm: Float?)

/** Builds the lens list shown above the shutter from what the HAL exposes. */
object LensCatalog {
    /** Lenses whose fields of view differ by less than this factor are the same lens. */
    private const val SAME_FOV = 1.15f
    private const val ULTRAWIDE_MAX = 0.8f
    private const val TELE_MIN = 1.4f
    /** Fixed-focus colour modules this small are the macro cameras of budget phones. */
    private const val MACRO_MAX_MP = 5.5f
    /** Smaller sensors are depth or auxiliary modules and are never offered. */
    private const val MIN_MP = 1.5f

    fun build(listed: List<LensSpec>, physical: Map<String, LensSpec>, front: Boolean): List<Lens> {
        val cams = listed.filter { it.front == front && it.usable && it.megapixels >= MIN_MP }
        // Android lists the main camera of each side first.
        val main = cams.firstOrNull() ?: return emptyList()
        val subs = main.physicalIds.mapNotNull { physical[it] }.filter { it.usable && it.megapixels >= MIN_MP }
        val mainEq = mainEquivMm(main, subs)
        val zr = main.zoomRatioRange
        val mainMin = min(1f, zr?.start ?: 1f)
        val mainMax = max(1f, zr?.endInclusive ?: main.maxDigitalZoom)
        val mainKind = if (front) LensKind.FRONT else LensKind.MAIN
        val mainMp = subs.maxByOrNull { it.diagonalMm }?.megapixels ?: main.megapixels
        val out = arrayListOf(Lens(main.id, mainKind, 1f, 1f, 1f, mainMin, mainMax, mainEq, mainMp))

        if (zr != null && main.logical) {
            // Physical lenses of a logical camera are reached through CONTROL_ZOOM_RATIO, without reopening.
            if (mainEq != null) for (p in subs) {
                val eq = p.focalsMm.firstOrNull()?.let(p::equivMm) ?: continue
                val z = eq / mainEq
                if (z < zr.start / SAME_FOV || z > zr.endInclusive) continue
                val r = z.coerceIn(zr.start, zr.endInclusive)
                if (out.any { sameFov(it.zoom, r) }) continue
                out += Lens(main.id, kindOf(r, p, front), r, r, 1f, mainMin, mainMax, eq, p.megapixels)
            }
            // Zoom-out works but the vendor hides the physical lenses: still offer the widest view.
            if (zr.start < ULTRAWIDE_MAX && out.none { it.zoom < ULTRAWIDE_MAX }) {
                out += Lens(main.id, LensKind.ULTRAWIDE, zr.start, zr.start, 1f, mainMin, mainMax, mainEq?.times(zr.start), 0f)
            }
        }

        if (mainEq != null) for (c in cams.drop(1)) {
            if (c.logical) continue
            val eq = c.focalsMm.firstOrNull()?.let(c::equivMm) ?: continue
            val z = eq / mainEq
            val kind = kindOf(z, c, front)
            // A second sensor with the main field of view (depth, mono, duplicate id): not a lens choice.
            if (kind == LensKind.MAIN || kind == LensKind.FRONT) continue
            // Compare real fields of view: a zoom-ratio lens may be clamped to the ratio range.
            val duplicate = if (kind == LensKind.MACRO) out.any { it.kind == LensKind.MACRO }
            else out.any { it.kind != LensKind.MACRO && sameFov((it.equivMm ?: (it.zoom * mainEq)) / mainEq, z) }
            if (duplicate) continue
            val maxR = max(1f, c.zoomRatioRange?.endInclusive ?: c.maxDigitalZoom)
            out += Lens(c.id, kind, z, 1f, z, 1f, maxR, eq, c.megapixels, c.hidden)
        }
        return out.sortedWith(compareBy<Lens>({ it.kind == LensKind.MACRO }, { it.zoom }))
    }

    private fun sameFov(a: Float, b: Float) = abs(ln(a / b)) < ln(SAME_FOV)

    private fun kindOf(z: Float, s: LensSpec, front: Boolean): LensKind = when {
        z < ULTRAWIDE_MAX -> LensKind.ULTRAWIDE
        !front && s.fixedFocus && s.megapixels <= MACRO_MAX_MP -> LensKind.MACRO
        z >= TELE_MIN -> LensKind.TELE
        front -> LensKind.FRONT
        else -> LensKind.MAIN
    }

    /** 35 mm equivalent focal length of 1×. */
    private fun mainEquivMm(main: LensSpec, subs: List<LensSpec>): Float? {
        if (main.logical && subs.isNotEmpty()) {
            // Zoom ratio 1.0 of a logical camera is its main wide lens, which has the largest sensor.
            val wide = subs.maxWith(compareBy<LensSpec>({ it.diagonalMm }, { it.megapixels }))
            return wide.focalsMm.firstOrNull()?.let(wide::equivMm)
        }
        val zr = main.zoomRatioRange
        val f = if (main.focalsMm.size > 1 && zr != null && zr.start < 1f) {
            // Several focal lengths but no physical details: ratio 1.0 is about first / minRatio.
            val target = main.focalsMm.first() / zr.start
            main.focalsMm.minBy { abs(ln(it / target)) }
        } else {
            main.focalsMm.firstOrNull()
        }
        return f?.let(main::equivMm)
    }
}

object ZoomMath {
    /** Digital zoom past the longest lens is capped here: beyond it the crop has too few real pixels. */
    const val MAX_ZOOM = 30f

    /** Chip label: "0.6", "1", "2.5", "5". */
    fun label(z: Float): String {
        val rounded = z.roundToInt()
        return when {
            z >= 0.995f && abs(z - rounded) < 0.12f -> rounded.toString()
            else -> String.format(Locale.US, "%.1f", z).removeSuffix(".0")
        }
    }

    /** Current zoom: "0.6×", "1×", "1.4×", "10×". */
    fun display(z: Float): String {
        val s = if (z >= 9.95f) z.roundToInt().toString() else String.format(Locale.US, "%.1f", z).removeSuffix(".0")
        return "$s×"
    }

    /** The optical lens for [zoom]: the longest one whose view is not narrower than requested. */
    fun lensFor(lenses: List<Lens>, zoom: Float): Lens? {
        val optical = lenses.filter { it.kind != LensKind.MACRO }
        return optical.filter { it.zoom <= zoom * 1.001f }.maxByOrNull { it.zoom } ?: optical.minByOrNull { it.zoom }
    }

    /** Digital zoom steps offered as buttons next to the lenses: 2× when there is no telephoto lens. */
    fun digitalStops(lenses: List<Lens>): List<Float> {
        val optical = lenses.filter { it.kind != LensKind.MACRO }
        if (optical.isEmpty() || optical.any { it.zoom >= 1.8f }) return emptyList()
        return if (range(lenses).endInclusive >= 2f) listOf(2f) else emptyList()
    }

    /** Zoom reachable with [lenses] (optical lenses plus their digital zoom). */
    fun range(lenses: List<Lens>): ClosedFloatingPointRange<Float> {
        val optical = lenses.filter { it.kind != LensKind.MACRO }
        if (optical.isEmpty()) return 1f..1f
        return optical.minOf { min(it.minZoom, it.zoom) }..optical.maxOf { max(it.maxZoom, it.zoom) }
    }
}
