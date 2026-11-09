package com.therealcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoConfigTest {
    private val hd = Dim(1280, 720)
    private val fhd = Dim(1920, 1080)
    private val uhd = Dim(3840, 2160)
    private val previews = listOf(Dim(1920, 1440), fhd, Dim(1440, 1080), hd, Dim(640, 480))

    private fun caps(
        recorder: List<Dim> = listOf(uhd, fhd, Dim(1440, 1080), hd, Dim(640, 480)),
        ranges: List<Pair<Int, Int>> = listOf(15 to 30, 30 to 30),
        recMin: Map<Dim, Long> = emptyMap(),
        prevMin: Map<Dim, Long> = emptyMap(),
        stab: Boolean = true,
        encoder: (Dim, Int) -> Boolean = { _, _ -> true },
    ) = VideoCapsInfo(recorder, previews, ranges, recMin, prevMin, stab, encoder)

    @Test
    fun offersOnlyRecorderSizesTheEncoderTakes() {
        val o = VideoConfig.options(caps(encoder = { d, _ -> d.pixels <= fhd.pixels }))
        assertEquals(listOf(VideoQuality.HD, VideoQuality.FHD), o.qualities)
        val noUhd = VideoConfig.options(caps(recorder = listOf(fhd, hd)))
        assertEquals(listOf(VideoQuality.HD, VideoQuality.FHD), noUhd.qualities)
    }

    @Test
    fun encoderListThatRejectsEverythingFallsBackToCameraSizes() {
        val o = VideoConfig.options(caps(recorder = listOf(fhd, hd), encoder = { _, _ -> false }))
        assertEquals(listOf(VideoQuality.HD, VideoQuality.FHD), o.qualities)
    }

    @Test
    fun sixtyFpsNeedsFixedRangeEncoderAndFastStreams() {
        assertTrue(VideoConfig.options(caps()).fps60.isEmpty())
        val fast = caps(ranges = listOf(15 to 30, 30 to 30, 60 to 60), recMin = mapOf(uhd to 33_333_333L, fhd to 16_666_666L))
        assertEquals(setOf(VideoQuality.HD, VideoQuality.FHD), VideoConfig.options(fast).fps60)
        val slowPreview = caps(ranges = listOf(60 to 60), prevMin = mapOf(fhd to 33_333_333L))
        assertFalse(VideoConfig.supports60(slowPreview, fhd))
        val noEncoder = caps(ranges = listOf(60 to 60), encoder = { _, fps -> fps <= 30 })
        assertFalse(VideoConfig.supports60(noEncoder, fhd))
    }

    @Test
    fun chooseFallsBackToLowerQualityAndThirtyFps() {
        val c = caps(recorder = listOf(fhd, hd))
        val s = VideoConfig.choose(c, VideoQuality.UHD, 60, stabilization = true)!!
        assertEquals(VideoQuality.FHD, s.quality)
        assertEquals(fhd, s.size)
        assertEquals(30, s.fps)
        assertEquals(30 to 30, s.aeFps)
        assertEquals(fhd, s.preview)
        assertTrue(s.stabilization)
        // Only 4K listed: a lower wish takes the lowest offered quality.
        assertEquals(VideoQuality.UHD, VideoConfig.choose(caps(recorder = listOf(uhd)), VideoQuality.HD, 30, true)!!.quality)
        assertNull(VideoConfig.choose(caps(recorder = listOf(Dim(640, 480))), VideoQuality.FHD, 30, true))
    }

    @Test
    fun chooseKeepsSixtyAndStabilizationOnlyWhenSupported() {
        val c = caps(ranges = listOf(30 to 30, 60 to 60), stab = false)
        val s = VideoConfig.choose(c, VideoQuality.FHD, 60, stabilization = true)!!
        assertEquals(60, s.fps)
        assertEquals(60 to 60, s.aeFps)
        assertEquals(20_000_000, s.bitRate)
        assertFalse(s.stabilization)
    }

    @Test
    fun bitRates() {
        assertEquals(6_000_000, VideoConfig.bitRate(hd, 30))
        assertEquals(12_000_000, VideoConfig.bitRate(fhd, 30))
        assertEquals(20_000_000, VideoConfig.bitRate(fhd, 60))
        assertEquals(40_000_000, VideoConfig.bitRate(uhd, 30))
        assertEquals(10_000_000, VideoConfig.bitRate(hd, 60))
        val odd = VideoConfig.bitRate(Dim(1440, 1080), 30)
        assertTrue(odd in 8_000_000..10_000_000)
    }

    @Test
    fun aeRangePrefersFixedThenNotAboveFps() {
        assertEquals(30 to 30, VideoConfig.aeRange(listOf(15 to 30, 30 to 30, 7 to 60), 30))
        assertEquals(24 to 30, VideoConfig.aeRange(listOf(15 to 30, 24 to 30, 30 to 60), 30))
        assertEquals(30 to 60, VideoConfig.aeRange(listOf(15 to 24, 30 to 60), 30))
        assertNull(VideoConfig.aeRange(listOf(15 to 24), 30))
    }

    @Test
    fun previewKeepsVideoAspectWithinFullHd() {
        assertEquals(fhd, VideoConfig.previewSize(previews, uhd))
        assertEquals(fhd, VideoConfig.previewSize(previews, hd))
        assertEquals(Dim(1440, 1080), VideoConfig.previewSize(listOf(Dim(1440, 1080), Dim(640, 480)), fhd))
        assertNull(VideoConfig.previewSize(emptyList(), fhd))
    }

    @Test
    fun qualityKeysRoundTrip() {
        VideoQuality.entries.forEach { assertEquals(it, VideoQuality.of(it.key)) }
        assertEquals(VideoQuality.FHD, VideoQuality.of(null))
        assertEquals(VideoQuality.FHD, VideoQuality.of("8k"))
    }
}
