package com.therealcam.processing

import com.therealcam.data.StackMode
import com.therealcam.processing.Synth.gray
import com.therealcam.processing.core.PlannedOutput
import com.therealcam.processing.core.StackEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Temporary diagnostics; deleted before hand-off.
class LocalAlignDebugTest {
    @Test
    fun dumpNightFields() = runBlocking {
        val w = 512
        val h = 384
        val n = 16
        val horizon = h * 2 / 3
        val seed = 77L
        val r0 = Random(seed)
        val poleX = 0.35 * w
        val poleY = -2.0 * h
        val starsBase = List(w * (horizon + 160) / 1200) {
            Blob(-80 + r0.nextDouble() * (w + 160), -80 + r0.nextDouble() * (horizon + 80), 70.0 + r0.nextDouble() * 130.0, 1.0 + r0.nextDouble() * 0.6)
        }
        val jitter = List(n) { (r0.nextInt(5) - 2) to (r0.nextInt(5) - 2) }
        val ground = Synth.texture(seed + 1, count = w * h / 800, w = w, h = h)
        fun starsAt(k: Int): List<Blob> {
            val a = Math.toRadians(0.2 * (k - (n - 1) / 2.0))
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
        val plan = engine.plan(frames.indices.map { Synth.frame(it) }, StackOptions(StackMode.SIGMA_CLIP, true, true, localAlign = true), MemoryFrameSource(frames, w, h))
        val ref = plan.reference
        println("ref=$ref jitter=$jitter")
        val layers = (plan.output as PlannedOutput.Merge).layers
        for (l in layers.drop(1)) {
            val f = l.field!!
            val g = f.grid
            val tx = jitter[ref].first - jitter[l.position].first
            val ty = jitter[ref].second - jitter[l.position].second
            val sb = StringBuilder("F${l.position} t=$tx,$ty:")
            for (j in 4 until g.ny) {
                var worst = 0.0
                var wi = -1
                for (i in 0 until g.nx) {
                    val t = j * g.nx + i
                    val e = maxOf(Math.abs(f.dx[t] - tx), Math.abs(f.dy[t] - ty)).toDouble()
                    if (e > worst) { worst = e; wi = i }
                }
                sb.append(" row$j max=${"%.2f".format(worst)}@$wi(${"%.2f".format(f.dx[j * g.nx + wi])},${"%.2f".format(f.dy[j * g.nx + wi])})")
            }
            println(sb)
        }
        // Per-frame ground error after warping each frame alone with its field (vs clean reference).
        val clean = Synth.toArgb(render(ref, 0.0, null))
        val out = IntArray(w * h)
        engine.merge(w, h, StackMode.SIGMA_CLIP, layers, MemoryFrameSource(frames, w, h), 2.5f, { y0, rows, px -> System.arraycopy(px, 0, out, y0 * w, rows * w) })
        val rowErr = StringBuilder("row rms: ")
        for (y in horizon + 8 until h step 8) {
            var s2 = 0.0
            var c = 0
            for (x in 8 until w - 8) { val d = (gray(out[y * w + x]) - gray(clean[y * w + x])).toDouble(); s2 += d * d; c++ }
            rowErr.append("$y:${"%.1f".format(sqrt(s2 / c))} ")
        }
        println(rowErr)
        val colErr = StringBuilder("col rms (y 304..376): ")
        for (x0 in 0 until w step 64) {
            var s2 = 0.0
            var c = 0
            for (y in 304 until 376) for (x in x0 until x0 + 64) { val d = (gray(out[y * w + x]) - gray(clean[y * w + x])).toDouble(); s2 += d * d; c++ }
            colErr.append("$x0:${"%.1f".format(sqrt(s2 / c))} ")
        }
        println(colErr)
    }
}
