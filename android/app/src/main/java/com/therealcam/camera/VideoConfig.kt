package com.therealcam.camera

import kotlin.math.abs
import kotlin.math.roundToLong

/** Resolutions of VIDEO mode; sizes are the landscape (sensor-oriented) recorder sizes. */
enum class VideoQuality(val key: String, val label: String, val width: Int, val height: Int) {
    HD("720p", "720p", 1280, 720),
    FHD("1080p", "1080p", 1920, 1080),
    UHD("4k", "4K", 3840, 2160);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: FHD
    }
}

/** Plain size, so the choice logic runs in JVM unit tests (android.util.Size is a stub there). */
data class Dim(val width: Int, val height: Int) {
    val pixels get() = width.toLong() * height
    val aspect get() = width.toDouble() / height
}

/** What one camera offers for recording (read by [VideoCapsReader]). */
class VideoCapsInfo(
    val recorderSizes: List<Dim>,
    val previewSizes: List<Dim>,
    /** CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES as (lower, upper). */
    val fpsRanges: List<Pair<Int, Int>>,
    /** Minimum frame durations (ns) of recorder and preview sizes; missing or 0 = unknown. */
    val recorderMinFrameNs: Map<Dim, Long>,
    val previewMinFrameNs: Map<Dim, Long>,
    val stabilization: Boolean,
    /** Whether an H.264 encoder takes this size at this frame rate. */
    val encoder: (Dim, Int) -> Boolean,
)

/** Choices shown in the UI for the open camera. */
data class VideoOptions(
    val qualities: List<VideoQuality>,
    /** Qualities that can also be recorded at 60 fps. */
    val fps60: Set<VideoQuality>,
    val stabilization: Boolean,
)

/** Recorder and capture session configuration. */
data class VideoSpec(
    val quality: VideoQuality,
    val size: Dim,
    val fps: Int,
    val bitRate: Int,
    /** CONTROL_AE_TARGET_FPS_RANGE; null when no listed range contains [fps]. */
    val aeFps: Pair<Int, Int>?,
    /** SurfaceTexture buffer size, same aspect as the video. */
    val preview: Dim,
    val stabilization: Boolean,
)

object VideoConfig {
    const val MAX_PREVIEW_W = 1920
    const val MAX_PREVIEW_H = 1080

    /** 60 fps frame duration with some tolerance for HALs that report 16.67 ms. */
    private const val FRAME_60_NS = 16_700_000L

    fun sizeFor(q: VideoQuality, sizes: List<Dim>): Dim? = sizes.firstOrNull { it.width == q.width && it.height == q.height }

    fun options(c: VideoCapsInfo): VideoOptions {
        var qs = VideoQuality.entries.filter { q -> sizeFor(q, c.recorderSizes)?.let { c.encoder(it, 30) } == true }
        // MediaCodecList can under-report; offering what the camera lists beats offering nothing.
        if (qs.isEmpty()) qs = VideoQuality.entries.filter { sizeFor(it, c.recorderSizes) != null }
        val fps60 = qs.filter { q -> sizeFor(q, c.recorderSizes)?.let { supports60(c, it) } == true }.toSet()
        return VideoOptions(qs, fps60, c.stabilization)
    }

    /** Needs a fixed [60, 60] AE range, an encoder for it, and streams fast enough for 60 fps. */
    fun supports60(c: VideoCapsInfo, size: Dim): Boolean {
        if (c.fpsRanges.none { it.first == 60 && it.second == 60 }) return false
        if (!c.encoder(size, 60)) return false
        val rec = c.recorderMinFrameNs[size] ?: 0L
        val prev = previewSize(c.previewSizes, size)?.let { c.previewMinFrameNs[it] } ?: 0L
        return rec <= FRAME_60_NS && prev <= FRAME_60_NS
    }

    /** The user's choice adjusted to the camera; null if it records none of the offered sizes. */
    fun choose(c: VideoCapsInfo, quality: VideoQuality, fps: Int, stabilization: Boolean): VideoSpec? {
        val o = options(c)
        val q = if (quality in o.qualities) quality
        else o.qualities.lastOrNull { it.ordinal < quality.ordinal } ?: o.qualities.firstOrNull() ?: return null
        val size = sizeFor(q, c.recorderSizes) ?: return null
        val f = if (fps >= 60 && q in o.fps60) 60 else 30
        return VideoSpec(
            quality = q, size = size, fps = f, bitRate = bitRate(size, f), aeFps = aeRange(c.fpsRanges, f),
            preview = previewSize(c.previewSizes, size) ?: size, stabilization = stabilization && c.stabilization,
        )
    }

    /** About 0.18 bit per pixel and frame: 6 / 12 / 40 Mbit/s at 30 fps, 5/3 of that at 60 fps. */
    fun bitRate(size: Dim, fps: Int): Int {
        val at30 = when {
            size.width == 3840 && size.height == 2160 -> 40_000_000L
            size.width == 1920 && size.height == 1080 -> 12_000_000L
            size.width == 1280 && size.height == 720 -> 6_000_000L
            else -> (size.pixels * 30 * 0.19).roundToLong().coerceAtLeast(1_000_000L)
        }
        return (if (fps > 30) at30 * fps / 36 else at30).toInt()
    }

    /** [fps, fps] if listed, else a range containing fps that does not exceed it and varies least. */
    fun aeRange(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? =
        ranges.filter { fps in it.first..it.second }
            .minWithOrNull(compareBy<Pair<Int, Int>>({ it.second != fps }, { -it.first }, { it.second }))

    /** Largest SurfaceTexture size with the video aspect up to 1920×1080 (any aspect if none matches). */
    fun previewSize(sizes: List<Dim>, video: Dim): Dim? {
        val fitting = sizes.filter { it.width <= MAX_PREVIEW_W && it.height <= MAX_PREVIEW_H }
        val same = fitting.filter { abs(it.aspect - video.aspect) < 0.01 }
        return same.ifEmpty { fitting }.maxByOrNull { it.pixels }
    }
}
