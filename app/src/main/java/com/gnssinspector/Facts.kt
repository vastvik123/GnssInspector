package com.gnssinspector

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.time.Instant

// Turns Android's GNSS objects into labelled, explained facts for the UI.

fun locationFacts(g: GnssCollector, l: Location): List<Fact> {
    val rows = mutableListOf(
        Fact("Latitude", "%.7f°".format(l.latitude)),
        Fact("Longitude", "%.7f°".format(l.longitude)),
    )
    if (l.hasAltitude()) rows += Fact("Altitude (GPS)", "%.1f m".format(l.altitude),
        "Height above the WGS-84 ellipsoid, the mathematical shape GPS uses for Earth. It can differ from sea level by up to ±100 m.")
    if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) {
        rows += Fact("Altitude (sea level)", "%.1f m".format(l.mslAltitudeMeters), "Height above mean sea level, the kind of altitude on maps.")
    }
    if (l.hasAccuracy()) rows += Fact("Horizontal accuracy", "±%.1f m".format(l.accuracy),
        "Radius of a circle that contains your true position about 68% of the time.")
    if (l.hasVerticalAccuracy()) rows += Fact("Vertical accuracy", "±%.1f m".format(l.verticalAccuracyMeters),
        "Altitude is always less accurate than horizontal position, because every satellite is above you.")
    if (l.hasSpeed()) rows += Fact("Speed", "%.1f km/h".format(l.speed * 3.6),
        "Measured from the Doppler shift of the satellite signals, not from position changes, so it is accurate even at walking pace.")
    if (l.hasSpeedAccuracy()) rows += Fact("Speed accuracy", "±%.1f km/h".format(l.speedAccuracyMetersPerSecond * 3.6))
    if (l.hasBearing()) rows += Fact("Heading", "%.0f° (%s)".format(l.bearing, Plain.direction(l.bearing)), "Direction of travel, clockwise from true north.")
    rows += Fact("Time (UTC)", Instant.ofEpochMilli(l.time).toString().replace('T', ' ').removeSuffix("Z"),
        "Comes from the satellites' atomic clocks, so it's accurate to well under a microsecond.")
    val (week, tow) = gpsTime(l.time, g.leapSeconds)
    rows += Fact("GPS time", "week $week, %.0f s".format(tow),
        "GPS counts weeks since 6 Jan 1980 and seconds within the week. It ignores leap seconds, so it is ${g.leapSeconds} s ahead of UTC.")
    g.ttffMs?.let { rows += Fact("Time to first fix", "%.1f s".format(it / 1000.0), "TTFF: how long the receiver took to calculate its first position (\"fix\") after starting. A standard benchmark for GNSS receivers.") }
    rows += Fact("Fix age", "%.0f s".format((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9), "How long ago this position was calculated.")
    rows += Fact("Fixes received", g.locationCount.toString())
    return rows
}

private fun nmeaFields(sentence: String) = sentence.substringBefore('*').split(',')

fun dopFacts(gsa: String): List<Fact> {
    val f = nmeaFields(gsa)
    fun v(i: Int) = f.getOrNull(i).orEmpty().ifEmpty { "—" }
    return listOf(
        Fact("Fix type", when (f.getOrNull(2)) { "2" -> "2D (no altitude)"; "3" -> "3D"; else -> "none" }),
        Fact("PDOP", v(15), "Position dilution of precision: how much the satellites' arrangement in the sky magnifies errors. " +
            "Below 2 is excellent, 2–5 good, above 5 poor. Satellites spread across the sky give low DOP."),
        Fact("HDOP", v(16), "The same, for horizontal position only."),
        Fact("VDOP", v(17), "The same, for altitude. Usually the worst of the three, because no satellites are below you."),
    )
}

fun ggaFacts(gga: String): List<Fact> {
    val f = nmeaFields(gga)
    return listOf(
        Fact("Fix quality", when (f.getOrNull(6)) {
            "0" -> "no fix"; "1" -> "standard GNSS"; "2" -> "differential"; "4" -> "RTK fixed"; "5" -> "RTK float"
            "6" -> "estimated"; else -> f.getOrNull(6).orEmpty()
        }),
        Fact("Satellites used", f.getOrNull(7).orEmpty()),
        Fact("HDOP", f.getOrNull(8).orEmpty()),
        Fact("Altitude (sea level)", "${f.getOrNull(9).orEmpty()} m"),
        Fact("Geoid separation", "${f.getOrNull(11).orEmpty()} m",
            "How far sea level (the geoid) sits above the WGS-84 ellipsoid at your location."),
    )
}

fun clockFacts(c: GnssClock): List<Fact> {
    val rows = mutableListOf<Fact>()
    if (c.hasFullBiasNanos()) {
        val gpsNs = c.timeNanos - c.fullBiasNanos
        rows += Fact("GPS time from receiver", "week ${gpsNs / 604_800_000_000_000L}, %.6f s".format((gpsNs % 604_800_000_000_000L) / 1e9),
            "The receiver's clock corrected to true GPS time, to nanoseconds. Solving for the phone's clock error " +
                "is why a position needs a 4th satellite.")
    }
    if (c.hasDriftNanosPerSecond()) rows += Fact("Clock drift", "%.1f ns/s".format(c.driftNanosPerSecond),
        "How fast the phone's crystal oscillator runs fast or slow, in parts per billion. It changes with temperature.")
    if (c.hasBiasUncertaintyNanos()) rows += Fact("Clock uncertainty", "%.1f ns".format(c.biasUncertaintyNanos))
    if (c.hasLeapSecond()) rows += Fact("Leap seconds", "${c.leapSecond} s")
    rows += Fact("Clock resets", c.hardwareClockDiscontinuityCount.toString(), "Counts how often the receiver clock has jumped.")
    rows += Fact("Hardware clock", "${c.timeNanos} ns", "The receiver's raw, free-running clock reading (TimeNanos).")
    if (c.hasFullBiasNanos()) rows += Fact("Full bias", "${c.fullBiasNanos} ns")
    if (c.hasBiasNanos()) rows += Fact("Sub-ns bias", "%.3f ns".format(c.biasNanos))
    return rows
}

