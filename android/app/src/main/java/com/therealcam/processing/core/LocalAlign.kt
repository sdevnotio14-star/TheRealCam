package com.therealcam.processing.core

import java.io.IOException
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class LocalAlignConfig(
    /** Tile pitch at full resolution = long side / this, clamped to [minTile, maxTile]. */
    val tilesPerLongSide: Int = 32,
    val minTile: Int = 64,
    val maxTile: Int = 160,
    /** Matching window as a multiple of the tile pitch. */
    val window: Double = 1.0,
    /** High-pass radius (box blur) at the matching levels. */
    val hpRadius: Int = 3,
    /** Exhaustive search radius around each prediction, in pixels of the matching level. */
    val searchRadius: Int = 2,
    /** Hill-climbing range beyond the exhaustive window. */
    val climb: Int = 3,
    /** Minimum z = ncc * sqrt(samples / 4) for a confident tile. */
    val minZ: Double = 4.0,
    /** A match is ambiguous if a shift 2+ px away scores at least this fraction of the peak (edges, repeats). */
    val ambiguity: Double = 0.9,
    /** Outlier tolerance against the neighbour median, in pixels of the matching level (raised by the neighbours' spread). */
    val outlierTolerance: Double = 2.0,
    /** Rings of neighbour-median fill into unconfident tiles before falling back to the frame's global shift. */
    val fillRings: Int = 3,
    /** Re-match confident tiles at half the analysis downscale (full resolution for small images). */
    val refine: Boolean = true,
    /** With local structure in the series, frames under this fraction of the median confident-tile share fail. */
    val relativeConfidence: Double = 0.4,
    /** Median confident-tile share below which the series has no local structure to judge by. */
    val minStructure: Double = 0.05,
)

/** Regular tiles over the full-resolution image; the offset field lives at their centres. */
internal class TileGrid(val width: Int, val height: Int, val nx: Int, val ny: Int) {
    init {
        require(nx in 1..width && ny in 1..height) { "Bad grid ${nx}x$ny for ${width}x$height" }
    }

    val count: Int get() = nx * ny

    fun centreX(i: Int): Double = (i + 0.5) * width / nx
    fun centreY(j: Int): Double = (j + 0.5) * height / ny

    /** Per pixel column: left tile column of its interpolation segment, and the right column's weight. */
    val colSegment = IntArray(width)
    val colWeight = FloatArray(width)

    init {
        for (x in 0 until width) {
            val k = segment(x + 0.5, nx, width)
            colSegment[x] = k
            colWeight[x] = weight(x + 0.5, nx, width, k).toFloat()
        }
    }

    companion object {
        fun forImage(width: Int, height: Int, cfg: LocalAlignConfig): TileGrid {
            val tile = (max(width, height) / cfg.tilesPerLongSide.coerceAtLeast(1)).coerceIn(cfg.minTile, cfg.maxTile)
            return TileGrid(
                width, height,
                (width.toDouble() / tile).roundToInt().coerceIn(1, width),
                (height.toDouble() / tile).roundToInt().coerceIn(1, height),
            )
        }

        /** Left centre index of the segment holding continuous coordinate p (n centres over [0, size)). */
        fun segment(p: Double, n: Int, size: Int): Int =
            if (n == 1) 0 else floor(p * n / size - 0.5).toInt().coerceIn(0, n - 2)

        /** Weight of the right centre; < 0 or > 1 extrapolates linearly beyond the outer centres. */
        fun weight(p: Double, n: Int, size: Int, k: Int): Double = if (n == 1) 0.0 else p * n / size - 0.5 - k
    }
}

/**
 * Applied shift per tile centre in full-resolution pixels (frame pixel p lands on reference pixel p + d),
 * bilinear between centres and linear beyond the outer ones, so neighbouring tiles join without seams.
 */
internal class OffsetField(val grid: TileGrid, val dx: FloatArray, val dy: FloatArray) {
    init {
        require(dx.size == grid.count && dy.size == grid.count)
    }

    /** Field at a continuous position (pixel x spans [x, x + 1)). */
    fun dxAt(x: Double, y: Double): Double = interpolate(dx, x, y)
    fun dyAt(x: Double, y: Double): Double = interpolate(dy, x, y)

    private fun interpolate(v: FloatArray, x: Double, y: Double): Double {
        val nx = grid.nx
        val kx = TileGrid.segment(x, nx, grid.width)
        val fx = TileGrid.weight(x, nx, grid.width, kx)
        val ky = TileGrid.segment(y, grid.ny, grid.height)
        val fy = TileGrid.weight(y, grid.ny, grid.height, ky)
        val kx1 = min(kx + 1, nx - 1)
        val r0 = ky * nx
        val r1 = min(ky + 1, grid.ny - 1) * nx
        val a = v[r0 + kx] + fx * (v[r0 + kx1] - v[r0 + kx])
        val b = v[r1 + kx] + fx * (v[r1 + kx1] - v[r1 + kx])
        return a + fy * (b - a)
    }

