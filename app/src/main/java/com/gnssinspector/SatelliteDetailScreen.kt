package com.gnssinspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SatelliteDetailScreen(g: GnssCollector, constellation: Int, svid: Int, modifier: Modifier) {
    val now = rememberNow()
    val id = Labels.satId(constellation, svid)
    val signals = g.satellites.filter { it.constellation == constellation && it.svid == svid }
    val sat = signals.takeIf { it.isNotEmpty() }?.let { SatSummary(constellation, svid, it) }
    val version = g.historyVersion
    val track = remember(version, id) { g.historyOf(id) }

    Screen(modifier) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SystemDot(constellation, 16)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Plain.satName(constellation, svid), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(Plain.origin(constellation), style = captionStyle)
                    }
                }
                Text(
                    when {
                        sat == null -> "Not in view right now: below the horizon or not yet listed by the chip."
                        sat.used -> "Your phone is using this satellite to work out your position."
                        sat.heard -> "Your phone hears this satellite but isn't using it for your position. " +
                            "It may still be downloading the orbit, or the signal is too weak."
                        else -> "Should be in your sky, but its signal isn't getting through."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (sat != null) {
            item {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        "Height", if (sat.hasPosition) "%.0f°".format(sat.elevation) else "—", trend(track, now) ?: "above horizon",
                        Modifier.weight(1f).fillMaxHeight(),
                        help = "Elevation: 0° is on the horizon, 90° is straight overhead. Satellites high in the sky give cleaner signals.",
                    )
                    StatTile(
                        "Direction", if (sat.hasPosition) Plain.shortDirection(sat.azimuth) else "—",
                        if (sat.hasPosition) "%.0f° from north".format(sat.azimuth) else null,
                        Modifier.weight(1f).fillMaxHeight(),
                        help = "Azimuth: the compass direction to look toward, measured clockwise from north.",
                    )
                    StatTile(
                        "Signal", "%.0f".format(sat.cn0), "${Plain.strength(sat.cn0)} · dB-Hz",
                        Modifier.weight(1f).fillMaxHeight(),
                        help = "Carrier-to-noise density (C/N0). 40+ is strong, under 25 is weak.",
                        extra = { SignalBars(sat.cn0) },
                    )
                }
            }
        }

        if (track.size >= 2) {
            item {
                Panel {
                    PanelTitle("Last ${windowMs(track, now) / 60_000} minutes", "Keep this open to watch the satellite move")
                    Text("Height in sky (°)", style = MaterialTheme.typography.labelLarge)
                    TrackChart(track, now, { it.elevation }, 90f, 30f, "°")
                    Text("Signal strength (dB-Hz)", style = MaterialTheme.typography.labelLarge)
                    TrackChart(track, now, { it.cn0 }, 50f, 10f, "")
                }
            }
        }

        item {
            Panel {
                PanelTitle("About this satellite")
                val facts = mutableListOf(
                    Plain.orbitType(constellation, svid).let { (kind, detail) ->
                        Fact(
                            "Orbit type", kind,
                            "$detail\n\nThis comes from public information about ${Plain.system(constellation)}, not from the satellite. " +
                                "The phone has the exact orbit, but doesn't share it with apps.",
                        )
                    },
                )
                if (sat != null) {
                    facts += Fact(
                        "Signals heard",
                        sat.signals.filter { it.cn0 > 0 }.joinToString { s -> s.band + (s.carrierHz?.let { " (%.2f MHz)".format(it / 1e6) } ?: "") }
                            .ifEmpty { "none" },
                        "Modern satellites transmit on several frequencies (bands). Receiving two lets the phone cancel the delay caused by the ionosphere.",
                    )
                    facts += Fact("Phone has its rough orbit", if (signals.any { it.hasAlmanac }) "yes" else "not yet",
                        "The almanac: a coarse orbit for every satellite, valid for weeks, which tells the phone where to look. " +
                            "The chip reports only whether it has one, not the orbit itself.")
                    facts += Fact("Phone has its precise orbit", if (signals.any { it.hasEphemeris }) "yes" else "not yet",
                        "The ephemeris: the exact orbit, valid for about 4 hours. The phone needs it before it can use the satellite, " +
                            "and gets it from the satellite (about 30 s) or the internet. The chip uses it internally but doesn't pass it to apps.")
                }
                facts += Fact("Code", id, "The standard short code for this satellite in GNSS software (RINEX).")
                InfoRows(facts)
            }
        }

        item { SectionLabel("Technical data") }
        item { MeasurementPanel(g, constellation, svid) }
        item { NavigationPanel(g, constellation, svid) }
        item { NmeaPanel(g, constellation, svid) }
    }
}