fun measurementFacts(m: GnssMeasurement, clock: GnssClock?, leapSeconds: Int): List<Fact> {
    val rows = mutableListOf<Fact>()
    val carrier = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null
    val pr = clock?.let { pseudorange(it, m, leapSeconds) }
    rows += Fact("Pseudorange", pr?.let { "%,.1f km".format(it / 1000) } ?: "not resolved yet",
        "Signal travel time × speed of light. It's called pseudo-range because the phone's clock error is still in it. " +
            "The receiver solves for that error using a 4th satellite.")
    pr?.let { rows += Fact("Signal travel time", "%.3f ms".format(it / SPEED_OF_LIGHT * 1000), "Radio waves take about 67–86 ms to reach you from orbit.") }
    rows += Fact("Range rate", "%.1f m/s".format(m.pseudorangeRateMetersPerSecond),
        "How fast the distance to the satellite is changing, from the Doppler shift. Negative means approaching.")
    carrier?.let {
        rows += Fact("Doppler shift", "%,.0f Hz".format(-m.pseudorangeRateMetersPerSecond * it / SPEED_OF_LIGHT),
            "How much the frequency is shifted by the satellite's motion relative to you, like a passing siren.")
        rows += Fact("Frequency", "%.3f MHz".format(it / 1e6))
    }
    if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) rows += Fact("Code tracked", Labels.codeType(m.codeType),
        "Each band can carry several ranging codes; this is the one the chip locked onto.")
    rows += Fact("Signal strength", "%.1f dB-Hz".format(m.cn0DbHz))
    rows += Fact("Tracking progress", Labels.measurementState(m.state),
        "How much of the signal the chip has locked onto, from code lock → bit sync → subframe sync → time decoded.")
    rows += Fact("Carrier phase", "%.3f m".format(m.accumulatedDeltaRangeMeters),
        "Change in distance measured by counting radio wave cycles (19 cm each on L1). Precise to millimetres. Used by survey-grade receivers.")
    rows += Fact("Carrier phase state", Labels.adrState(m.accumulatedDeltaRangeState))
    rows += Fact("Multipath", Labels.multipath(m.multipathIndicator), "Whether the signal is also arriving by bouncing off buildings, which distorts the range.")
    rows += Fact("Received satellite time", "${m.receivedSvTimeNanos} ns",
        "The satellite's clock reading when the signal left it, decoded from the signal.")
    rows += Fact("Satellite time uncertainty", "${m.receivedSvTimeUncertaintyNanos} ns")
    rows += Fact("Time offset", "%.3f ns".format(m.timeOffsetNanos))
    if (Build.VERSION.SDK_INT >= 30 && m.hasBasebandCn0DbHz()) rows += Fact("Strength inside chip", "%.1f dB-Hz".format(m.basebandCn0DbHz))
    @Suppress("DEPRECATION")
    if (m.hasAutomaticGainControlLevelDb()) rows += Fact("Amplifier gain", "%.1f dB".format(m.automaticGainControlLevelDb))
    if (Build.VERSION.SDK_INT >= 30 && m.hasFullInterSignalBiasNanos()) rows += Fact("Inter-signal bias", "%.2f ns".format(m.fullInterSignalBiasNanos))
    return rows
}

/** Where the decoded orbit places the satellite right now, compared with what the chip reports. */
fun orbitPositionFacts(g: GnssCollector, nav: GpsSatNav): List<Fact> {
    val (_, tow) = g.gpsNow()
    val pos = satellitePosition(nav, tow) ?: return emptyList()
    val (lat, lon, height) = subSatellitePoint(pos.ecef)
    val rows = mutableListOf(
        Fact("Directly above", Plain.latLon(lat, lon), "The point on Earth the satellite is over right now, computed from its broadcast orbit."),
        Fact("Height", "%,.0f km".format(height / 1000)),
        Fact("Clock correction", "%.2f µs".format(pos.clockOffsetSec * 1e6),
            "How far this satellite's atomic clock is from GPS time, including the relativity correction. 1 µs ≈ 300 m of range."),
    )
    val l = g.location
    if (l != null) {
        val (el, az, range) = lookAngles(l.latitude, l.longitude, l.altitude, pos.ecef)
        rows += Fact("Distance from you", "%,.0f km".format(range / 1000))
        rows += Fact("Position in sky (decoded)", "%.1f° up, %.0f°".format(el, az),
            "Worked out by this app from the decoded orbit. It should match what the chip reports.")
        g.satellites.firstOrNull { it.constellation == 1 && it.svid == nav.prn }?.let {
            rows += Fact("Position in sky (chip)", "%.1f° up, %.0f°".format(it.elevation, it.azimuth))
        }
    }
    return rows
}
