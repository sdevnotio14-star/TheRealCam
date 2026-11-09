package com.therealcam.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealcam.camera.Lens
import com.therealcam.camera.LensKind
import com.therealcam.camera.ZoomMath
import com.therealcam.ui.theme.RealColors
import kotlinx.coroutines.delay

/**
 * Lens chips over the bottom of the preview: "0.6  1×  2  ✿". Lenses switch the camera module, a plain
 * number is digital zoom on the main lens. The selected chip shows the current zoom.
 */
@Composable
fun ZoomBar(
    lenses: List<Lens>,
    selected: Lens?,
    zoom: Float,
    rot: Float,
    onLens: (Lens) -> Unit,
    modifier: Modifier = Modifier,
    onZoom: (Float) -> Unit = {},
) {
    if (lenses.isEmpty()) return
    val stops = remember(lenses) {
        val optical = lenses.filter { it.kind != LensKind.MACRO }.map { Stop(it.zoom, it) }
        val digital = ZoomMath.digitalStops(lenses).map { Stop(it, null) }
        (optical + digital).sortedBy { it.zoom } + lenses.filter { it.kind == LensKind.MACRO }.map { Stop(it.zoom, it) }
    }
    val current = if (selected?.kind == LensKind.MACRO) stops.firstOrNull { it.lens == selected }
    else stops.filter { it.lens?.kind != LensKind.MACRO && it.zoom <= zoom * 1.001f }.maxByOrNull { it.zoom }
        ?: stops.firstOrNull { it.lens?.kind != LensKind.MACRO }
    var caption by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf(true) }
    // Name the lens for a moment after it changes, so chips need no permanent text.
    LaunchedEffect(selected?.kind, selected?.cameraId) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        caption = selected?.kind?.title ?: return@LaunchedEffect
        delay(1600)
        caption = null
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        caption?.let {
            Text(
                it, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.rotate(rot).clip(RoundedCornerShape(8.dp)).background(RealColors.Scrim)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(RealColors.ScrimLight).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEach { st ->
                val l = st.lens
                val sel = st == current
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(if (sel) RealColors.Scrim else Color.Transparent)
                        .clickable { if (l != null) onLens(l) else onZoom(st.zoom) }
                        .semantics { contentDescription = l?.description ?: "Цифровой зум ${ZoomMath.display(st.zoom)}" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (l?.kind == LensKind.MACRO) {
                        Icon(
                            Icons.Filled.LocalFlorist, null, tint = if (sel) RealColors.Accent else Color.White,
                            modifier = Modifier.size(18.dp).rotate(rot),
                        )
                    } else {
                        Text(
                            if (sel) ZoomMath.display(zoom) else l?.label ?: ZoomMath.label(st.zoom),
                            color = if (sel) RealColors.Accent else Color.White,
                            fontSize = if (sel) 13.sp else 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.rotate(rot),
                        )
                    }
                }
            }
        }
    }
}

private data class Stop(val zoom: Float, val lens: Lens?)

/** One-finger tap (focus) and two-finger pinch (zoom) on the preview; [onPinchEnd] fires when all fingers lift. */
fun Modifier.previewGestures(
    enabled: Boolean,
    onTap: (Offset, IntSize) -> Unit,
    onPinch: (Float) -> Unit,
    onPinchEnd: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var pinched = false
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val z = event.calculateZoom()
                if (z.isFinite() && z != 1f) {
                    pinched = true
                    onPinch(z)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (!pinched) {
                event.changes.firstOrNull { it.id == down.id }?.let {
                    if ((it.position - down.position).getDistance() > slop) moved = true
                }
            }
            if (event.changes.none { it.pressed }) break
        }
        if (pinched) onPinchEnd() else if (!moved) onTap(down.position, size)
    }
}