@Composable
private fun MeasurementPanel(g: GnssCollector, constellation: Int, svid: Int) {
    val ms = g.measurements.filter { it.constellationType == constellation && it.svid == svid }
    ExpandablePanel(
        "Raw measurement",
        when {
            g.measurementCount == 0 && g.supportsMeasurements == false -> "Your phone doesn't provide this"
            g.measurementCount == 0 -> "None received yet"
            ms.isEmpty() -> "Not measured in the latest second"
            else -> "Travel time, distance, Doppler, carrier phase"
        },
        key = "meas",
    ) {
        if (ms.isEmpty()) {
            Text(
                "Raw measurements are what the chip times directly from the signal. " +
                    if (g.measurementCount == 0) "This phone hasn't shared any. Some phones need Developer options → " +
                        "Force full GNSS measurements turned on." else "This satellite wasn't part of the latest batch.",
                style = captionStyle,
            )
        }
        ms.forEach { m ->
            Text(Labels.band(m.constellationType, if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null),
                style = MaterialTheme.typography.labelLarge)
            InfoRows(measurementFacts(m, g.clock, g.leapSeconds))
        }
    }
}

@Composable
private fun NavigationPanel(g: GnssCollector, constellation: Int, svid: Int) {
    val nav = if (constellation == 1) g.gpsNav[svid] else null
    val raw = g.navLatest.values.filter { it.svid == svid && (it.type shr 8) == constellation }.sortedBy { it.submessageId }
    ExpandablePanel(
        "Navigation message",
        when {
            g.navCount == 0 && g.supportsNavMessages == false -> "Your phone doesn't provide this"
            nav?.ephemerisComplete == true -> "Orbit and clock decoded"
            raw.isNotEmpty() -> "${raw.size} messages received"
            else -> "None received yet"
        },
        key = "nav",
    ) {
        Text(
            "The data the satellite broadcasts: its health, clock correction and precise orbit. GPS sends it at 50 bits per second, " +
                "so a full set takes about 30 seconds.",
            style = captionStyle,
        )
        if (nav != null) GpsNavDetails(g, nav)
        raw.forEach { e -> NavMessageBlock(e) }
    }
}

