package com.therealcam.processing.core

import com.therealcam.data.StackMode
import com.therealcam.processing.FrameReport
import com.therealcam.processing.RejectReason
import com.therealcam.processing.StackFrame
import com.therealcam.processing.StackOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel access to the frames of a series (JPEG decoders on Android, arrays in tests). */
internal interface FrameSource {
    /** Full-resolution size shared by all frames. */
    val width: Int
    val height: Int

    /** Downscaled luminance for analysis, or null if the frame cannot be used. */
    fun analysisLuma(position: Int): Luma8?

    /** Full-resolution ARGB of a rect inside the image. May be called concurrently for different frames. */
    fun readRegion(position: Int, left: Int, top: Int, width: Int, height: Int, dst: IntArray, dstOffset: Int, dstStride: Int)

    /**
     * Luminance at 1/[sampleSize] of full resolution (box average) for refining local alignment; null if the
     * frame cannot be read. Implementations may decode faster (JPEG DCT scaling).
     */
    fun scaledLuma(position: Int, sampleSize: Int): Luma8? {
        val s = sampleSize.coerceAtLeast(1)
        val w = width / s
        val h = height / s
        if (w == 0 || h == 0) return null
        val out = ByteArray(w * h)
        val band = max(1, 64 / s)
        val buf = IntArray(band * s * width)
        val area = s * s
        var y = 0
        while (y < h) {
            val rows = min(band, h - y)
            try {
                readRegion(position, 0, y * s, width, rows * s, buf, 0, width)
            } catch (e: IOException) {
                return null
            }
            for (r in 0 until rows) {
                for (x in 0 until w) {
                    var sum = 0
                    for (j in 0 until s) {
                        var i = (r * s + j) * width + x * s
                        for (k in 0 until s) sum += luma8(buf[i++])
                    }
                    out[(y + r) * w + x] = ((sum + area / 2) / area).toByte()
                }
            }
            y += rows
        }
        return Luma8(w, h, out)
    }
}

/** Receives finished output rows [y0, y0 + rows) (row stride = image width), in order, from one coroutine. */
internal fun interface StripSink {
    fun write(y0: Int, rows: Int, argb: IntArray)
}

/**
 * A frame placed with its applied shift (frame pixel (x, y) -> output (x + dx, y + dy)). With a [field] the
 * shift varies per pixel (local alignment) and dx/dy are only its value at the image centre.
 */
internal data class Layer(val position: Int, val dx: Int, val dy: Int, val field: OffsetField? = null)

internal sealed interface PlannedOutput {
    /** Output is a byte copy of this frame's file. */
    data class Copy(val position: Int) : PlannedOutput

    /** Output is merged from these layers; the reference comes first. */
    data class Merge(val layers: List<Layer>) : PlannedOutput
}

internal class StackPlan(
    val reports: List<FrameReport>,
    /** Position of the reference frame (EXIF source). */
    val reference: Int,
    val output: PlannedOutput,
    val aligned: Boolean,
    /** Layers carry per-tile offset fields. */
    val localAligned: Boolean = false,
) {
    val usedFrames: Int
        get() = when (output) {
            is PlannedOutput.Copy -> 1
            is PlannedOutput.Merge -> output.layers.size
        }
}

/** Largest power-of-two sample size that keeps the long side at or above [minLongSide]. */
internal fun analysisSampleSize(width: Int, height: Int, minLongSide: Int = 800): Int {
    val long = max(width, height)
    var s = 1
    while (long / (s * 2) >= minLongSide) s *= 2
    return s
}

internal fun defaultMemoryBudget(): Long {
    val rt = Runtime.getRuntime()
    val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    return (free / 5 * 2).coerceIn(16L shl 20, 128L shl 20)
}

/**
 * Deterministic stacking: analysis -> frame selection -> translation alignment -> strip-wise merge.
 * Pure Kotlin; the Android adapter supplies decoding ([FrameSource]) and encoding ([StripSink]).
 */