    /** Values at every tile column for row coordinate y, into arrays of nx + 1 (the last repeats). */
    fun rowValues(y: Double, rx: FloatArray, ry: FloatArray) {
        val nx = grid.nx
        val ky = TileGrid.segment(y, grid.ny, grid.height)
        val fy = TileGrid.weight(y, grid.ny, grid.height, ky).toFloat()
        val r0 = ky * nx
        val r1 = min(ky + 1, grid.ny - 1) * nx
        for (k in 0 until nx) {
            rx[k] = dx[r0 + k] + fy * (dx[r1 + k] - dx[r0 + k])
            ry[k] = dy[r0 + k] + fy * (dy[r1 + k] - dy[r0 + k])
        }
        rx[nx] = rx[nx - 1]
        ry[nx] = ry[nx - 1]
    }

    /** out[0] <= rounded dy <= out[1] over output rows [y0, y1), all columns. */
    fun dyBounds(y0: Int, y1: Int, out: IntArray) {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        val top = y0 + 0.5
        val bottom = y1 - 0.5
        // Piecewise bilinear: the extremes lie on the borders or on rows/columns through tile centres.
        for (yi in -1..grid.ny) {
            val y = when (yi) {
                -1 -> top
                grid.ny -> bottom
                else -> grid.centreY(yi)
            }
            if (y < top || y > bottom) continue
            for (xi in -1..grid.nx) {
                val x = when (xi) {
                    -1 -> 0.5
                    grid.nx -> grid.width - 0.5
                    else -> grid.centreX(xi)
                }
                val v = dyAt(x, y)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        out[0] = floor(lo).toInt() - 1
        out[1] = ceil(hi).toInt() + 1
    }

    fun dySpread(): Int {
        val b = IntArray(2)
        dyBounds(0, grid.height, b)
        return b[1] - b[0]
    }

    fun scaled(f: Float) = OffsetField(grid, FloatArray(dx.size) { dx[it] * f }, FloatArray(dy.size) { dy[it] * f })

    /** Rounded shift at the image centre (reports). */
    fun centreDx(): Int = dxAt(grid.width / 2.0, grid.height / 2.0).roundToInt()
    fun centreDy(): Int = dyAt(grid.width / 2.0, grid.height / 2.0).roundToInt()

    companion object {
        fun constant(grid: TileGrid, dx: Float, dy: Float) =
            OffsetField(grid, FloatArray(grid.count) { dx }, FloatArray(grid.count) { dy })
    }
}

/** floor(v + 0.5) without the boxing and NaN handling of roundToInt. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun roundHalfUp(v: Float): Int {
    val t = v + 0.5f
    val i = t.toInt()
    return if (i > t) i - 1 else i
}

/** Per-frame tile post-processing: outlier rejection against neighbours and filling of unmatched tiles. */
internal object TileFilter {
    private const val MIN_NEIGHBOURS = 3

    /**
     * Unmarks confident tiles that differ from the median of their confident 8-neighbours (at least 3) by more
     * than max([minTol], 3 sigma of those neighbours) in x or y. Decisions use the input marks only, so one
     * bad tile cannot knock out its neighbours. Returns the number of rejected tiles.
     */
    fun rejectOutliers(nx: Int, ny: Int, dx: FloatArray, dy: FloatArray, conf: BooleanArray, minTol: Double): Int {
        val drop = BooleanArray(nx * ny)
        val ax = DoubleArray(8)
        val ay = DoubleArray(8)
        val tmp = DoubleArray(8)
        for (j in 0 until ny) for (i in 0 until nx) {
            val t = j * nx + i
            if (!conf[t]) continue
            var m = 0
            for (nj in max(0, j - 1)..min(ny - 1, j + 1)) for (ni in max(0, i - 1)..min(nx - 1, i + 1)) {
                val u = nj * nx + ni
                if (u == t || !conf[u]) continue
                ax[m] = dx[u].toDouble()
                ay[m] = dy[u].toDouble()
                m++
            }
            if (m < MIN_NEIGHBOURS) continue
            if (deviates(dx[t].toDouble(), ax, m, minTol, tmp) || deviates(dy[t].toDouble(), ay, m, minTol, tmp)) drop[t] = true
        }
        var n = 0
        for (t in drop.indices) {
            if (drop[t]) {
                conf[t] = false
                n++
            }
        }
        return n
    }

    /**
     * Gives unconfident tiles the median of their filled 8-neighbours, ring by ring outward from the confident
     * ones (the first ring therefore uses confident tiles only), for at most [rings] rings. Tiles still empty,
     * or all tiles if none is confident, get the fallback shift.
     */
    fun fill(
        nx: Int, ny: Int, dx: FloatArray, dy: FloatArray, conf: BooleanArray, rings: Int,
        fallbackDx: Float, fallbackDy: Float,
    ) {
        val n = nx * ny
        val filled = conf.copyOf()
        if (filled.none { it }) {
            dx.fill(fallbackDx)
            dy.fill(fallbackDy)
            return
        }
        val nextX = FloatArray(n)
        val nextY = FloatArray(n)
        val take = BooleanArray(n)
        val ax = DoubleArray(8)
        val ay = DoubleArray(8)
        val tmp = DoubleArray(8)
        for (ring in 0 until rings) {
            var any = false
            for (j in 0 until ny) for (i in 0 until nx) {
                val t = j * nx + i
                if (filled[t]) continue
                var m = 0
                for (nj in max(0, j - 1)..min(ny - 1, j + 1)) for (ni in max(0, i - 1)..min(nx - 1, i + 1)) {
                    val u = nj * nx + ni
                    if (u == t || !filled[u]) continue
                    ax[m] = dx[u].toDouble()
                    ay[m] = dy[u].toDouble()
                    m++
                }
                if (m == 0) continue
                nextX[t] = median(ax, m, tmp).toFloat()
                nextY[t] = median(ay, m, tmp).toFloat()
                take[t] = true
                any = true
            }
            if (!any) break
            for (t in 0 until n) {
                if (take[t]) {
                    dx[t] = nextX[t]
                    dy[t] = nextY[t]
                    filled[t] = true
                    take[t] = false
                }
            }
        }
        for (t in 0 until n) {
            if (!filled[t]) {
                dx[t] = fallbackDx
                dy[t] = fallbackDy
            }
        }
    }

    private fun deviates(v: Double, a: DoubleArray, m: Int, minTol: Double, tmp: DoubleArray): Boolean {
        val med = median(a, m, tmp)
        for (k in 0 until m) tmp[k] = abs(a[k] - med)
        val mad = median(tmp, m, tmp)
        return abs(v - med) > max(minTol, 3 * MAD_TO_SIGMA * mad)
    }

    /** Median of a[0 until m], sorting a copy in tmp (tmp may be a). */
    private fun median(a: DoubleArray, m: Int, tmp: DoubleArray): Double {
        if (a !== tmp) System.arraycopy(a, 0, tmp, 0, m)
        Arrays.sort(tmp, 0, m)
        return if (m % 2 == 1) tmp[m / 2] else 0.5 * (tmp[m / 2 - 1] + tmp[m / 2])
    }
}

/**
 * Series-level decision: frames whose confident-tile share is far below the median fail. Null if the
 * series has no local structure (nothing to judge against; the global result applies).
 */
internal fun judgeLocal(confidence: DoubleArray, cfg: LocalAlignConfig): BooleanArray? {
    if (confidence.isEmpty()) return null
    val med = RobustStats.median(confidence)
    if (med < cfg.minStructure) return null
    return BooleanArray(confidence.size) { confidence[it] < cfg.relativeConfidence * med }
}

internal class LocalResult(
    /** Per target (input order): offset field relative to the reference. */
    val fields: Array<OffsetField>,
    /** Per target: tiles matched confidently (after the outlier test); unmatched ones were filled. */
    val confident: Array<BooleanArray>,
    /** Per target: share of usable tiles matched confidently at the analysis level. */
    val confidence: DoubleArray,
    /** Per target failure from [judgeLocal]; null = no local structure, keep the global result. */
    val failed: BooleanArray?,
)

/**
 * Tile-wise translation field per frame, for scenes whose parts move differently (stars rotating over a
 * static foreground). Frames are matched in capture order outward from the reference, each predicted from
 * the last well-matched one; tiles are matched on high-passed luminance with the prediction pre-warped in,
 * so field rotation inside a tile does not bias the match. Unreliable tiles are replaced from neighbours.
 * Only an estimate of where pixels are: the merge still moves whole pixels.
 */
internal class LocalAligner(
    private val cfg: LocalAlignConfig = LocalAlignConfig(),
    private val workers: Int = defaultWorkers(),
) {

    /**
     * Aligns [targets] (frame positions) to [ref] using the analysis lumas; [globalDx]/[globalDy] are the
     * global full-resolution shifts per target, [globalOk] whether they were judged reliable.
     * Null if the image is too small for tiles.
     */
    suspend fun align(
        source: FrameSource,
        lumas: Array<Luma8?>,
        ref: Int,
        targets: List<Int>,
        globalDx: IntArray,
        globalDy: IntArray,
        globalOk: BooleanArray,
        onProgress: (Float) -> Unit = {},
    ): LocalResult? {
        val refLuma = lumas[ref] ?: return null
        val m = targets.size
        if (m == 0) return null
        val width = source.width
        val height = source.height
        val grid = TileGrid.forImage(width, height, cfg)
        val margin = cfg.hpRadius + 1
        val sx = width.toDouble() / refLuma.width
        val sy = height.toDouble() / refLuma.height
        val refHp = refLuma.toPlane().highPass(cfg.hpRadius)
        val win = Windows.of(grid, refLuma.width, refLuma.height, sx, sy, margin, cfg.window)
        if (win.usable == 0) return null

        val fields = arrayOfNulls<OffsetField>(m)
        val confident = arrayOfNulls<BooleanArray>(m)
        val confidence = DoubleArray(m)
        val fallbackX = FloatArray(m)
        val fallbackY = FloatArray(m)
        val scratch = Array(workers) { Scratch() }
        val refineFactor = max(1, floor(min(sx, sy) / 2).toInt())
        val refine = cfg.refine && refineFactor < min(sx, sy) - 0.5
        val total = if (refine) 2 * m else m
        val done = AtomicInteger()
        val coarseTol = cfg.outlierTolerance * max(sx, sy)

        val forward = targets.indices.filter { targets[it] > ref }.sortedBy { targets[it] }
        val backward = targets.indices.filter { targets[it] < ref }.sortedByDescending { targets[it] }
        for (chain in listOf(forward, backward)) {
            var last: OffsetField? = null
            var lastPos = ref
            val shares = ArrayList<Double>()
            for (k in chain) {
                val pos = targets[k]
                // Sky motion grows about linearly with time: scale the last good field by the elapsed frames.
                val prior = last?.scaled((pos - ref).toFloat() / (lastPos - ref)) ?: OffsetField.constant(grid, 0f, 0f)
                val hp = lumas[pos]!!.toPlane().highPass(cfg.hpRadius)
                val dx = FloatArray(grid.count)
                val dy = FloatArray(grid.count)
                val conf = BooleanArray(grid.count)
                val gx = globalDx[k].toDouble()
                val gy = globalDy[k].toDouble()
                parallelFor(grid.count, workers) { t, w ->
                    matchCoarse(refHp, hp, win, prior, gx, gy, sx, sy, t, scratch[w], dx, dy, conf)
                }
                TileFilter.rejectOutliers(grid.nx, grid.ny, dx, dy, conf, coarseTol)
                val share = conf.count { it }.toDouble() / win.usable
                if (globalOk[k] || share == 0.0) {
                    fallbackX[k] = gx.toFloat()
                    fallbackY[k] = gy.toFloat()
                } else {
                    fallbackX[k] = medianOf(dx, conf)
                    fallbackY[k] = medianOf(dy, conf)
                }
                TileFilter.fill(grid.nx, grid.ny, dx, dy, conf, cfg.fillRings, fallbackX[k], fallbackY[k])
                val field = OffsetField(grid, dx, dy)
                fields[k] = field
                confident[k] = conf
                confidence[k] = share
                shares += share
                if (share >= 0.5 * RobustStats.median(shares.toDoubleArray())) {
                    last = field
                    lastPos = pos
                }
                onProgress(done.incrementAndGet().toFloat() / total)
            }
        }

        if (refine) {
            refineAll(source, ref, targets, grid, refineFactor, fields, confident, fallbackX, fallbackY, scratch) {
                onProgress(done.incrementAndGet().toFloat() / total)
            }
        }
        return LocalResult(
            Array(m) { fields[it]!! }, Array(m) { confident[it]!! }, confidence, judgeLocal(confidence, cfg),
        )
    }

    private fun matchCoarse(
        refHp: LumaPlane, hp: LumaPlane, win: Windows, prior: OffsetField, gx: Double, gy: Double,
        sx: Double, sy: Double, t: Int, s: Scratch, dx: FloatArray, dy: FloatArray, conf: BooleanArray,
    ) {
        val w = win.ww[t]
        if (w == 0) return
        val h = win.wh[t]
        val wx0 = win.wx0[t]
        val wy0 = win.wy0[t]
        val r = cfg.searchRadius
        val m = r + cfg.climb
        val margin = cfg.hpRadius + 1
        s.ensureWindow(w, h, m, prior.grid.nx)
        val refOff = wy0 * refHp.width + wx0
        val n = (w + 2 * m) * (h + 2 * m)
        val px = prior.dx[t].toDouble()
        val py = prior.dy[t].toDouble()

        sourceCoords(prior, 0.0, 0.0, sx, sy, wx0, wy0, w, h, m, s)
        sample(hp.data, hp.width, 0, 0, margin, margin, hp.width - margin, hp.height - margin, n, s)
        val okA = search(refHp.data, refOff, refHp.width, w, h, s, m, r, s.a)
        val ax = px + s.a.x * sx
        val ay = py + s.a.y * sy

        // The global shift is a second candidate when the prediction's window does not reach it.
        var okB = false
        var bx = 0.0
        var by = 0.0
        if (abs(gx - px) > r * sx || abs(gy - py) > r * sy) {
            sourceCoords(null, gx, gy, sx, sy, wx0, wy0, w, h, m, s)
            sample(hp.data, hp.width, 0, 0, margin, margin, hp.width - margin, hp.height - margin, n, s)
            okB = search(refHp.data, refOff, refHp.width, w, h, s, m, r, s.b)
            bx = gx + s.b.x * sx
            by = gy + s.b.y * sy
        }
        if (!okA && !okB) {
            dx[t] = px.toFloat()
            dy[t] = py.toFloat()
            return
        }
        val useB = okB && (!okA || s.b.ncc > s.a.ncc)
        val best = if (useB) s.b else s.a
        var ok = best.confident(cfg)
        if (okA && okB) {
            val other = if (useB) s.a else s.b
            val apart = abs(ax - bx) >= 2 * sx || abs(ay - by) >= 2 * sy
            if (apart && other.ncc >= cfg.ambiguity * best.ncc) ok = false
        }
        dx[t] = (if (useB) bx else ax).toFloat()
        dy[t] = (if (useB) by else ay).toFloat()
        conf[t] = ok
    }

    /** Re-matches confident tiles at a finer level (frames in parallel, one decode each), then re-filters. */
    private suspend fun refineAll(
        source: FrameSource, ref: Int, targets: List<Int>, grid: TileGrid, factor: Int,
        fields: Array<OffsetField?>, confident: Array<BooleanArray?>, fallbackX: FloatArray, fallbackY: FloatArray,
        scratch: Array<Scratch>, tick: () -> Unit,
    ) {
        val refL = readScaled(source, ref, factor)
        if (refL == null) {
            repeat(targets.size) { tick() }
            return
        }
        val fx = grid.width.toDouble() / refL.width
        val fy = grid.height.toDouble() / refL.height
        val margin = cfg.hpRadius + 1
        val win = Windows.of(grid, refL.width, refL.height, fx, fy, margin, cfg.window)
        val tol = cfg.outlierTolerance * max(fx, fy)
        // Each worker holds one decoded frame at this level.
        parallelFor(targets.size, min(workers, MAX_REFINE_WORKERS)) { k, w ->
            val field = fields[k]!!
            val conf = confident[k]!!
            val frameL = readScaled(source, targets[k], factor)
            if (frameL != null && frameL.width == refL.width && frameL.height == refL.height) {
                val s = scratch[w]
                val dx = field.dx.copyOf()
                val dy = field.dy.copyOf()
                for (t in 0 until grid.count) {
                    if (!conf[t] || !refineTile(refL, frameL, field, win, t, fx, fy, s)) continue
                    dx[t] = (field.dx[t] + s.a.x * fx).toFloat()
                    dy[t] = (field.dy[t] + s.a.y * fy).toFloat()
                }
                val c = conf.copyOf()
                TileFilter.rejectOutliers(grid.nx, grid.ny, dx, dy, c, tol)
                TileFilter.fill(grid.nx, grid.ny, dx, dy, c, cfg.fillRings, fallbackX[k], fallbackY[k])
                fields[k] = OffsetField(grid, dx, dy)
            }
            tick()
        }
    }

    private fun refineTile(
        refL: Luma8, frameL: Luma8, field: OffsetField, win: Windows, t: Int, fx: Double, fy: Double, s: Scratch,
    ): Boolean {
        val w = win.ww[t]
        if (w == 0) return false
        val h = win.wh[t]
        val wx0 = win.wx0[t]
        val wy0 = win.wy0[t]
        val r = cfg.searchRadius
        val m = r + cfg.climb
        val hr = cfg.hpRadius
        val margin = hr + 1
        s.ensureWindow(w, h, m, field.grid.nx)
        s.ensurePatch(0, (h + 2 * hr) * w)
        hpPatch(refL, wx0, wy0, wx0 + w, wy0 + h, hr, s.ref, s.tmp)
        sourceCoords(field, 0.0, 0.0, fx, fy, wx0, wy0, w, h, m, s)
        val bx0 = max(margin, s.box[0])
        val by0 = max(margin, s.box[1])
        val bx1 = min(frameL.width - margin, s.box[2])
        val by1 = min(frameL.height - margin, s.box[3])
        if (bx1 - bx0 < 2 || by1 - by0 < 2) return false
        val pw = bx1 - bx0
        val ph = by1 - by0
        s.ensurePatch(pw * ph, (ph + 2 * hr) * pw)
        hpPatch(frameL, bx0, by0, bx1, by1, hr, s.patch, s.tmp)
        sample(s.patch, pw, bx0, by0, bx0, by0, bx1, by1, (w + 2 * m) * (h + 2 * m), s)
        return search(s.ref, 0, w, w, h, s, m, r, s.a) && s.a.confident(cfg)
    }

    /**
     * Source positions (level pixel indices) of the window [wx0 - m, wx0 + w + m) x [wy0 - m, wy0 + h + m)
     * under [field] (or the constant shift if null): u - d(u, v) / scale, into s.su/s.sv, with their
     * bounding box in s.box (x0, y0, x1, y1; exclusive ends include the bilinear neighbour).
     */
    private fun sourceCoords(
        field: OffsetField?, cdx: Double, cdy: Double, scx: Double, scy: Double,
        wx0: Int, wy0: Int, w: Int, h: Int, m: Int, s: Scratch,
    ) {
        val gw = w + 2 * m
        val gh = h + 2 * m
        val gx0 = wx0 - m
        val gy0 = wy0 - m
        if (field != null) {
            val grid = field.grid
            for (c in 0 until gw) {
                val x = (gx0 + c + 0.5) * scx
                val k = TileGrid.segment(x, grid.nx, grid.width)
                s.colK[c] = k
                s.colW[c] = TileGrid.weight(x, grid.nx, grid.width, k).toFloat()
            }
        }
        val isx = (1.0 / scx).toFloat()
        val isy = (1.0 / scy).toFloat()
        val cx = cdx.toFloat()
        val cy = cdy.toFloat()
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        val rx = s.rx
        val ry = s.ry
        var i = 0
        for (r in 0 until gh) {
            val v = gy0 + r
            if (field != null) field.rowValues((v + 0.5) * scy, rx, ry)
            for (c in 0 until gw) {
                var ddx = cx
                var ddy = cy
                if (field != null) {
                    val k = s.colK[c]
                    val f = s.colW[c]
                    ddx = rx[k] + f * (rx[k + 1] - rx[k])
                    ddy = ry[k] + f * (ry[k + 1] - ry[k])
                }
                val u = (gx0 + c) - ddx * isx
                val q = v - ddy * isy
                s.su[i] = u
                s.sv[i] = q
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (q < minV) minV = q
                if (q > maxV) maxV = q
                i++
            }
        }
        s.box[0] = floor(minU).toInt()
        s.box[1] = floor(minV).toInt()
        s.box[2] = floor(maxU).toInt() + 2
        s.box[3] = floor(maxV).toInt() + 2
    }

    /** Bilinear samples at s.su/s.sv from a plane with origin (ox, oy); outside [vx0, vx1) x [vy0, vy1) = invalid. */
    private fun sample(
        data: FloatArray, stride: Int, ox: Int, oy: Int, vx0: Int, vy0: Int, vx1: Int, vy1: Int, n: Int, s: Scratch,
    ) {
        val g = s.g
        val valid = s.valid
        for (i in 0 until n) {
            val u = s.su[i]
            val v = s.sv[i]
            val x0 = floor(u).toInt()
            val y0 = floor(v).toInt()
            if (x0 < vx0 || x0 + 1 >= vx1 || y0 < vy0 || y0 + 1 >= vy1) {
                g[i] = 0f
                valid[i] = 0f
                continue
            }
            val fx = u - x0
            val fy = v - y0
            val b = (y0 - oy) * stride + (x0 - ox)
            val top = data[b] + fx * (data[b + 1] - data[b])
            val bottom = data[b + stride] + fx * (data[b + stride + 1] - data[b + stride])
            g[i] = top + fy * (bottom - top)
            valid[i] = 1f
        }
    }

    /**
     * Residual shift of the warped window s.g (side w + 2m) against the reference window: exhaustive within
     * ±r, then hill-climbing up to ±m. False if no shift had a valid overlap.
     */
    private fun search(
        ref: FloatArray, refOff: Int, refStride: Int, w: Int, h: Int, s: Scratch, m: Int, r: Int, out: Match,
    ): Boolean {
        val side = 2 * m + 1
        val map = s.map
        val nMap = s.nMap
        Arrays.fill(map, 0, side * side, Double.NaN)
        var best = Ncc.INVALID
        var bx = 0
        var by = 0
        for (ry in -r..r) for (rx in -r..r) {
            val v = score(ref, refOff, refStride, w, h, s, m, rx, ry)
            val idx = (ry + m) * side + rx + m
            map[idx] = v
            nMap[idx] = s.lastN
            if (v > best) {
                best = v
                bx = rx
                by = ry
            }
        }
        out.valid = false
        if (best <= Ncc.INVALID) return false
        while (true) {
            var nb = best
            var nbx = bx
            var nby = by
            for (ddy in -1..1) for (ddx in -1..1) {
                val x = bx + ddx
                val y = by + ddy
                if (x < -m || x > m || y < -m || y > m) continue
                val idx = (y + m) * side + x + m
                var v = map[idx]
                if (v.isNaN()) {
                    v = score(ref, refOff, refStride, w, h, s, m, x, y)
                    map[idx] = v
                    nMap[idx] = s.lastN
                }
                if (v > nb) {
                    nb = v
                    nbx = x
                    nby = y
                }
            }
            if (nbx == bx && nby == by) break
            best = nb
            bx = nbx
            by = nby
        }
        var second = Ncc.INVALID
        for (y in -m..m) for (x in -m..m) {
            if (max(abs(x - bx), abs(y - by)) < 2) continue
            val v = map[(y + m) * side + x + m]
            if (!v.isNaN() && v > second) second = v
        }
        out.x = bx + parabolic(mapAt(map, side, m, bx - 1, by), best, mapAt(map, side, m, bx + 1, by))
        out.y = by + parabolic(mapAt(map, side, m, bx, by - 1), best, mapAt(map, side, m, bx, by + 1))
        out.ncc = best
        out.z = best * sqrt(nMap[(by + m) * side + bx + m] / 4.0)
        out.onEdge = abs(bx) == m || abs(by) == m
        out.ambiguous = best <= 0.0 || second >= cfg.ambiguity * best
        out.valid = true
        return true
    }

    private fun mapAt(map: DoubleArray, side: Int, m: Int, x: Int, y: Int): Double =
        if (x < -m || x > m || y < -m || y > m) Double.NaN else map[(y + m) * side + x + m]

    /** NCC of the reference window with the warped window displaced by (rx, ry); stores the valid count. */
    private fun score(ref: FloatArray, refOff: Int, refStride: Int, w: Int, h: Int, s: Scratch, m: Int, rx: Int, ry: Int): Double {
        val gw = w + 2 * m
        val g = s.g
        val valid = s.valid
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        var n = 0.0
        for (y in 0 until h) {
            var ri = refOff + y * refStride
            var gi = (y + m - ry) * gw + (m - rx)
            var sab = 0f
            var saa = 0f
            var sbb = 0f
            var sn = 0f
            for (x in 0 until w) {
                val a = ref[ri]
                val b = g[gi]
                val q = valid[gi]
                sab += a * b
                saa += a * a * q
                sbb += b * b
                sn += q
                ri++
                gi++
            }
            ab += sab
            aa += saa
            bb += sbb
            n += sn
        }
        s.lastN = n
        if (n < 0.5 * w * h) return Ncc.INVALID
        if (aa <= 0.0 || bb <= 0.0) return 0.0
        return ab / sqrt(aa * bb)
    }

    private fun parabolic(fm: Double, f0: Double, fp: Double): Double {
        if (fm.isNaN() || fp.isNaN() || fm <= Ncc.INVALID || fp <= Ncc.INVALID) return 0.0
        val den = fm - 2 * f0 + fp
        if (den >= 0.0) return 0.0
        return (0.5 * (fm - fp) / den).coerceIn(-0.5, 0.5)
    }

    private fun readScaled(source: FrameSource, position: Int, factor: Int): Luma8? =
        try {
            source.scaledLuma(position, factor)
        } catch (e: IOException) {
            null
        }

    private fun medianOf(v: FloatArray, mask: BooleanArray): Float {
        val a = v.indices.filter { mask[it] }.map { v[it].toDouble() }.toDoubleArray()
        return if (a.isEmpty()) 0f else RobustStats.median(a).toFloat()
    }

    /** Matching window per tile at one level (ww = 0: tile unusable). */
    private class Windows(val wx0: IntArray, val wy0: IntArray, val ww: IntArray, val wh: IntArray) {
        val usable: Int = ww.count { it > 0 }

        companion object {
            fun of(grid: TileGrid, lw: Int, lh: Int, scx: Double, scy: Double, margin: Int, window: Double): Windows {
                val n = grid.count
                val wx0 = IntArray(n)
                val wy0 = IntArray(n)
                val ww = IntArray(n)
                val wh = IntArray(n)
                val halfX = window * grid.width / grid.nx / 2 / scx
                val halfY = window * grid.height / grid.ny / 2 / scy
                for (j in 0 until grid.ny) for (i in 0 until grid.nx) {
                    val t = j * grid.nx + i
                    val cx = grid.centreX(i) / scx
                    val cy = grid.centreY(j) / scy
                    val x0 = max(margin, (cx - halfX).roundToInt())
                    val x1 = min(lw - margin, (cx + halfX).roundToInt())
                    val y0 = max(margin, (cy - halfY).roundToInt())
                    val y1 = min(lh - margin, (cy + halfY).roundToInt())
                    if (x1 - x0 < MIN_WINDOW || y1 - y0 < MIN_WINDOW) continue
                    wx0[t] = x0
                    wy0[t] = y0
                    ww[t] = x1 - x0
                    wh[t] = y1 - y0
                }
                return Windows(wx0, wy0, ww, wh)
            }
        }
    }

    private class Match {
        var x = 0.0
        var y = 0.0
        var ncc = 0.0
        var z = 0.0
        var onEdge = false
        var ambiguous = false
        var valid = false

        fun confident(cfg: LocalAlignConfig) = valid && z >= cfg.minZ && !onEdge && !ambiguous
    }

    /** Per-worker buffers; grown on demand, so the tile loops allocate nothing. */
    private class Scratch {
        var ref = FloatArray(0)
        var g = FloatArray(0)
        var valid = FloatArray(0)
        var su = FloatArray(0)
        var sv = FloatArray(0)
        var patch = FloatArray(0)
        var tmp = FloatArray(0)
        var map = DoubleArray(0)
        var nMap = DoubleArray(0)
        var colK = IntArray(0)
        var colW = FloatArray(0)
        var rx = FloatArray(0)
        var ry = FloatArray(0)
        val box = IntArray(4)
        val a = Match()
        val b = Match()
        var lastN = 0.0

        fun ensureWindow(w: Int, h: Int, m: Int, nx: Int) {
            val gn = (w + 2 * m) * (h + 2 * m)
            if (g.size < gn) {
                g = FloatArray(gn)
                valid = FloatArray(gn)
                su = FloatArray(gn)
                sv = FloatArray(gn)
            }
            if (ref.size < w * h) ref = FloatArray(w * h)
            val side = 2 * m + 1
            if (map.size < side * side) {
                map = DoubleArray(side * side)
                nMap = DoubleArray(side * side)
            }
            if (colK.size < w + 2 * m) {
                colK = IntArray(w + 2 * m)
                colW = FloatArray(w + 2 * m)
            }
            if (rx.size < nx + 1) {
                rx = FloatArray(nx + 1)
                ry = FloatArray(nx + 1)
            }
        }

        fun ensurePatch(n: Int, t: Int) {
            if (patch.size < n) patch = FloatArray(n)
            if (tmp.size < t) tmp = FloatArray(t)
        }
    }

    companion object {
        /** Smallest usable window side at a matching level. */
        const val MIN_WINDOW = 8
        const val MAX_REFINE_WORKERS = 4

        /**
         * High-pass (luma minus its box mean of radius r, edges clamped like [LumaPlane.boxBlur]) of the
         * region [x0, x1) x [y0, y1) into out (stride x1 - x0); tmp needs (y1 - y0 + 2r) * (x1 - x0).
         */
        fun hpPatch(l: Luma8, x0: Int, y0: Int, x1: Int, y1: Int, r: Int, out: FloatArray, tmp: FloatArray) {
            val w = x1 - x0
            val h = y1 - y0
            val lw = l.width
            val lh = l.height
            val d = l.data
            val norm = 1f / (2 * r + 1)
            for (tr in 0 until h + 2 * r) {
                val row = (y0 - r + tr).coerceIn(0, lh - 1) * lw
                var acc = 0
                for (k in -r..r) acc += d[row + (x0 + k).coerceIn(0, lw - 1)].toInt() and 0xFF
                val o = tr * w
                for (x in 0 until w) {
                    tmp[o + x] = acc * norm
                    acc += (d[row + (x0 + x + r + 1).coerceIn(0, lw - 1)].toInt() and 0xFF) -
                        (d[row + (x0 + x - r).coerceIn(0, lw - 1)].toInt() and 0xFF)
                }
            }
            for (x in 0 until w) {
                var acc = 0f
                for (k in 0 until 2 * r + 1) acc += tmp[k * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = (d[(y0 + y) * lw + x0 + x].toInt() and 0xFF) - acc * norm
                    if (y + 1 < h) acc += tmp[(y + 2 * r + 1) * w + x] - tmp[y * w + x]
                }
            }
        }
    }
}