@Composable
private fun NmeaPanel(g: GnssCollector, constellation: Int, svid: Int) {
    val matches = gsvMatches(g, constellation, svid)
    ExpandablePanel(
        "Chip's NMEA report",
        if (matches.isEmpty()) "Not listed in the latest report" else "Listed in ${matches.size} sentence(s)",
        key = "nmea",
    ) {
        Text("NMEA is the standard text format GNSS chips have used since the 1980s. GSV sentences list each satellite in view.", style = captionStyle)
        matches.forEach { (fact, sentence) ->
            InfoRow(fact)
            Mono(sentence)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// History chart
// ---------------------------------------------------------------------------------------------

private fun windowMs(track: List<SkySample>, now: Long) =
    (now - (track.firstOrNull()?.atMs ?: now)).coerceIn(2 * 60_000L, 30 * 60_000L)

private fun trend(track: List<SkySample>, now: Long): String? {
    val past = track.lastOrNull { now - it.atMs >= 5 * 60_000 } ?: return null
    val delta = track.last().elevation - past.elevation
    return when {
        delta > 0.5f -> "rising"
        delta < -0.5f -> "setting"
        else -> "steady"
    }
}

/** Single-series line over time: 2dp line, recessive grid, the latest value labelled at the end. */
@Composable
private fun TrackChart(track: List<SkySample>, now: Long, value: (SkySample) -> Float, max: Float, step: Float, unit: String) {
    val measurer = rememberTextMeasurer()
    val lineColor = Night.ice
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val inkMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = Night.skyBottom
    val window = windowMs(track, now)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val small = TextStyle(fontFamily = PlexSans, color = inkMuted, fontSize = 10.sp)
        val left = 28.dp.toPx()
        val right = size.width - 40.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        fun x(t: Long) = right - (now - t).toFloat() / window * (right - left)
        fun y(v: Float) = bottom - v.coerceIn(0f, max) / max * (bottom - top)

        var gv = 0f
        while (gv <= max) {
            drawLine(gridColor, Offset(left, y(gv)), Offset(right, y(gv)), 1.dp.toPx())
            val m = measurer.measure("%.0f".format(gv), small)
            drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), y(gv) - m.size.height / 2))
            gv += step
        }
        drawText(measurer.measure("${window / 60_000} min ago", small), topLeft = Offset(left, bottom + 2.dp.toPx()))
        val nowLabel = measurer.measure("now", small)
        drawText(nowLabel, topLeft = Offset(right - nowLabel.size.width, bottom + 2.dp.toPx()))

        // Break the line where samples are missing rather than bridging the gap.
        val path = Path()
        var prev: SkySample? = null
        for (s in track) {
            if (now - s.atMs > window) continue
            val p = Offset(x(s.atMs), y(value(s)))
            if (prev == null || s.atMs - prev.atMs > 5_000) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            prev = s
        }
        drawPath(path, lineColor.copy(alpha = 0.18f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val last = track.last()
        val end = Offset(x(last.atMs), y(value(last)))
        drawCircle(surface, 6.dp.toPx(), end)
        drawCircle(lineColor, 4.dp.toPx(), end)
        val label = measurer.measure("%.0f%s".format(value(last), unit), TextStyle(fontFamily = PlexSans, color = lineColor, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        drawText(label, topLeft = Offset(end.x + 8.dp.toPx(), (end.y - label.size.height / 2).coerceIn(0f, size.height - label.size.height)))
    }
}

// ---------------------------------------------------------------------------------------------
// NMEA GSV lookup
// ---------------------------------------------------------------------------------------------

/**
 * Finds this satellite in the latest GSV groups. NMEA numbers some systems differently from
 * Android: GLONASS slots are 65–96, SBAS 33–64, and QZSS may be 1–10 or 193–202.
 */
private fun gsvMatches(g: GnssCollector, constellation: Int, svid: Int): List<Pair<Fact, String>> {
    val talkers = when (constellation) {
        1 -> setOf("GP")
        2 -> setOf("GP", "SB")
        3 -> setOf("GL")
        4 -> setOf("GQ", "QZ", "GP")
        5 -> setOf("GB", "BD")
        6 -> setOf("GA")
        7 -> setOf("GI")
        else -> emptySet()
    }
    val prns = when (constellation) {
        2 -> setOf(svid - 87, svid)
        3 -> setOf(svid + 64)
        4 -> setOf(svid, svid - 192)
        else -> setOf(svid)
    }
    val out = mutableListOf<Pair<Fact, String>>()
    for ((key, sentences) in g.gsvGroups) {
        val talker = key.substringBefore('/')
        if (talker !in talkers) continue
        for (sentence in sentences) {
            val f = sentence.substringBefore('*').split(',')
            // Satellite blocks are 4 fields each from index 4; integer division skips a trailing signal ID.
            for (block in 0 until (f.size - 4) / 4) {
                val i = 4 + block * 4
                val prn = f.getOrNull(i)?.toIntOrNull() ?: continue
                // The GP talker shares its numbering between GPS, SBAS and QZSS.
                val inRange = talker != "GP" || when (constellation) {
                    1 -> prn in 1..32
                    2 -> prn in 33..64
                    else -> prn in 193..202
                }
                if (prn in prns && inRange) {
                    fun v(j: Int) = f.getOrNull(j).orEmpty().ifEmpty { "—" }
                    out += Fact("${f[0].removePrefix("$")} · satellite $prn", "${v(i + 1)}° up, ${v(i + 2)}°, ${v(i + 3)} dB") to sentence
                }
            }
        }
    }
    return out
}
