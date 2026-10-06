package com.gnssinspector

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun OverviewScreen(g: GnssCollector, openSystem: (Int) -> Unit, open: (String) -> Unit, modifier: Modifier) {
    rememberNow()
    val sats = summarize(g.satellites)
    val heard = sats.filter { it.heard }
    val used = sats.filter { it.used }
    val l = g.location

    Screen(modifier) {
        // Only flag weak signals when they are actually hurting the result.
        // Compare the whole-metre value shown on screen, matching the accuracy meter.
        val poorResult = l == null || !l.hasAccuracy() || Math.round(l.accuracy) > 10
        val weakNow = poorResult && heard.isNotEmpty() && heard.map { it.cn0 }.sorted()[heard.size / 2] < 25
        item { StatusHero(g, weakSignals = rememberSteady(weakNow, showAfterMs = 5_000, hideAfterMs = 10_000)) }

        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    "Satellites in use", "${used.size}", "of ${heard.size} heard",
                    Modifier.weight(1f).fillMaxHeight(),
                    help = "Your phone only uses satellites whose signal is clear enough and whose orbit it knows. " +
                        "Four is the minimum for a position; more gives a better one.",
                )
                val accuracy = l?.takeIf { it.hasAccuracy() }?.accuracy
                StatTile(
                    "Accuracy", accuracy?.let { "±%.0f m".format(it) } ?: "—", null,
                    Modifier.weight(1f).fillMaxHeight(),
                    help = "How far your true position could be from the one shown: within this distance about two times out of three.\n\n" +
                        "The meter fills toward the crosshair as your location gets more precise:\n" +
                        "4 segments: within 5 m\n3 segments: 5–10 m\n2 segments: 10–25 m\n1 segment: more than 25 m",
                    below = { AccuracyMeter(accuracy) },
                )
            }
        }

        item { SectionLabel("Satellite systems") }
        item {
            Panel(padding = PaddingValues(vertical = 4.dp)) {
                if (sats.isEmpty()) {
                    Text("Listening for satellites…", Modifier.padding(16.dp))
                }
                sats.groupBy { it.constellation }
                    .entries.sortedWith(compareByDescending<Map.Entry<Int, List<SatSummary>>> { e -> e.value.count { it.used } }
                        .thenByDescending { e -> e.value.count { it.heard } })
                    .forEachIndexed { i, (type, list) ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        LinkRow(
                            title = Plain.system(type),
                            subtitle = Plain.origin(type),
                            trailing = "${list.count { it.used }} in use\n${list.count { it.heard }} heard",
                            leading = { SystemDot(type, 12) },
                            onClick = { openSystem(type) },
                        )
                    }
            }
        }

        item {
            Panel(onClick = { open("learn") }) {
                PanelTitle("How does satellite positioning work?", "A two-minute explainer of what you're seeing in this app")
            }
        }
    }
}

@Composable
private fun StatusHero(g: GnssCollector, weakSignals: Boolean) {
    val l = g.location
    val context = LocalContext.current
    val age = l?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000 }
    val searching = l == null || (age ?: 0) > 10
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Night.ice.copy(alpha = 0.13f), Night.ice.copy(alpha = 0.03f))))
            .border(1.dp, Night.ice.copy(alpha = 0.18f), shape)
            .padding(20.dp),
    ) {
        val showRadar = searching && g.locationEnabled
        if (showRadar) RadarPulse(Modifier.align(Alignment.TopEnd).size(72.dp))
        // Keep text clear of the radar animation.
        Column(Modifier.padding(end = if (showRadar) 64.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Fixed height: fits the ⓘ, so the weak-signals notice can come and go without moving anything.
            Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(active = g.locationEnabled)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !g.locationEnabled -> "OFFLINE"
                        searching -> "SEARCHING"
                        else -> "LIVE"
                    },
                    style = MaterialTheme.typography.labelMedium, color = Night.ice,
                )
                // Lives in this fixed-height row so showing it never shifts the screen.
                if (weakSignals) {
                    Text("  ·  WEAK SIGNALS", style = MaterialTheme.typography.labelMedium, color = Night.inkSoft)
                    InfoButton(
                        "Weak signals",
                        "The satellite signals reaching your phone are faint, so you're probably indoors or surrounded by tall buildings.\n\n" +
                            "Go outside with open sky: you'll hear more satellites and get a much more accurate location.",
                    )
                }
            }
            when {
                !g.locationEnabled -> {
                    Text("Location is off", style = MaterialTheme.typography.headlineMedium)
                    Text("Turn on location so the phone's satellite receiver can start.", style = captionStyle)
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Open location settings") }
                }
                l == null -> {
                    Text("Searching the sky…", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Hearing ${summarize(g.satellites).count { it.heard }} satellites so far. " +
                            "Outdoors, finding your location usually takes 5–60 seconds.",
                        style = captionStyle,
                    )
                }
                else -> {
                    // The accuracy meter grades the fix, so the headline is simply where you are.
                    if (searching) Text("Signal lost", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        Plain.latLon(l.latitude, l.longitude),
                        style = (if (searching) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                            .copy(fontFeatureSettings = "tnum"),
                        color = if (searching) Night.inkSoft else Night.ink,
                    )
                    val parts = mutableListOf<String>()
                    if (l.hasAltitude()) parts += "%.0f m altitude".format(l.altitude)
                    if (l.hasSpeed()) parts += "%.0f km/h".format(l.speed * 3.6)
                    parts += if ((age ?: 0) <= 2) "updated just now" else "updated $age s ago"
                    Text(parts.joinToString("  ·  "), style = captionStyle)
                    g.ttffMs?.let { Text("Found your location in %.1f s".format(it / 1000.0), style = captionStyle) }
                }
            }
        }
    }
}

/** Small dot with a slowly expanding halo, like a beacon. */
@Composable
private fun LiveDot(active: Boolean) {
    val t = rememberInfiniteTransition(label = "live")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    Canvas(Modifier.size(10.dp)) {
        val r = size.minDimension / 2
        if (active) drawCircle(Night.ice.copy(alpha = 0.5f * (1 - p)), r * (1 + 1.6f * p))
        drawCircle(if (active) Night.ice else Night.inkMuted, r * 0.7f)
    }
}

/** Expanding rings shown while the receiver hunts for satellites. */
@Composable
private fun RadarPulse(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rings")
    Canvas(modifier) {
        val max = size.minDimension / 2
        for (k in 0 until 3) {
            val f = (p + k / 3f) % 1f
            drawCircle(Night.ice.copy(alpha = 0.35f * (1 - f)), max * f, style = Stroke(1.5.dp.toPx()))
        }
        drawCircle(Night.ice, 3.dp.toPx())
    }
}

/**
 * A flag that only turns on after [value] has been true for [showAfterMs], and only turns off
 * after it has been false for [hideAfterMs], so borderline conditions don't make it flicker.
 */
@Composable
private fun rememberSteady(value: Boolean, showAfterMs: Long, hideAfterMs: Long): Boolean {
    var steady by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value != steady) {
            delay(if (value) showAfterMs else hideAfterMs)
            steady = value
        }
    }
    return steady
}
