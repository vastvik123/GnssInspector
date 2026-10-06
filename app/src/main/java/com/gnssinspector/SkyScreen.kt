package com.gnssinspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private fun skyPoint(center: Offset, radius: Float, elevation: Float, azimuth: Float): Offset {
    val r = radius * (90 - elevation) / 90f
    val a = Math.toRadians(azimuth.toDouble())
    return Offset(center.x + r * sin(a).toFloat(), center.y - r * cos(a).toFloat())
}

@Composable
fun SkyScreen(g: GnssCollector, filter: Int?, onFilter: (Int?) -> Unit, openSat: (Int, Int) -> Unit, modifier: Modifier) {
    var showUnheard by rememberSaveable { mutableStateOf(false) }
    val all = summarize(g.satellites).filter { it.hasPosition }
    val systems = all.filter { it.heard }.map { it.constellation }.distinct().sorted()
    val shown = all.filter { (filter == null || it.constellation == filter) && (it.heard || showUnheard) }

    Screen(modifier) {
        item { SystemFilter(systems, filter, onFilter) }
        item {
            Panel {
                SkyPlot(shown, openSat)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendItem("In use") { SystemDot(0, 10) }
                    LegendItem("Heard, not used") { SystemDot(0, 10, hollow = true) }
                }
                Text(
                    "Centre = straight above you. Edge = the horizon. North is at the top. Tap a satellite for details.",
                    style = captionStyle,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = showUnheard, onClick = { showUnheard = !showUnheard },
                    label = { Text("Show expected but unheard satellites") },
                )
            }
        }
    }
}

@Composable
private fun LegendItem(text: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        mark()
        Text(text, style = captionStyle)
    }
}

@Composable
private fun SkyPlot(sats: List<SatSummary>, openSat: (Int, Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(sats)
    val sweep = rememberInfiniteTransition(label = "sweep")
    val angle by sweep.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "angle")

    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp).pointerInput(Unit) {
            detectTapGestures { tap ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) / 2f - 22.dp.toPx()
                current.minByOrNull { (skyPoint(c, radius, it.elevation, it.azimuth) - tap).getDistance() }
                    ?.takeIf { (skyPoint(c, radius, it.elevation, it.azimuth) - tap).getDistance() < 32.dp.toPx() }
                    ?.let { openSat(it.constellation, it.svid) }
            }
        },
    ) {
        val c = center
        val radius = size.minDimension / 2 - 22.dp.toPx()

        // Faint glow toward the zenith, then the radar sweep.
        drawCircle(Brush.radialGradient(listOf(Night.ice.copy(alpha = 0.09f), Color.Transparent), c, radius), radius, c)
        rotate(angle, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, Night.ice.copy(alpha = 0.10f)), c),
                startAngle = -60f, sweepAngle = 60f, useCenter = true,
                topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2, radius * 2),
            )
        }

        val ring = Night.ice.copy(alpha = 0.16f)
        for (el in listOf(0, 30, 60)) drawCircle(ring, radius * (90 - el) / 90f, c, style = Stroke(1.dp.toPx()))
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
        drawLine(ring, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), pathEffect = dash)
        drawLine(ring, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), pathEffect = dash)

        val small = TextStyle(fontFamily = PlexSans, color = Night.inkMuted, fontSize = 10.sp)
        for (el in listOf(30, 60)) {
            val m = measurer.measure("$el°", small)
            drawText(m, topLeft = Offset(c.x + 4.dp.toPx(), c.y - radius * (90 - el) / 90f + 2.dp.toPx()))
        }
        val bold = TextStyle(fontFamily = PlexSans, color = Night.inkSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        listOf("N" to Offset(0f, -1f), "E" to Offset(1f, 0f), "S" to Offset(0f, 1f), "W" to Offset(-1f, 0f)).forEach { (t, dir) ->
            val m = measurer.measure(t, bold)
            val p = c + dir * (radius + 12.dp.toPx())
            drawText(m, topLeft = Offset(p.x - m.size.width / 2, p.y - m.size.height / 2))
        }

        // Weaker satellites first so strong, used ones sit on top.
        for (s in sats.sortedWith(compareBy({ it.used }, { it.cn0 }))) {
            val p = skyPoint(c, radius, s.elevation, s.azimuth)
            drawSat(p, systemColor(s.constellation), s)
            val label = measurer.measure(
                "${s.svid}",
                TextStyle(fontFamily = PlexSans, color = if (s.heard) Night.ink else Night.inkMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium),
            )
            drawText(label, topLeft = Offset(p.x + 9.dp.toPx(), p.y - label.size.height / 2))
        }
    }
}

private fun DrawScope.drawSat(p: Offset, col: Color, s: SatSummary) {
    val r = 6.dp.toPx()
    if (s.heard) {
        // Halo grows with signal strength, so strong satellites glow brighter.
        val glow = r * (1.8f + 1.4f * (s.cn0.coerceIn(0f, 45f) / 45f))
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.6f), Color.Transparent), p, glow), glow, p)
    }
    when {
        s.used -> {
            drawCircle(col, r, p)
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.35f, p)
        }
        s.heard -> drawCircle(col, r - 1.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        else -> drawCircle(col.copy(alpha = 0.35f), r * 0.5f, p)
    }
}
