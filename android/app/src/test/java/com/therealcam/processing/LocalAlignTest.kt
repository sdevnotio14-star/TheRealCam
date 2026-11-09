package com.therealcam.processing

import com.therealcam.data.StackMode
import com.therealcam.processing.Synth.gray
import com.therealcam.processing.core.FrameSelector
import com.therealcam.processing.core.FrameStats
import com.therealcam.processing.core.Layer
import com.therealcam.processing.core.LocalAlignConfig
import com.therealcam.processing.core.LocalAligner
import com.therealcam.processing.core.Luma8
import com.therealcam.processing.core.OPAQUE
import com.therealcam.processing.core.OffsetField
import com.therealcam.processing.core.PlannedOutput
import com.therealcam.processing.core.StackEngine
import com.therealcam.processing.core.StackPlan
import com.therealcam.processing.core.TileFilter
import com.therealcam.processing.core.TileGrid
import com.therealcam.processing.core.judgeLocal
import com.therealcam.processing.core.packRgb
import com.therealcam.processing.core.roundHalfUp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class LocalAlignTest {

    /**
     * Stars turning about a celestial pole far above the frame, over a static textured foreground in the
     * bottom third; the whole camera jitters by a pixel or two between frames.
     */
    private class NightScene(val w: Int, val h: Int, val frames: Int, seed: Long, val degPerFrame: Double = 0.2) {
        val horizon = h * 2 / 3
        private val poleX = 0.35 * w
        private val poleY = -2.0 * h
        private val stars: List<Blob>
        private val ground = Synth.texture(seed + 1, count = w * h / 800, w = w, h = h)
        val jitter: List<Pair<Int, Int>>

        init {
            val r = Random(seed)
            stars = List(w * (horizon + 160) / 1200) {
                Blob(
                    -80 + r.nextDouble() * (w + 160), -80 + r.nextDouble() * (horizon + 80),
                    70.0 + r.nextDouble() * 130.0, 1.0 + r.nextDouble() * 0.6,
                )
            }
            jitter = List(frames) { (r.nextInt(5) - 2) to (r.nextInt(5) - 2) }
        }

        fun starsAt(k: Int): List<Blob> {
            val a = Math.toRadians(degPerFrame * (k - (frames - 1) / 2.0))
            val c = cos(a)
            val s = sin(a)
            val (jx, jy) = jitter[k]
            return stars.map { b ->
                val x = b.x - poleX
                val y = b.y - poleY
                Blob(poleX + c * x - s * y + jx, poleY + s * x + c * y + jy, b.amp, b.sigma)
            }
        }

        fun render(k: Int, noise: Double = 0.0, rnd: Random? = null): DoubleArray {
            val (jx, jy) = jitter[k]
            val sky = Synth.render(w, h, starsAt(k), SKY.toDouble())
            val fg = Synth.render(w, h, ground, GROUND, jx, jy)
            val r = rnd ?: Random(0)
            return DoubleArray(w * h) { i ->
                val v = if (i / w - jy >= horizon) fg[i] else sky[i]
                if (noise > 0) v + r.nextGaussian() * noise else v
            }
        }
    }

    private class StarStats(val brightness: Double, val keptShare: Double, val count: Int)

    /** Stacked vs single-frame star peaks (above the sky level) at the reference star positions inside [region]. */
    private fun starStats(out: IntArray, clean: IntArray, stars: List<Blob>, w: Int, region: (Int, Int) -> Boolean): StarStats {
        var so = 0.0
        var sc = 0.0
        var kept = 0
        var n = 0
        for (b in stars) {
            val x = b.x.roundToInt()
            val y = b.y.roundToInt()
            if (!region(x, y)) continue
            val c = gray(clean[y * w + x]) - SKY
            if (c < 40) continue
            val o = gray(out[y * w + x]) - SKY
            so += o
            sc += c
            n++
            if (o >= 0.5 * c) kept++
        }
        return StarStats(so / sc, kept.toDouble() / n, n)
    }

    private suspend fun mergeOf(plan: StackPlan, frames: List<IntArray>, w: Int, h: Int, engine: StackEngine): IntArray {
        val out = IntArray(w * h)
        engine.merge(
            w, h, StackMode.SIGMA_CLIP, (plan.output as PlannedOutput.Merge).layers, MemoryFrameSource(frames, w, h), 2.5f,
            { y0, rows, px -> System.arraycopy(px, 0, out, y0 * w, rows * w) },
        )
        return out
    }

    @Test
    fun longStarSeries_localAlignKeepsStarsAndForeground_globalAlignLosesStars() = runBlocking {
        val scene = NightScene(512, 384, frames = 16, seed = 77)
        val w = scene.w
        val h = scene.h
        val plane = 5
        val headlights = 11
        val streak = Synth.lineMask(w, h, 0.0, 30.0, w - 1.0, 150.0, halfWidth = 1.2)
        val rnd = Random(4)
        val frames = List(scene.frames) { k ->
            val c = scene.render(k, noise = 5.0, rnd = rnd)
            if (k == plane) Synth.add(c, streak, 150.0)
            if (k == headlights) for (i in c.indices) c[i] += 90.0 + 60.0 * (i % w) / w
            Synth.toArgb(c)
        }
        val engine = StackEngine()
        val meta = frames.indices.map { Synth.frame(it) }
        val options = StackOptions(StackMode.SIGMA_CLIP, align = true, rejectOutlierFrames = true, localAlign = true)

        val t0 = System.nanoTime()
        val plan = engine.plan(meta, options, MemoryFrameSource(frames, w, h))
        val t1 = System.nanoTime()
        val out = mergeOf(plan, frames, w, h, engine)
        val t2 = System.nanoTime()

        assertEquals(RejectReason.BRIGHTNESS_OUTLIER, plan.reports[headlights].rejectReason)
        for ((i, r) in plan.reports.withIndex()) if (i != headlights) assertTrue("frame $i: ${r.rejectReason}", r.accepted)
        assertTrue("reference ${plan.reference} is not the middle frame", plan.reference in 7..8)
        assertTrue(plan.localAligned)
        val layers = (plan.output as PlannedOutput.Merge).layers
        assertEquals(15, layers.size)
        assertTrue(layers.drop(1).all { it.field != null })

        val ref = plan.reference
        val clean = Synth.toArgb(scene.render(ref))
        val stars = scene.starsAt(ref)
        val margin = 48 // the tile row on each side of the horizon interpolates between sky and ground motion
        val inSky = { x: Int, y: Int -> x in 12 until w - 12 && y in 12 until scene.horizon - margin }
        val local = starStats(out, clean, stars, w, inSky)

        // Foreground: everywhere well below the horizon the stack matches the clean reference (noise / sqrt(15)).
        var s2 = 0.0
        var count = 0
        for (y in scene.horizon + margin until h - 8) for (x in 8 until w - 8) {
            val d = (gray(out[y * w + x]) - gray(clean[y * w + x])).toDouble()
            s2 += d * d
            count++
        }
        val groundRms = sqrt(s2 / count)

        // Dark sky away from stars: the plane streak (and anything else transient) is clipped away.
        var residue = 0
        for (y in 8 until scene.horizon - margin) for (x in 8 until w - 8) {
            val i = y * w + x
            if (gray(clean[i]) > SKY + 2) continue
            residue = max(residue, abs(gray(out[i]) - gray(clean[i])))
        }

        val globalPlan = engine.plan(meta, options.copy(localAlign = false), MemoryFrameSource(frames, w, h))
        val globalOut = mergeOf(globalPlan, frames, w, h, engine)
        val globalRef = globalPlan.reference
        val global = starStats(globalOut, Synth.toArgb(scene.render(globalRef)), scene.starsAt(globalRef), w, inSky)

        val conf = plan.reports.mapNotNull { it.alignConfidence }
        println(
            "local: stars ${"%.3f".format(local.brightness)} kept ${"%.2f".format(local.keptShare)} (n=${local.count}), " +
                "ground rms ${"%.2f".format(groundRms)}, sky residue $residue, tile confidence ${conf.map { "%.2f".format(it) }}; " +
                "global: stars ${"%.3f".format(global.brightness)} kept ${"%.2f".format(global.keptShare)}; " +
                "plan ${(t1 - t0) / 1_000_000} ms, merge ${(t2 - t1) / 1_000_000} ms",
        )
        assertTrue("too few stars measured: ${local.count}", local.count >= 40)
        assertTrue("stars lost brightness with local alignment: ${local.brightness}", local.brightness >= 0.8)
        assertTrue("stars clipped with local alignment: ${local.keptShare}", local.keptShare >= 0.9)
        assertTrue("foreground rms $groundRms", groundRms < 2.5)
        assertTrue("sky residue $residue", residue <= 12)
        assertTrue("every aligned frame should report its tile confidence", conf.size == 14 && conf.all { it > 0.5 })
        // The documented problem: one shift per frame cannot follow both stars and ground.
        assertTrue("global alignment unexpectedly kept the stars: ${global.brightness}", global.brightness < 0.6)
    }

    @Test
    fun alignerFillsStarlessTilesFromNeighbours_evenWhenTheGlobalShiftIsWrong() = runBlocking {
        val w = 384
        val h = 256
        val r = Random(5)
        // Stars only right of x = 170: the tiles on the left see nothing but noise.
        val stars = List(130) { Blob(170 + r.nextDouble() * (w - 150), -20 + r.nextDouble() * (h + 40), 80.0 + 120 * r.nextDouble(), 1.2) }
        val shifts = listOf(0 to 0, 4 to -3, -5 to 2, 7 to 6)
        val frames = shifts.map { (sx, sy) -> Synth.toArgb(Synth.render(w, h, stars, SKY.toDouble(), sx, sy, noise = 4.0, rnd = r)) }
        val source = MemoryFrameSource(frames, w, h)
        val lumas = Array<Luma8?>(frames.size) { source.analysisLuma(it) }
        val targets = listOf(1, 2, 3)
        val cfg = LocalAlignConfig()
        // Random jumps defeat the drift prediction, so the global shift has to seed the search. It is 1-2 px
        // off: star tiles must find the exact shift, and starless tiles take it from them, not the global one.
        val result = LocalAligner(cfg, workers = 2).align(
            source, lumas, 0, targets,
            IntArray(3) { -shifts[targets[it]].first + 1 }, IntArray(3) { -shifts[targets[it]].second - 2 },
            BooleanArray(3) { true },
        )
        assertNotNull(result)
        result!!
        val grid = result.fields[0].grid
        assertEquals(6 to 4, grid.nx to grid.ny)
        assertNotNull("series has structure", result.failed)
        assertTrue(result.failed!!.none { it })
        for ((k, pos) in targets.withIndex()) {
            val (sx, sy) = shifts[pos]
            val f = result.fields[k]
            for (t in 0 until grid.count) {
                val cx = grid.centreX(t % grid.nx)
                val confident = result.confident[k][t]
                if (cx < 130) assertFalse("starless tile $t of frame $pos matched", confident)
                if (cx > 220) assertTrue("star tile $t of frame $pos not matched", confident)
                // Starless tiles are filled from their neighbours, so the whole field is the true shift.
                assertEquals("dx tile $t frame $pos", -sx.toDouble(), f.dx[t].toDouble(), 0.6)
                assertEquals("dy tile $t frame $pos", -sy.toDouble(), f.dy[t].toDouble(), 0.6)
            }
            assertTrue(result.confidence[k] in 0.4..0.8)
        }
    }

    @Test
    fun featurelessSeriesFallsBackToGlobalAlignment() = runBlocking {
        val w = 256
        val h = 192
        val rnd = Random(8)
        val frames = List(5) { Synth.toArgb(Synth.render(w, h, emptyList(), 60.0, noise = 4.0, rnd = rnd)) }
        val plan = StackEngine().plan(
            frames.indices.map { Synth.frame(it) },
            StackOptions(StackMode.SIGMA_CLIP, align = true, rejectOutlierFrames = true, localAlign = true),
            MemoryFrameSource(frames, w, h),
        )
        assertFalse(plan.localAligned)
        assertTrue(plan.reports.all { it.accepted && it.dx == 0 && it.dy == 0 && it.alignConfidence == null })
        assertTrue((plan.output as PlannedOutput.Merge).layers.all { it.field == null })
    }

    @Test
    fun tileFilter_outlierIsReplacedByNeighbourhoodMedian() {
        val nx = 5
        val ny = 4
        // A smooth (linear) field as from a small rotation; one tile matched something else.
        val dx = FloatArray(nx * ny) { 0.5f * (it % nx) + 0.25f * (it / nx) }
        val dy = FloatArray(nx * ny) { -0.3f * (it % nx) + 1f }
        val truthX = dx.copyOf()
        val truthY = dy.copyOf()
        val bad = 1 * nx + 2
        dx[bad] += 30f
        dy[bad] -= 12f
        val conf = BooleanArray(nx * ny) { true }

        assertEquals(1, TileFilter.rejectOutliers(nx, ny, dx, dy, conf, minTol = 2.0))
        assertFalse(conf[bad])
        assertEquals(nx * ny - 1, conf.count { it })
        TileFilter.fill(nx, ny, dx, dy, conf, rings = 3, fallbackDx = 99f, fallbackDy = 99f)
        // For a linear field the median of the 8 neighbours is the tile's own true value.
        assertEquals(truthX[bad], dx[bad], 1e-5f)
        assertEquals(truthY[bad], dy[bad], 1e-5f)
        for (t in 0 until nx * ny) if (t != bad) assertEquals(truthX[t], dx[t], 0f)
    }

    @Test
    fun tileFilter_flatTilesFallBackToNeighboursThenGlobal() {
        val nx = 7
        val ny = 3
        val dx = FloatArray(nx * ny) { -40f }
        val dy = FloatArray(nx * ny) { -40f }
        // Only the two left columns found structure.
        val conf = BooleanArray(nx * ny) { it % nx < 2 }
        for (t in conf.indices) if (conf[t]) {
            dx[t] = 3f
            dy[t] = -2f
        }
        TileFilter.fill(nx, ny, dx, dy, conf, rings = 2, fallbackDx = 7f, fallbackDy = 8f)
        for (t in 0 until nx * ny) {
            val col = t % nx
            val (ex, ey) = if (col < 4) 3f to -2f else 7f to 8f
            assertEquals("dx col $col", ex, dx[t], 0f)
            assertEquals("dy col $col", ey, dy[t], 0f)
        }

        val none = BooleanArray(nx * ny)
        TileFilter.fill(nx, ny, dx, dy, none, rings = 3, fallbackDx = -1f, fallbackDy = 2f)
        assertTrue(dx.all { it == -1f } && dy.all { it == 2f })
    }

    @Test
    fun judgeLocal_failsOnlyFramesFarBelowTheMedian() {
        val cfg = LocalAlignConfig()
        assertArrayEquals(
            booleanArrayOf(false, false, true, false, false),
            judgeLocal(doubleArrayOf(0.62, 0.58, 0.1, 0.6, 0.55), cfg),
        )
        assertNull(judgeLocal(doubleArrayOf(0.01, 0.0, 0.03), cfg))
    }

    @Test
    fun middleReferenceIsTheAcceptedFrameNearestTheMiddle() {
        val stats = List(9) { FrameStats(if (it == 4) 90.0 else 20.0 + it * 0.1, sharpness = 10.0 + it, motionRms = null, usable = true) }
        val sel = FrameSelector.select(stats, StackMode.SIGMA_CLIP, true, 0.03, 0.4f, middleReference = true)
        assertEquals(RejectReason.BRIGHTNESS_OUTLIER, sel.reasons[4])
        // Frames 3 and 5 are equally near the middle; the sharper one wins.
        assertEquals(5, sel.reference)
        assertEquals(8, FrameSelector.select(stats, StackMode.SIGMA_CLIP, true, 0.03, 0.4f).reference)
        assertEquals(8, FrameSelector.select(stats, StackMode.LUCKY, true, 0.03, 0.4f, middleReference = true).reference)
    }

    @Test
    fun fieldLayersTakeWholeSourcePixels_missingOutsideTheFrame() = runBlocking {
        val w = 64
        val h = 50 // not a multiple of the 16-row strips
        val rnd = Random(3)
        val frames = List(2) { IntArray(w * h) { packRgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) } }
        val grid = TileGrid(w, h, 4, 3)
        // Rotation-like field with fractional values: shifts change across rows and columns.
        val field = OffsetField(
            grid,
            FloatArray(grid.count) { 2.6f + 0.9f * (it % 4) - 1.3f * (it / 4) },
            FloatArray(grid.count) { -3.4f + 1.1f * (it % 4) + 0.7f * (it / 4) },
        )
        val layers = listOf(Layer(0, 0, 0), Layer(1, field.centreDx(), field.centreDy(), field))
        val rx = FloatArray(grid.nx + 1)
        val ry = FloatArray(grid.nx + 1)
        val engine = StackEngine(workers = 2, stripRowsOverride = 16)
        for (mode in listOf(StackMode.MEAN, StackMode.LIGHTEN, StackMode.SIGMA_CLIP)) {
            val out = IntArray(w * h)
            engine.merge(w, h, mode, layers, MemoryFrameSource(frames, w, h), 2.5f, { y0, rows, px -> System.arraycopy(px, 0, out, y0 * w, rows * w) })
            var outside = 0
            for (y in 0 until h) {
                field.rowValues(y + 0.5, rx, ry)
                for (x in 0 until w) {
                    val k = grid.colSegment[x]
                    val f = grid.colWeight[x]
                    val sx = x - roundHalfUp(rx[k] + f * (rx[k + 1] - rx[k]))
                    val sy = y - roundHalfUp(ry[k] + f * (ry[k + 1] - ry[k]))
                    val a = frames[0][y * w + x]
                    val expected = if (sx !in 0 until w || sy !in 0 until h) {
                        outside++
                        a or OPAQUE
                    } else {
                        val b = frames[1][sy * w + sx]
                        if (mode == StackMode.LIGHTEN) {
                            packRgb(
                                max((a ushr 16) and 0xFF, (b ushr 16) and 0xFF),
                                max((a ushr 8) and 0xFF, (b ushr 8) and 0xFF),
                                max(a and 0xFF, b and 0xFF),
                            )
                        } else {
                            packRgb(
                                (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF) + 1) / 2,
                                (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF) + 1) / 2,
                                ((a and 0xFF) + (b and 0xFF) + 1) / 2,
                            )
                        }
                    }
                    assertEquals("$mode at ($x,$y) from ($sx,$sy)", expected, out[y * w + x])
                }
            }
            assertTrue("field should push some pixels outside the frame", outside in 1 until w * h / 2)
        }
    }

    private companion object {
        const val SKY = 20
        const val GROUND = 70.0
    }
}
