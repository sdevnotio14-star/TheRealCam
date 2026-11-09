package com.therealcam.processing

import com.therealcam.data.StackMode
import com.therealcam.processing.Synth.gray
import com.therealcam.processing.core.PlannedOutput
import com.therealcam.processing.core.StackEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Temporary diagnostics; deleted before hand-off.
class LocalAlignDebugTest {
    private fun run(deg: Double, n: Int, local: Boolean): String = runBlocking {
        val w = 512
        val h = 384
        val horizon = h * 2 / 3
        val seed = 77L
        val r0 = Random(seed)
        val poleX = 0.35 * w
        val poleY = -2.0 * h
        val starsBase = List(w * (horizon + 260) / 1200) {
            Blob(-130 + r0.nextDouble() * (w + 260), -130 + r0.nextDouble() * (horizon + 130), 70.0 + r0.nextDouble() * 130.0, 1.0 + r0.nextDouble() * 0.6)
        }
        val jitter = List(n) { (r0.nextInt(5) - 2) to (r0.nextInt(5) - 2) }
        val ground = Synth.texture(seed + 1, count = w * h / 800, w = w, h = h)
        fun starsAt(k: Int): List<Blob> {
            val a = Math.toRadians(deg * (k - (n - 1) / 2.0))
            val c = cos(a)
            val s = sin(a)
            val (jx, jy) = jitter[k]
            return starsBase.map { b -> val x = b.x - poleX; val y = b.y - poleY; Blob(poleX + c * x - s * y + jx, poleY + s * x + c * y + jy, b.amp, b.sigma) }
        }
        fun render(k: Int, noise: Double, rnd: Random?): DoubleArray {
            val (jx, jy) = jitter[k]
            val sky = Synth.render(w, h, starsAt(k), 20.0)
            val fg = Synth.render(w, h, ground, 70.0, jx, jy)
            val r = rnd ?: Random(0)
            return DoubleArray(w * h) { i -> val v = if (i / w - jy >= horizon) fg[i] else sky[i]; if (noise > 0) v + r.nextGaussian() * noise else v }
        }
        val rnd = Random(4)
        val frames = List(n) { k -> Synth.toArgb(render(k, 5.0, rnd)) }
        val engine = StackEngine()
        val t0 = System.nanoTime()
        val plan = engine.plan(frames.indices.map { Synth.frame(it) }, StackOptions(StackMode.SIGMA_CLIP, true, true, localAlign = local), MemoryFrameSource(frames, w, h))
        val ref = plan.reference
        val out = IntArray(w * h)
        engine.merge(w, h, StackMode.SIGMA_CLIP, (plan.output as PlannedOutput.Merge).layers, MemoryFrameSource(frames, w, h), 2.5f, { y0, rows, px -> System.arraycopy(px, 0, out, y0 * w, rows * w) })
        val ms = (System.nanoTime() - t0) / 1_000_000
        val clean = Synth.toArgb(render(ref, 0.0, null))
        fun stars(y0: Int, y1: Int): String {
            var so = 0.0; var sc = 0.0; var c = 0
            for (b in starsAt(ref)) {
                val x = b.x.roundToInt(); val y = b.y.roundToInt()
                if (x !in 12 until w - 12 || y !in y0 until y1) continue
                val cv = gray(clean[y * w + x]) - 20
                if (cv < 40) continue
                so += gray(out[y * w + x]) - 20; sc += cv; c++
            }
            return "%.2f(n=%d)".format(so / sc, c)
        }
        fun rms(y0: Int, y1: Int): String {
            var s2 = 0.0; var c = 0
            for (y in y0 until y1) for (x in 8 until w - 8) { val d = (gray(out[y * w + x]) - gray(clean[y * w + x])).toDouble(); s2 += d * d; c++ }
            return "%.1f".format(sqrt(s2 / c))
        }
        val errs = StringBuilder()
        if (local && deg > 0.4) {
            // Direct aligner run to see per-tile confidence (coarse + refine) for frame 0.
            val src = MemoryFrameSource(frames, w, h)
            val lumas = Array<com.therealcam.processing.core.Luma8?>(n) { src.analysisLuma(it) }
            val targets = (0 until n).filter { it != ref }
            for (refine in listOf(false, true)) {
                val res = com.therealcam.processing.core.LocalAligner(com.therealcam.processing.core.LocalAlignConfig(refine = refine), 2)
                    .align(src, lumas, ref, targets, IntArray(targets.size), IntArray(targets.size), BooleanArray(targets.size) { true })!!
                val k = targets.indexOf(0)
                val f = res.fields[k]
                val g = f.grid
                val a = Math.toRadians(deg * (0 - (n - 1) / 2.0))
                val ar = Math.toRadians(deg * (ref - (n - 1) / 2.0))
                errs.append("\n   direct refine=$refine F0 (signed ex,ey; * = confident):")
                for (j in 0 until 4) {
                    errs.append("\n    ")
                    for (i in 0 until g.nx) {
                        val t = j * g.nx + i
                        val bx0 = g.centreX(i) - jitter[ref].first - poleX
                        val by0 = g.centreY(j) - jitter[ref].second - poleY
                        val bx = cos(-ar) * bx0 - sin(-ar) * by0
                        val by = sin(-ar) * bx0 + cos(-ar) * by0
                        val kx = poleX + cos(a) * bx - sin(a) * by + jitter[0].first
                        val ky = poleY + sin(a) * bx + cos(a) * by + jitter[0].second
                        errs.append("%5.1f,%5.1f%s".format(f.dx[t] - (g.centreX(i) - kx), f.dy[t] - (g.centreY(j) - ky), if (res.confident[k][t]) "*" else " "))
                    }
                }
            }
        }
        if (local) {
            val refStars = starsAt(ref)
            for (l in (plan.output as PlannedOutput.Merge).layers.drop(1)) {
                val f = l.field!!
                val ks = starsAt(l.position)
                val e = ArrayList<Double>()
                for (si in refStars.indices) {
                    val p = refStars[si]
                    if (p.x !in 12.0..(w - 12.0) || p.y !in 12.0..(horizon - 48.0)) continue
                    val tx = p.x - ks[si].x
                    val ty = p.y - ks[si].y
                    val fx = f.dxAt(p.x + 0.5, p.y + 0.5)
                    val fy = f.dyAt(p.x + 0.5, p.y + 0.5)
                    e += maxOf(abs(fx - tx), abs(fy - ty))
                }
                e.sort()
                errs.append(" F${l.position}:med=%.2f,p90=%.2f,max=%.1f".format(e[e.size / 2], e[e.size * 9 / 10], e.last()))
                if (deg > 0.4 && l.position in listOf(0, 11, 12, 13)) {
                    // Tile map: true sky motion at each tile centre vs field; '*' = confident.
                    val g = f.grid
                    val a = Math.toRadians(deg * (l.position - (n - 1) / 2.0))
                    val ar = Math.toRadians(deg * (ref - (n - 1) / 2.0))
                    errs.append("\n   map F${l.position} (err per tile, rows 0..3):")
                    for (j in 0 until 4) {
                        errs.append("\n    ")
                        for (i in 0 until g.nx) {
                            val t = j * g.nx + i
                            val cx = g.centreX(i)
                            val cy = g.centreY(j)
                            // Invert the reference rotation to get the base point, then rotate to frame k.
                            val bx0 = cx - jitter[ref].first - poleX
                            val by0 = cy - jitter[ref].second - poleY
                            val bx = cos(-ar) * bx0 - sin(-ar) * by0
                            val by = sin(-ar) * bx0 + cos(-ar) * by0
                            val kx = poleX + cos(a) * bx - sin(a) * by + jitter[l.position].first
                            val ky = poleY + sin(a) * bx + cos(a) * by + jitter[l.position].second
                            val ex = f.dx[t] - (cx - kx)
                            val ey = f.dy[t] - (cy - ky)
                            errs.append("%5.1f%s".format(maxOf(abs(ex), abs(ey)), if (plan.reports[l.position].alignConfidence != null && true) "" else ""))
                        }
                    }
                }
            }
        }
        val acc = plan.reports.count { it.accepted }
        "deg=$deg n=$n local=$local acc=$acc ms=$ms stars[sky]=${stars(12, horizon - 48)} stars[band]=${stars(horizon - 48, horizon - 4)} " +
            "ground=${rms(horizon + 48, h - 8)} bandGround=${rms(horizon + 4, horizon + 48)}\n  errs:$errs"
    }

    @Test
    fun sweep() {
        for ((deg, n) in listOf(0.2 to 16, 0.5 to 16)) println(run(deg, n, true))
    }
}
