package com.therealcam.processing

import com.therealcam.data.StackMode
import com.therealcam.processing.Synth.gray
import com.therealcam.processing.core.LocalAlignConfig
import com.therealcam.processing.core.PlannedOutput
import com.therealcam.processing.core.StackEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Temporary diagnostics; deleted before hand-off.
class LocalAlignDebugTest {
    private fun run(w: Int, h: Int, factor: Int, deg: Double, n: Int, local: Boolean, cfg: LocalAlignConfig): String = runBlocking {
        val horizon = h * 2 / 3
        val r0 = Random(9)
        val poleX = 0.35 * w
        val poleY = -2.0 * h
        val pad = (w * 0.1).toInt()
        val starsBase = List(w * (horizon + 2 * pad) / 2500) {
            Blob(-pad + r0.nextDouble() * (w + 2 * pad), -pad + r0.nextDouble() * (horizon + pad), 60.0 + r0.nextDouble() * 140.0, 1.4 + r0.nextDouble() * 1.0)
        }
        val jitter = List(n) { (r0.nextInt(5) - 2) to (r0.nextInt(5) - 2) }
        val ground = Synth.texture(10, count = w * h / 800, w = w, h = h)
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
        val engine = StackEngine(localConfig = cfg)
        val t0 = System.nanoTime()
        val plan = engine.plan(frames.indices.map { Synth.frame(it) }, StackOptions(StackMode.SIGMA_CLIP, true, true, localAlign = local), MemoryFrameSource(frames, w, h, factor))
        val t1 = System.nanoTime()
        val ref = plan.reference
        val out = IntArray(w * h)
        engine.merge(w, h, StackMode.SIGMA_CLIP, (plan.output as PlannedOutput.Merge).layers, MemoryFrameSource(frames, w, h, factor), 2.5f, { y0, rows, px -> System.arraycopy(px, 0, out, y0 * w, rows * w) })
        val t2 = System.nanoTime()
        val clean = Synth.toArgb(render(ref, 0.0, null))
        var so = 0.0; var sc = 0.0; var c = 0
        val band = h / 8
        for (b in starsAt(ref)) {
            val x = b.x.roundToInt(); val y = b.y.roundToInt()
            if (x !in 12 until w - 12 || y !in 12 until horizon - band) continue
            val cv = gray(clean[y * w + x]) - 20
            if (cv < 40) continue
            so += gray(out[y * w + x]) - 20; sc += cv; c++
        }
        var s2 = 0.0; var cnt = 0
        for (y in horizon + band until h - 8) for (x in 8 until w - 8) { val d = (gray(out[y * w + x]) - gray(clean[y * w + x])).toDouble(); s2 += d * d; cnt++ }
        val conf = plan.reports.mapNotNull { it.alignConfidence }
        "${w}x$h f=$factor deg=$deg n=$n local=$local plan=${(t1 - t0) / 1_000_000}ms merge=${(t2 - t1) / 1_000_000}ms stars=%.2f(n=$c) ground=%.2f conf=%.2f..%.2f acc=${plan.reports.count { it.accepted }}"
            .format(so / sc, sqrt(s2 / cnt), conf.minOrNull() ?: 0.0, conf.maxOrNull() ?: 0.0)
    }

    @Test
    fun phoneLike() {
        val cfg = LocalAlignConfig(tilesPerLongSide = 8)
        println(run(1024, 768, 4, 0.25, 10, true, cfg))
        println(run(1024, 768, 4, 0.25, 10, false, cfg))
    }

    @Test
    fun bumpedFrame() = runBlocking {
        val w = 512
        val h = 384
        val horizon = h * 2 / 3
        val r0 = Random(77)
        val stars = List(150) { Blob(-80 + r0.nextDouble() * (w + 160), -80 + r0.nextDouble() * (horizon + 80), 70.0 + r0.nextDouble() * 130.0, 1.0 + r0.nextDouble() * 0.6) }
        val ground = Synth.texture(78, count = w * h / 800, w = w, h = h)
        val n = 8
        val rnd = Random(4)
        val frames = List(n) { k ->
            val sky = Synth.render(w, h, stars, 20.0, 2 * k, 0)
            val fg = Synth.render(w, h, ground, 70.0)
            var c = DoubleArray(w * h) { i -> (if (i / w >= horizon) fg[i] else sky[i]) + rnd.nextGaussian() * 5.0 }
            if (k == 2) {
                // Bumped during the exposure: smeared along a diagonal.
                val out = DoubleArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    var s = 0.0
                    for (d in -12..12) s += c[(y + d / 2).coerceIn(0, h - 1) * w + (x + d).coerceIn(0, w - 1)]
                    out[y * w + x] = s / 25
                }
                c = out
            }
            Synth.toArgb(c)
        }
        for (local in listOf(true, false)) {
            val plan = StackEngine().plan(frames.indices.map { Synth.frame(it) }, StackOptions(StackMode.SIGMA_CLIP, true, true, localAlign = local), MemoryFrameSource(frames, w, h))
            println("local=$local ref=${plan.reference} " + plan.reports.joinToString { "${it.index}:${it.rejectReason ?: "ok"}/${it.alignConfidence?.let { c -> "%.2f".format(c) }}" })
        }
    }
}