internal class StackEngine(
    private val workers: Int = defaultWorkers(),
    /** Heap budget for the per-strip buffers; decides the strip height. */
    private val memoryBudgetBytes: Long = defaultMemoryBudget(),
    /** Fixed strip height (tests); 0 = derive from the budget. */
    private val stripRowsOverride: Int = 0,
    private val alignConfig: AlignConfig = AlignConfig(),
    private val localConfig: LocalAlignConfig = LocalAlignConfig(),
) {

    suspend fun plan(
        frames: List<StackFrame>,
        options: StackOptions,
        source: FrameSource,
        onProgress: (Float) -> Unit = {},
    ): StackPlan = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty()) { "No frames to stack" }
        val n = frames.size
        val progress = Progress(onProgress)

        val lumas = arrayOfNulls<Luma8>(n)
        val means = DoubleArray(n)
        val sharpness = DoubleArray(n)
        val analysed = AtomicInteger()
        parallelFor(n, workers) { i, _ ->
            val l = source.analysisLuma(i)
            if (l != null) {
                lumas[i] = l
                means[i] = l.mean()
                sharpness[i] = l.laplacianVariance()
            }
            progress.report(0.5f * analysed.incrementAndGet() / n)
        }

        if (options.mode == StackMode.NONE || n == 1) {
            val reports = List(n) { FrameReport(frames[it].index, true, null, 0, 0, sharpness[it], means[it]) }
            return@withContext StackPlan(reports, 0, PlannedOutput.Copy(0), aligned = false)
        }

        val canonical = lumas.firstOrNull { it != null }
            ?: throw IOException("None of the $n frames could be decoded")
        val stats = List(n) { i ->
            val l = lumas[i]
            val usable = l != null && l.width == canonical.width && l.height == canonical.height
            if (!usable) lumas[i] = null
            FrameStats(means[i], sharpness[i], frames[i].motionRms, usable)
        }

        val merges = options.mode in MERGE_MODES
        val local = merges && options.align && options.localAlign
        val selection = FrameSelector.select(
            stats, options.mode, options.rejectOutlierFrames, options.maxMotionRadS, options.luckyKeepFraction,
            middleReference = local,
        )
        val reasons = selection.reasons
        val ref = selection.reference
        val dx = IntArray(n)
        val dy = IntArray(n)
        val fields = arrayOfNulls<OffsetField>(n)
        val confidence = arrayOfNulls<Double>(n)
        val targets = reasons.indices.filter { reasons[it] == null && it != ref }
        val aligned = merges && options.align && targets.isNotEmpty()
        if (aligned) {
            val global = align(source, lumas, ref, targets, progress, 0.5f, if (local) 0.75f else 1f)
            val localResult = if (local) alignLocal(source, lumas, ref, targets, global, progress) else null
            val failed = localResult?.failed
            for ((k, i) in targets.withIndex()) {
                if (localResult != null && failed != null) {
                    // Global verdicts are deferred: with field rotation, far frames match poorly as a whole.
                    confidence[i] = localResult.confidence[k]
                    if (failed[k]) {
                        reasons[i] = RejectReason.ALIGN_FAILED
                    } else {
                        val f = localResult.fields[k]
                        fields[i] = f
                        dx[i] = f.centreDx()
                        dy[i] = f.centreDy()
                    }
                } else {
                    when (global.verdicts[k]) {
                        AlignVerdict.OK -> {
                            dx[i] = global.dx[k]
                            dy[i] = global.dy[k]
                        }
                        AlignVerdict.FAILED -> reasons[i] = RejectReason.ALIGN_FAILED
                        AlignVerdict.NO_STRUCTURE -> Unit
                    }
                }
            }
        }
        progress.report(1f)

        val used = listOf(ref) + reasons.indices.filter { reasons[it] == null && it != ref }
        val output = if (!merges || used.size == 1) {
            PlannedOutput.Copy(ref)
        } else {
            PlannedOutput.Merge(used.map { Layer(it, dx[it], dy[it], fields[it]) })
        }
        val reports = List(n) { i ->
            FrameReport(frames[i].index, reasons[i] == null, reasons[i], dx[i], dy[i], sharpness[i], means[i], confidence[i])
        }
        val localAligned = output is PlannedOutput.Merge && output.layers.any { it.field != null }
        StackPlan(reports, ref, output, aligned, localAligned)
    }

    /** Global shift estimates per target (also for frames judged FAILED) and their verdicts. */
    private class GlobalAlignment(val dx: IntArray, val dy: IntArray, val verdicts: Array<AlignVerdict>)

    private suspend fun alignLocal(
        source: FrameSource, lumas: Array<Luma8?>, ref: Int, targets: List<Int>, global: GlobalAlignment, progress: Progress,
    ): LocalResult? {
        val m = targets.size
        val noStructure = BooleanArray(m) { global.verdicts[it] == AlignVerdict.NO_STRUCTURE }
        return LocalAligner(localConfig, workers).align(
            source, lumas, ref, targets,
            IntArray(m) { if (noStructure[it]) 0 else global.dx[it] },
            IntArray(m) { if (noStructure[it]) 0 else global.dy[it] },
            BooleanArray(m) { global.verdicts[it] == AlignVerdict.OK },
        ) { progress.report(0.75f + 0.25f * it) }
    }

    private suspend fun align(
        source: FrameSource,
        lumas: Array<Luma8?>,
        ref: Int,
        targets: List<Int>,
        progress: Progress,
        progressFrom: Float,
        progressTo: Float,
    ): GlobalAlignment {
        val cfg = alignConfig
        val searcher = TranslationSearcher(cfg)
        val refLuma = lumas[ref]!!
        val scaleX = source.width.toDouble() / refLuma.width
        val scaleY = source.height.toDouble() / refLuma.height
        val refPyramid = searcher.prepare(refLuma.toPlane())
        val maxShift = searcher.maxShift(refLuma.width, refLuma.height)
        val radius = max(cfg.refineRadius, ceil(max(scaleX, scaleY)).toInt())
        val margin = cfg.refineHpRadius + 1
        val crop = referenceCrop(source, ref, margin)

        val m = targets.size
        val ncc = DoubleArray(m)
        val z = DoubleArray(m)
        val fx = IntArray(m)
        val fy = IntArray(m)
        val done = AtomicInteger()
        parallelFor(m, workers) { k, _ ->
            val i = targets[k]
            val t = searcher.search(refPyramid, searcher.prepare(lumas[i]!!.toPlane()), maxShift)
            ncc[k] = t.ncc
            z[k] = t.z
            var sx = ((t.dx + t.subDx) * scaleX).roundToInt()
            var sy = ((t.dy + t.subDy) * scaleY).roundToInt()
            if (crop != null) {
                val r = refine(source, i, crop, sx, sy, radius, margin)
                // A peak on the window border means the true peak may lie outside: keep the coarse estimate.
                if (r != null && !r.onBorder && r.z >= cfg.minZ) {
                    sx = r.dx
                    sy = r.dy
                }
            }
            fx[k] = sx
            fy[k] = sy
            progress.report(progressFrom + (progressTo - progressFrom) * done.incrementAndGet() / m)
        }
        return GlobalAlignment(fx, fy, judgeAlignment(ncc, z, cfg))
    }

    private class Crop(val hp: LumaPlane, val left: Int, val top: Int)

    private fun referenceCrop(source: FrameSource, ref: Int, margin: Int): Crop? {
        val cw = min(alignConfig.refineCrop, source.width / 2)
        val ch = min(alignConfig.refineCrop, source.height / 2)
        if (cw < 8 * margin || ch < 8 * margin) return null
        val left = (source.width - cw) / 2
        val top = (source.height - ch) / 2
        val px = IntArray(cw * ch)
        source.readRegion(ref, left, top, cw, ch, px, 0, cw)
        return Crop(LumaPlane.fromArgb(cw, ch, px).highPass(alignConfig.refineHpRadius), left, top)
    }

    private fun refine(source: FrameSource, position: Int, crop: Crop, cdx: Int, cdy: Int, radius: Int, margin: Int): Refinement? {
        val l = max(0, crop.left - cdx - radius)
        val t = max(0, crop.top - cdy - radius)
        val r = min(source.width, crop.left + crop.hp.width - cdx + radius)
        val b = min(source.height, crop.top + crop.hp.height - cdy + radius)
        if (r - l < 4 * margin || b - t < 4 * margin) return null
        val w = r - l
        val h = b - t
        val px = IntArray(w * h)
        source.readRegion(position, l, t, w, h, px, 0, w)
        val hp = LumaPlane.fromArgb(w, h, px).highPass(alignConfig.refineHpRadius)
        return FullResRefiner.refine(crop.hp, crop.left, crop.top, hp, l, t, cdx, cdy, radius, margin)
    }

    /**
     * Merges [layers] at full resolution in horizontal strips. Memory is bounded by the strip buffers:
     * SIGMA_CLIP/LUCKY hold one strip per layer, MEAN/LIGHTEN stream layers through running accumulators.
     * Layers with an offset field are resampled per pixel (rounded shifts, whole source pixels) from a
     * per-worker band of source rows; pixels whose source lies outside a frame are missing for that frame.
     * Output pixels covered by no layer are black (cannot happen when the zero-shift reference is included).
     */
    suspend fun merge(
        width: Int,
        height: Int,
        mode: StackMode,
        layers: List<Layer>,
        source: FrameSource,
        sigma: Float,
        sink: StripSink,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        require(layers.isNotEmpty()) { "No layers to merge" }
        require(width > 0 && height > 0) { "Bad size ${width}x$height" }
        val progress = Progress(onProgress)
        val s = if (sigma.isFinite() && sigma >= 0f) sigma else 2.5f
        when (mode) {
            StackMode.SIGMA_CLIP, StackMode.LUCKY -> mergeSigmaClip(width, height, layers, source, s, sink, progress)
            StackMode.MEAN -> mergeStreaming(width, height, layers, source, mean = true, sink, progress)
            StackMode.LIGHTEN -> mergeStreaming(width, height, layers, source, mean = false, sink, progress)
            else -> throw IllegalArgumentException("$mode does not merge pixels")
        }
    }

    private suspend fun mergeSigmaClip(
        width: Int, height: Int, layers: List<Layer>, source: FrameSource, sigma: Float,
        sink: StripSink, progress: Progress,
    ) {
        val n = layers.size
        val bands = LocalBands.of(layers, width, workers, memoryBudgetBytes)
        val rows = stripRows(width, height, n + 1, bands)
        val strips = Array(n) { FrameStrip() }
        val scratch = Array(workers) { MergeScratch(n) }
        val out = IntArray(rows * width)
        var y0 = 0
        while (y0 < height) {
            val top = y0
            val h = min(rows, height - top)
            parallelFor(n, bands.workers) { j, w -> fill(strips[j], layers[j], top, h, width, height, source, bands, w) }
            parallelRanges(h, workers) { r0, r1, w ->
                StripKernels.sigmaClipRows(strips, n, top, r0, r1, width, out, sigma, scratch[w])
            }
            sink.write(top, h, out)
            y0 += h
            progress.report(y0.toFloat() / height)
        }
    }

    private suspend fun mergeStreaming(
        width: Int, height: Int, layers: List<Layer>, source: FrameSource, mean: Boolean,
        sink: StripSink, progress: Progress,
    ) {
        val n = layers.size
        val group = min(n, max(2, workers))
        val bands = LocalBands.of(layers, width, workers, memoryBudgetBytes)
        val rows = stripRows(width, height, group + if (mean) 5 else 2, bands)
        val size = rows * width
        val strips = Array(group) { FrameStrip() }
        val out = IntArray(size)
        val sumR = if (mean) IntArray(size) else null
        val sumG = if (mean) IntArray(size) else null
        val sumB = if (mean) IntArray(size) else null
        val cnt = if (mean) IntArray(size) else null
        val acc = if (mean) null else IntArray(size)
        var y0 = 0
        while (y0 < height) {
            val top = y0
            val h = min(rows, height - top)
            val used = h * width
            if (mean) {
                sumR!!.fill(0, 0, used)
                sumG!!.fill(0, 0, used)
                sumB!!.fill(0, 0, used)
                cnt!!.fill(0, 0, used)
            } else {
                acc!!.fill(0, 0, used)
            }
            var start = 0
            while (start < n) {
                val first = start
                val c = min(group, n - first)
                parallelFor(c, bands.workers) { j, w -> fill(strips[j], layers[first + j], top, h, width, height, source, bands, w) }
                parallelRanges(h, workers) { r0, r1, _ ->
                    for (j in 0 until c) {
                        if (mean) {
                            StripKernels.accumulateMean(strips[j], top, r0, r1, width, sumR!!, sumG!!, sumB!!, cnt!!)
                        } else {
                            StripKernels.accumulateMax(strips[j], top, r0, r1, width, acc!!)
                        }
                    }
                }
                start += c
            }
            parallelRanges(h, workers) { r0, r1, _ ->
                if (mean) {
                    StripKernels.finishMean(r0, r1, width, sumR!!, sumG!!, sumB!!, cnt!!, out)
                } else {
                    StripKernels.finishMax(r0, r1, width, acc!!, out)
                }
            }
            sink.write(top, h, out)
            y0 += h
            progress.report(y0.toFloat() / height)
        }
    }

    /** Reads the frame rows that land in output rows [y0, y0 + rows) (full width) and positions them. */
    private fun fill(
        strip: FrameStrip, layer: Layer, y0: Int, rows: Int, width: Int, height: Int, source: FrameSource,
        bands: LocalBands, worker: Int,
    ) {
        if (layer.field != null) {
            fillLocal(strip, layer.position, layer.field, y0, rows, width, height, source, bands.band(worker))
            return
        }
        val sy0 = max(0, y0 - layer.dy)
        val sy1 = min(height, y0 + rows - layer.dy)
        if (sy0 >= sy1 || layer.dx >= width || layer.dx <= -width) {
            strip.height = 0
            return
        }
        strip.ensureCapacity((sy1 - sy0) * width)
        source.readRegion(layer.position, 0, sy0, width, sy1 - sy0, strip.pixels, 0, width)
        strip.place(sy0, sy1 - sy0, width, layer.dx, layer.dy)
    }

    /**
     * Output strip [y0, y0 + rows) of a frame with an offset field: reads the source rows the field can reach
     * (full width) and takes every output pixel from (x - dx, y - dy), the bilinear field rounded to whole
     * pixels. Pixels whose source lies outside the frame are [MISSING].
     */
    private fun fillLocal(
        strip: FrameStrip, position: Int, field: OffsetField, y0: Int, rows: Int, width: Int, height: Int,
        source: FrameSource, band: LocalBand,
    ) {
        field.dyBounds(y0, y0 + rows, band.bounds)
        val sy0 = max(0, y0 - band.bounds[1])
        val sy1 = min(height, y0 + rows - band.bounds[0])
        if (sy0 >= sy1) {
            strip.height = 0
            return
        }
        if (band.px.size < (sy1 - sy0) * width) band.px = IntArray((sy1 - sy0) * width)
        source.readRegion(position, 0, sy0, width, sy1 - sy0, band.px, 0, width)
        strip.ensureCapacity(rows * width)
        val grid = field.grid
        if (band.rx.size < grid.nx + 1) {
            band.rx = FloatArray(grid.nx + 1)
            band.ry = FloatArray(grid.nx + 1)
        }
        val rx = band.rx
        val ry = band.ry
        val seg = grid.colSegment
        val wgt = grid.colWeight
        val src = band.px
        val dst = strip.pixels
        for (r in 0 until rows) {
            val y = y0 + r
            field.rowValues(y + 0.5, rx, ry)
            val o = r * width
            for (x in 0 until width) {
                val k = seg[x]
                val f = wgt[x]
                val sx = x - roundHalfUp(rx[k] + f * (rx[k + 1] - rx[k]))
                val sy = y - roundHalfUp(ry[k] + f * (ry[k + 1] - ry[k]))
                dst[o + x] = if (sx < 0 || sx >= width || sy < sy0 || sy >= sy1) MISSING else src[(sy - sy0) * width + sx]
            }
        }
        strip.place(y0, rows, width, 0, 0)
    }

    private fun stripRows(width: Int, height: Int, intsPerPixel: Int, bands: LocalBands): Int {
        if (stripRowsOverride > 0) return stripRowsOverride.coerceIn(1, height)
        // Band buffers hold (rows + reach) full-width rows per fill worker.
        val bytesPerRow = 4L * width * (intsPerPixel + bands.count)
        val fixed = 4L * width * bands.count * bands.reach
        var rows = ((memoryBudgetBytes - fixed) / bytesPerRow).coerceIn(MIN_STRIP_ROWS.toLong(), MAX_STRIP_ROWS.toLong()).toInt()
        if (rows >= 32) rows -= rows % 16 // whole JPEG MCU rows
        return rows.coerceIn(1, height)
    }

    /** Per-worker source rows for [fillLocal]. */
    private class LocalBand {
        var px = IntArray(0)
        var rx = FloatArray(0)
        var ry = FloatArray(0)
        val bounds = IntArray(2)
    }

    /** Band buffers for layers with offset fields; count = 0 when every layer has a plain shift. */
    private class LocalBands(val count: Int, val workers: Int, val reach: Int) {
        private val bands = Array(count) { LocalBand() }

        fun band(worker: Int): LocalBand = bands[worker]

        companion object {
            fun of(layers: List<Layer>, width: Int, workers: Int, budget: Long): LocalBands {
                val fields = layers.mapNotNull { it.field }
                if (fields.isEmpty()) return LocalBands(0, workers, 0)
                val reach = fields.maxOf { it.dySpread() } + 2
                // Bands get at most a quarter of the budget: fewer parallel reads when the field spreads far.
                val perBand = 4L * width * (reach + MIN_STRIP_ROWS)
                val n = (budget / 4 / perBand).coerceIn(1L, min(workers, MAX_LOCAL_FILL_WORKERS).toLong()).toInt()
                return LocalBands(n, n, reach)
            }
        }
    }

    companion object {
        const val MIN_STRIP_ROWS = 16
        const val MAX_STRIP_ROWS = 256
        const val MAX_LOCAL_FILL_WORKERS = 4
        val MERGE_MODES = setOf(StackMode.MEAN, StackMode.SIGMA_CLIP, StackMode.LUCKY, StackMode.LIGHTEN)
    }
}
