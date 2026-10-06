package com.gnssinspector

import android.location.GnssMeasurement
import android.location.GnssStatus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** One labelled value shown in the UI, with an optional plain-language explanation. */
data class Fact(val label: String, val value: String, val help: String? = null)

const val SPEED_OF_LIGHT = 299_792_458.0
const val GPS_PI = 3.1415926535898 // the exact value IS-GPS-200 uses for semicircle conversions
const val WEEK_SECONDS = 604_800.0
const val GPS_EPOCH_UNIX_MS = 315_964_800_000L // 1980-01-06T00:00:00Z
const val DEFAULT_LEAP_SECONDS = 18

object Labels {
    fun constellation(type: Int) = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        7 -> "NavIC"
        else -> "Unknown"
    }

    /** RINEX one-letter system code, e.g. G05 = GPS PRN 5. */
    fun letter(type: Int) = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "G"
        GnssStatus.CONSTELLATION_SBAS -> "S"
        GnssStatus.CONSTELLATION_GLONASS -> "R"
        GnssStatus.CONSTELLATION_QZSS -> "J"
        GnssStatus.CONSTELLATION_BEIDOU -> "C"
        GnssStatus.CONSTELLATION_GALILEO -> "E"
        7 -> "I"
        else -> "?"
    }

    fun satId(type: Int, svid: Int) = "%s%02d".format(letter(type), svid)

    /** Names the signal band from its carrier frequency. */
    fun band(type: Int, hz: Double?): String {
        if (hz == null || hz <= 0) return "—"
        val mhz = hz / 1e6
        fun near(f: Double) = abs(mhz - f) < 2.0
        return when {
            type == GnssStatus.CONSTELLATION_GLONASS && mhz in 1592.0..1610.0 -> "G1"
            type == GnssStatus.CONSTELLATION_GLONASS && mhz in 1237.0..1256.0 -> "G2"
            near(1575.42) -> when (type) {
                GnssStatus.CONSTELLATION_GALILEO -> "E1"
                GnssStatus.CONSTELLATION_BEIDOU -> "B1C"
                else -> "L1"
            }
            near(1561.098) -> "B1I"
            near(1176.45) -> when (type) {
                GnssStatus.CONSTELLATION_GALILEO -> "E5a"
                GnssStatus.CONSTELLATION_BEIDOU -> "B2a"
                else -> "L5"
            }
            near(1207.14) -> if (type == GnssStatus.CONSTELLATION_GALILEO) "E5b" else "B2I/B2b"
            near(1227.60) -> "L2"
            near(1278.75) -> "E6/L6"
            near(1268.52) -> "B3I"
            near(2492.028) -> "S"
            else -> "%.3f MHz".format(mhz)
        }
    }

    private val measurementStates = listOf(
        GnssMeasurement.STATE_CODE_LOCK to "code lock",
        GnssMeasurement.STATE_BIT_SYNC to "bit sync",
        GnssMeasurement.STATE_SUBFRAME_SYNC to "subframe sync",
        GnssMeasurement.STATE_TOW_DECODED to "time-of-week decoded",
        GnssMeasurement.STATE_TOW_KNOWN to "time-of-week known",
        GnssMeasurement.STATE_MSEC_AMBIGUOUS to "millisecond ambiguous",
        GnssMeasurement.STATE_SYMBOL_SYNC to "symbol sync",
        GnssMeasurement.STATE_GLO_STRING_SYNC to "GLONASS string sync",
        GnssMeasurement.STATE_GLO_TOD_DECODED to "GLONASS time-of-day decoded",
        GnssMeasurement.STATE_GLO_TOD_KNOWN to "GLONASS time-of-day known",
        GnssMeasurement.STATE_BDS_D2_BIT_SYNC to "BeiDou D2 bit sync",
        GnssMeasurement.STATE_BDS_D2_SUBFRAME_SYNC to "BeiDou D2 subframe sync",
        GnssMeasurement.STATE_GAL_E1BC_CODE_LOCK to "Galileo E1BC code lock",
        GnssMeasurement.STATE_GAL_E1C_2ND_CODE_LOCK to "Galileo E1C secondary code lock",
        GnssMeasurement.STATE_GAL_E1B_PAGE_SYNC to "Galileo E1B page sync",
        GnssMeasurement.STATE_SBAS_SYNC to "SBAS sync",
        GnssMeasurement.STATE_2ND_CODE_LOCK to "secondary code lock",
    )

    fun measurementState(state: Int): String =
        measurementStates.filter { (state and it.first) != 0 }.joinToString(", ") { it.second }
            .ifEmpty { "unknown / not tracking" }

    private val adrStates = listOf(
        GnssMeasurement.ADR_STATE_VALID to "valid",
        GnssMeasurement.ADR_STATE_RESET to "reset",
        GnssMeasurement.ADR_STATE_CYCLE_SLIP to "cycle slip",
        GnssMeasurement.ADR_STATE_HALF_CYCLE_RESOLVED to "half-cycle resolved",
        GnssMeasurement.ADR_STATE_HALF_CYCLE_REPORTED to "half-cycle reported",
    )

    fun adrState(state: Int): String =
        adrStates.filter { (state and it.first) != 0 }.joinToString(", ") { it.second }.ifEmpty { "not available" }

    fun multipath(indicator: Int) = when (indicator) {
        GnssMeasurement.MULTIPATH_INDICATOR_DETECTED -> "detected"
        GnssMeasurement.MULTIPATH_INDICATOR_NOT_DETECTED -> "not detected"
        else -> "unknown"
    }

    fun codeType(code: String) = code + when (code) {
        "C" -> " (C/A civilian code)"
        "I" -> " (in-phase / data channel)"
        "Q" -> " (quadrature / pilot channel)"
        "X" -> " (data + pilot combined)"
        "B" -> " (Galileo E1-B data)"
        "L" -> " (long code / L1C pilot)"
        "S" -> " (medium code / L1C data)"
        "P" -> " (precision code)"
        "Y" -> " (encrypted P code)"
        "M" -> " (military code)"
        "A" -> " (A channel)"
        else -> ""
    }

    fun navType(type: Int) = when (type) {
        0x0101 -> "GPS L1 C/A (LNAV)"
        0x0102 -> "GPS L2 CNAV"
        0x0103 -> "GPS L5 CNAV"
        0x0104 -> "GPS L1C CNAV-2"
        0x0201 -> "SBAS"
        0x0301 -> "GLONASS L1 C/A"
        0x0401 -> "QZSS L1 C/A"
        0x0501 -> "BeiDou D1"
        0x0502 -> "BeiDou D2"
        0x0503 -> "BeiDou CNAV1"
        0x0504 -> "BeiDou CNAV2"
        0x0601 -> "Galileo I/NAV"
        0x0602 -> "Galileo F/NAV"
        0x0701 -> "NavIC L5 C/A"
        else -> "Type 0x%04X".format(type)
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

// ---------------------------------------------------------------------------------------------
// GPS L1 C/A navigation message (LNAV), IS-GPS-200 section 20.3.
// Android delivers one subframe per message: 10 words of 30 bits, each right-aligned in 4 bytes.
// ---------------------------------------------------------------------------------------------

private val PARITY_BITS = arrayOf(
    intArrayOf(1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23),
    intArrayOf(2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24),
    intArrayOf(1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22),
    intArrayOf(2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23),
    intArrayOf(1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24),
    intArrayOf(3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24),
)
private val PARITY_USES_D29 = booleanArrayOf(true, false, true, false, false, true)

private fun parity(d: Int, d29: Int, d30: Int): Int {
    var out = 0
    for (k in 0..5) {
        var b = if (PARITY_USES_D29[k]) d29 else d30
        for (i in PARITY_BITS[k]) b = b xor ((d ushr (24 - i)) and 1)
        out = (out shl 1) or b
    }
    return out
}

class LnavSubframe(private val d: IntArray, val parityOkWords: Int) {
    /** Unsigned field: [len] bits starting at data bit [start] (1 = MSB) of 1-based [word]. */
    fun u(word: Int, start: Int, len: Int): Long =
        ((d[word - 1] ushr (24 - start - len + 1)) and ((1 shl len) - 1)).toLong()

    fun s(word: Int, start: Int, len: Int): Long = signExtend(u(word, start, len), len)

    /** A field whose high bits end one word and low 24 bits fill the next. */
    fun u2(word: Int, hiStart: Int, hiLen: Int): Long = (u(word, hiStart, hiLen) shl 24) or u(word + 1, 1, 24)

    fun s2(word: Int, hiStart: Int, hiLen: Int): Long = signExtend(u2(word, hiStart, hiLen), hiLen + 24)

    val preamble get() = u(1, 1, 8).toInt()
    val tlmMessage get() = u(1, 9, 14).toInt()
    val integrityFlag get() = u(1, 23, 1).toInt()
    val towCount get() = u(2, 1, 17).toInt()
    val alert get() = u(2, 18, 1).toInt()
    val antiSpoof get() = u(2, 19, 1).toInt()
    val subframeId get() = u(2, 20, 3).toInt()
    /** Page "SV ID" in subframes 4 and 5. */
    val pageSvId get() = u(3, 3, 6).toInt()

    fun headerRows() = listOf(
        Fact("Preamble", "0x%02X".format(preamble), "Always 0x8B — marks the start of every 6-second subframe."),
        Fact("TLM message", tlmMessage.toString(), "Telemetry word content (reserved for authorised users)."),
        Fact("Integrity flag", integrityFlag.toString(), "1 = enhanced integrity assurance for this signal."),
        Fact("TOW count", "$towCount  (= ${towCount * 6} s into the GPS week)",
            "Time-of-week of the NEXT subframe, in 6-second units. This is how a receiver learns the time."),
        Fact("Alert flag", alert.toString(), "1 = signal accuracy may be worse than advertised; use at own risk."),
        Fact("Anti-spoof", antiSpoof.toString(), "1 = P code is encrypted to Y code (normal)."),
        Fact("Subframe ID", subframeId.toString(),
            "1 = clock/health, 2-3 = ephemeris (precise orbit), 4-5 = almanac, ionosphere, UTC."),
        Fact("Words passing parity", "$parityOkWords / 10", "Each 30-bit word has 6 Hamming parity bits."),
    )

    companion object {
        fun signExtend(v: Long, bits: Int): Long = if (v and (1L shl (bits - 1)) != 0L) v - (1L shl bits) else v

        fun parse(raw: ByteArray): LnavSubframe? {
            if (raw.size < 40) return null
            val data = IntArray(10)
            var prev = 0 // D29*/D30* of the previous word; always 00 before word 1 by design
            var ok = 0
            for (i in 0 until 10) {
                val w = ((raw[4 * i].toInt() and 0xFF) shl 24) or ((raw[4 * i + 1].toInt() and 0xFF) shl 16) or
                    ((raw[4 * i + 2].toInt() and 0xFF) shl 8) or (raw[4 * i + 3].toInt() and 0xFF)
                val word = w and 0x3FFFFFFF
                val bits = (word ushr 6) and 0xFFFFFF
                val d29 = (prev ushr 1) and 1
                val d30 = prev and 1
                // Chipsets differ on whether they already undid the D30* polarity inversion,
                // so try each interpretation and keep the one whose parity checks out.
                val standard = if (d30 == 1) bits xor 0xFFFFFF else bits
                val candidates = intArrayOf(standard, bits, bits xor 0xFFFFFF)
                val match = candidates.firstOrNull { parity(it, d29, d30) == (word and 0x3F) }
                if (match != null) ok++
                data[i] = match ?: standard
                prev = word
            }
            return LnavSubframe(data, ok)
        }
    }
}

private fun p2(n: Int) = Math.scalb(1.0, n)
private fun semiToRad(v: Double) = v * GPS_PI
private fun radToDeg(v: Double) = Math.toDegrees(v)

data class ClockData(
    val weekNumber10: Int, val l2Codes: Int, val uraIndex: Int, val health: Int, val iodc: Int, val l2PFlag: Int,
    val tgd: Double, val toc: Double, val af2: Double, val af1: Double, val af0: Double,
) {
    fun rows(currentWeek: Int?) = listOf(
        Fact("Week number (10-bit)", weekNumber10.toString() + (currentWeek?.let { w ->
            "  → full week ${w - Math.floorMod(w - weekNumber10, 1024)}"
        } ?: ""), "GPS week, transmitted modulo 1024 (rolls over every 19.6 years)."),
        Fact("SV health", if (health == 0) "0 (healthy)" else "$health (unhealthy!)", "6-bit health summary."),
        Fact("URA index", "$uraIndex (≈ ${uraMeters(uraIndex)})", "User Range Accuracy: expected ranging error from this satellite."),
        Fact("Codes on L2", when (l2Codes) { 1 -> "P code"; 2 -> "C/A code"; else -> l2Codes.toString() }),
        Fact("L2 P data flag", l2PFlag.toString()),
        Fact("IODC", iodc.toString(), "Issue of Data, Clock — version number of this clock data set."),
        Fact("T_GD", "%.3f ns".format(tgd * 1e9), "Group delay between L1 and L2 inside the satellite."),
        Fact("t_oc", "%.0f s".format(toc), "Reference time (seconds into week) of the clock polynomial."),
        Fact("a_f0", "%.6f µs".format(af0 * 1e6), "Satellite clock offset from GPS time."),
        Fact("a_f1", "%.3e s/s".format(af1), "Satellite clock drift."),
        Fact("a_f2", "%.3e s/s²".format(af2), "Satellite clock drift rate."),
    )

    private fun uraMeters(i: Int) = when (i) {
        0 -> "2.4 m"; 1 -> "3.4 m"; 2 -> "4.85 m"; 3 -> "6.85 m"; 4 -> "9.65 m"; 5 -> "13.65 m"; 6 -> "24 m"
        7 -> "48 m"; 8 -> "96 m"; 9 -> "192 m"; 10 -> "384 m"; 11 -> "768 m"; 12 -> "1536 m"; 13 -> "3072 m"
        14 -> "6144 m"; else -> "no accuracy prediction"
    }
}

data class OrbitPart1(
    val iode: Int, val crs: Double, val deltaN: Double, val m0: Double, val cuc: Double, val e: Double,
    val cus: Double, val sqrtA: Double, val toe: Double, val fitFlag: Int, val aodo: Int,
) {
    fun rows() = listOf(
        Fact("IODE", iode.toString(), "Issue of Data, Ephemeris — must match subframe 3."),
        Fact("√A", "%.6f m^½".format(sqrtA), "Square root of the orbit's semi-major axis."),
        Fact("Semi-major axis", "%.3f km".format(sqrtA * sqrtA / 1000), "Size of the orbit (≈ 26 560 km for GPS)."),
        Fact("Eccentricity e", "%.9f".format(e), "How elliptical the orbit is (0 = circle)."),
        Fact("M₀", "%.8f°".format(radToDeg(m0)), "Mean anomaly at reference time — where the satellite is along its orbit."),
        Fact("Δn", "%.4e rad/s".format(deltaN), "Correction to computed mean motion."),
        Fact("t_oe", "%.0f s".format(toe), "Reference time of this ephemeris (seconds into week)."),
        Fact("C_uc / C_us", "%.4e / %.4e rad".format(cuc, cus), "Harmonic corrections to argument of latitude."),
        Fact("C_rs", "%.4f m".format(crs), "Sine harmonic correction to orbit radius."),
        Fact("Fit interval flag", if (fitFlag == 0) "0 (4 hours)" else "1 (> 4 hours)"),
        Fact("AODO", "${aodo * 900} s", "Age of data offset for the navigation message correction table."),
    )
}

data class OrbitPart2(
    val cic: Double, val omega0: Double, val cis: Double, val i0: Double, val crc: Double, val omega: Double,
    val omegaDot: Double, val iode: Int, val idot: Double,
) {
    fun rows() = listOf(
        Fact("IODE", iode.toString()),
        Fact("Ω₀", "%.8f°".format(radToDeg(omega0)), "Longitude of ascending node — orientation of the orbital plane."),
        Fact("i₀", "%.8f°".format(radToDeg(i0)), "Inclination of the orbit (≈ 55° for GPS)."),
        Fact("ω", "%.8f°".format(radToDeg(omega)), "Argument of perigee."),
        Fact("Ω̇", "%.4e rad/s".format(omegaDot), "Rate of change of right ascension."),
        Fact("IDOT", "%.4e rad/s".format(idot), "Rate of change of inclination."),
        Fact("C_ic / C_is", "%.4e / %.4e rad".format(cic, cis), "Harmonic corrections to inclination."),
        Fact("C_rc", "%.4f m".format(crc), "Cosine harmonic correction to orbit radius."),
    )
}

data class Almanac(
    val prn: Int, val e: Double, val toa: Double, val deltaI: Double, val omegaDot: Double, val health: Int,
    val sqrtA: Double, val omega0: Double, val omega: Double, val m0: Double, val af0: Double, val af1: Double,
) {
    fun rows() = listOf(
        Fact("Health", if (health == 0) "0 (healthy)" else health.toString()),
        Fact("Eccentricity", "%.6f".format(e)),
        Fact("√A", "%.3f m^½".format(sqrtA)),
        Fact("Inclination", "%.4f°".format(radToDeg(deltaI + 0.3 * GPS_PI))),
        Fact("Ω₀ / ω / M₀", "%.3f° / %.3f° / %.3f°".format(radToDeg(omega0), radToDeg(omega), radToDeg(m0))),
        Fact("Clock af0 / af1", "%.3f µs / %.2e".format(af0 * 1e6, af1)),
        Fact("t_oa", "%.0f s".format(toa)),
    )
}

data class IonoUtc(
    val alpha: DoubleArray, val beta: DoubleArray, val a0: Double, val a1: Double, val tot: Double, val wnt: Int,
    val leapSeconds: Int, val wnLsf: Int, val dn: Int, val leapSecondsFuture: Int,
) {
    fun rows() = listOf(
        Fact("Klobuchar α₀..α₃", alpha.joinToString { "%.3e".format(it) },
            "Ionosphere delay model coefficients — let single-frequency receivers correct the ionosphere."),
        Fact("Klobuchar β₀..β₃", beta.joinToString { "%.3e".format(it) }),
        Fact("GPS − UTC leap seconds", "$leapSeconds s", "GPS time does not include leap seconds; UTC does."),
        Fact("Future leap seconds", "$leapSecondsFuture s (week $wnLsf mod 256, day $dn)",
            "When the next leap second takes effect (or the last one did)."),
        Fact("A₀ / A₁", "%.3e s / %.3e s/s".format(a0, a1), "Fine offset between GPS time and UTC(USNO)."),
        Fact("t_ot / WN_t", "%.0f s / %d".format(tot, wnt)),
    )

    override fun equals(other: Any?) = other is IonoUtc && alpha.contentEquals(other.alpha) && beta.contentEquals(other.beta) &&
        a0 == other.a0 && a1 == other.a1 && leapSeconds == other.leapSeconds

    override fun hashCode() = alpha.contentHashCode()
}

object Lnav {
    fun clock(f: LnavSubframe) = ClockData(
        weekNumber10 = f.u(3, 1, 10).toInt(),
        l2Codes = f.u(3, 11, 2).toInt(),
        uraIndex = f.u(3, 13, 4).toInt(),
        health = f.u(3, 17, 6).toInt(),
        iodc = ((f.u(3, 23, 2) shl 8) or f.u(8, 1, 8)).toInt(),
        l2PFlag = f.u(4, 1, 1).toInt(),
        tgd = f.s(7, 17, 8) * p2(-31),
        toc = f.u(8, 9, 16) * 16.0,
        af2 = f.s(9, 1, 8) * p2(-55),
        af1 = f.s(9, 9, 16) * p2(-43),
        af0 = f.s(10, 1, 22) * p2(-31),
    )

    fun orbit1(f: LnavSubframe) = OrbitPart1(
        iode = f.u(3, 1, 8).toInt(),
        crs = f.s(3, 9, 16) * p2(-5),
        deltaN = semiToRad(f.s(4, 1, 16) * p2(-43)),
        m0 = semiToRad(f.s2(4, 17, 8) * p2(-31)),
        cuc = f.s(6, 1, 16) * p2(-29),
        e = f.u2(6, 17, 8) * p2(-33),
        cus = f.s(8, 1, 16) * p2(-29),
        sqrtA = f.u2(8, 17, 8) * p2(-19),
        toe = f.u(10, 1, 16) * 16.0,
        fitFlag = f.u(10, 17, 1).toInt(),
        aodo = f.u(10, 18, 5).toInt(),
    )

    fun orbit2(f: LnavSubframe) = OrbitPart2(
        cic = f.s(3, 1, 16) * p2(-29),
        omega0 = semiToRad(f.s2(3, 17, 8) * p2(-31)),
        cis = f.s(5, 1, 16) * p2(-29),
        i0 = semiToRad(f.s2(5, 17, 8) * p2(-31)),
        crc = f.s(7, 1, 16) * p2(-5),
        omega = semiToRad(f.s2(7, 17, 8) * p2(-31)),
        omegaDot = semiToRad(f.s(9, 1, 24) * p2(-43)),
        iode = f.u(10, 1, 8).toInt(),
        idot = semiToRad(f.s(10, 9, 14) * p2(-43)),
    )

    fun almanac(f: LnavSubframe) = Almanac(
        prn = f.pageSvId,
        e = f.u(3, 9, 16) * p2(-21),
        toa = f.u(4, 1, 8) * 4096.0,
        deltaI = semiToRad(f.s(4, 9, 16) * p2(-19)),
        omegaDot = semiToRad(f.s(5, 1, 16) * p2(-38)),
        health = f.u(5, 17, 8).toInt(),
        sqrtA = f.u(6, 1, 24) * p2(-11),
        omega0 = semiToRad(f.s(7, 1, 24) * p2(-23)),
        omega = semiToRad(f.s(8, 1, 24) * p2(-23)),
        m0 = semiToRad(f.s(9, 1, 24) * p2(-23)),
        af0 = LnavSubframe.signExtend((f.u(10, 1, 8) shl 3) or f.u(10, 20, 3), 11) * p2(-20),
        af1 = f.s(10, 9, 11) * p2(-38),
    )

    fun ionoUtc(f: LnavSubframe) = IonoUtc(
        alpha = doubleArrayOf(f.s(3, 9, 8) * p2(-30), f.s(3, 17, 8) * p2(-27), f.s(4, 1, 8) * p2(-24), f.s(4, 9, 8) * p2(-24)),
        beta = doubleArrayOf(f.s(4, 17, 8) * p2(11), f.s(5, 1, 8) * p2(14), f.s(5, 9, 8) * p2(16), f.s(5, 17, 8) * p2(16)),
        a1 = f.s(6, 1, 24) * p2(-50),
        a0 = LnavSubframe.signExtend((f.u(7, 1, 24) shl 8) or f.u(8, 1, 8), 32) * p2(-30),
        tot = f.u(8, 9, 8) * 4096.0,
        wnt = f.u(8, 17, 8).toInt(),
        leapSeconds = f.s(9, 1, 8).toInt(),
        wnLsf = f.u(9, 9, 8).toInt(),
        dn = f.u(9, 17, 8).toInt(),
        leapSecondsFuture = f.s(10, 1, 8).toInt(),
    )
}

/** Everything decoded so far from one GPS satellite's broadcast. */
data class GpsSatNav(
    val prn: Int,
    val clock: ClockData? = null,
    val orbit1: OrbitPart1? = null,
    val orbit2: OrbitPart2? = null,
    val subframesSeen: Int = 0,
    val lastTow: Int? = null,
) {
    /** True once subframes 1-3 belong to the same issue of data. */
    val ephemerisComplete
        get() = clock != null && orbit1 != null && orbit2 != null &&
            orbit1.iode == orbit2.iode && (clock.iodc and 0xFF) == orbit1.iode
}

// ---------------------------------------------------------------------------------------------
// Satellite position from broadcast ephemeris, IS-GPS-200 Table 20-IV.
// ---------------------------------------------------------------------------------------------

private const val MU = 3.986005e14
private const val OMEGA_E = 7.2921151467e-5

data class SatPosition(val ecef: DoubleArray, val clockOffsetSec: Double)

fun satellitePosition(nav: GpsSatNav, towSeconds: Double): SatPosition? {
    if (!nav.ephemerisComplete) return null
    val c = nav.clock!!
    val o1 = nav.orbit1!!
    val o2 = nav.orbit2!!
    val a = o1.sqrtA * o1.sqrtA
    var tk = towSeconds - o1.toe
    if (tk > WEEK_SECONDS / 2) tk -= WEEK_SECONDS
    if (tk < -WEEK_SECONDS / 2) tk += WEEK_SECONDS
    val n = sqrt(MU / (a * a * a)) + o1.deltaN
    val m = o1.m0 + n * tk
    var e = m
    repeat(10) { e = m + o1.e * sin(e) }
    val v = atan2(sqrt(1 - o1.e * o1.e) * sin(e), cos(e) - o1.e)
    val phi = v + o2.omega
    val u = phi + o1.cus * sin(2 * phi) + o1.cuc * cos(2 * phi)
    val r = a * (1 - o1.e * cos(e)) + o1.crs * sin(2 * phi) + o2.crc * cos(2 * phi)
    val i = o2.i0 + o2.cis * sin(2 * phi) + o2.cic * cos(2 * phi) + o2.idot * tk
    val xp = r * cos(u)
    val yp = r * sin(u)
    val omega = o2.omega0 + (o2.omegaDot - OMEGA_E) * tk - OMEGA_E * o1.toe
    val x = xp * cos(omega) - yp * cos(i) * sin(omega)
    val y = xp * sin(omega) + yp * cos(i) * cos(omega)
    val z = yp * sin(i)
    var dt = towSeconds - c.toc
    if (dt > WEEK_SECONDS / 2) dt -= WEEK_SECONDS
    if (dt < -WEEK_SECONDS / 2) dt += WEEK_SECONDS
    val relativistic = -4.442807633e-10 * o1.e * o1.sqrtA * sin(e)
    val clock = c.af0 + c.af1 * dt + c.af2 * dt * dt + relativistic - c.tgd
    return SatPosition(doubleArrayOf(x, y, z), clock)
}

/** WGS-84 geodetic (degrees, metres) to Earth-centred Earth-fixed metres. */
fun geodeticToEcef(latDeg: Double, lonDeg: Double, alt: Double): DoubleArray {
    val a = 6378137.0
    val e2 = 6.69437999014e-3
    val lat = Math.toRadians(latDeg)
    val lon = Math.toRadians(lonDeg)
    val nr = a / sqrt(1 - e2 * sin(lat) * sin(lat))
    return doubleArrayOf(
        (nr + alt) * cos(lat) * cos(lon),
        (nr + alt) * cos(lat) * sin(lon),
        (nr * (1 - e2) + alt) * sin(lat),
    )
}

/** Returns (elevation°, azimuth°, range m) of [sat] as seen from the receiver. */
fun lookAngles(latDeg: Double, lonDeg: Double, alt: Double, sat: DoubleArray): Triple<Double, Double, Double> {
    val rx = geodeticToEcef(latDeg, lonDeg, alt)
    val dx = sat[0] - rx[0]
    val dy = sat[1] - rx[1]
    val dz = sat[2] - rx[2]
    val lat = Math.toRadians(latDeg)
    val lon = Math.toRadians(lonDeg)
    val east = -sin(lon) * dx + cos(lon) * dy
    val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
    val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
    val el = Math.toDegrees(atan2(up, sqrt(east * east + north * north)))
    val az = (Math.toDegrees(atan2(east, north)) + 360) % 360
    return Triple(el, az, sqrt(dx * dx + dy * dy + dz * dz))
}

/** Sub-satellite point (geocentric latitude, longitude) and height above a spherical Earth. */
fun subSatellitePoint(sat: DoubleArray): Triple<Double, Double, Double> {
    val p = sqrt(sat[0] * sat[0] + sat[1] * sat[1])
    val r = sqrt(p * p + sat[2] * sat[2])
    return Triple(Math.toDegrees(atan2(sat[2], p)), Math.toDegrees(atan2(sat[1], sat[0])), r - 6_371_000.0)
}

/** GPS (week, seconds of week) for a UTC instant. */
fun gpsTime(unixMs: Long, leapSeconds: Int): Pair<Int, Double> {
    val s = (unixMs - GPS_EPOCH_UNIX_MS) / 1000.0 + leapSeconds
    val week = floor(s / WEEK_SECONDS).toInt()
    return week to (s - week * WEEK_SECONDS)
}
