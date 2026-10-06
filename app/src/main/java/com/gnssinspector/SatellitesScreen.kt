package com.gnssinspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SatellitesScreen(g: GnssCollector, filter: Int?, onFilter: (Int?) -> Unit, openSat: (Int, Int) -> Unit, modifier: Modifier) {
    val all = summarize(g.satellites)
    val systems = all.filter { it.heard }.map { it.constellation }.distinct().sorted()
    val shown = all.filter { filter == null || it.constellation == filter }
    // A stable order: rows must not jump around while someone is trying to tap one.
    val heard = shown.filter { it.heard }.sortedWith(compareByDescending<SatSummary> { it.used }.thenBy { it.constellation }.thenBy { it.svid })
    val unheard = shown.filter { !it.heard }.sortedWith(compareBy({ it.constellation }, { it.svid }))

    Screen(modifier) {
        item { SystemFilter(systems, filter, onFilter) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Hearing now · ${heard.size}", Modifier.weight(1f))
                InfoButton(
                    "Signal strength",
                    "The bars show carrier-to-noise density (C/N0), how far the satellite's signal stands above " +
                        "background noise, in dB-Hz.\n\n4 bars: 40+ (strong, open sky)\n3 bars: 32–40 (good)\n" +
                        "2 bars: 25–32 (fair)\n1 bar: under 25 (weak, blocked or indoors)",
                )
            }
        }
        item {
            Panel(padding = PaddingValues(vertical = 4.dp)) {
                if (heard.isEmpty()) Text("No satellites heard yet. Try going outside.", Modifier.padding(16.dp))
                heard.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SatelliteRow(s) { openSat(s.constellation, s.svid) }
                }
            }
        }
        if (unheard.isNotEmpty()) {
            item {
                ExpandablePanel(
                    "Expected but not heard · ${unheard.size}",
                    "Your phone knows these should be in the sky, but their signal isn't getting through. " +
                        "Usually walls, a roof or your body are in the way.",
                    key = "unheard",
                ) {
                    unheard.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openSat(s.constellation, s.svid) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SystemDot(s.constellation, 8, hollow = true)
                            Spacer(Modifier.width(12.dp))
                            Text(s.name, Modifier.weight(1f))
                            if (s.hasPosition) {
                                Text("%.0f° up · %s".format(s.elevation, Plain.shortDirection(s.azimuth)), style = captionStyle)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SatelliteRow(s: SatSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemDot(s.constellation, 12)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (s.used) Pill("In use")
            }
            val where = if (s.hasPosition) "%.0f° up, %s".format(s.elevation, Plain.direction(s.azimuth).lowercase()) else "position unknown"
            Text("$where · ${s.bands.joinToString(" + ")}", style = captionStyle)
        }
        Column(horizontalAlignment = Alignment.End) {
            SignalBars(s.cn0)
            Text(Plain.strength(s.cn0), style = captionStyle)
        }
    }
}
