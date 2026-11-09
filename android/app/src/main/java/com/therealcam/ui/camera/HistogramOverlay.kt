package com.therealcam.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.therealcam.ai.SceneFeatures
import com.therealcam.ui.theme.RealColors

/** Luma histogram of a preview frame; the last value is the clipped share (pixels at ≥ 98 %). */
object Histogram {
    const val BINS = 64

    fun of(px: IntArray): FloatArray {
        val h = FloatArray(BINS + 1)
        if (px.isEmpty()) return h
        var clipped = 0
        for (p in px) {
            val l = SceneFeatures.luma(p)
            h[(l * BINS).toInt().coerceIn(0, BINS - 1)] += 1f
            if (l >= 0.98f) clipped++
        }
        val max = (0 until BINS).maxOf { h[it] }.coerceAtLeast(1f)
        for (i in 0 until BINS) h[i] /= max
        h[BINS] = clipped.toFloat() / px.size
        return h
    }
}

/** Small histogram over the preview (manual mode with pro mode on). Red edge: blown highlights. */
@Composable
fun HistogramOverlay(h: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 120.dp, height = 52.dp).clip(RoundedCornerShape(8.dp)).background(RealColors.Scrim).padding(4.dp)) {
        val bins = Histogram.BINS
        val w = size.width / bins
        for (i in 0 until bins) {
            val bh = h[i].coerceIn(0f, 1f) * size.height
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * w, size.height - bh), Size(w, bh))
        }
        if (h[bins] > 0.005f) {
            drawRect(RealColors.Bad, Offset(size.width - w * 2, 0f), Size(w * 2, size.height))
        }
    }
}
