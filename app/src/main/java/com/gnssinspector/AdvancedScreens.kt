package com.gnssinspector

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AdvancedScreen(g: GnssCollector, open: (String) -> Unit, modifier: Modifier) {
    rememberNow()
    fun status(count: Int, supported: Boolean?) = when {
        count > 0 -> "receiving"
        supported == false -> "not on this phone"
        else -> "waiting"
    }
    Screen(modifier) {
        item {
            Text(
                "Everything the GNSS chip reports, in full detail, for people who want to dig deeper.",
                style = captionStyle, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            Panel(padding = PaddingValues(vertical = 4.dp)) {
                val entries = listOf(
                    Triple("Position details", "Every detail of your calculated location, plus precision (DOP)", if (g.location != null) "available" else "waiting") to "adv/position",
                    Triple("Raw measurements", "Signal timing, distance, Doppler and carrier phase", status(g.measurementCount, g.supportsMeasurements)) to "adv/raw",
                    Triple("Navigation messages", "Decoded orbits, clocks, ionosphere and UTC data", status(g.navCount, g.supportsNavMessages)) to "adv/nav",
                    Triple("NMEA sentences", "The chip's standard text output, live", status(g.nmeaCounts.size, null)) to "adv/nmea",
                    Triple("Chip & capabilities", "Hardware model and supported features", null) to "adv/device",
                )
                entries.forEachIndexed { i, (e, route) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    LinkRow(e.first, e.second, e.third, onClick = { open(route) })
                }
            }
        }
        item {
            Panel(onClick = { open("learn") }) {
                PanelTitle("How does satellite positioning work?", "A two-minute explainer")
            }
        }
    }
}

@Composable
fun PositionScreen(g: GnssCollector, modifier: Modifier) {
    rememberNow()
    val l = g.location
    Screen(modifier) {
        item {
            Panel {
                PanelTitle("Location fix", help = "In GNSS, a \"fix\" is a position the receiver has calculated from satellite signals. The word comes from navigation, where sailors \"fixed\" their position from stars.")
                if (l == null) Text("Waiting for a fix…") else InfoRows(locationFacts(g, l))
            }
        }
        g.nmeaLatest["GSA"]?.let { gsa ->
            item {
                Panel {
                    PanelTitle("Geometry (DOP)", "From the chip's GSA sentence",
                        help = "Dilution of precision measures how well spread the satellites are. Satellites bunched in one part " +
                            "of the sky give poor geometry, so small timing errors become large position errors.")
                    InfoRows(dopFacts(gsa))
                }
            }
        }
    }
}

@Composable
fun RawScreen(g: GnssCollector, openSat: (Int, Int) -> Unit, modifier: Modifier) {
    val now = rememberNow()
    val clock = g.clock
    Screen(modifier) {
        item {
            Hint(
                "For each satellite the chip measures when the signal left (stamped in the signal) and when it arrived " +
                    "(phone clock). Travel time × speed of light = distance. With four or more distances the phone solves for " +
                    "its position and its own clock error."
            )
        }
        if (clock == null) {
            item {
                Panel {
                    PanelTitle("No raw measurements")
                    Text(
                        if (g.supportsMeasurements == false) "Your phone's GNSS chip doesn't share raw measurements with apps. " +
                            "Everything else in this app still works."
                        else "None received yet. Go outside, and if nothing appears try Developer options → " +
                            "Force full GNSS measurements, then reopen the app.",
                    )
                }
            }
            return@Screen
        }
        item {
            Panel {
                PanelTitle("Receiver clock", "Updated %d s ago".format((now - g.lastMeasurementAt) / 1000))
                InfoRows(clockFacts(clock))
            }
        }
        if (g.agc.isNotEmpty()) {
            item {
                Panel {
                    PanelTitle("Amplifier gain", "Per band", help = "Automatic gain control: how much the chip amplifies each band. A sudden drop can mean jamming or interference.")
                    InfoRows(g.agc.map { Fact("${Plain.system(it.constellation)} ${Labels.band(it.constellation, it.carrierHz)}", "%.1f dB".format(it.levelDb)) })
                }
            }
        }
        item { SectionLabel("Per satellite · ${g.measurements.size} signals") }
        g.measurements.forEach { m ->
            item {
                val band = Labels.band(m.constellationType, if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null)
                val pr = pseudorange(clock, m, g.leapSeconds)
                ExpandablePanel(
                    "${Plain.satName(m.constellationType, m.svid)} · $band",
                    (pr?.let { "%,.0f km away".format(it / 1000) } ?: "distance not resolved") + " · %.0f dB-Hz".format(m.cn0DbHz),
                    key = "raw${m.constellationType}/${m.svid}/$band",
                ) {
                    InfoRows(measurementFacts(m, clock, g.leapSeconds))
                    LinkRow("All data for this satellite", null, onClick = { openSat(m.constellationType, m.svid) })
                }
            }
        }
    }
}

@Composable
fun NavScreen(g: GnssCollector, modifier: Modifier) {
    rememberNow()
    Screen(modifier) {
        item {
            Hint(
                "Satellites continuously broadcast a slow data stream: their health, clock correction and precise orbit, " +
                    "plus rough orbits for the whole fleet, an ionosphere model and the UTC offset. GPS L1 sends 50 bits per second."
            )
        }
        if (g.navCount == 0) {
            item {
                Panel {
                    PanelTitle("No navigation messages")
                    Text(
                        if (g.supportsNavMessages == false) "Your phone's GNSS chip decodes these internally but doesn't share the bits with apps."
                        else "None received yet. Go outside; a full GPS frame takes 30 seconds of clean signal.",
                    )
                }
            }
            return@Screen
        }
        item { Panel { PanelTitle("Received"); InfoRows(listOf(Fact("Messages", g.navCount.toString()))) } }
        g.ionoUtc?.let { iono -> item { Panel { PanelTitle("Ionosphere & UTC", "Broadcast by all GPS satellites"); InfoRows(iono.rows()) } } }
        val sats = g.gpsNav.values.sortedBy { it.prn }
        if (sats.isNotEmpty()) item { SectionLabel("Decoded GPS satellites") }
        sats.forEach { nav ->
            item {
                ExpandablePanel(
                    Plain.satName(1, nav.prn),
                    if (nav.ephemerisComplete) "Orbit and clock decoded" else "Collecting… ${nav.subframesSeen} parts so far",
                    key = "navsat${nav.prn}",
                ) { GpsNavDetails(g, nav) }
            }
        }
        if (g.almanacs.isNotEmpty()) {
            item {
                ExpandablePanel("Almanac · ${g.almanacs.size} satellites", "Rough orbits for the whole GPS fleet", key = "almanac") {
                    g.almanacs.values.sortedBy { it.prn }.forEach { a ->
                        Text(Plain.satName(1, a.prn), style = MaterialTheme.typography.labelLarge)
                        InfoRows(a.rows())
                    }
                }
            }
        }
        item {
            ExpandablePanel("Raw message bits · ${g.navLatest.size}", "Latest message per satellite and part, in hex", key = "rawnav") {
                g.navLatest.entries.sortedBy { it.key }.forEach { NavMessageBlock(it.value) }
            }
        }
    }
}

@Composable
fun GpsNavDetails(g: GnssCollector, nav: GpsSatNav) {
    val (week, _) = g.gpsNow()
    if (nav.ephemerisComplete) {
        Text("Where the decoded orbit puts it now", style = MaterialTheme.typography.labelLarge)
        InfoRows(orbitPositionFacts(g, nav))
    }
    nav.clock?.let { Text("Health & clock (subframe 1)", style = MaterialTheme.typography.labelLarge); InfoRows(it.rows(week)) }
    nav.orbit1?.let { Text("Orbit, part 1 (subframe 2)", style = MaterialTheme.typography.labelLarge); InfoRows(it.rows()) }
    nav.orbit2?.let { Text("Orbit, part 2 (subframe 3)", style = MaterialTheme.typography.labelLarge); InfoRows(it.rows()) }
}

@Composable
fun NavMessageBlock(e: NavEntry) {
    val age = (System.currentTimeMillis() - e.receivedAt) / 1000
    Text(
        "${Labels.navType(e.type)} · satellite ${e.svid} · part ${e.submessageId} · " +
            "${when (e.status) { 1 -> "checksum OK"; 2 -> "checksum repaired"; else -> "unchecked" }} · ${age}s ago",
        style = MaterialTheme.typography.labelMedium,
    )
    Mono(e.data.toHex())
}

private fun nmeaMeaning(type: String): String = if (type.startsWith("P")) "Manufacturer-specific sentence" else when (type.drop(2)) {
    "GGA" -> "Fix: position, altitude, quality, satellites used"
    "RMC" -> "Minimum data: time, date, position, speed, course"
    "GSA" -> "Precision (DOP) and which satellites are used"
    "GSV" -> "Satellites in view, with position and strength"
    "VTG" -> "Course and speed over ground"
    "GNS" -> "Multi-system fix data"
    "GST" -> "Position error estimates"
    "ZDA" -> "UTC date and time"
    "GLL" -> "Latitude and longitude"
    "DTM" -> "Map datum"
    else -> "Other"
} + when (type.take(2)) {
    "GP" -> " (GPS)"
    "GL" -> " (GLONASS)"
    "GA" -> " (Galileo)"
    "GB", "BD" -> " (BeiDou)"
    "GQ", "QZ" -> " (QZSS)"
    "GI" -> " (NavIC)"
    "GN" -> " (all systems)"
    else -> ""
}

@Composable
fun NmeaScreen(g: GnssCollector, modifier: Modifier) {
    Screen(modifier) {
        item { Hint("NMEA 0183 is the plain-text format GNSS receivers have spoken since the 1980s. Each line is a \"sentence\" starting with \$.") }
        g.nmeaLatest["GGA"]?.let { item { Panel { PanelTitle("Latest fix (GGA)"); InfoRows(ggaFacts(it)) } } }
        item {
            Panel {
                PanelTitle("Sentence types", "How many of each the chip has sent")
                if (g.nmeaCounts.isEmpty()) Text("None yet.")
                InfoRows(g.nmeaCounts.entries.sortedBy { it.key }.map { (t, n) -> Fact(t, n.toString(), nmeaMeaning(t)) })
            }
        }
        item {
            Panel {
                PanelTitle("Live log", "Newest first")
                g.nmeaLog.takeLast(60).asReversed().forEach { Mono(it) }
            }
        }
    }
}

@Composable
fun DeviceScreen(g: GnssCollector, modifier: Modifier) {
    val lm = g.lm
    Screen(modifier) {
        item {
            Panel {
                PanelTitle("Hardware")
                val rows = mutableListOf(
                    Fact("Phone", "${Build.MANUFACTURER} ${Build.MODEL}"),
                    Fact("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    rows += Fact("GNSS chip", lm.gnssHardwareModelName?.substringBefore(';')?.uppercase() ?: "not reported")
                    rows += Fact("Chip firmware", lm.gnssHardwareModelName?.substringAfter(';', "")?.trim(';')?.ifEmpty { null } ?: "—")
                    rows += Fact("Hardware year", lm.gnssYearOfHardware.toString())
                }
                InfoRows(rows)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            item {
                Panel {
                    PanelTitle("What the chip supports")
                    val c = lm.gnssCapabilities
                    val rows = mutableListOf(
                        Fact("Raw measurements", yesNo(c.hasMeasurements()), "Signal timing data per satellite."),
                        Fact("Navigation messages", yesNo(c.hasNavigationMessages()), "The raw data bits satellites broadcast."),
                        Fact("Antenna calibration", yesNo(c.hasAntennaInfo())),
                    )
                    if (Build.VERSION.SDK_INT >= 34) {
                        rows += Fact("Assisted GNSS", yesNo(c.hasMsa() || c.hasMsb()),
                            "Downloading orbits from the internet so the first position comes in seconds instead of a minute.")
                        rows += Fact("Low power mode", yesNo(c.hasLowPowerMode()))
                        rows += Fact("Measurement corrections", yesNo(c.hasMeasurementCorrections()),
                            "Accepts building-reflection corrections from 3D city maps.")
                    }
                    InfoRows(rows)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && g.antennas.isNotEmpty()) {
            item {
                Panel {
                    PanelTitle("Antennas", help = "Where inside the phone each band's antenna effectively receives, relative to the phone's reference point.")
                    InfoRows(g.antennas.map { a ->
                        val o = a.phaseCenterOffset
                        Fact("%.2f MHz".format(a.carrierFrequencyMHz), "x %.1f, y %.1f, z %.1f mm".format(o.xOffsetMm, o.yOffsetMm, o.zOffsetMm))
                    })
                }
            }
        }
        item {
            Hint(
                "Not available on any phone: the raw radio signal itself. It never leaves the GNSS chip. To record it you need " +
                    "a software-defined radio (e.g. an RTL-SDR with an active GPS antenna) and software such as GNSS-SDR."
            )
        }
    }
}

fun yesNo(b: Boolean) = if (b) "yes" else "no"
